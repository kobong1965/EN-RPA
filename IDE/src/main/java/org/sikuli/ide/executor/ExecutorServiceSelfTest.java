package org.sikuli.ide.executor;

import org.json.JSONArray;
import org.json.JSONObject;
import javax.tools.ToolProvider;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.jar.*;

/** Explicit non-GUI regression suite. Runs a tiny fake child JVM, never OculiX or desktop input. */
public final class ExecutorServiceSelfTest {
  private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
  private static void finished(ExecutorService service) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    while (service.isRunning() && System.nanoTime() < deadline) Thread.sleep(25);
    check(!service.isRunning(), "test task did not finish");
  }
  public static void main(String[] args) throws Exception {
    Path root = Files.createTempDirectory("oculix-executor-test-");
    Path jar = fakeEngine(root);
    Path script = root.resolve("任务.py");
    Files.writeString(script, "success");
    String id;
    try (ExecutorService service = new ExecutorService(root, jar)) {
      check(!service.connection().getBoolean("enabled"), "API must start disabled");
      id = service.importTask(script).getString("id");
      check(service.importTask(script).getString("id").equals(id), "import must be idempotent");
      service.setExecutionAllowed(() -> false);
      boolean advancedBlocked = false;
      try { service.run(id); } catch (IllegalStateException expected) { advancedBlocked = expected.getMessage().equals("请先返回执行中心，再运行任务。"); }
      check(advancedBlocked && !service.isRunning() && service.history().isEmpty(), "advanced tools guard rejects start without creating a run");
      check(service.tasks().length() == 1, "guard keeps read-only task access available");
      service.setExecutionAllowed(() -> true);
      service.run(id); finished(service);
      JSONObject good = service.snapshot();
      check(good.getString("status").equals("success"), "successful task status");
      check(good.getJSONArray("rows").getJSONObject(0).getString("关键词").equals("中文结果"), "UTF-8 result parsing");
      check(!good.getBoolean("verified"), "new result must not be marked manually reviewed");
      check(Files.exists(Paths.get(good.getString("resultDir"), "结果.txt")), "export text saved");
      Files.writeString(script, "fail");
      service.run(id);
      check(service.snapshot().getJSONArray("rows").isEmpty(), "new run must clear previous rows immediately");
      finished(service);
      check(service.snapshot().getString("status").equals("failed"), "failed task status");
      check(service.result(id).getJSONArray("rows").isEmpty(), "failed run must not reuse successful results");
      check(service.history().length() == 2, "history contains both attempts");
      Files.writeString(script, "slow"); service.run(id);
      Path pidFile = root.resolve("grandchild.pid");
      long deadline = System.nanoTime() + Duration.ofSeconds(12).toNanos();
      while (!Files.exists(pidFile) && service.isRunning() && System.nanoTime() < deadline) Thread.sleep(25);
      check(Files.exists(pidFile), "fake descendant started");
      long pid = Long.parseLong(Files.readString(pidFile));
      boolean rejected = false;
      try { service.run(id); } catch (IllegalStateException expected) { rejected = true; }
      check(rejected, "concurrent runs must be rejected");
      service.stop(); finished(service);
      check(service.snapshot().getString("status").equals("cancelled"), "cancel must not report success");
      check(service.snapshot().getJSONArray("rows").isEmpty(), "cancel must not expose prior data");
      deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
      while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < deadline) Thread.sleep(25);
      check(!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "descendant must terminate");
    }
    try (ExecutorService restored = new ExecutorService(root, jar)) {
      check(restored.tasks().length() == 1, "task restored");
      check(restored.history().length() == 3, "history restored");
      check(restored.result(id).getString("status").equals("cancelled"), "latest cancellation persists");
      restored.removeTask(id); check(restored.tasks().isEmpty(), "unregister task");
      check(Files.exists(script), "unregister must preserve source");
    }
    Path text = root.resolve("文字.txt"); Files.writeString(text, "第一行\n第二行\n");
    check(ExecutorService.parseResult(text).getJSONArray("rows").length() == 2, "plain text results");
    Path array = root.resolve("数组.json"); Files.writeString(array, "[\"一\",\"二\"]");
    check(ExecutorService.parseResult(array).getJSONArray("rows").getJSONObject(1).getString("内容").equals("二"), "array results");
    testInterrupted(root, jar);
    testEvidence(root, jar);
    testCommand(root, jar);
    testCloseDuringStart(root, jar);
    testTelemetryAndTimeout(root, jar);
    testFailurePriority(root, jar);
    testCompleteSycmSteps(root, jar);
    testSycmAccount(root, jar);
    testApi(root, jar);
    System.out.println("PASS: account configuration preservation/validation, seven-step workflow, structured step authority, honest legacy steps, description migration, advanced-tools guard, UTF-8 results, fresh-run isolation, root-cause priority, nonblocking starts/stops, heartbeat persistence, silence warning, configured timeout, process-tree cancellation, close-during-start ownership, persistence, import/remove, plain text, arrays, interrupted recovery, current-run evidence, child preferences, API authentication.");
    System.out.println("Test artifacts: " + root);
  }
  private static void testEvidence(Path root, Path jar) throws Exception {
    Path evidenceRoot = root.resolve("evidence-test"); Files.createDirectories(evidenceRoot);
    Path script = evidenceRoot.resolve("evidence.py"); Files.writeString(script, "fail-evidence");
    try (ExecutorService service = new ExecutorService(evidenceRoot, jar)) {
      String id = service.importTask(script).getString("id");
      service.run(id); finished(service);
      JSONObject failed = service.snapshot();
      check(failed.getString("status").equals("failed") && failed.getJSONArray("rows").isEmpty(), "evidence never changes failed outcome");
      check(Files.isRegularFile(Paths.get(failed.getString("imagePath"))), "current failure image saved");
      check(Paths.get(failed.getString("imagePath")).getParent().equals(Paths.get(failed.getString("resultDir"))), "evidence copied into this run");
      Files.writeString(script, "fail"); service.run(id); finished(service);
      check(!service.snapshot().has("imagePath") && !service.snapshot().has("sourceDir"), "missing marker must not reuse earlier evidence");
      Files.writeString(script, "stale-evidence"); service.run(id); finished(service);
      check(!service.snapshot().has("imagePath"), "old image in explicitly reported directory must not become fresh evidence");
      Files.writeString(script, "fail-captured"); service.run(id); finished(service);
      check(Files.isRegularFile(Paths.get(service.snapshot().getString("imagePath"))), "CAPTURED can identify current evidence");
    }
  }
  private static void testCommand(Path root, Path jar) {
    String previous = System.getProperty("oculix.preferencesNode");
    try {
      System.setProperty("oculix.preferencesNode", "org/zhishou/test");
      List<String> command = ExecutorService.command(root, jar, root.resolve("任务.py"));
      check(command.contains("-Doculix.executorChild=true"), "child avoids duplicate abort hook");
      check(command.contains("-Doculix.preferencesNode=org/zhishou/test"), "child shares parent's preferences namespace");
    } finally {
      if (previous == null) System.clearProperty("oculix.preferencesNode"); else System.setProperty("oculix.preferencesNode", previous);
    }
  }
  private static void testCloseDuringStart(Path root, Path jar) throws Exception {
    Path closeRoot = root.resolve("close-during-start"); Files.createDirectories(closeRoot);
    Path script = closeRoot.resolve("blocking.py"); Files.writeString(script, "slow");
    java.util.concurrent.CountDownLatch created = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch allowAssignment = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch closeEntered = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch closeReturned = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.atomic.AtomicReference<Process> launched = new java.util.concurrent.atomic.AtomicReference<>();
    ExecutorService service = new ExecutorService(closeRoot, jar, builder -> {
      Process child = builder.start(); launched.set(child); created.countDown();
      try {
        if (!allowAssignment.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IOException("test start gate timeout");
      } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
      return child;
    });
    Thread closer = null;
    try {
      String id = service.importTask(script).getString("id"); service.run(id);
      check(created.await(10, java.util.concurrent.TimeUnit.SECONDS), "native child started before assignment gate");
      Path pidFile = closeRoot.resolve("grandchild.pid");
      long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
      while (!Files.exists(pidFile) && System.nanoTime() < deadline) Thread.sleep(25);
      check(Files.exists(pidFile), "close test descendant started");
      long descendant = Long.parseLong(Files.readString(pidFile));
      long pollBegan = System.nanoTime();
      check(service.snapshot().getString("phase").equals("starting"), "startup phase visible before native ownership assignment");
      check(Duration.ofNanos(System.nanoTime() - pollBegan).toMillis() < 300, "slow native start must not block state polling");
      closer = new Thread(() -> { closeEntered.countDown(); try { service.close(); } finally { closeReturned.countDown(); } });
      closer.start(); check(closeEntered.await(2, java.util.concurrent.TimeUnit.SECONDS), "close entered");
      check(!closeReturned.await(150, java.util.concurrent.TimeUnit.MILLISECONDS), "close cannot pass unassigned native process");
      allowAssignment.countDown();
      check(closeReturned.await(12, java.util.concurrent.TimeUnit.SECONDS), "close must complete without lock/worker deadlock");
      check(!launched.get().isAlive(), "close terminates launcher before returning");
      check(!ProcessHandle.of(descendant).map(ProcessHandle::isAlive).orElse(false), "close terminates descendant before returning");
      check(!service.isRunning() && service.snapshot().getString("status").equals("cancelled"), "close persists cancelled completion");
      boolean rejected = false;
      try { service.run(id); } catch (IllegalStateException expected) { rejected = true; }
      check(rejected, "closed service rejects later runs");
    } finally {
      allowAssignment.countDown();
      if (closer != null) closer.join(12000);
      service.close();
      Process child = launched.get(); if (child != null && child.isAlive()) child.destroyForcibly();
    }
  }
  private static JSONObject waitRecord(ExecutorService service, java.util.function.Predicate<JSONObject> condition) throws Exception {
    Path record = Paths.get(service.snapshot().getString("resultDir"), "运行.json");
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (System.nanoTime() < deadline) {
      try { JSONObject saved = new JSONObject(Files.readString(record)); if (condition.test(saved)) return saved; }
      catch (IOException | RuntimeException ignored) { }
      Thread.sleep(25);
    }
    throw new AssertionError("persisted heartbeat/result not observed: " + record);
  }
  private static void testTelemetryAndTimeout(Path root, Path jar) throws Exception {
    Path workspace = root.resolve("telemetry"); Files.createDirectories(workspace);
    Path script = workspace.resolve("quiet.py"); Files.writeString(script, "silent");
    String id;
    try (ExecutorService service = new ExecutorService(workspace, jar)) {
      JSONObject imported = service.importTask(script); id = imported.getString("id");
      check(imported.getLong("timeoutMs") == 600000 && imported.getLong("silenceWarningMs") == 30000, "all imported tasks receive default monitoring settings");
      service.configureTimeouts(id, 4500, 1000);
      long start = System.nanoTime(); service.run(id);
      check(Duration.ofNanos(System.nanoTime() - start).toMillis() < 300, "run returns before native startup or disk persistence");
      JSONObject heartbeat = waitRecord(service, record -> record.optBoolean("running") && record.optBoolean("stalled") && record.optLong("durationMs") >= 1000);
      check(heartbeat.getString("phase").equals("starting") && !heartbeat.getString("heartbeatAt").isEmpty(), "silent startup has persisted live heartbeat");
      check(service.isRunning(), "silence warning alone must not fail the task");
      long pid = heartbeat.getLong("pid");
      finished(service);
      JSONObject timeout = service.snapshot();
      check(timeout.getString("status").equals("failed") && timeout.getString("failureCode").equals("TIMEOUT"), "configured timeout is failure, not user cancellation");
      check(!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "timeout terminates the process");
      waitRecord(service, record -> record.optString("failureCode").equals("TIMEOUT") && !record.optBoolean("running"));
      Files.writeString(script, "progress"); service.configureTimeouts(id, 10000, 1000); service.run(id);
      JSONObject progress = waitRecord(service, record -> record.optString("stage").equals("正在读取业务列表") && record.optString("phase").equals("running"));
      check(!progress.getString("lastOutputAt").isBlank() && progress.getString("healthMessage").contains("任务正在执行"), "generic progress has a human-readable live state");
      finished(service); check(service.snapshot().getString("status").equals("success"), "reasonable longer task can finish with configured timeout");
      Files.writeString(script, "silent"); service.run(id);
      waitRecord(service, record -> record.optLong("pid") > 0);
      long stopStart = System.nanoTime(); service.stop();
      check(Duration.ofNanos(System.nanoTime() - stopStart).toMillis() < 300, "stop does not wait for process termination on UI thread");
      finished(service); check(service.snapshot().getString("status").equals("cancelled"), "manual stop remains distinct from timeout");
    }
    try (ExecutorService restored = new ExecutorService(workspace, jar)) {
      JSONObject task = restored.tasks().getJSONObject(0);
      check(task.getLong("timeoutMs") == 10000 && task.getLong("silenceWarningMs") == 1000, "task execution settings persist");
    }
  }
  private static void testFailurePriority(Path root, Path jar) throws Exception {
    Path workspace = root.resolve("failure-priority"); Files.createDirectories(workspace);
    Path script = workspace.resolve("error.py"); Files.writeString(script, "root-error");
    try (ExecutorService service = new ExecutorService(workspace, jar)) {
      String id = service.importTask(script).getString("id"); service.run(id); finished(service);
      JSONObject failed = service.snapshot();
      check(failed.getString("rootCause").contains("Image timeout: [market-active]"), "wrapper RuntimeError must not replace original image timeout");
      check(failed.getString("summary").contains("市场") && failed.getString("failureCode").equals("IMAGE_NOT_FOUND"), "specific translated failure remains visible");
      Files.writeString(script, "explicit-error"); service.run(id); finished(service);
      check(service.snapshot().getString("summary").equals("页面中的市场入口无法识别，请更新图片模板。"), "explicit Chinese task error outranks later traceback");
    }
  }
  private static void allPending(JSONArray steps, int from, String reason) {
    for (int i = from; i < steps.length(); i++) check(steps.getJSONObject(i).getString("status").equals("pending"), reason + " at " + i);
  }
  private static void testCompleteSycmSteps(Path root, Path jar) throws Exception {
    Path workspace = root.resolve("sycm-steps"); Path bundle = workspace.resolve("OculiX/生意参谋关键词.sikuli"); Files.createDirectories(bundle);
    Path script = bundle.resolve("生意参谋关键词.py"); Files.writeString(script, "sycm-steps");
    Path data = workspace.resolve("执行数据"); Files.createDirectories(data);
    JSONObject old = new JSONObject().put("id", "sycm-keywords").put("name", "生意参谋关键词").put("path", script.toString())
        .put("description", "获取当前搜索排行的前10个关键词").put("type", "网页数据采集");
    Files.writeString(data.resolve("任务.json"), new JSONArray().put(old).toString());
    try (ExecutorService service = new ExecutorService(workspace, jar)) {
      check(service.tasks().getJSONObject(0).getString("description").startsWith("从打开千牛进入工作台"), "old built-in description migrates to full workflow");
      check(Files.readString(data.resolve("任务.json")).contains("从打开千牛进入工作台"), "description migration is persisted");
      JSONArray preview = service.taskSteps("sycm-keywords"); check(preview.length() == 7, "full workflow has seven steps"); allPending(preview, 0, "idle preview must not imply execution");
      service.run("sycm-keywords"); allPending(service.snapshot().getJSONArray("steps"), 0, "engine startup must not mark business steps complete");
      JSONObject preparing = waitRecord(service, r -> r.optString("stage").equals("正在准备执行引擎"));
      check(preparing.getString("phase").equals("starting"), "preparation stays outside business steps"); allPending(preparing.getJSONArray("steps"), 0, "preparation text cannot jump to task steps");
      Files.writeString(bundle.resolve("go-open"), "go");
      JSONObject opened = waitRecord(service, r -> r.optString("stage").equals("准备读取关键词（未来步骤提示）"));
      check(opened.getString("activeStepId").equals("open-qianniu") && opened.getJSONArray("steps").getJSONObject(0).getString("status").equals("running"), "progress cannot override explicit open-qianniu event");
      allPending(opened.getJSONArray("steps"), 1, "later steps remain unstarted");
      Files.writeString(bundle.resolve("go-workbench"), "go");
      JSONObject workbench = waitRecord(service, r -> r.optString("activeStepId").equals("enter-workbench"));
      check(workbench.getJSONArray("steps").getJSONObject(0).getString("status").equals("done"), "explicit verified open step completed");
      allPending(workbench.getJSONArray("steps"), 2, "workbench does not imply sycm opened");
      Files.writeString(bundle.resolve("go-sycm"), "go");
      JSONObject sycm = waitRecord(service, r -> r.optString("activeStepId").equals("open-sycm"));
      check(sycm.getJSONArray("steps").getJSONObject(3).getString("status").equals("pending"), "legacy click cannot override structured step authority");
      Files.writeString(bundle.resolve("finish-steps"), "go"); finished(service);
      check(service.snapshot().getString("status").equals("success"), "complete structured flow succeeds");
      for (Object item : service.snapshot().getJSONArray("steps")) check(((JSONObject)item).getString("status").equals("done"), "reported complete flow has completed steps");
      Files.writeString(script, "sycm-legacy"); service.run("sycm-keywords");
      JSONObject legacy = waitRecord(service, r -> r.optString("activeStepId").equals("open-sycm"));
      check(legacy.getJSONArray("steps").getJSONObject(0).getString("status").equals("pending") && legacy.getJSONArray("steps").getJSONObject(1).getString("status").equals("pending"), "legacy late start does not invent launcher/workbench completion");
      Files.writeString(bundle.resolve("finish-legacy"), "go"); finished(service);
      check(service.snapshot().getString("status").equals("success"), "legacy output remains compatible");
      check(service.snapshot().getJSONArray("steps").getJSONObject(0).getString("status").equals("pending"), "legacy successful extraction still does not claim unreported launch");
    }
  }
  private static void testInterrupted(Path root, Path jar) throws Exception {
    Path dir = root.resolve("执行数据/运行记录/interrupted"); Files.createDirectories(dir);
    JSONObject record = new JSONObject().put("taskId", "interrupted").put("running", true).put("startedAt", "2025-01-01T00:00:00Z")
        .put("rows", new JSONArray().put("stale")).put("resultCount", 1).put("verified", true);
    Files.writeString(dir.resolve("运行.json"), record.toString());
    try (ExecutorService service = new ExecutorService(root, jar)) {
      JSONObject recovered = service.result("interrupted");
      check(!recovered.getBoolean("running") && recovered.getString("status").equals("failed"), "interrupted status recovery");
      check(recovered.getJSONArray("rows").isEmpty() && !recovered.getBoolean("verified"), "interrupted data must not look complete");
    }
  }
  private static void testApi(Path root, Path jar) throws Exception {
    int port;
    try (java.net.ServerSocket socket = new java.net.ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { port = socket.getLocalPort(); }
    Path config = root.resolve("执行数据/连接.json"); JSONObject stored = new JSONObject(Files.readString(config)); stored.put("port", port); Files.writeString(config, stored.toString());
    try (ExecutorService service = new ExecutorService(root, jar)) {
      service.setApiEnabled(true);
      HttpClient client = HttpClient.newHttpClient();
      URI uri = URI.create(service.connection().getString("url") + "/api/tasks");
      check(client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode() == 403, "API rejects missing token");
      String auth = "Bearer " + service.connection().getString("token");
      check(client.send(HttpRequest.newBuilder(uri).header("Authorization", auth).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode() == 200, "API authorized tasks");
      check(client.send(HttpRequest.newBuilder(uri).header("Authorization", auth).header("Origin", "https://example.com").GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode() == 403, "API rejects browser-origin calls");
      service.setApiEnabled(false); check(!service.connection().getBoolean("enabled"), "API disabled");
    }
  }
  private static void testSycmAccount(Path root, Path jar) throws Exception {
    Path workspace = root.resolve("account-settings"); Path flow = workspace.resolve("oculix-flow"); Files.createDirectories(flow);
    Path file = flow.resolve("千牛启动.properties");
    Files.writeString(file, "\uFEFFshortcut=C:/官方快捷方式/千牛工作台.lnk\nwindowTitle=千牛工作台\nkeepMe=原来的其他配置\n", StandardCharsets.UTF_8);
    try (ExecutorService service = new ExecutorService(workspace, jar)) {
      check(service.sycmAccount().equals("示例账号:店铺"), "missing account property uses explicit default, not arbitrary selection");
      service.configureSycmAccount("  tb123456:测试店铺  ");
      check(service.sycmAccount().equals("tb123456:测试店铺"), "account saves UTF-8 and trims outer spaces");
      Properties settings = new Properties(); try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { settings.load(reader); }
      check(settings.getProperty("shortcut").equals("C:/官方快捷方式/千牛工作台.lnk"), "shortcut preserved including BOM input");
      check(settings.getProperty("windowTitle").equals("千牛工作台") && settings.getProperty("keepMe").equals("原来的其他配置"), "other launch properties preserved");
      String before = Files.readString(file, StandardCharsets.UTF_8);
      for (String invalid : new String[]{"", "  ", "账号\n另一账号", "账号\r", "账号\u2028", "x".repeat(129)}) {
        boolean rejected = false; try { service.configureSycmAccount(invalid); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected && Files.readString(file, StandardCharsets.UTF_8).equals(before), "invalid account must preserve saved configuration");
      }
      Path script = workspace.resolve("account-lock.py"); Files.writeString(script, "silent"); String id = service.importTask(script).getString("id"); service.run(id);
      boolean blocked = false; try { service.configureSycmAccount("另一家店铺"); } catch (IllegalStateException expected) { blocked = true; }
      check(blocked && service.sycmAccount().equals("tb123456:测试店铺"), "running task blocks account changes"); service.stop(); finished(service);
    }
    try (ExecutorService restored = new ExecutorService(workspace, jar)) { check(restored.sycmAccount().equals("tb123456:测试店铺"), "account persists after service restart"); }
  }
  private static Path fakeEngine(Path root) throws Exception {
    String source = """
        import java.nio.file.*;
        public class FakeEngine {
          static void gate(Path dir, String name) throws Exception {
            long until=System.nanoTime()+15000000000L;
            while(!Files.exists(dir.resolve(name)) && System.nanoTime()<until) Thread.sleep(20);
            if(!Files.exists(dir.resolve(name))) throw new IllegalStateException("test step gate timeout: "+name);
          }
          static void step(String id, String status) {
            char q=34; System.out.println("OCULIX_STEP {"+q+"id"+q+":"+q+id+q+","+q+"status"+q+":"+q+status+q+"}"); System.out.flush();
          }
          public static void main(String[] args) throws Exception {
            if (args.length > 0 && args[0].equals("--grandchild")) { Thread.sleep(120000); return; }
            Path script = Paths.get(args[args.length-1]);
            String mode = Files.readString(script).trim();
            if(mode.equals("sycm-steps") || mode.equals("sycm-legacy")) {
              Path dir=script.getParent();
              if(mode.equals("sycm-steps")) {
                System.out.println("[DEBUG STARTUP] initializing engine");
                System.out.println("开始流程：打开千牛、工作台、生意参谋、市场、搜索排行、关键词");
                System.out.println("OCULIX_PROGRESS 正在准备执行引擎"); System.out.flush(); gate(dir,"go-open");
                step("open-qianniu","running"); System.out.println("OCULIX_PROGRESS 准备读取关键词（未来步骤提示）"); System.out.flush(); gate(dir,"go-workbench");
                step("open-qianniu","done"); step("enter-workbench","running"); gate(dir,"go-sycm");
                step("enter-workbench","done"); step("open-sycm","running"); System.out.println("CLICK market-active"); System.out.flush(); gate(dir,"finish-steps");
                step("open-sycm","done");
                for(String id:new String[]{"enter-market","open-ranking","read-keywords"}) {step(id,"running");step(id,"done");}
              } else {
                System.out.println("OCULIX_PROGRESS 正在打开生意参谋"); System.out.flush(); gate(dir,"finish-legacy");
                System.out.println("OCULIX_PROGRESS 生意参谋已确认，正在进入市场");
                System.out.println("OCULIX_PROGRESS 市场已确认，正在打开搜索排行");
                System.out.println("OCULIX_PROGRESS 搜索排行已确认，正在读取关键词");
              }
              Path result=dir.resolve("步骤结果.json"); Files.writeString(result,"[1,2,3,4,5,6,7,8,9,10]");
              System.out.println("OCULIX_RESULT "+result); System.out.println("COMPLETED_PENDING_QA simulated successful read"); return;
            }
            if (mode.equals("silent")) { Thread.sleep(120000); return; }
            if (mode.equals("progress")) {
              System.out.println("OCULIX_PROGRESS 正在读取业务列表"); System.out.flush(); Thread.sleep(2200);
              System.out.println("OCULIX_PROGRESS 正在整理业务结果"); System.out.flush(); Thread.sleep(1800);
            }
            if (mode.equals("root-error") || mode.equals("explicit-error")) {
              System.out.println("[DEBUG STARTUP] native loader InvocationTargetException recovered");
              if (mode.equals("explicit-error")) System.out.println("OCULIX_ERROR 页面中的市场入口无法识别，请更新图片模板。");
              System.out.println(java.time.Instant.now() + " STOP java.lang.IllegalStateException: Image timeout: [market-active]");
              System.out.println("[error] RuntimeError ( 流程未完成，退出码：2；请查看上方日志 )");
              System.out.println("[error] --- Traceback --- end --------------"); System.exit(2);
            }
            if (mode.equals("fail")) { System.out.println("STOP simulated failure"); System.exit(2); }
            if (mode.equals("fail-evidence") || mode.equals("stale-evidence") || mode.equals("fail-captured")) {
              Path evidence = script.getParent().resolve("evidence-" + java.util.UUID.randomUUID()); Files.createDirectories(evidence);
              Path image = evidence.resolve(mode.equals("fail-captured") ? "keyword-column.png" : "02-failure.png");
              Files.writeString(image, "simulated image file, no desktop capture");
              if (mode.equals("stale-evidence")) Files.setLastModifiedTime(image, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()-60000));
              System.out.println(java.time.Instant.now() + (mode.equals("fail-captured") ? " CAPTURED " + image : " RUN_DIR " + evidence));
              System.exit(2);
            }
            if (mode.equals("slow")) {
              String java = Paths.get(System.getProperty("java.home"),"bin","java.exe").toString();
              String jar = Paths.get(FakeEngine.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
              Process child = new ProcessBuilder(java,"-jar",jar,"--grandchild").start();
              Files.writeString(script.getParent().resolve("grandchild.pid"),Long.toString(child.pid()));
              Thread.sleep(120000); return;
            }
            Path result = script.getParent().resolve("结果.json");
            Files.writeString(result,"{\\"lines\\":[{\\"text\\":\\"中文结果\\"},{\\"text\\":\\"second\\"}]}");
            System.out.println("OCULIX_RESULT " + result);
          }
        }
        """;
    Path java = root.resolve("FakeEngine.java"); Files.writeString(java, source, StandardCharsets.UTF_8);
    int compile = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-encoding", "UTF-8", "-d", root.toString(), java.toString());
    check(compile == 0, "fake JVM compilation");
    Manifest manifest = new Manifest(); manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0"); manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "FakeEngine");
    Path jar = root.resolve("fake-engine.jar");
    try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) { out.putNextEntry(new JarEntry("FakeEngine.class")); Files.copy(root.resolve("FakeEngine.class"), out); out.closeEntry(); }
    return jar;
  }
}
