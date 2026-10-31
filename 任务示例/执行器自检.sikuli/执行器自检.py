# -*- coding: utf-8 -*-
# Local smoke task: no mouse, keyboard, network or business application actions.
import codecs
import json
import os
import sys
import time
from java.lang import System
from java.util import UUID

def emit(text):
    sys.stdout.write((unicode(text) + u"\n").encode("utf-8"))
    sys.stdout.flush()

root = unicode(System.getProperty("user.dir"))
out = os.path.join(root, u"自检记录", unicode(UUID.randomUUID().toString()))
os.makedirs(out)
emit(u"OCULIX_RUN_DIR " + out)
emit(u"OCULIX_PROGRESS 正在检查中文文件读写")
sample = os.path.join(out, u"中文读写.txt")
with codecs.open(sample, "w", "utf-8") as stream:
    stream.write(u"执手执行器中文读写正常")
with codecs.open(sample, "r", "utf-8") as stream:
    assert stream.read() == u"执手执行器中文读写正常"
time.sleep(2)
emit(u"OCULIX_PROGRESS 中文读写通过，正在生成结果")
rows = [{u"检查项": u"任务脚本启动", u"结果": u"通过"},
        {u"检查项": u"中文文件读写", u"结果": u"通过"},
        {u"检查项": u"结果文件生成", u"结果": u"通过"}]
result = os.path.join(out, u"自检结果.json")
with codecs.open(result, "w", "utf-8") as stream:
    stream.write(json.dumps({"rows": rows}, ensure_ascii=False))
time.sleep(1)
emit(u"OCULIX_RESULT " + result)
