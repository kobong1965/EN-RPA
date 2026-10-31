"""抖店日度采集：仅使用 ego 页面 DOM；日期不符不会登记。"""
from __future__ import annotations
import argparse
import copy
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
from urllib.parse import urlparse
import uuid

BASE=Path(__file__).resolve().parent
FLOW=BASE.parent
sys.path.insert(0,str(FLOW/'daily'))
from daily_sync import execute, ledger_window, write_json
from process_guard import protect_process_tree
BRIDGE=FLOW/'bridge/import_daily.py'
spec=importlib.util.spec_from_file_location('ledger_bridge',BRIDGE)
bridge=importlib.util.module_from_spec(spec);spec.loader.exec_module(bridge)
TZ=timezone(timedelta(hours=8))

def yesterday():return (datetime.now(TZ).date()-timedelta(days=1)).isoformat()
def config(store_id):
    matches=[s for s in json.loads((BASE/'stores.json').read_text(encoding='utf-8'))['stores'] if s['storeId']==store_id and s.get('confirmed') is True]
    if len(matches)!=1:raise ValueError('抖店店铺尚未确认')
    return matches[0]
def number(value):
    value=value.strip().replace(',','').replace('¥','').replace('￥','')
    if value in {'','-','--','—'}:return None
    if not re.fullmatch(r'\d+(?:\.\d+)?%?',value):raise ValueError('未识别的指标单位或数值：'+value)
    out=Decimal(value.rstrip('%'))/(100 if value.endswith('%') else 1)
    return float(out)
def parse_source(source, store, target_date, source_path):
    if source.get('platform')!='douyin' or source.get('status')!='captured':raise ValueError('抖店尚未返回目标日期数据')
    if source.get('storeId')!=store['storeId'] or source.get('storeName')!=store['storeName']:raise ValueError('抖店店铺不匹配')
    if source.get('startDate')!=target_date or source.get('endDate')!=target_date or source.get('requestedDate')!=target_date:raise ValueError('页面不是目标单日数据')
    if source.get('selectedPeriod')!='近1天' or urlparse(source.get('sourceUrl','')).hostname!='fxg.jinritemai.com':raise ValueError('抖店来源页面或统计范围错误')
    metrics={}
    for card in source['cards']:
        label=''.join(card['label'].split())
        if label in metrics:raise ValueError('抖店指标名称重复：'+label)
        metrics[label]=number(card['rawValue'])
    required={'成交金额','成交订单数','用户支付金额','投放消耗店铺被投'}
    if not required.issubset(metrics) or any(metrics[k] is None for k in required):raise ValueError('抖店核心指标未完整加载')
    if metrics['成交订单数']%1:raise ValueError('成交订单数不是整数')
    row={'date':target_date,'paymentAmount':metrics['成交金额'],'promotionSpend':metrics['投放消耗店铺被投'],
         'paidItemCount':None,'orderRefundRate':None,'refundAmountByPaymentDate':metrics.get('退款金额支付时间'),
         'douyinOrderCount':int(metrics['成交订单数']),'douyinUserPaymentAmount':metrics['用户支付金额'],
         'douyinExposureUsers':metrics.get('商品曝光人数'),'douyinClickUsers':metrics.get('商品点击人数'),
         'douyinDealUsers':metrics.get('成交人数'),'douyinSettlementAmount':metrics.get('结算金额'),
         'douyinExpenseAmount':metrics.get('支出金额'),'douyinRefundByRefundTime':metrics.get('退款金额退款时间'),
         'douyinAdSpendRate':metrics.get('投放费比'),'averageOrderValue':metrics.get('客单价')}
    row['provenance']={'platform':'douyin','storeName':store['storeName'],'pageDate':target_date,'sourceUrl':source['sourceUrl'],
        'capturedAt':source['capturedAt'],'method':'ego-browser-dom','sourcePath':str(source_path.resolve()),
        'sourceSha256':hashlib.sha256(source_path.read_bytes()).hexdigest(),
        'metricDefinitions':{'paymentAmount':'抖店成交金额，含平台优惠；用户支付金额另存',
          'promotionSpend':'投放消耗（店铺被投）','paidItemCount':'页面未提供成交件数，保留空值',
          'orderRefundRate':'页面未提供订单退款率，保留空值','douyinOrderCount':'成交订单数，不能作为支付件数'}}
    return row

def import_row(row,store,run_dir,apply):
    original,ledger=bridge.read_ledger();updated=copy.deepcopy(ledger)
    matches=[s for s in updated['stores'] if s['id']==store['storeId']]
    if len(matches)>1:raise ValueError('抖店账本存在重复店铺')
    if not matches:
        shop={'id':store['storeId'],'name':store['displayName'],'platform':'douyin','number':max(s.get('number',0) for s in updated['stores'])+1,
              'status':'verified','history':[],'latestDetails':{}}
        updated['stores'].append(shop)
    else:shop=matches[0]
    if shop.get('platform')!='douyin':raise ValueError('店铺平台不是抖店，停止写入')
    old=next((r for r in shop['history'] if r['date']==row['date']),None)
    inserted=[]
    if old:
        if any(old.get(k)!=v for k,v in row.items() if k!='provenance'):raise ValueError('已登记日期与平台指标不同，需核对后处理')
    else:
        shop['history'].append(row);shop['history'].sort(key=lambda r:r['date']);inserted=[row['date']]
        latest=shop['history'][-1];shop['latestDetails']={k:v for k,v in latest.items() if k not in {'date','provenance'}}
        shop['latestDetailsDate']=latest['date'];shop['source']={'kind':'抖店经营数据 · ego页面采集','periodEnd':latest['date'],'evidence':latest['provenance']}
        shop['sync']={'lastImportedDate':latest['date'],'lastImportedAt':datetime.now(TZ).isoformat()}
        updated['revision']=int(updated.get('revision',0))+1;updated['generatedAt']=datetime.now(TZ).isoformat()
        updated['reportDate']=max(r['date'] for s in updated['stores'] for r in s['history'])
    assert [s for s in ledger['stores'] if s['id']!=store['storeId']]==[s for s in updated['stores'] if s['id']!=store['storeId']]
    receipt={'insertedDates':inserted,'skippedDates':[] if inserted else [row['date']],'writesUserData':False}
    if apply and inserted:
        was_running=ledger_window('close',run_dir)
        try:
            receipt['backupPath'],receipt['ledgerSha256']=bridge.commit(bridge.LEDGER,original,updated);receipt['writesUserData']=True
        finally:
            if was_running:ledger_window('reopen',run_dir)
    write_json(run_dir/'receipt.json',receipt)
    return receipt

def main():
    sys.stdout.reconfigure(encoding='utf-8')
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--store',default='douyin_sikepai');p.add_argument('--date',default=yesterday())
    p.add_argument('--mode',choices=['plan','run'],default='plan');p.add_argument('--apply',action='store_true');p.add_argument('--source',type=Path)
    args=p.parse_args();protect_process_tree()
    try:
        if date.fromisoformat(args.date).isoformat()!=args.date or args.date>yesterday():raise ValueError('只接受北京时间昨天或更早日期')
        store=config(args.store);original,ledger=bridge.read_ledger()
        existing=next((s for s in ledger['stores'] if s['id']==args.store),{'history':[]})
        present=any(r['date']==args.date for r in existing['history'])
        run_dir=BASE/'runs'/(datetime.now().strftime('%Y%m%d-%H%M%S')+'-'+uuid.uuid4().hex[:8]);run_dir.mkdir(parents=True)
        result={'platform':'douyin','storeId':args.store,'storeName':store['storeName'],'date':args.date,'status':'already_registered' if present else 'planned','rows':[]}
        if args.mode=='plan' or (present and not args.source):
            result['rows']=[{'平台':'抖店','店铺':store['storeName'],'日期':args.date,'状态':'已登记，无需重复采集' if present else '待采集'}]
        else:
            source_path=args.source.resolve() if args.source else run_dir/'source.json'
            if not args.source:
                request={**store,'targetDate':args.date,'spaceName':'店脉抖店日报-'+args.date+'-'+uuid.uuid4().hex[:6],'outputPath':str(source_path).replace('\\','/')}
                entry=run_dir/'collect-entry.mjs'
                entry.write_text('import {collect} from '+json.dumps((BASE/'collect.mjs').as_uri())+';\nawait collect('+json.dumps(request,ensure_ascii=False)+');\n',encoding='utf-8')
                cli=shutil.which('ego-browser')
                if not cli:raise ValueError('ego-browser 不可用')
                code,_=execute([cli,'nodejs','-e',"await import('"+entry.as_uri()+"')"],run_dir/'ego.log','ego采集抖店昨日数据')
                if code:raise ValueError('ego采集失败，请查看本次ego.log；未登记抖店数据')
            source=json.loads(source_path.read_text(encoding='utf-8-sig'))
            if source.get('status')=='waiting_for_data':
                result.update(status='waiting_for_data',sourceDate=source.get('endDate'))
                result['rows']=[{'平台':'抖店','店铺':store['storeName'],'日期':args.date,'状态':'平台昨日数据尚未开放','平台当前日期':source.get('endDate')}]
            else:
                row=parse_source(source,store,args.date,source_path);write_json(run_dir/'normalized.json',row)
                receipt=import_row(row,store,run_dir,args.apply)
                result.update(status='registered' if args.apply else 'validated',receipt=receipt)
                result['rows']=[{'平台':'抖店','方式':'ego','店铺':store['storeName'],'日期':args.date,'成交金额':row['paymentAmount'],'用户支付金额':row['douyinUserPaymentAmount'],'成交订单数':row['douyinOrderCount'],'投放消耗':row['promotionSpend'],'状态':'已登记' if args.apply else '校验通过，未登记'}]
        write_json(run_dir/'result.json',result)
        print('OCULIX_RESULT '+str(run_dir/'result.json'),flush=True)
        return 3 if result['status']=='waiting_for_data' else 0
    except (ValueError,OSError,KeyError,bridge.ImportFailure) as exc:
        print('OCULIX_ERROR '+str(exc),flush=True);return 2
if __name__=='__main__':raise SystemExit(main())
