"""Register the cross-platform manual entry without altering other tasks."""
import json
from pathlib import Path
import shutil

workspace=Path(__file__).resolve().parent.parent
source=workspace/'OculiX/三店每日经营数据.sikuli/三店每日经营数据.py'
folder=workspace/'OculiX/淘宝与抖店每日数据.sikuli'
folder.mkdir(exist_ok=True)
wrapper=source.read_text(encoding='utf-8').replace('oculix-flow/daily/daily_sync.py','oculix-flow/daily_workflow.py').replace(', "--all-stores"','').replace('正在准备三店每日经营数据顺序同步','正在分步骤同步淘宝三店与抖店数据')
assert 'daily_workflow.py' in wrapper and '--all-stores' not in wrapper
entry=folder/'淘宝与抖店每日数据.py';entry.write_text(wrapper,encoding='utf-8')
registry=workspace/'执行数据/任务.json'
backup=workspace/'执行数据/任务备份/任务-新增跨平台每日数据前-20261029.json'
backup.parent.mkdir(exist_ok=True)
if not backup.exists():shutil.copy2(registry,backup)
tasks=json.loads(registry.read_text(encoding='utf-8-sig'))
item={'id':'daily-cross-platform','name':'淘宝与抖店每日数据','path':str(entry),'type':'跨平台数据同步',
      'description':'分步骤同步昨日：淘宝三店使用RPA，抖店示例抖店使用ego；分别校验日期并登记到店脉',
      'silenceWarningMs':30000,'timeoutMs':2700000}
tasks=[t for t in tasks if t.get('id')!=item['id']]+[item]
pending=registry.with_suffix('.json.pending');pending.write_text(json.dumps(tasks,ensure_ascii=False,indent=2),encoding='utf-8');pending.replace(registry)
print(json.dumps({'registered':item['id'],'entry':str(entry)},ensure_ascii=True))
