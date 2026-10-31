import com.sun.jna.Native;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.*;
import com.sun.jna.ptr.IntByReference;
import java.nio.file.*;
import java.util.*;

/** Graceful lifecycle only: never kill a process, never click an unknown dialog. */
public class LedgerWindow {
  static final Path EXE=Path.of(System.getenv().getOrDefault("DIANMAI_EXE", "../店脉淘宝数据中控台/程序/TaobaoPulse.exe")).toAbsolutePath().normalize();
  static final User32 USER=User32.INSTANCE;
  static List<ProcessHandle> processes(){
    return ProcessHandle.allProcesses().filter(p->p.info().command().map(c->Path.of(c).toAbsolutePath().normalize().toString().equalsIgnoreCase(EXE.toString())).orElse(false)).toList();
  }
  public static void main(String[] args) throws Exception {
    if(args.length!=1)throw new IllegalArgumentException("close or reopen required");
    if(args[0].equals("reopen")){
      if(!Files.isRegularFile(EXE))throw new IllegalStateException("店脉程序文件不存在");
      if(processes().isEmpty())new ProcessBuilder(Path.of(System.getenv().getOrDefault("WINDIR","C:/Windows"),"explorer.exe").toString(),EXE.toString()).start();
      System.out.println("LEDGER_REOPEN_REQUESTED");return;
    }
    if(!args[0].equals("close"))throw new IllegalArgumentException("unknown action");
    List<ProcessHandle> owned=processes();
    if(owned.isEmpty()){System.out.println("LEDGER_WAS_RUNNING false");return;}
    Set<Long> ids=new HashSet<>();for(ProcessHandle p:owned)ids.add(p.pid());
    List<HWND> found=new ArrayList<>();
    USER.EnumWindows((h,d)->{
      IntByReference pid=new IntByReference();USER.GetWindowThreadProcessId(h,pid);
      if(ids.contains(Integer.toUnsignedLong(pid.getValue()))&&USER.IsWindowVisible(h)){
        char[] title=new char[512];USER.GetWindowText(h,title,title.length);
        if(Native.toString(title).equals("店脉淘宝数据中控台"))found.add(h);
      }
      return true;
    },null);
    if(found.isEmpty()||found.stream().anyMatch(h->!USER.IsWindowEnabled(h)))throw new IllegalStateException("店脉存在未关闭对话框或没有可正常退出的主窗口，请正常退出后重试；未写账本");
    // Multiple normal instances can retain stale ledger state. Close each exact
    // executable/title match normally; a save prompt still blocks the write.
    for(HWND handle:found)USER.PostMessage(handle,0x0010,new WPARAM(0),new LPARAM(0));
    long deadline=System.nanoTime()+20_000_000_000L;
    while(System.nanoTime()<deadline){if(processes().isEmpty()){System.out.println("LEDGER_WAS_RUNNING true");return;}Thread.sleep(250);}
    throw new IllegalStateException("店脉未能正常退出，可能有未保存提示；不会强制结束程序，未写账本");
  }
}
