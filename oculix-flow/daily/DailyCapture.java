import org.sikuli.script.*;
import org.sikuli.basics.Settings;
import com.sun.jna.Native;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.DWORD;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import javax.imageio.ImageIO;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.text.Normalizer;
import java.util.concurrent.TimeUnit;
import org.json.*;

/** Copy the already-authorized visible 示例店铺A metrics table, without website APIs.
 * Compile together with ../SycmFlow.java and run with cwd=oculix-flow.
 * Does not parse/import business data; collector.py owns those validated stages.
 */
public class DailyCapture extends SycmFlow {
  public interface ClipboardSequenceApi extends StdCallLibrary {
    ClipboardSequenceApi INSTANCE=Native.load("user32", ClipboardSequenceApi.class, W32APIOptions.DEFAULT_OPTIONS);
    int GetClipboardSequenceNumber();
  }
  record Control(String label, Location center, Rectangle windowBounds, Rectangle captureBounds, Path screenshot) {}
  static Path textPath;
  static boolean discoveryMode;
  static String expectedStoreText="示例店铺A";
  static String selectedStoreId="neutcut";
  static String selectedStoreName="示例店铺A";

  public static void main(String[] args) {
    try {
      if(args.length!=1)throw new IllegalArgumentException("每日数据采集需要一个请求JSON文件参数。");
      JSONObject request=new JSONObject(Files.readString(Path.of(args[0]),StandardCharsets.UTF_8).replaceFirst("^\\uFEFF",""));
      String account=request.getString("targetAccount").trim();
      discoveryMode=request.optString("mode").equals("discover-account");
      if(account.isEmpty()||account.length()>128||account.contains("\n")||account.contains("\r"))
        throw new IllegalArgumentException("账号核对需要完整有效的千牛顶部账号名。");
      if(!discoveryMode)validateConfiguredStore(request,account);
      JSONArray dates=request.optJSONArray("missingDates");
      if(dates==null)dates=new JSONArray();
      if(!discoveryMode&&dates.isEmpty())throw new IllegalArgumentException("没有缺失日期，不应调用桌面采集。");
      if(!discoveryMode)LocalDate.parse(request.getString("endDate"));
      for(int i=0;i<dates.length();i++)LocalDate.parse(dates.getString(i));
      runDir=Path.of(request.getString("evidenceDir")).toAbsolutePath().normalize();
      Files.createDirectories(runDir);
      textPath=runDir.resolve("table.txt");
      if(Files.exists(textPath)||Files.exists(runDir.resolve("capture.json")))
        throw new IllegalStateException("本次采集目录已有结果，请使用新的运行目录。");
      protocol("OCULIX_RUN_DIR",runDir.toString());
      progress("正在恢复已登录千牛会话："+account+(discoveryMode?"（只读核对店铺名称）":""));
      Settings.CheckLastSeen=false; Settings.AutoWaitTimeout=0; Settings.MoveMouseDelay=.15f;
      Settings.OcrLanguage="chi_sim";
      sequence=0; dismissedPromotions=0; mode="daily";
      launchSettings.clear();
      Path properties=ROOT.resolve("千牛启动.properties");
      if(Files.isRegularFile(properties))try(var reader=Files.newBufferedReader(properties,StandardCharsets.UTF_8)){launchSettings.load(reader);}
      launchSettings.setProperty("targetAccount",account);
      screen=new Screen();
      restoreInitialForeground();
      window=openQianniu();
      checkStable(bounds());
      capture("daily-session-confirmed");
      progress("正在从千牛工作台打开生意参谋");
      click("tabs","workbench-label-normal","workbench-label-active");
      waitFor("tabs",30,"workbench-label-active");
      waitFor("app-sidebar",30,"data-label-normal");
      click("app-sidebar","data-label-normal");
      waitForSycmReady();
      // The embedded page can survive across days with an old statistics date.
      // Give the verified web page focus, reload it, and wait for it again.
      // The downstream parser still requires the requested date and store;
      // reloading alone is never evidence that the data is current.
      progress("正在刷新生意参谋页面，避免读取上次打开的旧数据");
      click("main","sycm-logo","sycm-logo-home","sycm-logo-compact");
      waitForSycmReady();
      Rectangle beforeReload=bounds();
      checkStable(beforeReload);
      screen.type("r",KeyModifier.CTRL);
      log("DAILY_RELOAD","已向生意参谋页面发送刷新快捷键；重新等待页面并核验日期。");
      boolean sawLoading=false;
      long reloadDeadline=System.nanoTime()+15_000_000_000L;
      while(System.nanoTime()<reloadDeadline){
        checkStable(beforeReload);
        if(search("main","sycm-logo","sycm-logo-home","sycm-logo-compact")==null ||
           search("nav","market-label-normal","market-label-active","market-label-home")==null){
          sawLoading=true; break;
        }
        Thread.sleep(100);
      }
      if(!sawLoading)throw new IllegalStateException("未观察到生意参谋页面刷新，已停止以免读取旧页面。");
      waitForSycmReady();
      USER.ShowWindow(window,3);
      Thread.sleep(800);
      checkStable(bounds());
      capture("daily-home-maximized");

      // Reuse a visibly confirmed expanded table on repeat runs. A merged OCR
      // label or ambiguous match is never resolved by guessing a coordinate.
      Control table=locate("指标名称");
      if(table==null){
        Control expanded=locate("退出全屏");
        if(expanded==null){
          progress("正在打开全部指标");
          clickExact("全部指标",40);
        }
        progress("正在切换指标表格");
        clickExact("表格",40);
        waitControl("指标名称",40);
      }
      progress(discoveryMode?"正在复制原文以核对账号所属店铺":"正在复制"+selectedStoreName+"表格原文");
      clickExact("指标名称",30);
      Rectangle stable=bounds();
      checkStable(stable);
      screen.type("a",KeyModifier.CTRL);
      Thread.sleep(250);
      checkStable(stable);
      int before=ClipboardSequenceApi.INSTANCE.GetClipboardSequenceNumber();
      screen.type("c",KeyModifier.CTRL);
      String copied=readFreshClipboard(before,stable);
      Files.writeString(textPath,copied,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
      Path image=saveScreenshot("table-final");
      JSONObject metadata=new JSONObject().put("textPath",textPath.toString()).put("screenshotPath",image.toString())
          .put("targetAccount",account).put("windowTitle",windowTitle()).put("capturedAt",Instant.now().toString())
          .put("requestedMissingDates",dates).put("requestedEndDate",request.optString("endDate"))
          .put("discoveryOnly",discoveryMode).put("importable",!discoveryMode)
          .put("storeId",discoveryMode?JSONObject.NULL:selectedStoreId).put("storeName",discoveryMode?JSONObject.NULL:selectedStoreName)
          .put("captureMethod","visible-ui-clipboard").put("clipboardSequenceChanged",true)
          .put("sourceTextLength",copied.length());
      Path manifest=runDir.resolve("capture.json");
      Files.writeString(manifest,metadata.toString(2),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
      log("DAILY_CAPTURE",manifest.toString());
      progress("表格原文与截图已保存，正在交给数据校验步骤");
    }catch(Throwable failure){
      try{if(runDir!=null&&window!=null&&screen!=null)capture("daily-failure");}catch(Throwable ignored){}
      try{if(runDir!=null)protocol("OCULIX_ERROR",friendlyError(failure));else System.out.println("OCULIX_ERROR "+failure.getMessage());}catch(Throwable ignored){}
      failure.printStackTrace();
      System.exit(2);
    }
  }

  static String compactText(String value){return Normalizer.normalize(value,Normalizer.Form.NFKC).replaceAll("\\s+","");}
  static void validateConfiguredStore(JSONObject request,String account)throws Exception{
    String identifier=request.getString("storeId");
    JSONObject config=new JSONObject(Files.readString(ROOT.resolve("daily/stores.json"),StandardCharsets.UTF_8).replaceFirst("^\\uFEFF",""));
    JSONArray stores=config.getJSONArray("stores");
    JSONObject selected=null;
    for(int i=0;i<stores.length();i++){
      JSONObject item=stores.getJSONObject(i);
      if(item.getString("storeId").equals(identifier)){
        if(selected!=null)throw new IllegalStateException("同一店铺ID存在多个配置，已停止。");
        selected=item;
      }
    }
    if(selected==null||!selected.optBoolean("confirmed"))throw new IllegalStateException("账号与店铺映射尚未确认，不能执行正式采集。");
    if(!selected.getString("targetAccount").equals(account))throw new IllegalStateException("请求账号与店铺配置不一致，已停止。");
    for(String field:new String[]{"storeName","pageStoreName"})
      if(request.has(field)&&!request.getString(field).equals(selected.getString(field)))
        throw new IllegalStateException("请求店铺名称与已确认配置不一致，已停止。");
    selectedStoreId=identifier;selectedStoreName=selected.getString("storeName");
    String pageName=selected.getString("pageStoreName").trim();
    if(pageName.isEmpty()||selectedStoreName.isBlank())throw new IllegalStateException("已确认店铺缺少完整名称，已停止。");
    expectedStoreText=compactText(pageName)+"主店";
  }

  static String windowTitle(){
    char[] title=new char[512]; USER.GetWindowText(window,title,title.length);return Native.toString(title);
  }
  static String titleOf(HWND handle){
    if(handle==null)return "<none>";
    char[] title=new char[512];USER.GetWindowText(handle,title,title.length);return Native.toString(title);
  }
  static void restoreInitialForeground()throws Exception{
    // Only at task start, for one uniquely identified session window. Never
    // repeatedly steal focus during a running task or dismiss unrelated dialogs.
    HWND target=findWindow();
    if(target==null)target=findWindow("千牛接待台");
    if(target==null)return; // The official shortcut launch remains in openQianniu.
    if(!USER.IsWindowEnabled(target))throw new IllegalStateException("千牛主窗口被对话框阻塞，请先处理后重试。");
    for(int key:new int[]{1,2,4,16,17,18})if(USER.GetAsyncKeyState(key)<0)
      throw new IllegalStateException("A mouse button or modifier is held");
    HWND before=USER.GetForegroundWindow();
    log("FOREGROUND_RESTORE_BEGIN","target="+target+" title="+titleOf(target)+" foreground="+before+" title="+titleOf(before));
    if(target.equals(before))return;
    int currentThread=Kernel32.INSTANCE.GetCurrentThreadId();
    int targetThread=USER.GetWindowThreadProcessId(target,null);
    int foregroundThread=before==null?0:USER.GetWindowThreadProcessId(before,null);
    boolean attachedForeground=false,attachedTarget=false;
    try{
      if(foregroundThread!=0&&foregroundThread!=currentThread)
        attachedForeground=USER.AttachThreadInput(new DWORD(currentThread),new DWORD(foregroundThread),true);
      if(targetThread!=currentThread&&targetThread!=foregroundThread)
        attachedTarget=USER.AttachThreadInput(new DWORD(currentThread),new DWORD(targetThread),true);
      USER.ShowWindow(target,9);
      USER.BringWindowToTop(target);
      USER.SetForegroundWindow(target);
      USER.SetFocus(target);
    }finally{
      if(attachedTarget)USER.AttachThreadInput(new DWORD(currentThread),new DWORD(targetThread),false);
      if(attachedForeground)USER.AttachThreadInput(new DWORD(currentThread),new DWORD(foregroundThread),false);
    }
    long deadline=System.nanoTime()+1_500_000_000L;
    while(System.nanoTime()<deadline){
      if(target.equals(USER.GetForegroundWindow())){
        log("FOREGROUND_RESTORE_OK","target="+target+" title="+titleOf(target));return;
      }
      Thread.sleep(100);
    }
    HWND after=USER.GetForegroundWindow();
    log("FOREGROUND_RESTORE_FAILED","target="+target+" foreground="+after+" title="+titleOf(after));
    throw new IllegalStateException("千牛窗口未能恢复到前台，可能有其他操作正在抢占焦点；已停止，不会持续抢焦点。");
  }
  static void checkStable(Rectangle expected){
    if(Files.exists(ROOT.resolve("STOP")))throw new IllegalStateException("STOP file present");
    if(!USER.GetForegroundWindow().equals(window))throw new IllegalStateException("Target lost foreground during daily capture");
    if(!expected.equals(bounds()))throw new IllegalStateException("Window changed during daily capture");
    if(!windowTitle().equals(launchSettings.getProperty("windowTitle","千牛工作台")))
      throw new IllegalStateException("千牛目标窗口标题发生变化，已停止。");
  }
  static Path saveScreenshot(String name)throws Exception{
    Rectangle stable=bounds();checkStable(stable);
    Rectangle visible=stable.intersection(screen.getBounds());
    if(visible.width<600||visible.height<400)throw new IllegalStateException("千牛窗口显示区域不足，已停止。");
    Path image=runDir.resolve(String.format("daily-%03d-%s.png",++sequence,name));
    ImageIO.write(screen.capture(visible).getImage(),"png",image.toFile());
    checkStable(stable);
    return image;
  }
  static Control locate(String label)throws Exception{
    Rectangle stable=bounds();checkStable(stable);
    Rectangle visible=stable.intersection(screen.getBounds());
    Path image=saveScreenshot("control");
    Path output=runDir.resolve(image.getFileName()+".ocr.json");
    Path error=runDir.resolve(image.getFileName()+".ocr-error.txt");
    Process process=new ProcessBuilder(ROOT.resolve("ocr/.venv/Scripts/python.exe").toString(),"-B",
        ROOT.resolve("daily/find_control.py").toString(),image.toString(),"--label",label)
        .redirectOutput(output.toFile()).redirectError(error.toFile()).start();
    try{
      if(!process.waitFor(45,TimeUnit.SECONDS))throw new IllegalStateException("控件文字识别超过45秒："+label);
    }finally{if(process.isAlive())process.destroyForcibly();}
    checkStable(stable);
    JSONObject recognized=new JSONObject(Files.readString(output,StandardCharsets.UTF_8));
    if(!recognized.optBoolean("ok")){
      JSONObject problem=recognized.optJSONObject("error");
      if(problem!=null&&problem.optString("code").equals("NO_MATCH"))return null;
      throw new IllegalStateException(problem==null?"控件文字识别失败："+label:problem.optString("message"));
    }
    if(process.exitValue()!=0)throw new IllegalStateException("控件识别进程异常退出："+label);
    JSONObject match=recognized.getJSONObject("match");
    if(!match.getString("text").replaceAll("\\s+","").equals(label)||match.getDouble("confidence")<.90)
      throw new IllegalStateException("控件匹配不满足完整文字和置信度条件："+label);
    JSONArray center=match.getJSONArray("center");
    Location point=new Location(visible.x+(int)Math.round(center.getDouble(0)),visible.y+(int)Math.round(center.getDouble(1)));
    if(!visible.contains(point.x,point.y))throw new IllegalStateException("识别的控件位置超出当前窗口："+label);
    return new Control(label,point,stable,visible,image);
  }
  static Control waitControl(String label,int seconds)throws Exception{
    long deadline=System.nanoTime()+seconds*1_000_000_000L;
    do{
      Control control=locate(label);
      if(control!=null)return control;
      progress("正在等待页面控件："+label);
      Thread.sleep(600);
    }while(System.nanoTime()<deadline);
    throw new IllegalStateException("未能在页面中唯一识别“"+label+"”，已停止并保留截图。");
  }
  static void clickExact(String label,int seconds)throws Exception{
    for(int retry=0;retry<3;retry++){
      Control first=waitControl(label,seconds);
      Thread.sleep(200);
      Control fresh=locate(label);
      if(fresh==null||!first.windowBounds.equals(fresh.windowBounds)||!first.captureBounds.equals(fresh.captureBounds)
          ||distance(first.center,fresh.center)>3)continue;
      checkStable(fresh.windowBounds);
      progress("正在点击"+label);
      log("DAILY_CLICK",label+" at "+fresh.center+" source="+fresh.screenshot);
      Settings.ClickDelay=.15;
      screen.click(fresh.center);
      Thread.sleep(650);
      checkStable(fresh.windowBounds);
      saveScreenshot("after-control");
      return;
    }
    throw new IllegalStateException("两次截图中的控件位置不稳定："+label+"，已停止。");
  }
  static String readFreshClipboard(int before,Rectangle stable)throws Exception{
    long deadline=System.nanoTime()+5_000_000_000L;
    while(System.nanoTime()<deadline){
      checkStable(stable);
      if(ClipboardSequenceApi.INSTANCE.GetClipboardSequenceNumber()!=before){
        try{
          Object clipboard=Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
          if(clipboard instanceof String text&&(discoveryMode?text.contains("生意参谋"):compactText(text).contains(expectedStoreText))&&text.contains("指标名称")&&text.contains("支付金额")){
            checkStable(stable);return text;
          }
        }catch(IllegalStateException temporarilyBusy){/* another process briefly owns the clipboard */}
        catch(java.awt.datatransfer.UnsupportedFlavorException notText){/* wait for text representation */}
        catch(java.io.IOException notReady){/* clipboard delayed rendering may not yet be ready */}
      }
      Thread.sleep(100);
    }
    throw new IllegalStateException("复制后没有收到本次店铺指标表格文本；不会读取旧剪贴板内容。");
  }
}
