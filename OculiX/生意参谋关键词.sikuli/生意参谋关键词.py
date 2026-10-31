# -*- coding: utf-8 -*-
from __future__ import print_function

# 生意参谋关键词：OculiX IDE 运行入口（Jython 2.7）。
# 从官方快捷方式打开或唤醒千牛，进入工作台后通过数据入口打开生意参谋。
# 千牛需要保持登录；运行期间请勿操作鼠标。
# baseline：按当前窗口运行；moved-small：移动并缩小；maximized：最大化。
# 如需停止，使用 IDE 的停止按钮；退出时会终止仍运行的点击进程。
MODE = "baseline"

import json
import sys
from sikuli import getBundlePath
from java.io import File, FileInputStream, BufferedReader, InputStreamReader
from java.lang import ProcessBuilder, Thread
from java.util import ArrayList


def emit(message):
    # Jython's IDE stdout wrapper otherwise attempts implicit ASCII encoding.
    sys.stdout.write((unicode(message) + u"\n").encode("utf-8"))
    sys.stdout.flush()


def main():
    if MODE not in ("baseline", "moved-small", "maximized"):
        raise ValueError("MODE must be baseline, moved-small, or maximized")

    bundle = File(getBundlePath()).getCanonicalFile()
    workspace = bundle.getParentFile().getParentFile()
    flow = File(workspace, "oculix-flow")
    java = File(workspace, "runtime/java/jdk-21.0.12.1+1/bin/javaw.exe")
    api = File(workspace, "runtime/oculixapi-4.0.0-windows.jar")
    source = File(flow, "SycmFlow.java")
    for required in (java, api, source):
        if not required.isFile():
            raise IOError(u"缺少运行文件：" + unicode(required))

    argv = ArrayList()
    for value in (java.getAbsolutePath(), "-Dfile.encoding=UTF-8",
                  "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                  "-cp", api.getAbsolutePath(), "SycmFlow.java", MODE):
        argv.add(value)

    child = None
    reader = None
    candidate_path = None
    pending_qa = False
    emit(u"开始生意参谋流程，模式：" + MODE)
    emit(u"流程：打开千牛 → 进入工作台 → 数据入口打开生意参谋 → 市场 → 搜索排行 → 读取前 10 个关键词 → 保存结果。")
    try:
        child = ProcessBuilder(argv).directory(flow).redirectErrorStream(True).start()
        reader = BufferedReader(InputStreamReader(child.getInputStream(), "UTF-8"))
        # ready() prevents a blocking read while the child has no new output.
        # Thread.sleep is interruptible so IDE cancellation enters finally.
        while child.isAlive() or reader.ready():
            while reader.ready():
                line = reader.readLine()
                if line is None:
                    break
                line = unicode(line)
                emit(line)
                marker = "OCR_CANDIDATES "
                if marker in line:
                    candidate_path = line.split(marker, 1)[1].strip()
                if "COMPLETED_PENDING_QA" in line:
                    pending_qa = True
            if child.isAlive():
                Thread.sleep(100)

        exit_code = child.exitValue()
        if exit_code != 0:
            raise RuntimeError(u"流程未完成，退出码：%s；请查看上方日志和 oculix-flow/runs 中的失败截图。" % exit_code)
        if not candidate_path:
            raise RuntimeError(u"运行进程已退出，但未收到 OCR_CANDIDATES，不能确认已记录关键词。")

        result_file = File(candidate_path)
        result_reader = BufferedReader(InputStreamReader(
            FileInputStream(result_file), "UTF-8"))
        try:
            chunks = []
            while True:
                line = result_reader.readLine()
                if line is None:
                    break
                chunks.append(unicode(line))
            result = json.loads(u"\n".join(chunks))
        finally:
            result_reader.close()

        lines = result.get("lines", [])
        if not pending_qa or len(lines) != 10:
            raise RuntimeError(u"完成标记或关键词条数与预期不符，请检查日志及截图。")
        emit(u"\n本次识别的关键词（需与截图核对）：")
        for index, row in enumerate(lines[:10], 1):
            emit(u"%d. %s" % (index, row.get("text", u"")))
        emit(u"识别条数：%d；结果目录：%s" %
             (len(lines), unicode(result_file.getParentFile())))
        emit(u"状态：COMPLETED_PENDING_QA（等待核对）。OCR 置信度不等于内容一定正确。")
    finally:
        # Stop the actual executor, not just the IDE wrapper.
        if child is not None and child.isAlive():
            child.destroyForcibly()
        if reader is not None:
            reader.close()


main()
