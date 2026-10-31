package org.sikuli.ide.executor;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** Local execution only. Tasks remain ordinary OculiX scripts; there is no model or planner. */
public final class ExecutorService implements AutoCloseable {
  private static final String SYCM_DESCRIPTION = "从打开千牛进入工作台，依次打开生意参谋、市场和搜索排行，获取前10个关键词";
  private static final String DEFAULT_SYCM_ACCOUNT = "示例账号:店铺";
  private static final String[] SYCM_STEP_IDS = {"open-qianniu", "enter-workbench", "open-sycm", "enter-market", "open-ranking", "read-keywords", "save-results"};
  private static final String[] SYCM_STEP_NAMES = {"打开千牛", "进入工作台", "打开生意参谋", "进入市场", "打开搜索排行", "读取关键词", "保存结果"};
  private final Path workspace, appJar, dataDir, recordsDir;
  @FunctionalInterface interface ProcessStarter { Process start(ProcessBuilder builder) throws IOException; }
  private final ProcessStarter processStarter;
  private final Object processLock = new Object();
  private final java.util.concurrent.ConcurrentMap<Long, ProcessHandle> ownedProcesses = new java.util.concurrent.ConcurrentHashMap<>();
  private final java.util.concurrent.ExecutorService stateWriter = Executors.newSingleThreadExecutor(r -> daemon(r, "运行记录保存"));
  private final java.util.concurrent.ExecutorService control = Executors.newSingleThreadExecutor(r -> daemon(r, "执行进程停止"));
  private final java.util.concurrent.ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "执行状态心跳"));
  private static Thread daemon(Runnable action, String name) { Thread thread = new Thread(action, name); thread.setDaemon(true); return thread; }
  private final LinkedHashMap<String, JSONObject> registered = new LinkedHashMap<>();
  private final List<JSONObject> records = new ArrayList<>();
  private final java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
    Thread thread = new Thread(r, "本地任务执行"); thread.setDaemon(true); return thread;
  });
  private JSONObject current = idle();
  private volatile Process child;
  private volatile String activeRunId = "";
  private volatile boolean cancelled, closed, timedOut;
  private volatile java.util.function.BooleanSupplier executionAllowed = () -> true;
  private HttpServer server;
  private java.util.concurrent.ExecutorService apiWorker;
  private String token;
  private int apiPort = 18986;

  public ExecutorService(Path workspace, Path appJar) {
    this(workspace, appJar, ProcessBuilder::start);
  }
  /** Injectable start boundary makes process ownership races testable without GUI actions. */
  ExecutorService(Path workspace, Path appJar, ProcessStarter processStarter) {
    this.processStarter = Objects.requireNonNull(processStarter);
    this.workspace = workspace.toAbsolutePath().normalize();
    this.appJar = appJar.toAbsolutePath().normalize();
    dataDir = this.workspace.resolve("执行数据");
    recordsDir = dataDir.resolve("运行记录");
    try {
      Files.createDirectories(recordsDir);
      loadTasks(); loadRecords(); importExistingSycm(); loadConnection();
    } catch (IOException e) { throw new IllegalStateException("无法读取执行数据目录，请检查文件权限。", e); }
    monitor.scheduleAtFixedRate(this::heartbeat, 1, 1, TimeUnit.SECONDS);
  }

  private static JSONObject idle() {
    return new JSONObject().put("running", false).put("status", "idle").put("stage", "等待执行")
        .put("summary", "选择任务后开始执行").put("rows", new JSONArray()).put("steps", new JSONArray())
        .put("resultCount", 0).put("durationMs", 0).put("verified", false).put("phase", "idle")
        .put("heartbeatAt", "").put("lastOutputAt", "").put("silentForMs", 0).put("stalled", false).put("healthMessage", "等待执行");
  }
  private static JSONObject copy(JSONObject value) { return new JSONObject(value.toString()); }
  private static String now() { return Instant.now().toString(); }
  public synchronized JSONObject snapshot() {
    JSONObject value = copy(current);
    if (value.optBoolean("running")) health(value, System.currentTimeMillis());
    return value;
  }
  public synchronized JSONArray tasks() {
    JSONArray result = new JSONArray(); registered.values().forEach(t -> result.put(copy(t))); return result;
  }
  public synchronized JSONArray taskSteps(String taskId) {
    requireTask(taskId); JSONArray preview = steps(taskId);
    for (int i = 0; i < preview.length(); i++) preview.getJSONObject(i).put("status", "pending");
    return preview;
  }
  public synchronized JSONArray history() {
    JSONArray result = new JSONArray(); records.stream().sorted(Comparator.comparing((JSONObject r) -> r.optString("startedAt")).reversed())
        .forEach(r -> result.put(copy(r))); return result;
  }
  public synchronized JSONObject result(String taskId) {
    if (current.optString("taskId").equals(taskId) && !current.optString("status").equals("idle")) return snapshot();
    return records.stream().filter(r -> taskId.equals(r.optString("taskId")))
        .max(Comparator.comparing(r -> r.optString("startedAt"))).map(ExecutorService::copy).orElseGet(JSONObject::new);
  }
  public synchronized boolean isRunning() { return current.optBoolean("running"); }
  /** The host can suspend starts while its advanced tools own desktop input. */
  public void setExecutionAllowed(java.util.function.BooleanSupplier allowed) {
    executionAllowed = allowed == null ? () -> true : allowed;
  }

  private void loadTasks() throws IOException {
    Path file = dataDir.resolve("任务.json");
    if (Files.exists(file)) {
      try {
        JSONArray array = new JSONArray(Files.readString(file));
        boolean changed = false;
        for (int i = 0; i < array.length(); i++) {
          JSONObject task = array.getJSONObject(i);
          if (!task.optString("id").isEmpty() && !task.optString("path").isEmpty()) {
            String previous = task.toString(); taskDefaults(task); changed |= !previous.equals(task.toString()); registered.put(task.getString("id"), task);
          }
        }
        if (changed) saveTasks();
      } catch (RuntimeException e) { throw new IOException("任务清单格式错误，原文件已保留。", e); }
    }
    Path builtIn = workspace.resolve("OculiX/生意参谋关键词.sikuli/生意参谋关键词.py");
    if (!Files.exists(file) && Files.isRegularFile(builtIn) && !registered.containsKey("sycm-keywords")) {
      registered.put("sycm-keywords", taskDefaults(new JSONObject().put("id", "sycm-keywords").put("name", "生意参谋关键词")
          .put("description", SYCM_DESCRIPTION).put("type", "网页数据采集").put("path", builtIn.toString())));
      saveTasks();
    }
  }
  private void saveTasks() throws IOException { writeJson(dataDir.resolve("任务.json"), tasks().toString(2)); }

  public synchronized JSONObject importTask(Path script) {
    if (closed) throw new IllegalStateException("执行器已经关闭。");
    Path path = script.toAbsolutePath().normalize();
    String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
    boolean bundle = Files.isDirectory(path) && lower.endsWith(".sikuli");
    boolean file = Files.isRegularFile(path) && (lower.endsWith(".py") || lower.endsWith(".js") || lower.endsWith(".rb") || lower.endsWith(".robot"));
    if (!bundle && !file) throw new IllegalArgumentException("请选择任务文件夹（.sikuli），或 .py、.js、.rb、.robot 脚本。");
    for (JSONObject task : registered.values()) if (Paths.get(task.getString("path")).equals(path)) return copy(task);
    String id = "task-" + UUID.randomUUID().toString().substring(0, 8);
    String name = path.getFileName().toString().replaceFirst("(?i)\\.(sikuli|py|js|rb|robot)$", "");
    JSONObject task = new JSONObject().put("id", id).put("name", name).put("description", "执行已导入的本地任务")
        .put("type", "本地自动化").put("path", path.toString());
    taskDefaults(task);
    registered.put(id, task);
    try { saveTasks(); } catch (IOException e) { registered.remove(id); throw new IllegalStateException("任务保存失败。", e); }
    return copy(task);
  }
  public synchronized void renameTask(String id, String name) {
    if (name == null || name.isBlank()) throw new IllegalArgumentException("任务名称不能为空。");
    JSONObject task = requireTask(id); String previous = task.getString("name"); task.put("name", name.trim());
    try { saveTasks(); } catch (IOException e) { task.put("name", previous); throw new IllegalStateException("任务名称保存失败。", e); }
  }
  public synchronized void removeTask(String id) {
    if (isRunning() && id.equals(current.optString("taskId"))) throw new IllegalStateException("请先停止正在执行的任务。");
    JSONObject removed = registered.remove(id);
    try { saveTasks(); } catch (IOException e) { if (removed != null) registered.put(id, removed); throw new IllegalStateException("任务清单保存失败。", e); }
  }
  private JSONObject requireTask(String id) {
    JSONObject task = registered.get(id); if (task == null) throw new IllegalArgumentException("找不到这个任务，请重新选择。"); return task;
  }
  private static JSONObject taskDefaults(JSONObject task) {
    long timeout = task.optLong("timeoutMs", 600000), warning = task.optLong("silenceWarningMs", 30000);
    task.put("timeoutMs", timeout >= 1000 && timeout <= 86400000 ? timeout : 600000);
    task.put("silenceWarningMs", warning >= 1000 && warning <= 86400000 ? warning : 30000);
    if ("sycm-keywords".equals(task.optString("id")) && (task.optString("description").isBlank()
        || "获取当前搜索排行的前10个关键词".equals(task.optString("description")))) task.put("description", SYCM_DESCRIPTION);
    return task;
  }
  public synchronized void configureTimeouts(String id, long timeoutMs, long silenceWarningMs) {
    if (closed) throw new IllegalStateException("执行器已经关闭。");
    if (timeoutMs < 1000 || timeoutMs > 86400000 || silenceWarningMs < 1000 || silenceWarningMs > 86400000)
      throw new IllegalArgumentException("执行时限和无输出提示时长须在 1 秒至 24 小时之间。");
    JSONObject task = requireTask(id); long beforeTimeout = task.getLong("timeoutMs"), beforeWarning = task.getLong("silenceWarningMs");
    task.put("timeoutMs", timeoutMs).put("silenceWarningMs", silenceWarningMs);
    try { saveTasks(); } catch (IOException e) { task.put("timeoutMs", beforeTimeout).put("silenceWarningMs", beforeWarning); throw new IllegalStateException("执行设置保存失败。", e); }
  }
  public synchronized String sycmAccount() {
    try { return validatedSycmAccount(readSycmProperties().getProperty("targetAccount", DEFAULT_SYCM_ACCOUNT)); }
    catch (IOException e) { throw new IllegalStateException("无法读取千牛目标账号设置，请检查配置文件。", e); }
  }
  public synchronized void configureSycmAccount(String account) {
    if (closed) throw new IllegalStateException("执行器已经关闭。");
    if (isRunning()) throw new IllegalStateException("请先结束当前任务，再修改千牛目标账号。");
    String value = validatedSycmAccount(account);
    try {
      Properties settings = readSycmProperties(); settings.setProperty("targetAccount", value);
      StringWriter text = new StringWriter(); settings.store(text, "千牛启动与已登录账号设置");
      Path file = workspace.resolve("oculix-flow/千牛启动.properties"); Files.createDirectories(file.getParent());
      writeJson(file, text.toString());
    } catch (IOException e) { throw new IllegalStateException("千牛目标账号保存失败，原设置已保留。", e); }
  }
  private Properties readSycmProperties() throws IOException {
    Path file = workspace.resolve("oculix-flow/千牛启动.properties"); Properties settings = new Properties();
    if (Files.isRegularFile(file)) {
      String text = Files.readString(file, StandardCharsets.UTF_8);
      if (text.startsWith("\uFEFF")) text = text.substring(1);
      try { settings.load(new StringReader(text)); }
      catch (IllegalArgumentException e) { throw new IOException("千牛启动配置格式错误。", e); }
    }
    return settings;
  }
  private static String validatedSycmAccount(String account) {
    if (account == null || account.isBlank()) throw new IllegalArgumentException("请填写千牛顶部已登录账号的完整名称，不能留空。");
    if (account.codePoints().anyMatch(c -> Character.isISOControl(c) || c == 0x2028 || c == 0x2029))
      throw new IllegalArgumentException("千牛目标账号不能包含换行或控制字符。");
    String value = account.strip();
    if (value.length() > 128) throw new IllegalArgumentException("千牛目标账号名称不能超过 128 个字符。");
    return value;
  }

  public synchronized void run(String taskId) {
    if (closed) throw new IllegalStateException("执行器已经关闭。");
    if (!executionAllowed.getAsBoolean()) throw new IllegalStateException("请先返回执行中心，再运行任务。");
    if (isRunning()) throw new IllegalStateException("已有任务正在执行，请等待完成或先停止。");
    JSONObject task = copy(requireTask(taskId));
    cancelled = false; timedOut = false; ownedProcesses.clear();
    String runId = Instant.now().toString().replace(':', '-') + "_" + UUID.randomUUID().toString().substring(0, 6);
    activeRunId = runId;
    Path runDir = recordsDir.resolve(runId);
    current = idle().put("running", true).put("status", "running").put("stage", "准备执行")
        .put("taskId", taskId).put("taskName", task.getString("name")).put("runId", runId)
        .put("startedAt", now()).put("startedEpoch", System.currentTimeMillis()).put("finishedAt", "")
        .put("summary", "正在准备任务，请暂时不要操作鼠标。")
        .put("phase", "starting").put("heartbeatAt", now()).put("timeoutMs", task.getLong("timeoutMs"))
        .put("activeStepId", "").put("stepProtocol", "legacy")
        .put("silenceWarningMs", task.getLong("silenceWarningMs")).put("healthMessage", "正在准备并启动执行引擎，请稍候。")
        .put("logPath", runDir.resolve("执行日志.txt").toString()).put("resultDir", runDir.toString())
        .put("steps", steps(taskId));
    saveCurrent();
    JSONObject run = copy(current);
    worker.submit(() -> execute(task, run, runDir));
  }
  private static JSONArray steps(String taskId) {
    JSONArray steps = new JSONArray();
    boolean sycm = "sycm-keywords".equals(taskId);
    String[] names = sycm ? SYCM_STEP_NAMES
        : new String[]{"准备执行", "执行任务", "读取结果", "保存结果"};
    String[] ids = sycm ? SYCM_STEP_IDS : new String[]{"prepare", "execute", "read-result", "save-results"};
    for (int i = 0; i < names.length; i++) steps.put(new JSONObject().put("id", ids[i]).put("name", names[i]).put("status", !sycm && i == 0 ? "running" : "pending"));
    return steps;
  }
  private synchronized void startingStage() {
    if (cancelled || timedOut || closed) return;
    current.put("stage", "正在启动执行引擎").put("summary", "正在启动执行引擎…").put("phase", "starting");
    health(current, System.currentTimeMillis()); saveCurrent();
  }
  private synchronized void stage(int index, String name) {
    if (cancelled || timedOut || closed) return;
    JSONArray list = current.getJSONArray("steps");
    if ("sycm-keywords".equals(current.optString("taskId"))) {
      if ("structured".equals(current.optString("stepProtocol"))) return;
      // Legacy progress can finish a step that was actually reported as running;
      // it cannot assert that omitted earlier steps were ever executed.
      for (int i = 0; i < list.length(); i++) if (i != index && "running".equals(list.getJSONObject(i).optString("status")))
        list.getJSONObject(i).put("status", i < index ? "done" : "pending");
      applyStep(index, "running", name); return;
    }
    current.put("stage", name).put("summary", name + "…").put("phase", index == 0 ? "starting" : "running");
    current.put("activeStepId", list.getJSONObject(index).optString("id"));
    for (int i = 0; i < list.length(); i++) list.getJSONObject(i).put("status", i < index ? "done" : i == index ? "running" : "pending");
    health(current, System.currentTimeMillis()); saveCurrent();
  }
  private void applyStep(int index, String status, String message) {
    JSONArray list = current.getJSONArray("steps"); JSONObject target = list.getJSONObject(index);
    if ("running".equals(status)) {
      for (int i = 0; i < list.length(); i++) if (i != index && "running".equals(list.getJSONObject(i).optString("status")))
        list.getJSONObject(i).put("status", "pending");
      current.put("activeStepId", target.getString("id"));
    } else if (target.getString("id").equals(current.optString("activeStepId"))) current.put("activeStepId", "");
    target.put("status", status);
    if ("failed".equals(status)) current.put("reportedStepFailure", true);
    current.put("stage", message).put("summary", message).put("phase", "running");
    health(current, System.currentTimeMillis()); saveCurrent();
  }
  private boolean structuredStep(String payload) {
    try {
      JSONObject event = new JSONObject(payload); String status = event.optString("status");
      if (!Arrays.asList("running", "done", "failed").contains(status)) return false;
      String selector = event.optString("id", event.optString("name", event.optString("step")));
      JSONArray list = current.getJSONArray("steps");
      for (int i = 0; i < list.length(); i++) {
        JSONObject step = list.getJSONObject(i);
        if (selector.equals(step.optString("id")) || selector.equals(step.optString("name"))) {
          String message = event.optString("message", step.getString("name") + (status.equals("done") ? "已完成" : status.equals("failed") ? "未完成" : "进行中"));
          if (message.length() > 180 || !message.matches("(?s).*[\\p{IsHan}].*")) message = step.getString("name");
          current.put("stepProtocol", "structured"); applyStep(i, status, message); return true;
        }
      }
    } catch (RuntimeException ignored) { /* Malformed optional telemetry never changes the task outcome. */ }
    return false;
  }
  private synchronized void savingStage() {
    if (cancelled || timedOut || closed) return;
    int index = current.getJSONArray("steps").length() - 1;
    if ("sycm-keywords".equals(current.optString("taskId")) && "structured".equals(current.optString("stepProtocol"))) applyStep(index, "running", "保存结果");
    else stage(index, "保存结果");
  }
  private static void health(JSONObject state, long timestamp) {
    long duration = Math.max(0, timestamp - state.optLong("startedEpoch", timestamp));
    long silence = Math.max(0, timestamp - state.optLong("lastOutputEpoch", state.optLong("startedEpoch", timestamp)));
    boolean stalled = !"stopping".equals(state.optString("phase")) && silence >= state.optLong("silenceWarningMs", 30000);
    String message = "stopping".equals(state.optString("phase")) ? state.optString("summary", "正在停止执行。")
        : stalled ? "已有 " + silence / 1000 + " 秒没有新输出，执行进程可能仍在等待；你可以继续等待或停止。"
        : "starting".equals(state.optString("phase")) ? "正在启动执行引擎，等待任务开始；首次初始化可能需要十余秒。" : "任务正在执行，最近一次输出在 " + silence / 1000 + " 秒前。";
    state.put("durationMs", duration).put("silentForMs", silence).put("stalled", stalled).put("healthMessage", message);
  }
  private void heartbeat() {
    try {
      String terminate = null;
      Process process = child;
      if (process != null) process.descendants().forEach(p -> ownedProcesses.put(p.pid(), p));
      synchronized (this) {
        if (!current.optBoolean("running")) return;
        long timestamp = System.currentTimeMillis(); current.put("heartbeatAt", now());
        health(current, timestamp);
        if (!cancelled && !timedOut && timestamp - current.optLong("startedEpoch") >= current.optLong("timeoutMs", 600000)) {
          timedOut = true; terminate = current.optString("runId");
          current.put("phase", "stopping").put("stage", "执行超时，正在停止").put("summary", "已达到任务最长执行时间，正在停止进程。")
              .put("failureCode", "TIMEOUT").put("rootCause", "超过配置的最长执行时间：" + current.optLong("timeoutMs") + " 毫秒。");
          health(current, timestamp);
        }
        saveCurrent();
      }
      if (terminate != null) requestTermination(terminate);
    } catch (RuntimeException ignored) { /* A monitor failure must not disable later checks. */ }
  }
  private synchronized void outputReceived(String line) {
    if (cancelled || timedOut || closed) return;
    current.put("lastOutputAt", now()).put("lastOutputEpoch", System.currentTimeMillis());
    if (line.startsWith("OCULIX_STEP ") && structuredStep(line.substring(12).trim())) return;
    boolean sycm = "sycm-keywords".equals(current.optString("taskId"));
    if ("starting".equals(current.optString("phase")) && !line.isBlank() && !line.startsWith("[DEBUG STARTUP]")) {
      if (sycm) {
        current.put("phase", "running").put("stage", "执行引擎已启动，等待流程步骤").put("summary", "执行引擎已启动，等待流程步骤"); saveCurrent();
      } else stage(1, "执行任务");
    }
    String marker = "OCULIX_PROGRESS "; int offset = line.indexOf(marker);
    if (offset >= 0) {
      String message = line.substring(offset + marker.length()).trim();
      if (!message.isEmpty() && message.length() <= 180 && message.matches("(?s).*[\\p{IsHan}].*")) {
        if ("structured".equals(current.optString("stepProtocol"))) {
          current.put("stage", message).put("summary", message); saveCurrent();
        } else if (sycm) {
          int index = sycmProgressIndex(message);
          if (index >= 0) stage(index, message);
          else {
            current.put("stage", message).put("summary", message);
            if (preparationMessage(message) && current.optString("activeStepId").isBlank()) current.put("phase", "starting");
            saveCurrent();
          }
        } else {
          int index = message.contains("返回结果") || message.contains("保存结果") ? 3 : message.contains("读取") || message.contains("整理") || message.contains("采集") ? 2 : 1;
          stage(index, message); current.put("phase", "running"); saveCurrent();
        }
      }
    }
    health(current, System.currentTimeMillis());
  }
  private static int sycmProgressIndex(String message) {
    if (preparationMessage(message)) return -1;
    // Prefer the current action after “正在”, not earlier confirmed pages named
    // in the same sentence (e.g. 市场已确认，正在打开搜索排行).
    int action = message.lastIndexOf("正在"); String target = action < 0 ? message : message.substring(action);
    if (target.contains("返回结果") || target.contains("保存结果")) return 6;
    if (target.contains("关键词") || target.contains("识别文字")) return 5;
    if (target.contains("搜索排行")) return 4;
    if (target.contains("市场")) return 3;
    if (target.contains("生意参谋") || target.contains("顶部导航")) return 2;
    if (target.contains("工作台") && (target.contains("进入") || target.contains("打开") || target.contains("切换") || target.contains("确认"))) return 1;
    if (target.contains("千牛") || target.contains("登录") || target.contains("快捷方式")) return 0;
    return -1;
  }
  private static boolean preparationMessage(String message) {
    return message.contains("执行引擎") || message.contains("初始化") || message.contains("准备执行") || message.contains("准备任务");
  }

  /** Package-private command hook permits process-lifecycle tests without desktop actions. */
  static List<String> command(Path workspace, Path jar, Path script) {
    Path java = workspace.resolve("runtime/java/jdk-21.0.12.1+1/bin/javaw.exe");
    if (!Files.exists(java)) java = Paths.get(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "javaw.exe" : "java");
    List<String> command = new ArrayList<>(Arrays.asList(java.toString(), "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Doculix.executorChild=true"));
    String preferencesNode = System.getProperty("oculix.preferencesNode", "").trim();
    if (!preferencesNode.isEmpty()) command.add("-Doculix.preferencesNode=" + preferencesNode);
    command.addAll(Arrays.asList("-jar", jar.toString(), "-r", script.toString()));
    return command;
  }
  private void execute(JSONObject task, JSONObject run, Path runDir) {
    Path resultPath = null;
    Path evidenceDir = null, capturedImage = null;
    boolean completedMarker = false;
    String failureHint = "";
    int exit = -1;
    long began = run.optLong("startedEpoch");
    try (BufferedWriter log = openRunLog(runDir)) {
      Path script = Paths.get(task.getString("path"));
      if (!Files.exists(script)) throw new IOException("任务文件不存在，请重新导入。");
      if (!Files.isRegularFile(appJar)) throw new IOException("执行引擎文件不存在，请检查软件目录。");
      ProcessBuilder builder = new ProcessBuilder(command(workspace, appJar, script))
          .directory(Files.isDirectory(script) ? script.toFile() : script.getParent().toFile()).redirectErrorStream(true);
      Process process;
      startingStage();
      synchronized (processLock) {
        if (cancelled || closed || timedOut) return;
        // stop/close cannot observe a null child between native process creation
        // and ownership assignment, nor can a child start after closure begins.
        child = process = processStarter.start(builder);
      }
      synchronized (this) { current.put("pid", process.pid()); saveCurrent(); }
      if (cancelled || closed || timedOut) terminateRun(run.getString("runId"));
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          log.write(line); log.newLine(); log.flush();
          outputReceived(line);
          if (line.contains("OCR_CANDIDATES ")) resultPath = Paths.get(line.substring(line.indexOf("OCR_CANDIDATES ") + 15).trim());
          if (line.startsWith("OCULIX_RESULT ")) resultPath = Paths.get(line.substring(14).trim());
          Path explicitDir = loggedPath(line, "OCULIX_RUN_DIR");
          if (explicitDir == null) explicitDir = loggedPath(line, "RUN_DIR");
          if (explicitDir != null) evidenceDir = explicitDir;
          Path captured = loggedPath(line, "CAPTURED");
          if (captured != null) { capturedImage = captured; evidenceDir = captured.getParent(); }
          if (resultPath != null && resultPath.isAbsolute()) evidenceDir = resultPath.getParent();
          if (line.contains("COMPLETED_PENDING_QA")) completedMarker = true;
          failureHint = strongerFailure(failureHint, line);
          if ("sycm-keywords".equals(task.getString("id"))) {
            if (line.contains("CLICK workbench")) stage(1, "进入工作台");
            else if (line.contains("CLICK sycm-tab")) stage(2, "打开生意参谋");
            else if (line.contains("CLICK market-")) stage(3, "进入市场");
            else if (line.contains("CLICK search-menu") || line.contains("CLICK search-active")) stage(4, "打开搜索排行");
            else if (line.contains(" CAPTURED ")) stage(5, "读取关键词");
          }
          if (cancelled || timedOut) terminateRun(run.getString("runId"));
        }
      }
      exit = process.waitFor();
      if (!cancelled && !timedOut && exit == 0) {
        synchronized (this) { if (current.optBoolean("reportedStepFailure")) throw new IOException("任务报告了执行步骤失败，请查看步骤与诊断记录。"); }
        if ("sycm-keywords".equals(task.getString("id")) && (!completedMarker || resultPath == null)) throw new IOException("任务没有返回完整的关键词结果。");
        savingStage();
        JSONObject parsed = resultPath == null ? new JSONObject().put("rows", new JSONArray()) : parseResult(resultPath);
        if ("sycm-keywords".equals(task.getString("id")) && parsed.getJSONArray("rows").length() != 10) throw new IOException("关键词条数不符合任务要求。");
        if (resultPath != null) Files.copy(resultPath, runDir.resolve(resultPath.toString().endsWith(".json") ? "原始结果.json" : "原始结果.txt"), StandardCopyOption.REPLACE_EXISTING);
        JSONObject output = new JSONObject().put("taskName", task.getString("name")).put("startedAt", run.optString("startedAt")).put("rows", parsed.getJSONArray("rows"));
        Files.writeString(runDir.resolve("结果.txt"), resultText(output), StandardCharsets.UTF_8);
        synchronized (this) {
          if (!cancelled && !timedOut) {
            current.put("rows", parsed.getJSONArray("rows")).put("resultCount", parsed.getJSONArray("rows").length())
                .put("verified", false).put("resultText", parsed.optString("text"));
            if (resultPath != null) current.put("sourceResultPath", resultPath.toString());
          }
        }
      }
    } catch (Throwable error) {
      String cause = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
      failureHint = strongerFailure(failureHint, cause);
      if (failureHint.isBlank()) failureHint = cause;
      try (PrintWriter log = new PrintWriter(Files.newBufferedWriter(runDir.resolve("执行日志.txt"), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND))) { error.printStackTrace(log); } catch (IOException ignored) {}
      exit = -1;
    } finally {
      terminateRun(run.getString("runId"));
      synchronized (processLock) { child = null; }
      JSONObject evidence = evidence(runDir, evidenceDir, capturedImage, began, exit == 0 && !cancelled && !timedOut);
      synchronized (this) {
        for (String key : evidence.keySet()) current.put(key, evidence.get(key));
        String status = timedOut ? "failed" : cancelled ? "cancelled" : exit == 0 ? "success" : "failed";
        current.put("status", status).put("running", false).put("phase", "finished").put("finishedAt", now()).put("heartbeatAt", now())
            .put("durationMs", System.currentTimeMillis() - began).put("exitCode", exit).put("stalled", false);
        if (timedOut) current.put("summary", "任务超过最长执行时间（" + current.optLong("timeoutMs") / 1000 + " 秒），已停止进程并保存记录。")
            .put("stage", "执行超时").put("rows", new JSONArray()).put("resultCount", 0).put("verified", false).put("failureCode", "TIMEOUT");
        else if (cancelled) current.put("summary", "任务已停止，已保存执行记录。").put("stage", "已停止").put("rows", new JSONArray()).put("resultCount", 0).put("verified", false);
        else if (exit == 0) current.put("summary", current.optInt("resultCount") > 0 ? "已获取 " + current.optInt("resultCount") + " 条结果" : "执行完成，任务未提供结构化结果。")
            .put("stage", "执行完成");
        else current.put("summary", friendlyError(failureHint)).put("stage", "执行未完成").put("rows", new JSONArray()).put("resultCount", 0).put("verified", false)
            .put("rootCause", failureHint).put("failureCode", failureCode(failureHint));
        current.put("healthMessage", current.optString("summary"));
        JSONArray list = current.getJSONArray("steps");
        for (int i = 0; i < list.length(); i++) {
          JSONObject step = list.getJSONObject(i);
          if (exit == 0 && !cancelled && !timedOut && (!"sycm-keywords".equals(current.optString("taskId")) || "running".equals(step.optString("status")))) step.put("status", "done");
          else if ("running".equals(step.optString("status"))) step.put("status", "failed");
        }
        current.put("activeStepId", "");
        records.add(copy(current)); saveCurrent();
      }
    }
  }
  private static BufferedWriter openRunLog(Path dir) throws IOException {
    Files.createDirectories(dir); return Files.newBufferedWriter(dir.resolve("执行日志.txt"), StandardCharsets.UTF_8);
  }
  /** Only paths explicitly reported by this child may identify its evidence. */
  private static Path loggedPath(String line, String marker) {
    int offset = line.startsWith(marker + " ") ? marker.length() + 1 : line.indexOf(" " + marker + " ");
    if (offset < 0) return null;
    if (!line.startsWith(marker + " ")) offset += marker.length() + 2;
    try {
      String value = line.substring(offset).trim();
      if (value.startsWith("\"") && value.endsWith("\"")) value = value.substring(1, value.length() - 1);
      Path path = Paths.get(value);
      return path.isAbsolute() ? path.normalize() : null;
    } catch (RuntimeException ignored) { return null; }
  }
  private JSONObject evidence(Path runDir, Path evidenceDir, Path capturedImage, long began, boolean success) {
    JSONObject result = new JSONObject();
    if (evidenceDir == null || !Files.isDirectory(evidenceDir)) return result;
    result.put("sourceDir", evidenceDir.toString());
    try (var files = Files.list(evidenceDir)) {
      List<Path> images = files.filter(Files::isRegularFile).filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".png"))
          .filter(p -> { try { return Files.getLastModifiedTime(p).toMillis() >= began; } catch (IOException e) { return false; } })
          .sorted(Comparator.comparingInt((Path p) -> evidencePriority(p, success)).thenComparing(p -> p.getFileName().toString(), Comparator.reverseOrder())).collect(java.util.stream.Collectors.toList());
      Path image = images.isEmpty() ? null : images.get(0);
      // A captured image can use a different extension; it must still be fresh.
      if (image == null && capturedImage != null && Files.isRegularFile(capturedImage)
          && Files.getLastModifiedTime(capturedImage).toMillis() >= began) image = capturedImage;
      if (image == null) return result;
      Path saved = runDir.resolve(image.getFileName());
      Files.copy(image, saved, StandardCopyOption.REPLACE_EXISTING);
      result.put("sourceImagePath", image.toString()).put("imagePath", saved.toString());
    } catch (IOException ignored) {
      // Evidence is optional and must never change the execution outcome.
    }
    return result;
  }
  private static int evidencePriority(Path image, boolean success) {
    String name = image.getFileName().toString().toLowerCase(Locale.ROOT);
    if (!success && name.contains("failure")) return 0;
    if (success && name.contains("keyword-column")) return 0;
    if (name.contains("final-page")) return 1;
    return 2;
  }
  private static String friendlyError(String raw) {
    if (raw.startsWith("OCULIX_ERROR ") && raw.substring(13).matches("(?s).*[\\p{IsHan}].*")) return raw.substring(13).trim();
    if (raw.contains("Image timeout") && raw.contains("market")) return "未找到“市场”入口。请检查页面状态或更新入口图片模板后重试。";
    if (raw.contains("mouse button") || raw.contains("modifier is held")) return "检测到鼠标或修饰键仍被按下，请松开后重试。";
    if (raw.contains("foreground")) return "目标窗口失去焦点，执行已停止。请关闭遮挡窗口后重试。";
    if (raw.contains("Image timeout") || raw.contains("FindFailed")) return "未找到预期的页面元素。请确认目标页面已打开且没有弹窗遮挡。";
    if (raw.contains("Expected one visible")) return "未找到唯一的千牛工作台，请确认已登录并打开目标窗口。";
    if (raw.contains("OCR") || raw.contains("关键词")) return "关键词读取未完成，请查看结果目录中的截图和诊断日志。";
    if (raw.contains("任务文件不存在") || raw.contains("执行引擎文件不存在") || raw.contains("结果文件不存在")) return raw;
    if (raw.contains("任务报告了执行步骤失败")) return raw;
    return "任务未完成。请检查目标软件状态，诊断日志已保存。";
  }
  private static String strongerFailure(String previous, String candidate) {
    return failurePriority(candidate) > failurePriority(previous) ? candidate.length() > 2000 ? candidate.substring(0, 2000) : candidate : previous;
  }
  private static int failurePriority(String value) {
    if (value.startsWith("OCULIX_ERROR ")) return 200;
    if (value.startsWith("[DEBUG STARTUP]")) return 0;
    if (value.contains("Image timeout") || value.contains("FindFailed") || value.contains("Expected one visible")
        || value.contains("foreground") || value.contains("modifier is held") || value.contains("OCR failed") || value.contains("OCR timeout")) return 100;
    if (value.contains(" STOP ") || value.startsWith("STOP ")) return 90;
    if (value.contains("Exception") || value.contains("任务文件不存在") || value.contains("执行引擎文件不存在") || value.contains("结果文件不存在")) return 60;
    if (value.contains("RuntimeError")) return 20;
    if (value.startsWith("[error]") && !value.contains("Traceback")) return 10;
    return 0;
  }
  private static String failureCode(String value) {
    if (value.startsWith("OCULIX_ERROR ")) return "TASK_ERROR";
    if (value.contains("Image timeout") || value.contains("FindFailed")) return "IMAGE_NOT_FOUND";
    if (value.contains("foreground")) return "WINDOW_FOCUS_LOST";
    if (value.contains("Expected one visible")) return "TARGET_WINDOW_NOT_FOUND";
    if (value.contains("OCR")) return "OCR_FAILED";
    return "EXECUTION_FAILED";
  }

  static JSONObject parseResult(Path path) throws IOException {
    if (!Files.isRegularFile(path)) throw new IOException("结果文件不存在。");
    if (Files.size(path) > 16 * 1024 * 1024) throw new IOException("结果文件过大，请分批返回。");
    String raw = Files.readString(path, StandardCharsets.UTF_8).replaceFirst("^\\uFEFF", "");
    JSONArray rows = new JSONArray();
    if (path.toString().toLowerCase(Locale.ROOT).endsWith(".json")) {
      Object parsed = new JSONTokener(raw).nextValue();
      if (parsed instanceof JSONArray) rows = normalizeRows((JSONArray) parsed);
      else if (parsed instanceof JSONObject) {
        JSONObject obj = (JSONObject) parsed;
        if (obj.optJSONArray("lines") != null) {
          JSONArray lines = obj.getJSONArray("lines");
          for (int i = 0; i < lines.length(); i++) rows.put(new JSONObject().put("序号", i + 1).put("关键词", lines.getJSONObject(i).optString("text")));
        } else {
          for (String key : new String[]{"rows", "data", "list"}) if (obj.optJSONArray(key) != null) { rows = normalizeRows(obj.getJSONArray(key)); break; }
          if (rows.length() == 0 && obj.length() > 0) rows.put(obj);
        }
      } else rows.put(new JSONObject().put("内容", String.valueOf(parsed)));
      return new JSONObject().put("rows", rows);
    }
    for (String line : raw.split("\\R")) if (!line.isBlank()) rows.put(new JSONObject().put("序号", rows.length() + 1).put("内容", line));
    return new JSONObject().put("rows", rows).put("text", raw);
  }
  private static JSONArray normalizeRows(JSONArray values) {
    JSONArray rows = new JSONArray();
    for (int i = 0; i < values.length(); i++) { Object value = values.get(i); rows.put(value instanceof JSONObject ? value : new JSONObject().put("序号", i + 1).put("内容", value)); }
    return rows;
  }
  private static String resultText(JSONObject result) {
    StringBuilder text = new StringBuilder(result.optString("taskName")).append("\n执行时间：").append(result.optString("startedAt")).append("\n\n");
    JSONArray rows = result.getJSONArray("rows");
    for (int i = 0; i < rows.length(); i++) {
      JSONObject row = rows.getJSONObject(i);
      if (row.has("关键词")) text.append(i + 1).append(". ").append(row.optString("关键词"));
      else text.append(row.toString());
      text.append('\n');
    }
    return text.toString();
  }

  public void stop() {
    String runId;
    synchronized (this) {
      if (!isRunning()) return;
      if (!timedOut) cancelled = true;
      current.put("phase", "stopping").put("stage", "正在停止").put("summary", "正在结束执行进程…");
      health(current, System.currentTimeMillis()); saveCurrent(); runId = current.getString("runId");
    }
    requestTermination(runId);
  }
  private void requestTermination(String runId) {
    try { control.execute(() -> terminateRun(runId)); } catch (java.util.concurrent.RejectedExecutionException ignored) { /* close already owns termination. */ }
  }
  private void terminateRun(String runId) {
    Process process;
    List<ProcessHandle> descendants;
    synchronized (processLock) {
      if (!runId.equals(activeRunId)) return;
      process = child;
      if (process != null) process.descendants().forEach(p -> ownedProcesses.put(p.pid(), p));
      descendants = new ArrayList<>(ownedProcesses.values());
    }
    // Terminate the launcher first so it cannot spawn more work while descendants are stopped.
    if (process != null && process.isAlive()) process.destroyForcibly();
    Collections.reverse(descendants);
    descendants.forEach(handle -> { if (handle.isAlive()) handle.destroyForcibly(); });
    try { if (process != null) process.waitFor(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
  }
  private synchronized void saveCurrent() {
    JSONObject saved = copy(current);
    try {
      stateWriter.execute(() -> {
        try { Path file = Paths.get(saved.getString("resultDir")).resolve("运行.json"); Files.createDirectories(file.getParent()); writeJson(file, saved.toString(2)); }
        catch (IOException e) { synchronized (this) { if (saved.optString("runId").equals(current.optString("runId"))) current.put("storageWarning", "运行记录写入失败，请检查磁盘空间或文件权限。"); } }
      });
    } catch (java.util.concurrent.RejectedExecutionException ignored) { /* Writer drains only after the worker has stopped. */ }
  }
  private static void writeJson(Path target, String json) throws IOException {
    Path tmp = target.resolveSibling(target.getFileName() + ".tmp"); Files.writeString(tmp, json, StandardCharsets.UTF_8);
    try { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
    catch (AtomicMoveNotSupportedException e) { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING); }
  }
  private void loadRecords() throws IOException {
    try (var stream = Files.list(recordsDir)) {
      for (Path dir : stream.filter(Files::isDirectory).collect(Collectors.toList())) {
        Path recordFile = dir.resolve("运行.json"); if (!Files.isRegularFile(recordFile)) continue;
        try {
          JSONObject record = new JSONObject(Files.readString(recordFile));
          if (record.optBoolean("running")) {
            record.put("running", false).put("status", "failed").put("stage", "执行中断").put("summary", "上次执行意外中断，未确认完成。")
                .put("rows", new JSONArray()).put("resultCount", 0).put("verified", false);
            writeJson(recordFile, record.toString(2));
          }
          records.add(record);
        } catch (RuntimeException ignored) { /* A corrupt individual record does not hide other history. */ }
      }
    }
  }
  private void importExistingSycm() throws IOException {
    if (records.stream().anyMatch(r -> "sycm-keywords".equals(r.optString("taskId")))) return;
    Path legacy = workspace.resolve("oculix-flow/runs"); if (!Files.isDirectory(legacy)) return;
    List<Path> dirs;
    try (var stream = Files.list(legacy)) { dirs = stream.filter(Files::isDirectory).sorted(Comparator.reverseOrder()).collect(Collectors.toList()); }
    for (Path dir : dirs) {
      Path result = dir.resolve("ocr-result.json"), log = dir.resolve("execution.log");
      if (!Files.isRegularFile(result) || !Files.isRegularFile(log)) continue;
      String logText = Files.readString(log); if (!logText.contains("COMPLETED_PENDING_QA")) continue;
      try {
        JSONObject parsed = parseResult(result); if (parsed.getJSONArray("rows").length() != 10) continue;
        String stamp = Files.getLastModifiedTime(result).toInstant().toString();
        JSONObject record = idle().put("taskId", "sycm-keywords").put("taskName", "生意参谋关键词")
            .put("runId", "import-" + dir.getFileName()).put("status", "success").put("stage", "执行完成")
            .put("startedAt", stamp).put("finishedAt", stamp).put("imported", true).put("summary", "已获取 10 条关键词（历史记录）")
            .put("rows", parsed.getJSONArray("rows")).put("resultCount", 10).put("sourceDir", dir.toString())
            .put("sourceResultPath", result.toString()).put("imagePath", dir.resolve("keyword-column.png").toString()).put("logPath", log.toString());
        Path recordDir = recordsDir.resolve(record.getString("runId")); Files.createDirectories(recordDir); record.put("resultDir", recordDir.toString());
        Path qa = dir.resolve("核对结果.json");
        if (Files.isRegularFile(qa)) { JSONObject validation = new JSONObject(Files.readString(qa)); record.put("verified", "VERIFIED".equals(validation.optString("status"))).put("metadata", validation); }
        writeJson(recordDir.resolve("运行.json"), record.toString(2)); Files.writeString(recordDir.resolve("结果.txt"), resultText(record), StandardCharsets.UTF_8);
        records.add(record); break;
      } catch (RuntimeException ignored) { /* Legacy experimental output is not a successful result. */ }
    }
  }

  private void loadConnection() throws IOException {
    Path config = dataDir.resolve("连接.json");
    if (Files.isRegularFile(config)) {
      JSONObject stored = new JSONObject(Files.readString(config)); token = stored.optString("token"); apiPort = stored.optInt("port", 18986);
    }
    if (token == null || token.length() < 32) token = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    if (apiPort < 1024 || apiPort > 65535) apiPort = 18986;
    writeJson(config, new JSONObject().put("token", token).put("port", apiPort).toString(2));
  }
  public synchronized JSONObject connection() {
    return new JSONObject().put("enabled", server != null).put("url", "http://127.0.0.1:" + apiPort)
        .put("token", token).put("status", server == null ? "未开启" : "仅接受本机指令");
  }
  public synchronized void setApiEnabled(boolean enabled) {
    if (closed) throw new IllegalStateException("执行器已经关闭。");
    if (!enabled) { if (server != null) server.stop(0); server = null; if (apiWorker != null) apiWorker.shutdownNow(); apiWorker = null; return; }
    if (server != null) return;
    try {
      HttpServer candidate = HttpServer.create(new InetSocketAddress("127.0.0.1", apiPort), 8);
      candidate.createContext("/api/", this::handleApi);
      apiWorker = Executors.newFixedThreadPool(2, r -> { Thread t = new Thread(r, "本机指令接口"); t.setDaemon(true); return t; });
      candidate.setExecutor(apiWorker); candidate.start(); server = candidate;
    } catch (IOException e) { throw new IllegalStateException("本机指令端口已被占用，无法开启连接。", e); }
  }
  private void handleApi(HttpExchange exchange) throws IOException {
    try {
      String auth = exchange.getRequestHeaders().getFirst("Authorization");
      if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress() || exchange.getRequestHeaders().containsKey("Origin")
          || auth == null || !MessageDigest.isEqual(("Bearer " + token).getBytes(StandardCharsets.UTF_8), auth.getBytes(StandardCharsets.UTF_8))) {
        respond(exchange, 403, new JSONObject().put("error", "本机身份验证失败。")); return;
      }
      String path = exchange.getRequestURI().getPath(), method = exchange.getRequestMethod();
      if (method.equals("GET")) {
        switch (path) {
          case "/api/health": respond(exchange, 200, snapshot()); return;
          case "/api/tasks": respond(exchange, 200, new JSONObject().put("tasks", tasks())); return;
          case "/api/runs": respond(exchange, 200, new JSONObject().put("runs", history())); return;
        }
      } else if (method.equals("POST") && (path.equals("/api/run") || path.equals("/api/stop"))) {
        byte[] body = exchange.getRequestBody().readNBytes(65537);
        if (body.length > 65536) { respond(exchange, 413, new JSONObject().put("error", "指令内容过大。")); return; }
        if (path.equals("/api/run")) run(new JSONObject(new String(body, StandardCharsets.UTF_8)).getString("taskId")); else stop();
        respond(exchange, 200, snapshot()); return;
      }
      respond(exchange, 404, new JSONObject().put("error", "不支持的指令。"));
    } catch (IllegalStateException e) { respond(exchange, 409, new JSONObject().put("error", e.getMessage())); }
      catch (RuntimeException e) { respond(exchange, 400, new JSONObject().put("error", "指令格式不正确，请检查任务编号。")); }
      finally { exchange.close(); }
  }
  private static void respond(HttpExchange exchange, int status, JSONObject body) throws IOException {
    byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
  }
  @Override public void close() {
    String runId;
    HttpServer api;
    java.util.concurrent.ExecutorService requests;
    synchronized (this) {
      closed = true;
      if (!timedOut) cancelled = true;
      if (current.optBoolean("running")) { current.put("phase", "stopping").put("stage", "正在停止").put("summary", "正在结束执行进程…"); saveCurrent(); }
      runId = activeRunId;
      api = server; server = null;
      requests = apiWorker; apiWorker = null;
    }
    if (api != null) api.stop(0);
    if (requests != null) requests.shutdownNow();
    monitor.shutdownNow();
    terminateRun(runId);
    worker.shutdown();
    boolean interrupted = false;
    try { worker.awaitTermination(5, TimeUnit.SECONDS); }
    catch (InterruptedException e) { interrupted = true; }
    // Never hold the service monitor while joining the worker: its final state
    // write needs the same monitor. Recheck ownership after the bounded wait.
    terminateRun(runId);
    if (!worker.isTerminated()) {
      worker.shutdownNow();
      try { worker.awaitTermination(2, TimeUnit.SECONDS); }
      catch (InterruptedException e) { interrupted = true; }
    }
    control.shutdownNow();
    stateWriter.shutdown();
    try { stateWriter.awaitTermination(3, TimeUnit.SECONDS); }
    catch (InterruptedException e) { interrupted = true; }
    if (interrupted) Thread.currentThread().interrupt();
  }
}
