package org.sikuli.ide;

import net.miginfocom.swing.MigLayout;
import org.json.JSONArray;
import org.json.JSONObject;
import org.sikuli.ide.executor.ExecutorService;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.prefs.Preferences;

/** Result-first desktop surface. Execution stays in the original OculiX runners. */
public final class ExecutorDashboard extends JPanel {
  private static final Color BG = new Color(0xf5f5f7), INK = new Color(0x1d1d1f);
  private static final Color MUTED = new Color(0x68686d), BLUE = new Color(0x0071e3);
  private static final Color LINE = new Color(0xe8e8ed), GREEN = new Color(0x237a40);
  private static final Color RED = new Color(0xc92a25);
  private final SikulixIDE owner;
  private final ExecutorService service;
  private final Path workspace;
  private final JPanel content = new JPanel(new BorderLayout());
  private final JLabel connectionLabel = label("本机执行器已就绪", 12, MUTED, false);
  private final Map<String, PillButton> nav = new LinkedHashMap<>();
  private final javax.swing.Timer poll;
  private String page = "执行中心", selectedTask = "sycm-keywords", renderKey = "";
  private JSONObject viewedRun;
  private boolean wasRunning, windowMinimizedForRun;
  private int previousWindowState;
  private volatile boolean launchPending, disposed;
  private volatile long launchGeneration;
  private javax.swing.Timer launchTimer;
  private JLabel elapsedLabel, healthLabel;
  private String announcedRun = "";
  private static boolean reduceMotion = Preferences.userNodeForPackage(ExecutorDashboard.class).getBoolean("reduceMotion", false);

  public ExecutorDashboard(SikulixIDE owner, Path workspace, Path jar) {
    this.owner = owner;
    this.workspace = workspace;
    service = new ExecutorService(workspace, jar);
    setLayout(new BorderLayout());
    setBackground(BG);
    add(sidebar(), BorderLayout.WEST);
    content.setOpaque(false);
    add(content, BorderLayout.CENTER);
    render();
    poll = new javax.swing.Timer(700, e -> refresh());
    poll.start();
  }

  public void shutdown() { disposed=true; launchGeneration++; if(launchTimer!=null)launchTimer.stop(); poll.stop(); service.close(); }
  public boolean isRunning() { return launchPending || service.isRunning(); }
  public void stop() {
    if(!SwingUtilities.isEventDispatchThread()){SwingUtilities.invokeLater(this::stop);return;}
    launchGeneration++; launchPending=false;
    if(launchTimer!=null)launchTimer.stop();
    service.stop(); renderKey=""; refresh();
    if(!service.isRunning() && windowMinimizedForRun)restoreAfterRun();
  }
  public void runSelectedTask() { if (!isRunning()) runSelected(); }
  public ExecutorService service() { return service; }

  private JPanel sidebar() {
    JPanel side = new JPanel(new MigLayout("fillx, wrap 1, insets 32 20 24 20, gap 0", "[grow,fill]", "[]16[]40[]8[]8[]8[][grow]20[]12[]"));
    side.setPreferredSize(new Dimension(214, 0));
    side.setBackground(Color.WHITE);
    side.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, LINE));
    JLabel brand = label("执手", 25, INK, true);
    brand.setIcon(new LineIcon("cursor", 26, INK)); brand.setIconTextGap(12);
    side.add(brand);
    side.add(label("桌面任务执行器", 12, MUTED, false));
    String[] names = {"执行中心", "任务库", "运行记录", "设置"};
    String[] icons = {"play", "grid", "history", "settings"};
    for (int i = 0; i < names.length; i++) {
      String name = names[i];
      PillButton button = new PillButton(name, false);
      button.setHorizontalAlignment(SwingConstants.LEFT);
      button.setIcon(new LineIcon(icons[i], 18, MUTED)); button.setIconTextGap(13);
      button.addActionListener(e -> { page = name; viewedRun = null; renderKey = ""; render(); });
      nav.put(name, button); side.add(button, "h 46!");
    }
    side.add(new JLabel(), "growy");
    PillButton advanced = new PillButton("高级工具", false);
    advanced.setIcon(new LineIcon("tools", 17, MUTED)); advanced.setIconTextGap(10);
    advanced.setToolTipText("录制、截图、图像匹配、脚本编辑与原有全部工具");
    advanced.addActionListener(e -> {
      if (isRunning()) { message("请先停止当前任务，再打开高级工具。"); return; }
      owner.showExecutorAdvanced();
    });
    side.add(advanced, "h 42!");
    connectionLabel.setIcon(new DotIcon(GREEN)); connectionLabel.setIconTextGap(7);
    side.add(connectionLabel, "alignx center");
    return side;
  }

  private void refresh() {
    try {
      JSONObject state = service.snapshot();
      boolean running = state.optBoolean("running");
      if (running && !wasRunning) {
        selectedTask = state.optString("taskId", selectedTask); viewedRun = null;
        minimizeForRun();
      }
      String completedId=state.optString("runId");
      boolean completed=!running && !state.optString("finishedAt").isBlank() && !completedId.equals(announcedRun);
      if (completed) {
        announcedRun=completedId; launchPending=false; restoreAfterRun();
        selectedTask=state.optString("taskId",selectedTask);
        page = "执行中心"; viewedRun = null;
      }
      wasRunning = running;
      if (running) owner.setTitle("执手 · "+state.optString("stage","执行中")+" · "+formatDuration(state.optLong("durationMs")));
      connectionLabel.setText(running ? "任务正在执行" : launchPending ? "正在准备启动" : "本机执行器已就绪");
      String key = page + selectedTask + state.optString("status") + state.optString("stage")
          + state.optString("runId") + state.optString("finishedAt") + state.optJSONArray("steps") + service.tasks().length() + launchPending;
      if (!key.equals(renderKey)) { renderKey = key; render(); }
      if(elapsedLabel!=null)elapsedLabel.setText(launchPending ? "启动请求已接收，正在准备…" : "已用时 "+formatDuration(state.optLong("durationMs"))+"  ·  超时上限 "+formatDuration(state.optLong("timeoutMs",600000)));
      if(healthLabel!=null)healthLabel.setText(launchPending ? "任务已收到，即将让出操作窗口。" : state.optString("healthMessage","执行器仍在运行，等待任务返回进度。"));
      if(completed && state.optString("status").equals("failed")) {
        Toolkit.getDefaultToolkit().beep();
        message(state.optString("taskName","任务")+"未完成：\n"+state.optString("summary")+"\n\n失败步骤、截图和日志已保留在结果页。");
      }
    } catch (Exception e) { connectionLabel.setText("状态读取失败，请查看任务记录"); }
  }

  private void minimizeForRun(){
    if(windowMinimizedForRun)return;
    previousWindowState=owner.getExtendedState();windowMinimizedForRun=true;
    owner.setExtendedState(previousWindowState | Frame.ICONIFIED);
  }
  private void restoreAfterRun(){
    if(windowMinimizedForRun)owner.setExtendedState(previousWindowState & ~Frame.ICONIFIED);
    windowMinimizedForRun=false;owner.setVisible(true);owner.toFront();owner.setTitle("执手 · 桌面任务执行器");
  }

  private void render() {
    elapsedLabel=null;healthLabel=null;
    for (Map.Entry<String, PillButton> entry : nav.entrySet()) entry.getValue().setSelected(entry.getKey().equals(page));
    JPanel body = new JPanel(new MigLayout("fillx, wrap 1, insets 0, gap 0 22", "[grow,fill]"));
    body.setOpaque(false);
    String subtitle = switch (page) {
      case "任务库" -> "接收任务，按既定步骤执行。";
      case "运行记录" -> "每次执行，都有结果可查。";
      case "设置" -> "连接你的大脑软件，让执行留在本机。";
      default -> "把操作交给执手，把注意力留给结果。";
    };
    JPanel heading = transparent("fillx, insets 0, gap 0", "[grow][]");
    heading.add(label(page, 32, INK, true), "growx");
    heading.add(pill(launchPending ? "启动中" : service.isRunning() ? "正在执行" : "准备就绪", isRunning() ? BLUE : GREEN));
    heading.add(label(subtitle, 14, MUTED, false), "newline, span, gaptop 8");
    body.add(heading, "gaptop 3, gapbottom 3");
    switch (page) {
      case "任务库" -> renderTasks(body);
      case "运行记录" -> renderHistory(body);
      case "设置" -> renderSettings(body);
      default -> renderExecution(body);
    }
    JPanel centered = new ViewportPanel(new MigLayout("fillx, insets 26 28 26 28, gap 0", "[grow,center]", "[top]"));
    centered.setBackground(BG);
    centered.add(body, "width 0:980:1080, growx, top");
    JScrollPane scroll = new JScrollPane(centered);
    scroll.setBorder(null); scroll.getViewport().setBackground(BG);
    scroll.getVerticalScrollBar().setUnitIncrement(24);
    scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    content.removeAll(); content.add(scroll, BorderLayout.CENTER);
    content.revalidate(); content.repaint();
  }

  private void renderExecution(JPanel body) {
    if (viewedRun != null) {
      body.add(label(viewedRun.optString("taskName", "历史任务"), 20, INK, true));
      renderResult(body, viewedRun);
      body.add(link("返回当前任务", () -> { viewedRun=null; render(); }));
      return;
    }
    JSONArray tasks = service.tasks();
    if (tasks.length() == 0) {
      body.add(empty("还没有任务", "导入外部软件准备好的任务，就可以开始执行。", "导入任务", this::importTask));
      return;
    }
    JSONObject task = findTask(selectedTask);
    if (task == null) { task = tasks.getJSONObject(0); selectedTask = task.getString("id"); }
    JSONObject current = service.snapshot();
    boolean running = launchPending || current.optBoolean("running");
    JPanel taskCard = card("fillx, insets 23 26 22 26, gap 0", "[grow,fill]24[]");
    taskCard.add(label("当前任务", 12, MUTED, false), "span");
    DefaultComboBoxModel<TaskOption> model = new DefaultComboBoxModel<>();
    for (int i = 0; i < tasks.length(); i++) model.addElement(new TaskOption(tasks.getJSONObject(i)));
    JComboBox<TaskOption> chooser = new JComboBox<>(model);
    chooser.setFont(font(21, true)); chooser.setBackground(Color.WHITE); chooser.setForeground(INK);
    chooser.putClientProperty("JComponent.roundRect", true);
    chooser.putClientProperty("JComponent.sizeVariant", "large");
    for (int i = 0; i < model.getSize(); i++) if (model.getElementAt(i).id.equals(selectedTask)) chooser.setSelectedIndex(i);
    chooser.setEnabled(!running); chooser.getAccessibleContext().setAccessibleName("选择执行任务");
    chooser.addActionListener(e -> { TaskOption t = (TaskOption) chooser.getSelectedItem(); if(t!=null) {selectedTask=t.id; viewedRun=null; renderKey=""; render();} });
    taskCard.add(chooser, "newline, growx, h 42!, gaptop 4");
    PillButton execute = new PillButton(running ? "停止执行" : "开始执行", !running);
    execute.setIcon(new LineIcon(running ? "stop" : "play", 16, running ? RED : Color.WHITE));
    execute.setIconTextGap(9);
    execute.addActionListener(e -> { if(isRunning()) stop(); else runSelected(); });
    taskCard.add(execute, "w 142!, h 44!, spany 2, aligny center");
    taskCard.add(wrapped(task.optString("description"), 13, MUTED), "newline, growx, gaptop 6");
    JSONObject result = service.result(selectedTask);
    JSONArray stepStates=service.taskSteps(selectedTask);
    if(!launchPending){
      JSONObject shown=selectedTask.equals(current.optString("taskId"))?current:result;
      if(shown!=null && shown.optJSONArray("steps")!=null && !shown.optJSONArray("steps").isEmpty())stepStates=shown.optJSONArray("steps");
    }
    JPanel steps = transparent("fillx, wrap 4, insets 0, gap 16 12", "[grow][grow][grow][grow]");
    for (int i = 0; i < stepStates.length(); i++) {
      JSONObject item=stepStates.getJSONObject(i);String state=item.optString("status","pending");
      String prefix=switch(state){case "done"->"已完成";case "running"->"执行中";case "failed"->"未完成";default->String.valueOf(i+1);};
      Color color=switch(state){case "done"->GREEN;case "running"->BLUE;case "failed"->RED;default->MUTED;};
      JLabel step = label(prefix+"  "+item.optString("name"), 12, color, state.equals("running"));
      step.setToolTipText(switch(state){case "done"->"已确认完成";case "running"->"正在执行";case "failed"->"此步骤未完成";default->"尚未报告完成";});
      steps.add(step, "growx");
    }
    taskCard.add(separator(), "newline, span, growx, gaptop 18, gapbottom 12");
    taskCard.add(steps, "newline, span, growx"); body.add(taskCard);

    if (launchPending || (running && selectedTask.equals(current.optString("taskId")))) {
      JPanel active = card("fillx, wrap 1, insets 30, gap 0 18", "[grow,fill]");
      active.add(label(launchPending ? "任务已收到，正在启动" : current.optString("stage","任务正在执行"), 24, INK, true));
      elapsedLabel=label("正在准备执行环境…",14,MUTED,false); active.add(elapsedLabel);
      healthLabel=label("即将进入后台执行",13,MUTED,false);active.add(healthLabel);
      JProgressBar progress = new JProgressBar(); progress.setIndeterminate(true); progress.setForeground(BLUE);
      active.add(progress, "h 5!, growx");
      active.add(wrapped("窗口将暂时最小化，任务栏标题会显示当前步骤和用时；完成或失败后自动返回。运行期间请勿同时操作鼠标。", 12, MUTED));
      body.add(active);
    } else if (result == null || result.isEmpty()) {
      body.add(empty("结果将在这里出现", "点击开始执行，获取的数据、截图和执行状态会直接显示在这里。", null, null));
    } else {
      renderResult(body, result);
    }
    JPanel footer = transparent("fillx, insets 0, gap 12", "[grow][]");
    footer.add(label("仅执行已配置的任务 · 数据保存在这台电脑", 12, MUTED, false));
    footer.add(link("管理任务", () -> {page="任务库"; render();})); body.add(footer);
  }

  private void renderResult(JPanel body, JSONObject result) {
    String status = result.optString("status", "success");
    boolean failure = status.equals("failed") || status.equals("cancelled");
    JSONArray rows = result.optJSONArray("rows"); if(rows==null) rows=new JSONArray();
    boolean verified = result.optBoolean("verified");
    JPanel panel = card("fillx, wrap 1, insets 24 28 23 28, gap 0 12", "[grow,fill]");
    JPanel title = transparent("fillx, insets 0, gap 12", "[][grow][]");
    title.add(label(failure ? (status.equals("cancelled") ? "任务已停止" : "这次执行未完成") : result.optBoolean("imported") ? "上次采集结果" : "获取结果", 22, INK, true));
    title.add(pill(failure ? "未完成" : verified ? "已核对" : "待核对", failure ? RED : verified ? GREEN : BLUE));
    title.add(label(formatTime(result.optString("finishedAt", result.optString("startedAt"))), 12, MUTED, false));
    panel.add(title);
    JSONObject metadata=result.optJSONObject("metadata");
    if(metadata!=null && !metadata.optString("category").isBlank()) {
      panel.add(label(metadata.optString("category")+"   ·   统计日期 "+metadata.optString("statDate")+"   ·   "+metadata.optString("list"),12,MUTED,false));
    }
    if (failure) {
      panel.add(wrapped(result.optString("summary", "请查看诊断记录，调整任务后重试。"), 14, MUTED));
      JSONArray steps=result.optJSONArray("steps");
      if(steps!=null)for(int i=0;i<steps.length();i++)if(steps.getJSONObject(i).optString("status").equals("failed")){panel.add(label("停止步骤："+steps.getJSONObject(i).optString("name"),13,RED,false));break;}
      panel.add(link("查看诊断记录", () -> openPath(result.optString("logPath"))));
    } else {
      JPanel summary = transparent("fillx, insets 0, gap 9", "[][][grow][]");
      summary.add(label(String.valueOf(rows.length()), 36, INK, true));
      summary.add(label(result.optString("taskId").equals("sycm-keywords") ? "个关键词" : "条数据", 14, MUTED, false), "aligny bottom, gapbottom 7");
      summary.add(new JLabel(), "growx");
      summary.add(label("用时 " + formatDuration(result.optLong("durationMs")), 12, MUTED, false));
      panel.add(summary);
      if(rows.length()>0) panel.add(resultTable(rows), "growx");
      else panel.add(wrapped(result.optString("summary", "任务已执行完成，本次未返回结构化数据。"),14,MUTED));
      panel.add(label(rows.length()==0 ? "任务已执行完成，本次没有返回数据。" : verified ? "该次数据已对照页面与截图核验。" : "采集已完成。请结合截图核对数据内容。", 12, MUTED, false), "gaptop 3");
    }
    JPanel actions = transparent("fillx, insets 0, gap 10", "[][][][grow][]");
    final JSONArray exportRows = rows;
    if(!failure && rows.length()>0) {
      actions.add(secondary("复制结果", () -> { copy(rowsText(exportRows)); toast("结果已复制"); }), "h 36!");
      actions.add(secondary("导出结果", () -> exportResult(result)), "h 36!");
    }
    String resultDir = result.optString("resultDir");
    if (evidencePath(result) != null) actions.add(secondary("查看截图", () -> openEvidence(result)), "h 36!");
    actions.add(new JLabel(), "growx");
    actions.add(link("打开结果文件夹", () -> openPath(resultDir)));
    panel.add(actions, "gaptop 8"); body.add(panel);
  }

  private JComponent resultTable(JSONArray rows) {
    LinkedHashSet<String> keys = new LinkedHashSet<>();
    for(int i=0;i<Math.min(rows.length(),100);i++) if(rows.optJSONObject(i)!=null) keys.addAll(rows.getJSONObject(i).keySet());
    List<String> columns = new ArrayList<>(keys);
    if(columns.remove("序号")) columns.add(0,"序号");
    if(columns.remove("关键词")) columns.add(Math.min(1,columns.size()),"关键词");
    if(columns.isEmpty()) columns.add("结果");
    DefaultTableModel model = new DefaultTableModel(columns.toArray(),0){ public boolean isCellEditable(int r,int c){return false;} };
    for(int i=0;i<rows.length();i++) {
      Object[] values = new Object[columns.size()]; JSONObject row = rows.optJSONObject(i);
      for(int c=0;c<values.length;c++) values[c]=row==null?rows.opt(i):row.opt(columns.get(c));
      model.addRow(values);
    }
    JTable table = new JTable(model); table.setFont(font(14,false)); table.setForeground(INK);
    table.setRowHeight(32); table.setShowGrid(false); table.setIntercellSpacing(new Dimension(0,0));
    table.setSelectionBackground(new Color(0xe9f2ff)); table.setSelectionForeground(INK);
    table.setBackground(Color.WHITE); table.setFillsViewportHeight(true);
    table.getTableHeader().setFont(font(12,false)); table.getTableHeader().setForeground(MUTED);
    table.getTableHeader().setBackground(BG); table.getTableHeader().setPreferredSize(new Dimension(0,32));
    table.getTableHeader().setReorderingAllowed(false);
    table.setDefaultRenderer(Object.class,new DefaultTableCellRenderer(){
      public Component getTableCellRendererComponent(JTable t,Object v,boolean selected,boolean focus,int row,int column){
        JLabel cell=(JLabel)super.getTableCellRendererComponent(t,v,selected,focus,row,column);
        cell.setBorder(new EmptyBorder(0,12,0,12));
        if(!selected) cell.setBackground(row%2==0?Color.WHITE:new Color(0xfafafa));
        cell.setForeground(column==0&&columns.size()>1?MUTED:INK); return cell;
      }
    });
    if(columns.size()>1&&columns.get(0).equals("序号")) {table.getColumnModel().getColumn(0).setMaxWidth(75);}
    JScrollPane scroll=new JScrollPane(table); scroll.setBorder(null);
    scroll.setPreferredSize(new Dimension(300,Math.min(10,rows.length())*32+34));
    scroll.getViewport().setBackground(Color.WHITE); return scroll;
  }

  private void renderTasks(JPanel body) {
    JPanel actions=transparent("fillx,insets 0,gap 10","[grow][]");
    actions.add(label("已配置 "+service.tasks().length()+" 个任务",14,MUTED,false));
    actions.add(primary("导入任务",this::importTask),"h 40!"); body.add(actions);
    JSONArray tasks=service.tasks();
    for(int i=0;i<tasks.length();i++) {
      JSONObject task=tasks.getJSONObject(i);
      JPanel row=card("fillx,insets 24 26,gap 0 8","[grow][]12[]");
      row.add(label(task.optString("name"),20,INK,true),"growx");
      row.add(secondary("查看结果",()->{selectedTask=task.getString("id");viewedRun=null;page="执行中心";render();}),"h 38!,spany 2");
      JButton execute=primary(isRunning()?"执行中":"执行",()->{selectedTask=task.getString("id");page="执行中心";runSelected();});
      execute.setEnabled(!isRunning());
      execute.setToolTipText(isRunning()?"请等待当前任务完成，或在执行中心停止任务。": "执行此任务");
      row.add(execute,"h 38!,w 82!,spany 2");
      row.add(label(task.optString("description","已导入的本地执行任务"),13,MUTED,false),"newline");
      row.add(pill(task.optString("type","本地任务"),MUTED),"newline,gaptop 7");
      row.add(link("执行设置",()->configureTask(task)),"newline,alignx left"); body.add(row);
    }
    body.add(wrapped("任务由外部软件准备，执手负责执行并返回结果。原有录制、截图和脚本工具仍可通过左下角“高级工具”使用。",13,MUTED));
  }

  private void renderHistory(JPanel body) {
    JSONArray records=service.history();
    if(records.length()==0){body.add(empty("还没有运行记录","执行一个任务后，这里会保留时间、状态和采集结果。",null,null));return;}
    JPanel list=card("fillx,wrap 1,insets 8 26,gap 0","[grow,fill]");
    for(int i=0;i<Math.min(records.length(),80);i++) {
      JSONObject run=records.getJSONObject(i);
      String status=run.optString("status");
      JPanel line=transparent("fillx,insets 18 0,gap 8","[grow][]24[]");
      line.add(label(run.optString("taskName","执行任务"),17,INK,true));
      line.add(pill(statusText(status),status.equals("success")?GREEN:status.equals("running")?BLUE:RED));
      line.add(secondary("查看结果",()->{selectedTask=run.optString("taskId",selectedTask);viewedRun=run;page="执行中心";render();}),"h 35!,spany 2");
      line.add(label(formatTime(run.optString("startedAt"))+"   ·   "+run.optInt("resultCount")+" 条数据   ·   "+formatDuration(run.optLong("durationMs")),12,MUTED,false),"newline,span 2");
      list.add(line); if(i+1<records.length()&&i<79) list.add(separator(),"growx");
    } body.add(list);
  }

  private void renderSettings(JPanel body) {
    JSONObject connection=service.connection();
    JPanel panel=card("fillx,wrap 1,insets 28,gap 0 16","[grow,fill]");
    panel.add(label("连接外部软件",22,INK,true));
    panel.add(wrapped("由你的大脑软件决定做什么，执手只接收任务并执行。接口仅在本机开放，需要连接密钥。",14,MUTED));
    JCheckBox enable=new JCheckBox("启用本地指令接口",connection.optBoolean("enabled")); enable.setOpaque(false);enable.setFont(font(14,false));enable.setForeground(INK);
    enable.addActionListener(e->{try{service.setApiEnabled(enable.isSelected());render();}catch(Exception ex){error(ex);enable.setSelected(false);}}); panel.add(enable);
    if(connection.optBoolean("enabled")) {
      panel.add(label(connection.optString("baseUrl",connection.optString("url","仅本机可访问")),14,INK,false));
      panel.add(secondary("复制连接配置",()->{copy(service.connection().toString(2));toast("连接配置已复制，请仅交给你信任的本机软件。");}),"alignx left,w 180!,h 38!");
    } else panel.add(label("当前已关闭，不监听任务指令。",12,MUTED,false));
    body.add(panel);
    JPanel prefs=card("fillx,wrap 1,insets 28,gap 0 16","[grow,fill]");
    prefs.add(label("界面与数据",22,INK,true));
    JCheckBox motion=new JCheckBox("减少动态效果",reduceMotion);motion.setOpaque(false);motion.setFont(font(14,false));motion.setForeground(INK);
    motion.addActionListener(e->{reduceMotion=motion.isSelected();Preferences.userNodeForPackage(ExecutorDashboard.class).putBoolean("reduceMotion",reduceMotion);});prefs.add(motion);
    prefs.add(label("简体中文 · 浅色外观 · 本地保存",14,MUTED,false));
    prefs.add(secondary("打开数据文件夹",()->openPath(workspace.resolve("执行数据").toString())),"alignx left,w 180!,h 38!"); body.add(prefs);
    JPanel about=card("fillx,wrap 1,insets 28,gap 0 12","[grow,fill]");
    about.add(label("关于执手",22,INK,true));
    about.add(wrapped("基于 OculiX 4.0.0 源码改造。保留图像识别、文字识别、鼠标键盘操作、录制、多语言脚本与命令行执行能力。",14,MUTED));
    about.add(label("本软件不内置大模型，也不替你作业务决策。",13,MUTED,false));
    about.add(link("查看开源许可",()->openPath(workspace.resolve("执手执行器/LICENSE").toString())));body.add(about);
  }

  private void runSelected() {
    if(isRunning() || disposed)return;
    final String taskId=selectedTask;
    announcedRun=service.snapshot().optString("runId");
    final long generation=++launchGeneration;
    viewedRun=null;page="执行中心";launchPending=true;renderKey="";render();
    launchTimer=new javax.swing.Timer(1500,event->{
      if(disposed || generation!=launchGeneration)return;
      if(service.isRunning()){launchPending=false;refresh();return;}
      minimizeForRun();
      new SwingWorker<Void,Void>(){
        protected Void doInBackground(){if(!disposed && generation==launchGeneration){service.run(taskId);if(disposed || generation!=launchGeneration)service.stop();}return null;}
        protected void done(){if(disposed || generation!=launchGeneration)return;launchPending=false;try{get();refresh();}catch(Exception e){if(service.isRunning()){refresh();return;}restoreAfterRun();renderKey="";render();Throwable cause=e.getCause();error(cause instanceof Exception?(Exception)cause:e);}}
      }.execute();
    });
    launchTimer.setRepeats(false);launchTimer.start();
  }
  private void configureTask(JSONObject task){
    if(isRunning()){message("请在当前任务结束后调整执行设置。");return;}
    JPanel form=transparent("wrap 2,insets 8,gap 12","[][grow,fill]");
    JSpinner minutes=new JSpinner(new SpinnerNumberModel((int)Math.max(1,task.optLong("timeoutMs",600000)/60000),1,1440,1));
    JSpinner silence=new JSpinner(new SpinnerNumberModel((int)Math.max(5,task.optLong("silenceWarningMs",30000)/1000),5,86400,5));
    boolean sycm="sycm-keywords".equals(task.optString("id"));
    JTextField account=new JTextField(sycm?service.sycmAccount():"",24);
    if(sycm){form.add(label("目标店铺账号",14,INK,false));form.add(account);form.add(wrapped("填写千牛顶部已登录账号的完整名称，例如 示例账号:店铺。执行时会核对并选中此账号。",12,MUTED),"span,growx,width 360!");}
    form.add(label("最长运行时间（分钟）",14,INK,false));form.add(minutes);
    form.add(label("无新进度提示（秒）",14,INK,false));form.add(silence);
    form.add(wrapped("无新进度时只提示；超过最长运行时间会停止任务并显示原因。",12,MUTED),"span,growx,width 360!");
    if(JOptionPane.showConfirmDialog(owner,form,"执行设置 · "+task.optString("name"),JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
    long timeout=((Number)minutes.getValue()).longValue()*60000,warning=((Number)silence.getValue()).longValue()*1000;
    new SwingWorker<Void,Void>(){
      protected Void doInBackground(){if(sycm)service.configureSycmAccount(account.getText());service.configureTimeouts(task.getString("id"),timeout,warning);return null;}
      protected void done(){try{get();render();}catch(Exception e){error(e.getCause() instanceof Exception?(Exception)e.getCause():e);}}
    }.execute();
  }
  private void importTask() {
    if(isRunning()){message("请等待当前任务完成后再导入。");return;}
    JFileChooser chooser=new JFileChooser(workspace.toFile());
    chooser.setDialogTitle("导入执行任务");chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
    chooser.setApproveButtonText("导入任务");
    if(chooser.showOpenDialog(owner)==JFileChooser.APPROVE_OPTION){
      try{JSONObject task=service.importTask(chooser.getSelectedFile().toPath());selectedTask=task.getString("id");page="任务库";render();}catch(Exception e){error(e);}
    }
  }
  private JSONObject findTask(String id){JSONArray tasks=service.tasks();for(int i=0;i<tasks.length();i++)if(tasks.getJSONObject(i).optString("id").equals(id))return tasks.getJSONObject(i);return null;}
  private void exportResult(JSONObject result){
    JFileChooser chooser=new JFileChooser(workspace.toFile());chooser.setDialogTitle("导出采集结果");chooser.setApproveButtonText("导出");
    chooser.setSelectedFile(new java.io.File("采集结果.txt"));
    if(chooser.showSaveDialog(owner)==JFileChooser.APPROVE_OPTION){
      Path file=chooser.getSelectedFile().toPath();
      if(Files.exists(file)&&JOptionPane.showConfirmDialog(owner,"该文件已存在，是否替换？","导出结果",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;
      try{String text=file.toString().toLowerCase(Locale.ROOT).endsWith(".json")?result.toString(2):rowsText(result.optJSONArray("rows"));Files.writeString(file,text,StandardCharsets.UTF_8);toast("结果已导出。");}catch(Exception e){error(e);}
    }
  }
  private void openEvidence(JSONObject result){
    Path image=evidencePath(result);
    if(image!=null) openPath(image.toString());
    else message("这次运行没有保存截图，可以打开结果文件夹查看其他输出。");
  }
  private Path evidencePath(JSONObject result){
    String explicit=result.optString("imagePath",result.optString("screenshotPath"));
    if(!explicit.isBlank()&&Files.isRegularFile(Path.of(explicit))) return Path.of(explicit);
    String directory=result.optString("sourceDir",result.optString("resultDir"));
    if(directory.isBlank()) return null;
    Path dir=Path.of(directory);
    try(var files=Files.list(dir)){
      List<Path> images=files.filter(p->p.toString().endsWith(".png")).sorted().toList();
      Optional<Path> image=images.stream().filter(p->p.getFileName().toString().contains("final-page")).findFirst();
      if(image.isEmpty()) image=images.stream().filter(p->p.getFileName().toString().contains("keyword-column")).findFirst();
      return image.orElse(images.isEmpty()?null:images.get(images.size()-1));
    }catch(Exception e){return null;}
  }
  private void openPath(String path){try{if(path==null||path.isBlank())throw new IllegalArgumentException("本次没有对应文件。");Path p=Path.of(path);if(!Files.exists(p))throw new IllegalArgumentException("文件不存在，可能已移动。");Desktop.getDesktop().open(p.toFile());}catch(Exception e){error(e);}}
  private static String rowsText(JSONArray rows){if(rows==null)return "";StringBuilder b=new StringBuilder();for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row!=null&&row.has("关键词"))b.append(i+1).append(". ").append(row.optString("关键词"));else b.append(rows.get(i));b.append(System.lineSeparator());}return b.toString();}
  private static void copy(String text){Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text),null);}
  private void message(String text){JOptionPane.showMessageDialog(owner,text,"执手",JOptionPane.INFORMATION_MESSAGE);}
  private void error(Exception e){String text=e.getMessage();message(text!=null&&text.matches("(?s).*[\\p{IsHan}].*")?text:"操作未完成，请检查本地文件和任务配置。");}
  private void toast(String text){message(text);}
  private static int stageIndex(String s){if(s.contains("结果")||s.contains("完成"))return 3;if(s.contains("读取")||s.contains("识别")||s.contains("数据")||s.contains("截图"))return 2;if(s.contains("打开")||s.contains("进入")||s.contains("点击")||s.contains("执行")||s.contains("市场"))return 1;return 0;}
  private static String statusText(String s){return switch(s){case "success"->"已完成";case "running"->"执行中";case "cancelled"->"已停止";case "failed"->"未完成";default->"已记录";};}
  private static String formatDuration(long ms){if(ms<=0)return "—";if(ms<60000)return String.format(Locale.ROOT,"%.1f 秒",ms/1000.0);return String.format(Locale.ROOT,"%d 分 %d 秒",ms/60000,ms/1000%60);}
  private static String formatTime(String value){try{if(value==null||value.isBlank())return "";Instant time=Instant.parse(value);return DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm").withZone(ZoneId.systemDefault()).format(time);}catch(Exception e){return value.length()>19?value.substring(0,19).replace('T',' '):value;}}
  private static Font font(int size,boolean bold){return new Font("Microsoft YaHei UI",bold?Font.BOLD:Font.PLAIN,size);}
  private static JLabel label(String text,int size,Color color,boolean bold){JLabel l=new JLabel(text);l.setFont(font(size,bold));l.setForeground(color);return l;}
  private static JComponent wrapped(String text,int size,Color color){JTextArea t=new JTextArea(text);t.setFont(font(size,false));t.setForeground(color);t.setOpaque(false);t.setEditable(false);t.setLineWrap(true);t.setWrapStyleWord(true);t.setFocusable(false);return t;}
  private static JPanel transparent(String layout,String cols){JPanel p=new JPanel(new MigLayout(layout,cols));p.setOpaque(false);return p;}
  private static JPanel card(String layout,String cols){JPanel p=new RoundedPanel();p.setLayout(new MigLayout(layout,cols));return p;}
  private static JComponent separator(){JPanel p=new JPanel();p.setBackground(LINE);p.setPreferredSize(new Dimension(1,1));p.setMaximumSize(new Dimension(Integer.MAX_VALUE,1));return p;}
  private static JComponent pill(String text,Color color){JLabel l=label(text,12,color,false);l.setBorder(new EmptyBorder(5,9,5,9));JPanel p=new JPanel(new BorderLayout());p.setOpaque(false);p.add(l);return p;}
  private static PillButton primary(String text,Runnable action){PillButton b=new PillButton(text,true);b.addActionListener(e->action.run());return b;}
  private static PillButton secondary(String text,Runnable action){PillButton b=new PillButton(text,false);b.addActionListener(e->action.run());return b;}
  private static JButton link(String text,Runnable action){JButton b=new JButton(text);b.setFont(font(12,false));b.setForeground(BLUE);b.setBorder(new EmptyBorder(6,1,6,1));b.setContentAreaFilled(false);b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));b.addActionListener(e->action.run());return b;}
  private JPanel empty(String title,String description,String action,Runnable handler){JPanel panel=card("fillx,wrap 1,insets 48 30,gap 0 16","[grow,center]");panel.add(label(title,24,INK,true));panel.add(wrapped(description,14,MUTED),"width 200:520:650");if(action!=null)panel.add(primary(action,handler),"h 42!,gaptop 10");return panel;}

  private static final class TaskOption {final String id,name;TaskOption(JSONObject task){id=task.getString("id");name=task.optString("name",id);}public String toString(){return name;}}
  private static final class ViewportPanel extends JPanel implements Scrollable {
    ViewportPanel(LayoutManager layout){super(layout);}
    public Dimension getPreferredScrollableViewportSize(){return getPreferredSize();}
    public int getScrollableUnitIncrement(Rectangle r,int orientation,int direction){return 24;}
    public int getScrollableBlockIncrement(Rectangle r,int orientation,int direction){return Math.max(24,r.height-32);}
    public boolean getScrollableTracksViewportWidth(){return true;}
    public boolean getScrollableTracksViewportHeight(){return false;}
  }
  private static final class RoundedPanel extends JPanel {
    RoundedPanel(){setOpaque(false);}
    protected void paintComponent(Graphics graphics){Graphics2D g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setColor(new Color(0,0,0,7));g.fillRoundRect(0,2,getWidth(),getHeight()-2,22,22);g.setColor(Color.WHITE);g.fillRoundRect(0,0,getWidth(),getHeight()-2,22,22);g.dispose();super.paintComponent(graphics);}
  }
  private static final class PillButton extends JButton {
    final boolean primary;float hover;javax.swing.Timer animation;
    PillButton(String text,boolean primary){super(text);this.primary=primary;setFont(font(14,true));setForeground(primary?Color.WHITE:INK);setContentAreaFilled(false);setBorder(new EmptyBorder(10,18,10,18));setFocusPainted(false);setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
      addMouseListener(new MouseAdapter(){public void mouseEntered(MouseEvent e){animateTo(1);}public void mouseExited(MouseEvent e){animateTo(0);}});
    }
    void animateTo(float end){if(animation!=null)animation.stop();if(reduceMotion){hover=end;repaint();return;}float start=hover;long began=System.nanoTime();animation=new javax.swing.Timer(16,e->{double t=Math.min(1,(System.nanoTime()-began)/200_000_000.0);double eased=1-Math.pow(1-t,3);hover=(float)(start+(end-start)*eased);repaint();if(t>=1)animation.stop();});animation.start();}
    protected void paintComponent(Graphics graphics){Graphics2D g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);if(getModel().isPressed()&&!reduceMotion){g.translate(getWidth()*.01,getHeight()*.01);g.scale(.98,.98);}Color bg=primary?BLUE:isSelected()?new Color(0xe9f2ff):new Color(0xf5f5f7);if(hover>0)bg=new Color(Math.max(0,(int)(bg.getRed()*(1-.035*hover))),Math.max(0,(int)(bg.getGreen()*(1-.035*hover))),Math.max(0,(int)(bg.getBlue()*(1-.035*hover))));g.setColor(isEnabled()?bg:LINE);g.fillRoundRect(1,1,getWidth()-2,getHeight()-2,40,40);if(hasFocus()){g.setColor(new Color(0,113,227,90));g.setStroke(new BasicStroke(2));g.drawRoundRect(1,1,getWidth()-3,getHeight()-3,40,40);}super.paintComponent(g);g.dispose();}
  }
  private static final class DotIcon implements Icon {final Color color;DotIcon(Color c){color=c;}public int getIconWidth(){return 8;}public int getIconHeight(){return 8;}public void paintIcon(Component c,Graphics g,int x,int y){g.setColor(color);g.fillOval(x,y,6,6);}}
  private static final class LineIcon implements Icon {
    final String name;final int size;final Color color;LineIcon(String n,int s,Color c){name=n;size=s;color=c;}public int getIconWidth(){return size;}public int getIconHeight(){return size;}
    public void paintIcon(Component c,Graphics graphics,int x,int y){Graphics2D g=(Graphics2D)graphics.create();g.translate(x,y);g.scale(size/24.0,size/24.0);g.setColor(color);g.setStroke(new BasicStroke(1.6f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
      switch(name){case "play"->{Path2D p=new Path2D.Double();p.moveTo(8,4);p.lineTo(20,12);p.lineTo(8,20);p.closePath();g.draw(p);}case "stop"->g.drawRoundRect(5,5,14,14,3,3);case "grid"->{for(int a:new int[]{4,14})for(int b:new int[]{4,14})g.drawRoundRect(a,b,6,6,2,2);}case "history"->{g.drawOval(3,3,18,18);g.drawLine(12,7,12,12);g.drawLine(12,12,16,14);}case "settings"->{g.drawOval(4,4,16,16);g.drawOval(9,9,6,6);for(int a=0;a<8;a++){double t=a*Math.PI/4;g.drawLine((int)(12+8*Math.cos(t)),(int)(12+8*Math.sin(t)),(int)(12+11*Math.cos(t)),(int)(12+11*Math.sin(t)));}}case "tools"->{g.drawLine(5,19,19,5);g.drawOval(2,15,6,6);g.drawLine(13,4,19,5);g.drawLine(19,5,20,11);}default->{Path2D p=new Path2D.Double();p.moveTo(5,2);p.lineTo(5,21);p.lineTo(10,16);p.lineTo(14,23);p.lineTo(18,21);p.lineTo(14,14);p.lineTo(21,13);p.closePath();g.draw(p);}}g.dispose();}
  }
}
