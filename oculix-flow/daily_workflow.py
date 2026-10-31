"""每日跨平台流程：淘宝三店与抖店各用绑定的 ego 页面采集。"""
from __future__ import annotations
import argparse
import ctypes
from ctypes import wintypes
from datetime import date,datetime,timedelta,timezone
import json
from pathlib import Path
import sys
import uuid

BASE=Path(__file__).resolve().parent
sys.path.insert(0,str(BASE/'daily'))
from daily_sync import execute,read_json,write_json,SyncError
from process_guard import protect_process_tree
from store_registry import configured_stores,get_store
TZ=timezone(timedelta(hours=8))

def target_yesterday():return (datetime.now(TZ).date()-timedelta(days=1)).isoformat()
def exclusive_run():
    api=ctypes.WinDLL('kernel32',use_last_error=True)
    api.CreateMutexW.argtypes=[ctypes.c_void_p,wintypes.BOOL,wintypes.LPCWSTR];api.CreateMutexW.restype=wintypes.HANDLE
    api.CloseHandle.argtypes=[wintypes.HANDLE];api.CloseHandle.restype=wintypes.BOOL
    handle=api.CreateMutexW(None,True,'Local\\ZhishouDailyCrossPlatformSync')
    if not handle:raise OSError('无法建立每日任务运行锁')
    if ctypes.get_last_error()==183:api.CloseHandle(handle);raise OSError('已有每日采集任务正在运行，请查看其运行记录')
    return api,handle

def run_step(command,folder,label):
    folder.mkdir(parents=True,exist_ok=True)
    code,output=execute(command,folder/'execution.log',label)
    result_paths=[line.removeprefix('OCULIX_RESULT ').strip() for line in output.splitlines() if line.startswith('OCULIX_RESULT ')]
    result=read_json(Path(result_paths[-1])) if result_paths else None
    if result is not None and (not isinstance(result,dict) or not isinstance(result.get('rows'),list)):
        raise ValueError('本次结果文件结构不正确')
    errors=[line.removeprefix('OCULIX_ERROR ').strip() for line in output.splitlines() if line.startswith('OCULIX_ERROR ')]
    return code,result,(errors[-1] if errors else ''),result_paths[-1] if result_paths else None

def main():
    if hasattr(sys.stdout,'reconfigure'):sys.stdout.reconfigure(encoding='utf-8')
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--mode',choices=['plan','run'],default='plan')
    p.add_argument('--date',default=target_yesterday());p.add_argument('--apply',action='store_true')
    p.add_argument('--only',choices=['all','taobao','douyin'],default='all');p.add_argument('--owner-pid',type=int)
    args=p.parse_args();api=None;mutex=None
    try:
        if date.fromisoformat(args.date).isoformat()!=args.date or args.date>target_yesterday():raise ValueError('日期必须是北京时间昨天或更早日期')
        if args.apply and args.mode!='run':raise ValueError('只有运行模式可以登记数据')
        protect_process_tree(args.owner_pid);api,mutex=exclusive_run()
        run_dir=BASE/'workflow-runs'/(datetime.now(TZ).strftime('%Y%m%d-%H%M%S')+'-'+uuid.uuid4().hex[:8]);run_dir.mkdir(parents=True)
        print('OCULIX_RUN_DIR '+str(run_dir),flush=True)
        steps=[];rows=[]
        stores=[get_store(s['storeId']) for s in configured_stores()] if args.only!='douyin' else []
        count=len(stores)+(args.only!='taobao')
        print('OCULIX_PROGRESS 步骤1：按北京时间确定目标日期 '+args.date,flush=True)
        for index,store in enumerate(stores,1):
            label='淘宝ego '+store['storeName'];print(f'OCULIX_PROGRESS 采集步骤 {index}/{count}：{label}',flush=True)
            if args.mode=='plan':
                command=[sys.executable,'-B',str(BASE/'daily/daily_sync.py'),'--mode','plan','--store',store['storeId'],'--end-date',args.date,'--output-root',str(run_dir/store['storeId'])]
            else:
                command=[sys.executable,'-B',str(BASE/'taobao_ego/sync.py'),'--store',store['storeId'],'--date',args.date,'--output-root',str(run_dir/store['storeId'])]
            if args.apply:command.append('--apply')
            try:
                code,result,error,path=run_step(command,run_dir/store['storeId'],label)
                if code or result is None:raise ValueError(error or '该店没有返回有效的本次结果')
                if args.mode=='plan':
                    missing=result.get('plan',{}).get('missingDates',[])
                    state='待采集' if missing else '已齐全，无需重复采集'
                    inserted=0;pending=len(missing)
                else:
                    if result.get('storeId')!=store['storeId'] or result.get('date')!=args.date:raise ValueError('采集结果的店铺或日期不一致')
                    status=result.get('status')
                    if status not in ({'registered','already_registered'} if args.apply else {'validated','already_registered'}):raise ValueError('采集结果状态无效或未完成')
                    state={'registered':'已登记','already_registered':'已齐全，无需重复采集','validated':'已采集校验，未登记'}[status]
                    inserted=int(status=='registered');pending=0
                rows.append({'平台':'淘宝','方式':'ego','店铺':store['storeName'],'日期':args.date,'状态':state,'新增天数':inserted,'待补天数':pending})
                steps.append({'platform':'taobao','storeId':store['storeId'],'status':'completed','resultPath':path})
            except (OSError,ValueError,SyncError) as exc:
                rows.append({'平台':'淘宝','方式':'ego','店铺':store['storeName'],'日期':args.date,'状态':'未完成','原因':str(exc)})
                steps.append({'platform':'taobao','storeId':store['storeId'],'status':'failed','error':str(exc)})
        if args.only!='taobao':
            print(f'OCULIX_PROGRESS 采集步骤 {count}/{count}：抖店ego 示例抖店',flush=True)
            command=[sys.executable,'-B',str(BASE/'douyin/douyin_sync.py'),'--mode',args.mode,'--date',args.date]
            if args.apply:command.append('--apply')
            try:
                code,result,error,path=run_step(command,run_dir/'douyin','ego采集抖店')
                if result is None:raise ValueError(error or '抖店没有返回本次结果')
                rows.extend(result['rows'])
                steps.append({'platform':'douyin','storeId':result['storeId'],'status':'waiting_for_data' if code==3 else 'completed' if code==0 else 'failed','resultPath':path,
                              'error':('抖店昨日数据尚未开放，页面当前日期：'+str(result.get('sourceDate'))) if code==3 else error})
            except (OSError,ValueError,SyncError) as exc:
                rows.append({'平台':'抖店','方式':'ego','店铺':'示例抖店','日期':args.date,'状态':'未完成','原因':str(exc)})
                steps.append({'platform':'douyin','storeId':'douyin_sikepai','status':'failed','error':str(exc)})
        incomplete=any(s['status']!='completed' for s in steps)
        result={'date':args.date,'timezone':'Asia/Shanghai','mode':args.mode,'status':'partial' if incomplete else 'completed','rows':rows,'steps':steps,'sequential':True}
        write_json(run_dir/'result.json',result)
        print('OCULIX_PROGRESS 最后一步：结果已逐店汇总并保存',flush=True)
        if incomplete:
            print('OCULIX_ERROR 部分步骤未完成：'+'；'.join(s.get('error') or s['storeId'] for s in steps if s['status']!='completed')+'。已完成步骤及逐店结果已保存。',flush=True)
        print('OCULIX_RESULT '+str(run_dir/'result.json'),flush=True)
        return 3 if incomplete else 0
    except (OSError,ValueError,SyncError) as exc:
        print('OCULIX_ERROR '+str(exc),flush=True);return 2
    finally:
        if api and mutex:api.CloseHandle(mutex)
if __name__=='__main__':raise SystemExit(main())
