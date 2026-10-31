"""Own this run's Windows descendants, including when its launcher is killed.

The non-inheritable Job handle deliberately lives until process exit. Windows
closes it on both normal exit and TerminateProcess, killing all job members.
Do not close it from an atexit/finally handler: this process is a member too.
"""
from __future__ import annotations

import ctypes
from ctypes import wintypes
import os
from pathlib import Path
import subprocess
import threading

_job_handle = None


def protect_process_tree(owner_pid: int | None = None) -> None:
    global _job_handle
    if os.name != "nt" or _job_handle is not None:
        return
    owner_pid = os.getppid() if owner_pid is None else owner_pid
    if owner_pid <= 0 or owner_pid == os.getpid():
        raise OSError("任务入口进程无效，未启动采集")
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.CreateJobObjectW.argtypes = [ctypes.c_void_p, wintypes.LPCWSTR]
    kernel.CreateJobObjectW.restype = wintypes.HANDLE
    kernel.SetInformationJobObject.argtypes = [wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD]
    kernel.SetInformationJobObject.restype = wintypes.BOOL
    kernel.AssignProcessToJobObject.argtypes = [wintypes.HANDLE, wintypes.HANDLE]
    kernel.AssignProcessToJobObject.restype = wintypes.BOOL
    kernel.GetCurrentProcess.restype = wintypes.HANDLE
    kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    kernel.OpenProcess.restype = wintypes.HANDLE
    kernel.WaitForSingleObject.argtypes = [wintypes.HANDLE, wintypes.DWORD]
    kernel.WaitForSingleObject.restype = wintypes.DWORD
    kernel.CloseHandle.argtypes = [wintypes.HANDLE]
    kernel.CloseHandle.restype = wintypes.BOOL

    class BasicLimits(ctypes.Structure):
        _fields_ = [("processTime", ctypes.c_longlong), ("jobTime", ctypes.c_longlong),
                    ("flags", wintypes.DWORD), ("minWorkingSet", ctypes.c_size_t),
                    ("maxWorkingSet", ctypes.c_size_t), ("activeProcesses", wintypes.DWORD),
                    ("affinity", ctypes.c_size_t), ("priority", wintypes.DWORD), ("scheduling", wintypes.DWORD)]

    class IoCounters(ctypes.Structure):
        _fields_ = [(name, ctypes.c_ulonglong) for name in
                    ("readOps", "writeOps", "otherOps", "readBytes", "writeBytes", "otherBytes")]

    class ExtendedLimits(ctypes.Structure):
        _fields_ = [("basic", BasicLimits), ("io", IoCounters), ("processMemory", ctypes.c_size_t),
                    ("jobMemory", ctypes.c_size_t), ("peakProcessMemory", ctypes.c_size_t),
                    ("peakJobMemory", ctypes.c_size_t)]

    # The explicit owner is the Jython JVM, not the Windows venv redirector.
    owner = kernel.OpenProcess(0x00100000, False, owner_pid)  # SYNCHRONIZE
    if not owner:
        raise OSError("任务入口已退出，未启动采集")
    if kernel.WaitForSingleObject(owner, 0) != 258:  # WAIT_TIMEOUT means still running.
        kernel.CloseHandle(owner)
        raise OSError("任务入口已退出，未启动采集")
    job = kernel.CreateJobObjectW(None, None)
    if not job:
        kernel.CloseHandle(owner)
        raise ctypes.WinError(ctypes.get_last_error())
    limits = ExtendedLimits()
    limits.basic.flags = 0x00002000  # JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE, no breakaway.
    if not kernel.SetInformationJobObject(job, 9, ctypes.byref(limits), ctypes.sizeof(limits)):
        error = ctypes.get_last_error()
        kernel.CloseHandle(job)
        kernel.CloseHandle(owner)
        raise ctypes.WinError(error)
    if not kernel.AssignProcessToJobObject(job, kernel.GetCurrentProcess()):
        error = ctypes.get_last_error()
        kernel.CloseHandle(job)
        kernel.CloseHandle(owner)
        raise OSError("无法建立任务进程保护，未启动采集：" + str(ctypes.WinError(error)))
    _job_handle = job

    def watch_owner() -> None:
        result = kernel.WaitForSingleObject(owner, 0xFFFFFFFF)
        kernel.CloseHandle(owner)
        # Fail closed on a dead owner or a failed wait. os._exit closes the Job
        # handle through the OS; cleanup must not depend on Python finalizers.
        if result != 258:
            os._exit(130)

    threading.Thread(target=watch_owner, name="daily-run-owner", daemon=True).start()


def terminate_process_tree(process: subprocess.Popen) -> None:
    """Best-effort immediate cancellation; the Job is the abnormal-exit backstop."""
    if process.poll() is not None:
        return
    if os.name == "nt":
        command = Path(os.environ.get("WINDIR", "C:/Windows")) / "System32/taskkill.exe"
        try:
            subprocess.run([str(command), "/PID", str(process.pid), "/T", "/F"],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                           timeout=5, creationflags=subprocess.CREATE_NO_WINDOW, check=False)
        except (OSError, subprocess.TimeoutExpired):
            pass
    if process.poll() is None:
        process.kill()
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        raise OSError("任务子进程未能停止，任务不会报告完成")
