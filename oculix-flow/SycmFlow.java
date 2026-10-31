import org.sikuli.script.*;
import org.sikuli.basics.Settings;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.*;
import com.sun.jna.platform.win32.WinUser.WINDOWPLACEMENT;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.time.*;
import java.util.*;
import org.json.JSONObject;

/** A local, read-only navigation proof using OculiX image matching and mouse input. */
public class SycmFlow {
  static final Path ROOT=Path.of(System.getProperty("user.dir"));
  static final Path ASSETS=ROOT.resolve("assets");
  static Path runDir;
  static HWND window;
  static Screen screen;
  static final User32 USER=User32.INSTANCE;
  static int sequence=0;
  static String mode;
  static final Properties launchSettings=new Properties();
  static String lastWindowCandidates="";
  static int dismissedPromotions=0;
  static final float[] SCALES={1f,.9f,1.1f,.8f,1.25f};
  record Hit(Match match,String template,float scale,Rectangle bounds) {}

  public static void main(String[] args) throws Exception {
    mode=args.length==0?"probe":args[0];
    runDir=ROOT.resolve("runs").resolve(LocalDateTime.now().toString().replace(':','-')+"_"+mode);
    Files.createDirectories(runDir);
    log("RUN_DIR", runDir.toString());
    Settings.CheckLastSeen=false;
    Settings.AutoWaitTimeout=0;
    Settings.MoveMouseDelay=.15f;
    Settings.OcrLanguage="chi_sim";
    try {
      screen=new Screen();
      if(Files.isRegularFile(ROOT.resolve("千牛启动.properties")))
        try(var reader=Files.newBufferedReader(ROOT.resolve("千牛启动.properties"),StandardCharsets.UTF_8)){launchSettings.load(reader);}
      step("open-qianniu","running","正在打开或唤醒千牛");
      window=openQianniu();
      WINDOWPLACEMENT placement=new WINDOWPLACEMENT();
      USER.GetWindowPlacement(window,placement);
      if(placement.showCmd==2) USER.ShowWindow(window,9);
      USER.SetForegroundWindow(window);
      Thread.sleep(700);
      if(!USER.GetForegroundWindow().equals(window)) throw new IllegalStateException("Target lost foreground while opening");
      step("open-qianniu","done","千牛窗口已打开");
      log("START",mode+" OculiX API 4.0.0; window="+bounds()+"; screens="+Screen.getNumberScreens());
      log("INPUT_STATE","left="+(USER.GetAsyncKeyState(1)<0)+" right="+(USER.GetAsyncKeyState(2)<0)+" middle="+(USER.GetAsyncKeyState(4)<0)+" ctrl="+(USER.GetAsyncKeyState(0x11)<0)+" shift="+(USER.GetAsyncKeyState(0x10)<0)+" alt="+(USER.GetAsyncKeyState(0x12)<0));
      for(int key:new int[]{1,2,4,16,17,18})
        if(USER.GetAsyncKeyState(key)<0) throw new IllegalStateException("A mouse button or modifier is held (virtual-key="+key+"); release it before running.");
      if(!Files.exists(ROOT.resolve("original-window.txt"))) {
        Rectangle r=bounds();
        Files.writeString(ROOT.resolve("original-window.txt"),r.x+","+r.y+","+r.width+","+r.height);
      }
      if(mode.equals("restore")) {
        String[] p=Files.readString(ROOT.resolve("original-window.txt")).trim().split(",");
        USER.ShowWindow(window,9);
        USER.MoveWindow(window,Integer.parseInt(p[0]),Integer.parseInt(p[1]),Integer.parseInt(p[2]),Integer.parseInt(p[3]),true);
        log("RESTORED",bounds().toString()); return;
      }
      if(mode.equals("moved-small")) {
        USER.ShowWindow(window,9); USER.MoveWindow(window,80,90,1600,1100,true); Thread.sleep(800);
      } else if(mode.equals("maximized")) {
        USER.ShowWindow(window,3); Thread.sleep(800);
      }
      capture("start");
      if(mode.equals("probe")) {
        Hit h=waitFor("tabs",5,"sycm-tab","sycm-tab-active");
        log("PROBE_OK",describe(h)); return;
      }
      if(mode.equals("ocr")) { extract(); return; }
      step("enter-workbench","running","正在进入千牛工作台");
      click("tabs","workbench-label-normal","workbench-label-active");
      waitFor("tabs",30,"workbench-label-active");
      waitFor("app-sidebar",30,"data-label-normal");
      step("enter-workbench","done","千牛工作台和数据入口已确认");
      step("open-sycm","running","正在从千牛数据入口打开生意参谋");
      click("app-sidebar","data-label-normal");
      waitForSycmReady();
      step("open-sycm","done","生意参谋页面已确认");
      step("enter-market","running","正在进入市场");
      // Selected navigation text is bold, unselected text is regular. Match both
      // observed labels without the selection underline; never require the page
      // to be at the destination before the navigation click can succeed.
      click("nav","market-label-normal","market-label-active","market-label-home");
      waitFor("sidebar",20,"search-menu","search-active");
      step("enter-market","done","市场页面已确认");
      step("open-ranking","running","正在打开搜索排行");
      click("sidebar","search-menu","search-active");
      waitFor("main",20,"keywords-title");
      waitFor("table",20,"keywords-column");
      step("open-ranking","done","搜索排行表格已确认");
      step("read-keywords","running","正在读取当前页前十个关键词");
      capture("keywords-page");
      extract();
      log("COMPLETED_PENDING_QA","Navigation completed and OCR candidates saved. Verify text against the screenshots.");
    } catch(Throwable ex) {
      try{capture("failure");}catch(Throwable ignored){}
      protocol("OCULIX_ERROR", friendlyError(ex));
      log("STOP",ex.toString());
      ex.printStackTrace(); System.exit(2);
    }
  }

  static HWND findWindow() { return findWindow(launchSettings.getProperty("windowTitle","千牛工作台")); }
  static HWND findWindow(String title) {
    List<HWND> found=new ArrayList<>();
    List<String> details=new ArrayList<>();
    USER.EnumWindows((h,d)->{
      char[] t=new char[1024]; USER.GetWindowText(h,t,t.length);
      if(USER.IsWindowVisible(h)&&Native.toString(t).equals(title)) {
        RECT rect=new RECT(); USER.GetWindowRect(h,rect);
        int exStyle=USER.GetWindowLong(h,-20);
        HWND owner=USER.GetWindow(h,new DWORD(4));
        // Qt notification/helper windows can carry the same title as the main
        // workbench. They are not actionable application windows.
        boolean auxiliary=(exStyle&0x08000080)!=0 || owner!=null;
        WINDOWPLACEMENT placement=new WINDOWPLACEMENT(); USER.GetWindowPlacement(h,placement);
        boolean usable=placement.showCmd==2||(rect.right-rect.left>=600&&rect.bottom-rect.top>=400);
        details.add(h+" bounds="+rect.toRectangle()+" exStyle="+Integer.toHexString(exStyle)+" owner="+owner+" eligible="+(!auxiliary&&usable));
        if(!auxiliary&&usable)found.add(h);
      }
      return true;
    },null);
    String diagnostic=title+" "+details;
    if(!diagnostic.equals(lastWindowCandidates)){
      lastWindowCandidates=diagnostic;
      try{log("WINDOW_CANDIDATES",diagnostic);}catch(Exception ignored){}
    }
    if(found.size()>1) throw new IllegalStateException("发现多个“"+title+"”窗口，无法唯一确认已登录会话。");
    return found.isEmpty()?null:found.get(0);
  }
  static HWND findOpeningWorkbench() {
    try{return findWindow();}
    catch(IllegalStateException transientWindows){
      // Qt briefly exposes both the outgoing and incoming workbench while it
      // recreates the window. Wait for one stable target; do not pick either.
      if(transientWindows.getMessage().startsWith("发现多个“"))return null;
      throw transientWindows;
    }
  }
  static HWND openQianniu() throws Exception {
    HWND existing=findWindow();
    if(existing!=null){activate(existing);selectAccount();log("APPLICATION_REUSED","已确认现有工作台的目标账号。");return existing;}
    HWND reception=findWindow("千牛接待台");
    if(reception!=null)return reopenFromReception(reception);
    String publicDir=System.getenv("PUBLIC");
    Path shortcut=Path.of(launchSettings.getProperty("shortcut",(publicDir==null?"C:/Users/Public":publicDir)+"/Desktop/千牛工作台.lnk"));
    if(!Files.isRegularFile(shortcut))throw new IllegalStateException("千牛启动入口不存在："+shortcut+"。请在千牛启动.properties中设置官方快捷方式路径。");
    // Explorer brokers the official shortcut, so the desktop application remains
    // owned by the Windows shell rather than by the disposable RPA process tree.
    Path explorer=Path.of(System.getenv().getOrDefault("WINDIR","C:/Windows"),"explorer.exe");
    log("APPLICATION_LAUNCH","Windows Explorer -> "+shortcut);
    new ProcessBuilder(explorer.toString(),shortcut.toString()).start();
    long deadline=System.nanoTime()+90_000_000_000L,nextReport=0;
    while(System.nanoTime()<deadline){
      if(Files.exists(ROOT.resolve("STOP")))throw new IllegalStateException("STOP file present");
      HWND opened=findOpeningWorkbench();if(opened!=null){activate(opened);selectAccount();return opened;}
      HWND signedIn=findWindow("千牛接待台");if(signedIn!=null)return reopenFromReception(signedIn);
      if(System.nanoTime()>=nextReport){progress("正在等待千牛工作台打开；如出现登录页，请先完成登录");nextReport=System.nanoTime()+3_000_000_000L;}
      Thread.sleep(500);
    }
    throw new IllegalStateException("千牛启动后未出现已登录的工作台。请完成千牛登录，再重新执行；执行器不会代填密码或验证码。");
  }
  static void activate(HWND target) throws Exception {
    window=target;USER.ShowWindow(window,9);USER.SetForegroundWindow(window);Thread.sleep(650);
    Rectangle targetBounds=bounds();long bestArea=0;Screen best=null;
    for(int index=0;index<Screen.getNumberScreens();index++){
      Screen candidate=Screen.getScreen(index);Rectangle overlap=targetBounds.intersection(candidate.getBounds());
      long area=overlap.isEmpty()?0:(long)overlap.width*overlap.height;
      if(area>bestArea){bestArea=area;best=candidate;}
    }
    if(best==null)throw new IllegalStateException("目标窗口不在任何显示器可见范围内，请恢复窗口后再运行。");
    screen=best;log("WINDOW_SCREEN","window="+targetBounds+" screen="+screen.getBounds());
    if(!USER.GetForegroundWindow().equals(window))throw new IllegalStateException("Target lost foreground while restoring session");
    for(int key:new int[]{1,2,4,16,17,18})if(USER.GetAsyncKeyState(key)<0)throw new IllegalStateException("A mouse button or modifier is held");
  }
  static HWND reopenFromReception(HWND reception) throws Exception {
    progress("已找到登录中的千牛接待台，正在恢复目标店铺工作台");
    activate(reception);selectAccount();
    click("reception-sidebar","reception-workbench");
    long deadline=System.nanoTime()+30_000_000_000L;
    while(System.nanoTime()<deadline){
      if(Files.exists(ROOT.resolve("STOP")))throw new IllegalStateException("STOP file present");
      HWND opened=findOpeningWorkbench();if(opened!=null){activate(opened);selectAccount();log("SESSION_RESTORED","从已登录接待台恢复工作台，未重新登录。");return opened;}
      Thread.sleep(500);
    }
    throw new IllegalStateException("已找到登录账号，但工作台未能打开。请检查千牛是否存在遮挡弹窗。");
  }
  static void selectAccount() throws Exception {
    String account=launchSettings.getProperty("targetAccount","示例账号:店铺").trim();
    if(account.isEmpty())throw new IllegalStateException("未配置目标店铺账号，请先在任务执行设置中填写千牛顶部账号名。");
    progress("正在确认目标店铺账号："+account);
    for(int attempt=0;attempt<4;attempt++){
      Rectangle stable=bounds();
      Rectangle header=new Rectangle(stable.x,stable.y,stable.width,55).intersection(screen.getBounds());
      if(header.width<150||header.height<40)throw new IllegalStateException("目标账号栏未完整显示，请将窗口移入同一显示器后重试。");
      if(!USER.GetForegroundWindow().equals(window))throw new IllegalStateException("Target lost foreground while selecting account");
      screen.mouseMove(new Location(stable.x+stable.width/2,stable.y+85));Thread.sleep(150);
      Path image=runDir.resolve("account-header-"+(++sequence)+".png"),output=runDir.resolve("account-match-"+sequence+".json");
      ImageIO.write(screen.capture(header).getImage(),"png",image.toFile());
      Process scan=new ProcessBuilder(ROOT.resolve("ocr/.venv/Scripts/python.exe").toString(),ROOT.resolve("ocr/find_account.py").toString(),image.toString(),"--account",account)
          .redirectOutput(output.toFile()).redirectError(runDir.resolve("account-ocr-stderr.txt").toFile()).start();
      if(!scan.waitFor(120,TimeUnit.SECONDS)){scan.destroyForcibly();throw new IllegalStateException("目标账号识别超时。");}
      JSONObject matchResult=new JSONObject(Files.readString(output,StandardCharsets.UTF_8));
      if(scan.exitValue()!=0||!matchResult.optBoolean("ok"))throw new IllegalStateException("未能唯一确认已登录的目标账号“"+account+"”。请确认顶部标签完整显示此账号；不会选择其他店铺。");
      JSONObject match=matchResult.getJSONObject("match");
      if(!USER.GetForegroundWindow().equals(window))throw new IllegalStateException("Target lost foreground while selecting account");
      if(!bounds().equals(stable)){
        log("ACCOUNT_REACQUIRE","Window bounds changed; discard OCR coordinates and observe again: "+bounds());
        if(attempt<3){progress("千牛已调整窗口尺寸，正在重新识别目标账号");Thread.sleep(700);continue;}
        throw new IllegalStateException("Window changed while selecting account");
      }
      if(match.optBoolean("active")){log("ACCOUNT_CONFIRMED",account+" confidence="+match.getDouble("confidence"));return;}
      if(attempt==3)break;
      var center=match.getJSONArray("center");
      Location point=new Location(header.x+(int)Math.round(center.getDouble(0)),header.y+(int)Math.round(center.getDouble(1)));
      if(point.y<header.y+8||point.y>header.y+44)throw new IllegalStateException("账号标签位置异常，已停止。");
      if(Files.exists(ROOT.resolve("STOP")))throw new IllegalStateException("STOP file present");
      log("ACCOUNT_SELECT",account+" at "+point);Settings.ClickDelay=.15;screen.click(point);Thread.sleep(700);
    }
    throw new IllegalStateException("目标账号标签未显示为选中状态，已停止以避免在错误店铺执行。");
  }
  static void waitForSycmReady() throws Exception {
    long deadline=System.nanoTime()+60_000_000_000L,nextReport=0,readySince=0;
    while(System.nanoTime()<deadline){
      if(Files.exists(ROOT.resolve("STOP")))throw new IllegalStateException("STOP file present");
      if(!USER.GetForegroundWindow().equals(window))throw new IllegalStateException("Target lost foreground while opening sycm");
      if(dismissKnownPromotion()){readySince=0;continue;}
      Hit logo=search("main","sycm-logo","sycm-logo-home","sycm-logo-compact");
      if(logo!=null){
        try{validateAppearance(logo);}catch(IllegalStateException obscured){readySince=0;Thread.sleep(500);continue;}
        Hit market=search("nav","market-label-normal","market-label-active","market-label-home");
        if(market!=null){
          try{validateAppearance(market);}catch(IllegalStateException obscured){readySince=0;Thread.sleep(500);continue;}
          if(readySince==0)readySince=System.nanoTime();
          if(System.nanoTime()-readySince>=1_200_000_000L){capture("sycm-ready");return;}
        }else readySince=0;
      }else readySince=0;
      if(System.nanoTime()>=nextReport){progress("正在等待生意参谋页面与市场入口加载");nextReport=System.nanoTime()+3_000_000_000L;}
      Thread.sleep(500);
    }
    throw new IllegalStateException("生意参谋未在60秒内加载出市场入口。请检查登录状态、店铺权限或是否有未识别的弹窗。");
  }
  static boolean dismissKnownPromotion() throws Exception {
    // Close the foreground achievement first; the price warning can be visible
    // behind it but is dimmed and must never be clicked through the overlay.
    Hit promotion=search("main","promo-achievement-title","promo-peak-title");
    if(promotion!=null){
      Hit close=search("main","promo-achievement-close","promo-peak-close");
      if(close==null||Math.abs(promotion.scale-close.scale)>=.01
          ||Math.abs((close.match.getTarget().x-promotion.match.getTarget().x)/promotion.scale-370)>=40
          ||Math.abs((close.match.getTarget().y-promotion.match.getTarget().y)/promotion.scale+46)>=40)return false;
      if(++dismissedPromotions>4)throw new IllegalStateException("活动展示弹窗反复出现，请先关闭后重新执行。");
      validateAppearance(promotion);
      progress("正在关闭已识别的活动成就展示弹窗");
      click("main",close.template);return true;
    }
    // The "优惠竞争力变差提醒" dialog has a distinctive header including
    // its close cross. Match the complete header, rather than a generic X,
    // so a different popup cannot be dismissed by accident.
    Hit warning=search("main","promo-price-header");
    if(warning!=null){
      if(++dismissedPromotions>4)throw new IllegalStateException("活动提醒弹窗反复出现，请先关闭后重新执行。");
      if(!USER.GetForegroundWindow().equals(window))throw new IllegalStateException("Target lost foreground while closing popup");
      if(!bounds().equals(warning.bounds))throw new IllegalStateException("Window moved while closing popup");
      validateAppearance(warning);
      Hit fresh=search("main","promo-price-header");
      if(fresh==null||!fresh.bounds.equals(warning.bounds)||distance(fresh.match.getTarget(),warning.match.getTarget())>4)
        throw new IllegalStateException("Window changed while closing popup");
      validateAppearance(fresh);
      Location cross=new Location(fresh.match.x+(int)Math.round(671*fresh.scale),fresh.match.y+(int)Math.round(35*fresh.scale));
      if(Files.exists(ROOT.resolve("STOP")))throw new IllegalStateException("STOP file present");
      if(!USER.GetForegroundWindow().equals(window)||!bounds().equals(fresh.bounds))throw new IllegalStateException("Target lost foreground before click");
      progress("正在关闭已识别的优惠竞争力提醒弹窗");
      log("CLOSE_PRICE_WARNING",describe(fresh));
      Settings.ClickDelay=.15;screen.click(cross);
      Rectangle b=bounds();screen.mouseMove(new Location(b.x+b.width/2,b.y+110));Thread.sleep(650);
      if(!USER.GetForegroundWindow().equals(window)||!bounds().equals(fresh.bounds))throw new IllegalStateException("Window changed while closing popup");
      if(search("main","promo-price-header")!=null)throw new IllegalStateException("优惠竞争力提醒未能关闭，请手动关闭后重试。");
      capture("closed-price-warning");return true;
    }
    return false;
  }
  static Rectangle bounds() {
    RECT r=new RECT(); if(!USER.GetWindowRect(window,r)) throw new IllegalStateException("Window closed");
    return r.toRectangle();
  }
  static Region region(String area) {
    Rectangle b=bounds();
    if(area.equals("table")) {
      Hit title=search("main","keywords-title");
      if(title==null) throw new IllegalStateException("Search ranking heading not visible");
      int top=title.match.y+120;
      Region table=new Region(new Rectangle(b.x+280,top,b.width-290,b.y+b.height-top-15).intersection(screen.getBounds()));
      table.setAutoWaitTimeout(0); return table;
    }
    Rectangle r=switch(area){
      case "tabs" -> new Rectangle(b.x+105,b.y+40,b.width-115,100);
      case "app-sidebar" -> new Rectangle(b.x,b.y+140,105,Math.min(650,b.height-150));
      case "reception-sidebar" -> new Rectangle(b.x,b.y+100,70,240);
      case "nav" -> new Rectangle(b.x+400,b.y+140,b.width-410,Math.min(570,b.height-150));
      case "sidebar" -> new Rectangle(b.x+105,b.y+260,180,Math.min(630,b.height-270));
      default -> new Rectangle(b.x+105,b.y+140,b.width-115,b.height-150);
    };
    Rectangle clipped=r.intersection(screen.getBounds());
    if(clipped.width<50||clipped.height<20) throw new IllegalStateException("Target is off the primary screen");
    Region result=new Region(clipped); result.setAutoWaitTimeout(0); return result;
  }
  static Hit search(String area,String...names) {
    Rectangle before=bounds();
    Region roi=region(area);
    // Search at native scale first; scale fallback only if it has no confident unique match.
    for(float scale:SCALES) {
      List<Hit> hits=new ArrayList<>();
      for(String name:names) {
        Pattern p=new Pattern(ASSETS.resolve(name+".png").toString()).similar(.90).resize(scale);
        for(Match m:roi.findAllList(p)) {
          if(hits.stream().noneMatch(h->distance(h.match.getTarget(),m.getTarget())<20)) hits.add(new Hit(m,name,scale,before));
        }
      }
      if(!before.equals(bounds())) return null;
      if(hits.size()>1) throw new IllegalStateException("Ambiguous image target "+Arrays.toString(names)+": "+hits.size());
      if(hits.size()==1) return hits.get(0);
    }
    return null;
  }
  static Hit waitFor(String area,int seconds,String...names) throws Exception {
    long end=System.nanoTime()+seconds*1_000_000_000L;
    long started=System.nanoTime(), nextReport=started;
    do {
      if(Files.exists(ROOT.resolve("STOP"))) throw new IllegalStateException("STOP file present");
      if(names.length>0&&names[0].startsWith("market-")&&dismissKnownPromotion())continue;
      if(System.nanoTime()>=nextReport) {
        progress("正在定位"+targetLabel(names)+"（已等待 "+((System.nanoTime()-started)/1_000_000_000L)+" 秒）");
        nextReport=System.nanoTime()+3_000_000_000L;
      }
      Hit h=search(area,names);
      if(h!=null) return h;
      Thread.sleep(350);
    }while(System.nanoTime()<end);
    throw new IllegalStateException("未找到“"+targetLabel(names)+"”，等待 "+seconds+" 秒后已停止。请检查页面是否加载完成、是否被弹窗遮挡或页面样式是否变化。 [Image timeout: "+Arrays.toString(names)+"]");
  }
  static void normalizePageTop() throws Exception {
    // Scrolling collapses the site's header; restore it before locating navigation.
    if(search("main","sycm-logo")!=null) return;
    progress("正在恢复页面顶部导航");
    log("NORMALIZE","Expanded header is not visible; scrolling the page to the top.");
    for(int attempt=0;attempt<4;attempt++) {
      Rectangle stable=bounds();
      Rectangle visible=stable.intersection(screen.getBounds());
      Location content=new Location(visible.x+visible.width*3/4,visible.y+visible.height/2);
      if(Files.exists(ROOT.resolve("STOP"))) throw new IllegalStateException("STOP file present");
      if(!USER.GetForegroundWindow().equals(window)) throw new IllegalStateException("Target lost foreground before scrolling");
      if(!stable.equals(bounds())) throw new IllegalStateException("Window moved before scrolling");
      screen.wheel(content,Mouse.WHEEL_UP,20);
      Thread.sleep(650);
      if(!stable.equals(bounds())||!USER.GetForegroundWindow().equals(window)) throw new IllegalStateException("Window changed while scrolling");
      if(search("main","sycm-logo")!=null) {
        capture("normalized-page-top");
        return;
      }
    }
  }
  static void click(String area,String...names) throws Exception {
    for(int tries=0;tries<3;tries++) {
      Hit h=waitFor(area,20,names);
      if(!USER.GetForegroundWindow().equals(window)) throw new IllegalStateException("Target lost foreground; stopping");
      if(!bounds().equals(h.bounds)) continue;
      Hit fresh=search(area,names);
      if(fresh==null||distance(fresh.match.getTarget(),h.match.getTarget())>4) continue;
      if(!bounds().equals(fresh.bounds)) continue;
      try{validateAppearance(fresh);}
      catch(IllegalStateException obscured){
        if(names[0].startsWith("market-")&&dismissKnownPromotion())continue;
        throw obscured;
      }
      progress("正在点击"+targetLabel(names));
      log("CLICK",describe(fresh));
      if(Files.exists(ROOT.resolve("STOP"))) throw new IllegalStateException("STOP file present");
      if(!USER.GetForegroundWindow().equals(window)) throw new IllegalStateException("Target lost foreground before click");
      if(!bounds().equals(fresh.bounds)) continue;
      // Use a deliberate press/release interval in the embedded browser.
      // OculiX resets this setting after each click, so set it for every action.
      Settings.ClickDelay=.15;
      screen.click(fresh.match.getTarget());
      // Move away before validating the resulting page: a hover highlight or
      // visible pointer must not cover the same small text we are checking.
      Rectangle clickedWindow=bounds();
      screen.mouseMove(new Location(clickedWindow.x+clickedWindow.width/2,clickedWindow.y+110));
      Thread.sleep(650);
      if(!names[0].equals("reception-workbench"))capture("after-"+names[0]);
      return;
    }
    throw new IllegalStateException("Target moved during click validation");
  }
  static String describe(Hit h) {
    return h.template+" score="+String.format(Locale.ROOT,"%.4f",h.match.getScore())+" scale="+h.scale+" target="+h.match.getTarget()+" window="+h.bounds;
  }
  static double distance(Location a,Location b) { return Math.hypot(a.x-b.x,a.y-b.y); }
  static void validateAppearance(Hit hit) throws Exception {
    BufferedImage expected=ImageIO.read(ASSETS.resolve(hit.template+".png").toFile());
    BufferedImage actual=screen.capture(hit.match).getImage();
    double error=0; int count=0;
    for(int y=0;y<actual.getHeight();y++) for(int x=0;x<actual.getWidth();x++) {
      int a=actual.getRGB(x,y),e=expected.getRGB(Math.min(expected.getWidth()-1,x*expected.getWidth()/actual.getWidth()),Math.min(expected.getHeight()-1,y*expected.getHeight()/actual.getHeight()));
      for(int shift:new int[]{0,8,16}) {error+=Math.abs(((a>>shift)&255)-((e>>shift)&255));count++;}
    }
    if(error/count>42) throw new IllegalStateException("Target appearance changed or is dimmed by an overlay: "+hit.template);
  }
  static void capture(String name) throws Exception {
    Rectangle b=bounds().intersection(screen.getBounds());
    char[] title=new char[128];USER.GetWindowText(window,title,title.length);
    if(Native.toString(title).equals("千牛接待台"))b.height=Math.min(55,b.height);
    ImageIO.write(screen.capture(b).getImage(),"png",runDir.resolve(String.format("%02d-%s.png",++sequence,name)).toFile());
  }
  static void log(String type,String message) throws Exception {
    String line=Instant.now()+" "+type+" "+message+System.lineSeparator();
    System.out.print(line); System.out.flush();
    Files.writeString(runDir.resolve("execution.log"),line,StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
  }
  static void protocol(String type,String message) throws Exception {
    String line=type+" "+message.replace('\n',' ').replace('\r',' ')+System.lineSeparator();
    System.out.print(line); System.out.flush();
    Files.writeString(runDir.resolve("execution.log"),line,StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
  }
  static void progress(String message) throws Exception { protocol("OCULIX_PROGRESS",message); }
  static void step(String id,String status,String message) throws Exception {
    protocol("OCULIX_STEP",new JSONObject().put("id",id).put("status",status).put("message",message).toString());
  }
  static String targetLabel(String...names) {
    String name=names.length==0?"目标页面":names[0];
    if(name.startsWith("market-")) return "市场入口";
    if(name.startsWith("sycm-tab")) return "生意参谋标签页";
    if(name.equals("sycm-logo")) return "生意参谋页面顶部";
    if(name.startsWith("workbench")) return "工作台标签页";
    if(name.startsWith("data-label")) return "千牛数据入口";
    if(name.equals("reception-workbench")) return "已登录店铺的工作台入口";
    if(name.startsWith("promo-")) return "活动成就展示弹窗关闭按钮";
    if(name.startsWith("search-")) return "搜索排行菜单";
    if(name.equals("keywords-title")) return "搜索排行标题";
    if(name.equals("keywords-column")) return "关键词表格";
    if(name.equals("popularity-column")) return "搜索人气列";
    if(name.equals("page-size-label")) return "表格分页栏";
    return "目标页面元素";
  }
  static String friendlyError(Throwable failure) {
    String raw=String.valueOf(failure.getMessage());
    if(raw.contains("未找到“")&&raw.contains(" [Image timeout:")) return raw.substring(0,raw.indexOf(" [Image timeout:"));
    if(raw.contains("STOP file present"))return "已收到停止指令，后续操作已取消。";
    if(raw.contains("Expected one visible")) return "未找到唯一的千牛工作台。请确认目标窗口已打开且处于登录状态。";
    if(raw.contains("foreground")) return "千牛窗口失去焦点，已停止点击。请关闭遮挡窗口后重试。";
    if(raw.contains("Ambiguous image target")) return "发现多个相似的页面元素，无法确认点击位置，已停止。";
    if(raw.contains("mouse button")||raw.contains("modifier is held")) return "检测到鼠标或修饰键仍被按住，请松开后重新执行。";
    if(raw.contains("off the primary screen")) return "目标窗口不在主显示器的可见范围，请移回后重试。";
    if(raw.contains("OCR")) return "页面已定位，但关键词识别失败或超时。请查看本次截图与识别记录。";
    if(raw.contains("appearance changed")) return "页面元素外观变化或被弹窗遮挡，已停止点击。";
    if(raw.contains("Window moved")||raw.contains("Window changed")||raw.contains("Target moved")) return "操作过程中窗口位置发生变化，已停止，请保持窗口稳定后重试。";
    if(raw.matches("(?s).*[\\p{IsHan}].*"))return raw;
    return "页面操作未完成，已保存本次失败截图和详细日志。";
  }
  static void extract() throws Exception {
    if(search("main","page-size-label")==null) {
      log("NORMALIZE","Table footer is outside the viewport; maximizing before reading all 10 rows.");
      USER.ShowWindow(window,3); Thread.sleep(800);
    }
    if(!USER.GetForegroundWindow().equals(window)) throw new IllegalStateException("Target lost foreground");
    Rectangle stable=bounds();
    Hit key=waitFor("table",15,"keywords-column"),pop=waitFor("table",10,"popularity-column"),footer=waitFor("main",10,"page-size-label");
    validateAppearance(key);
    if(!stable.equals(bounds())) throw new IllegalStateException("Window moved during data capture; rerun to re-locate");
    int x=key.match.x+Math.round(18*key.scale),top=key.match.y+key.match.h+Math.round(10*key.scale);
    int width=pop.match.x-x-Math.round(30*key.scale),bottom=footer.match.y-Math.round(15*key.scale);
    if(width<70||bottom-top<200) throw new IllegalStateException("Keyword column not fully visible");
    Region column=new Region(x,top,width,bottom-top);
    Path crop=runDir.resolve("keyword-column.png");
    ImageIO.write(screen.capture(column).getImage(),"png",crop.toFile());
    if(!stable.equals(bounds())||!USER.GetForegroundWindow().equals(window)) throw new IllegalStateException("Window changed while reading");
    capture("final-page");
    log("CAPTURED",crop.toString());
    progress("关键词截图已保存，正在识别文字");
    Path python=ROOT.resolve("ocr/.venv/Scripts/python.exe"),reader=ROOT.resolve("ocr/ocr_image.py");
    if(!Files.exists(python)||!Files.exists(reader)) throw new IllegalStateException("Local OCR helper is missing");
    Path result=runDir.resolve("ocr-result.json");
    Process process=new ProcessBuilder(python.toString(),reader.toString(),crop.toString(),"--expected-rows","10","--min-confidence","0.95").redirectOutput(result.toFile()).redirectError(runDir.resolve("ocr-stderr.txt").toFile()).start();
    if(!process.waitFor(60,TimeUnit.SECONDS)){process.destroyForcibly();throw new IllegalStateException("OCR timeout");}
    if(process.exitValue()!=0) throw new IllegalStateException("OCR failed; see ocr-stderr.txt");
    step("read-keywords","done","十行关键词识别及置信度检查通过");
    step("save-results","running","正在返回关键词与截图");
    log("OCR_CANDIDATES",result.toString());
    progress("关键词识别完成，正在返回结果");
  }
}
