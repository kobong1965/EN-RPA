# -*- coding: utf-8 -*-
from __future__ import print_function

# Jython 2.7 entry point. The subprocess owns all collection and validation.
import sys
from sikuli import getBundlePath
from java.io import File, BufferedReader, InputStreamReader
from java.lang import ProcessBuilder, ProcessHandle, Thread, System
from java.util import ArrayList


def emit(message):
    sys.stdout.write((unicode(message) + u"\n").encode("utf-8"))
    sys.stdout.flush()


def main():
    workspace = File(getBundlePath()).getCanonicalFile().getParentFile().getParentFile()
    python = File(workspace, "oculix-flow/ocr/.venv/Scripts/python.exe")
    script = File(workspace, "oculix-flow/daily/daily_sync.py")
    for item in (python, script):
        if not item.isFile():
            raise IOError(u"缺少每日任务运行文件：" + unicode(item))
    argv = ArrayList()
    for value in (python.getAbsolutePath(), "-B", script.getAbsolutePath(), "--mode", "run", "--apply", "--all-stores",
                  "--owner-pid", str(ProcessHandle.current().pid())):
        argv.add(value)
    child = None
    reader = None
    result_reported = False
    owned = {}
    try:
        emit(u"OCULIX_PROGRESS 正在准备三店每日经营数据顺序同步")
        child = ProcessBuilder(argv).directory(script.getParentFile()).redirectErrorStream(True).start()
        reader = BufferedReader(InputStreamReader(child.getInputStream(), "UTF-8"))
        while child.isAlive() or reader.ready():
            descendants = child.descendants()
            try:
                for handle in descendants.toArray():
                    owned[handle.pid()] = handle
            finally:
                descendants.close()
            while reader.ready():
                line = reader.readLine()
                if line is None:
                    break
                line = unicode(line)
                emit(line)
                if line.startswith("OCULIX_RESULT "):
                    result_reported = True
            if child.isAlive():
                Thread.sleep(100)
        if child.exitValue() != 0:
            raise RuntimeError(u"每日数据同步未完成，退出码：" + unicode(child.exitValue()))
        if not result_reported:
            raise RuntimeError(u"任务未返回本次处理结果，不能确认同步完成")
    finally:
        if child is not None:
            descendants = child.descendants()
            try:
                for handle in descendants.toArray():
                    owned[handle.pid()] = handle
            finally:
                descendants.close()
        if child is not None and child.isAlive():
            child.destroyForcibly()
        for handle in owned.values():
            if handle.isAlive():
                handle.destroyForcibly()
        deadline = System.nanoTime() + 3000000000
        while any(handle.isAlive() for handle in owned.values()) and System.nanoTime() < deadline:
            Thread.sleep(20)
        if any(handle.isAlive() for handle in owned.values()):
            raise RuntimeError(u"任务后代进程未能完全停止，请查看诊断记录")
        if reader is not None:
            reader.close()


main()
