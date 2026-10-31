"""按独立 ego 配置采集一个淘宝店，先核验来源再调用现有账本桥接。"""
from __future__ import annotations
import argparse
from datetime import datetime, timedelta, timezone, date
import json
from pathlib import Path
import shutil
import subprocess
import sys
import uuid

BASE=Path(__file__).resolve().parent
FLOW=BASE.parent
BRIDGE=FLOW/'bridge'/'import_daily.py'
PREPARE=FLOW/'bridge'/'prepare_ego_import.py'
SYNC=FLOW/'daily'/'daily_sync.py'
STORES={'neutcut':('Profile 1','淘宝1号店','示例店铺A'),
        'fangmianqiu':('Profile 2','淘宝2号店','示例店铺B'),
        'amgx':('Profile 3','淘宝3号店','示例店铺C')}
TZ=timezone(timedelta(hours=8))

def validate_args(store, day):
    if store not in STORES: raise ValueError('未确认的店铺')
    if date.fromisoformat(day).isoformat()!=day or day>(datetime.now(TZ).date()-timedelta(days=1)).isoformat():
        raise ValueError('日期必须是北京时间昨天或更早')

def run_step(command, log, timeout=180):
    result=subprocess.run(command, cwd=FLOW, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True,encoding='utf-8',errors='replace',timeout=timeout,
        creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
    log.write_text(result.stdout,encoding='utf-8')
    if result.returncode: raise RuntimeError('步骤失败，请查看 '+str(log))
    return result.stdout

def main():
    p=argparse.ArgumentParser();p.add_argument('--store',required=True,choices=STORES)
    p.add_argument('--date',required=True);p.add_argument('--apply',action='store_true');p.add_argument('--output-root',type=Path,default=BASE/'runs')
    p.add_argument('--space-id',type=int,help='仅恢复此采集任务的现有 ego 空间；不新建替代空间')
    a=p.parse_args()
    if hasattr(sys.stdout,'reconfigure'):sys.stdout.reconfigure(encoding='utf-8')
    try:
        validate_args(a.store,a.date)
        if a.space_id is not None and a.space_id<1:raise ValueError('任务空间编号无效')
        run=a.output_root.resolve()/(datetime.now(TZ).strftime('%Y%m%d-%H%M%S')+'-'+uuid.uuid4().hex[:8]);run.mkdir(parents=True)
        print('OCULIX_RUN_DIR '+str(run),flush=True)
        out=run_step([sys.executable,str(BRIDGE),'plan','--store',a.store,'--start-date',a.date,'--end-date',a.date],run/'plan.log',30)
        plan=json.loads(out)
        if not plan.get('ok') or plan.get('storeId')!=a.store or plan.get('missingDates') not in ([],[a.date]):raise ValueError('日期计划校验失败')
        if not plan['missingDates']:
            result={'storeId':a.store,'date':a.date,'status':'already_registered','rows':[{'店铺':a.store,'日期':a.date,'状态':'已登记'}]}
        else:
            cli=shutil.which('ego-browser')
            if not cli: raise RuntimeError('ego-browser 不可用')
            profile_id,profile_name,page_name=STORES[a.store]
            evidence=run/'source.json'; payload=run/'payload.json'
            req={'storeId':a.store,'targetDate':a.date,'profileId':profile_id,'profileName':profile_name,
                 'pageStoreName':page_name,'spaceName':'店脉淘宝日报-'+a.store+'-'+uuid.uuid4().hex[:10],
                 'outputPath':str(evidence).replace('\\','/')}
            if a.space_id is not None: req['spaceId']=a.space_id
            entry=run/'entry.mjs';entry.write_text('import {collect} from '+json.dumps((BASE/'collect.mjs').as_uri())+';\nawait collect('+json.dumps(req,ensure_ascii=False)+');\n',encoding='utf-8')
            run_step([cli,'nodejs','-e',"await import('"+entry.as_uri()+"')"],run/'ego.log')
            run_step([sys.executable,str(PREPARE),'--evidence',str(evidence),'--store',a.store,'--date',a.date,'--output',str(payload)],run/'prepare.log',30)
            command=[sys.executable,str(SYNC),'--mode','run','--store',a.store,'--start-date',a.date,'--end-date',a.date,'--input',str(payload),'--output-root',str(run/'sync')]
            if a.apply:command.append('--apply')
            output=run_step(command,run/'sync.log',120)
            paths=[line.removeprefix('OCULIX_RESULT ').strip() for line in output.splitlines() if line.startswith('OCULIX_RESULT ')]
            if len(paths)!=1: raise ValueError('同步未返回唯一结果')
            detail=json.loads(Path(paths[0]).read_text(encoding='utf-8-sig'))
            if detail.get('storeId')!=a.store or (a.apply and not detail.get('imported')):raise ValueError('同步结果未通过核验')
            result={'storeId':a.store,'date':a.date,'status':'registered' if a.apply else 'validated','rows':detail['rows'],'detailPath':paths[0]}
        result_path=run/'result.json';result_path.write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
        print('OCULIX_RESULT '+str(result_path),flush=True);return 0
    except (ValueError,RuntimeError,OSError,subprocess.TimeoutExpired,KeyError) as exc:
        print('OCULIX_ERROR '+str(exc),flush=True);return 2
if __name__=='__main__':raise SystemExit(main())
