"""Confirmed-store daily importer. plan and dry-run never write the ledger."""
from __future__ import annotations
import argparse, copy, ctypes, hashlib, json, math, os, re, sys, tempfile
from urllib.parse import urlparse
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

LEDGER = Path(os.environ.get('APPDATA', str(Path.home() / 'AppData/Roaming'))) / '店脉淘宝数据中控台/data/dashboard-history.json'
STORES_CONFIG = Path(__file__).resolve().parent.parent / 'daily/stores.json'
REQUIRED = {'paymentAmount', 'promotionSpend', 'paidItemCount', 'refundAmountByPaymentDate', 'orderRefundRate'}
QIANCHUAN_ORDER_COUNT = 'qianchuanOverallOrderCount'
MONEY = {'paymentAmount','promotionSpend','refundAmountByPaymentDate','netPaymentAmount','refundAmountByCompletionDate','averageOrderValue','keywordPromotionSpend','audiencePromotionSpend','smartSceneSpend','affiliateCommission','repeatCustomerAmount'}
COUNTS = {'paidItemCount','qianchuanOverallOrderCount','visitors','paidBuyers','addToCartItems','addToCartBuyers','productCollectors','pageViews','paidSubOrderCount','repeatCustomerCount'}
RATES = {'orderRefundRate','amountRefundRate','paymentConversionRate','signedReturnRate','consultationRate','disputeLiabilityRate','collectionWithin48hRate','repeatCustomerRate'}
DURATIONS = {'wangwangResponseSeconds','logisticsArrivalHours','refundProcessingDays','averageStayDuration'}
METRICS = MONEY | COUNTS | RATES | DURATIONS
SHANGHAI = timezone(timedelta(hours=8))

class ImportFailure(Exception):
    def __init__(self, code, message, **details):
        super().__init__(message); self.code=code; self.details=details

def fail(code, message, **details): raise ImportFailure(code, message, **details)
def digest(raw): return hashlib.sha256(raw).hexdigest()
def yesterday(): return datetime.now(SHANGHAI).date() - timedelta(days=1)
def parse_date(value):
    try:
        parsed = date.fromisoformat(value)
        if parsed.isoformat() != value: raise ValueError()
        return parsed
    except (ValueError, TypeError): fail('INVALID_DATE', '日期必须是有效的 YYYY-MM-DD', value=value)

def load_store(store_id, path=None):
    if not isinstance(store_id,str) or not re.fullmatch(r'[a-z][a-z0-9_-]{0,63}',store_id) or store_id=='all': fail('STORE_NOT_ALLOWED','店铺 ID 无效')
    try: config=json.loads((path or STORES_CONFIG).read_text(encoding='utf-8-sig'))
    except (OSError,ValueError) as exc: fail('STORE_CONFIG_INVALID','无法读取已确认店铺配置：'+str(exc))
    stores=config.get('stores',[]) if isinstance(config,dict) else config
    if not isinstance(stores,list): fail('STORE_CONFIG_INVALID','stores 配置必须是数组')
    matches=[s for s in stores if isinstance(s,dict) and s.get('storeId')==store_id]
    if len(matches)!=1 or matches[0].get('confirmed') is not True: fail('STORE_UNCONFIRMED','店铺身份尚未明确确认',storeId=store_id)
    store=matches[0]
    if not isinstance(store.get('storeName'),str) or not store['storeName'].strip() or not isinstance(store.get('targetAccount'),str) or not store['targetAccount'].strip(): fail('STORE_CONFIG_INVALID','店名和账号不能为空')
    if not isinstance(store.get('nameAliases',[]),list) or any(not isinstance(alias,str) or not alias.strip() for alias in store.get('nameAliases',[])): fail('STORE_CONFIG_INVALID','店名别名无效')
    return store

def read_ledger(path=LEDGER):
    try: raw=path.read_bytes(); ledger=json.loads(raw)
    except (OSError, ValueError) as exc: fail('LEDGER_READ_FAILED', str(exc))
    stores=ledger.get('stores', [])
    if not isinstance(stores,list) or not stores or any(not isinstance(s,dict) or not re.fullmatch(r'[a-z][a-z0-9_-]{0,63}',str(s.get('id',''))) or s['id']=='all' or not isinstance(s.get('history'),list) for s in stores): fail('LEDGER_INVALID','账本店铺列表无效')
    if len({s['id'] for s in stores})!=len(stores): fail('LEDGER_INVALID','账本店铺 ID 重复')
    for store in stores:
        dates=[r.get('date') for r in store.get('history', [])]
        if len(dates)!=len(set(dates)): fail('LEDGER_INVALID','账本存在重复日期', storeId=store['id'])
    return raw, ledger

def plan(ledger, original_hash, start=None, end=None, days=30, store_id='neutcut'):
    store=next((s for s in ledger['stores'] if s['id']==store_id),{'history':[]})
    end=parse_date(end) if end else yesterday()
    if end>yesterday(): fail('FUTURE_DATE','只能同步截至北京时间昨天的数据')
    if days<1 or days>3660: fail('INVALID_RANGE','days 必须在 1 到 3660 之间')
    anchor=store.get('sync',{}).get('anchorDate')
    start=parse_date(start or anchor) if start or anchor else end-timedelta(days=days-1)
    if start>end or (end-start).days>3660: fail('INVALID_RANGE','日期窗口无效或超过十年')
    target=[(start+timedelta(days=i)).isoformat() for i in range((end-start).days+1)]
    present={r['date'] for r in store['history']}
    return {'ok':True,'mode':'plan','storeId':store_id,'timezone':'Asia/Shanghai','startDate':start.isoformat(),'endDate':end.isoformat(),'existingDates':[d for d in target if d in present],'missingDates':[d for d in target if d not in present],'ledgerSha256':original_hash}

def normalize_input(payload, store_config=None):
    store_config=store_config or load_store(payload.get('storeId'))
    store_id=store_config['storeId']
    if payload.get('storeId')!=store_id or payload.get('timezone')!='Asia/Shanghai': fail('STORE_NOT_ALLOWED','输入店铺或时区与任务不符')
    allowed_names={store_config['storeName'],*store_config.get('nameAliases',[])}
    is_douyin = store_config.get('platform') == 'douyin'
    rows=payload.get('days')
    if not isinstance(rows,list) or len(rows)>3660: fail('INVALID_INPUT','days 必须是最多 3660 行的数组')
    normalized=[]; seen=set()
    for entry in rows:
        if not isinstance(entry,dict): fail('INVALID_ROW','每天的数据必须是对象')
        unknown=set(entry)-METRICS-{'date','provenance'}
        if unknown: fail('UNKNOWN_FIELD','存在未定义字段', fields=sorted(unknown))
        day=parse_date(entry.get('date')).isoformat()
        if day in seen: fail('DUPLICATE_INPUT','同一批输入包含重复日期', date=day)
        seen.add(day)
        if day>yesterday().isoformat(): fail('FUTURE_DATE','不接受今天或未来日期', date=day)
        required = REQUIRED - {'paidItemCount'} if is_douyin else REQUIRED
        if is_douyin: required = required | {QIANCHUAN_ORDER_COUNT}
        missing=required-set(entry)
        if missing: fail('MISSING_REQUIRED','缺少必要日指标', date=day, fields=sorted(missing))
        row={'date':day}
        for key in sorted(set(entry)&METRICS):
            value=entry[key]
            if value is None:
                if key in {'paymentAmount','paidItemCount'}: fail('MISSING_REQUIRED','支付金额和支付件数不能缺失，不能用 0 补空', date=day,field=key)
            elif isinstance(value,bool) or not isinstance(value,(int,float)) or not math.isfinite(value) or value<0:
                fail('INVALID_NUMBER','指标必须是非负有限数值或允许的 null',date=day,field=key)
            elif key in COUNTS and int(value)!=value: fail('INVALID_COUNT','数量必须是整数',date=day,field=key)
            elif key in RATES and value>1: fail('INVALID_RATE','比例必须为 0 到 1 小数',date=day,field=key)
            row[key]=value
        provenance=entry.get('provenance')
        browser = isinstance(provenance,dict) and provenance.get('method')=='ego-browser'
        browser_profiles={'neutcut':'淘宝1号店','amgx':'淘宝3号店','fangmianqiu':'淘宝2号店'}
        if browser:
            url=urlparse(provenance.get('sourceUrl',''))
            identity_ok=(provenance.get('profileName')==browser_profiles.get(store_id) and
                         url.scheme=='https' and url.hostname=='sycm.taobao.com' and
                         not url.username and not url.password and bool(provenance.get('textPath')))
        else:
            identity_ok=isinstance(provenance,dict) and provenance.get('targetAccount')==store_config['targetAccount']
        if not isinstance(provenance,dict) or provenance.get('storeName') not in allowed_names or not identity_ok or provenance.get('pageDate')!=day:
            fail('EVIDENCE_MISMATCH','证据店名、已确认账号、页面日期必须与本次任务一致',date=day,storeId=store_id)
        text_evidence=None
        if provenance.get('textPath'):
            try:
                text_path=Path(provenance['textPath'])
                if not text_path.is_absolute() or not text_path.is_file(): raise ValueError('页面文本路径无效')
                text_raw=text_path.read_bytes()
                if digest(text_raw)!=str(provenance.get('textSha256','')).lower(): raise ValueError('页面文本 SHA256 不符')
                text_decoded=text_raw.decode('utf-8-sig')
                if browser:
                    source=json.loads(text_decoded)
                    if source.get('url')!=provenance['sourceUrl'] or source.get('profileName')!=provenance['profileName']:
                        raise ValueError('浏览器证据来源或配置不匹配')
                    page_text=source.get('pageText')
                    if not isinstance(page_text,str): raise ValueError('浏览器证据缺少页面原文')
                    if day not in page_text: raise ValueError('浏览器页面未显示目标日期')
                    if not allowed_names.intersection(line.strip() for line in page_text.splitlines()): raise ValueError('页面原文未包含独立且完全匹配的店名')
                    parser_dir=Path(__file__).resolve().parent.parent / 'daily'
                    if str(parser_dir) not in sys.path: sys.path.insert(0,str(parser_dir))
                    from table_parser import parse_table_matrix
                    parsed=parse_table_matrix(source['matrix'],store_id=store_id,store_name=store_config['storeName'])
                    found=[item for item in parsed['days'] if item['date']==day]
                    if len(found)!=1 or max(item['date'] for item in parsed['days'])!=day:
                        raise ValueError('表格最新日期与目标日期不符')
                    for key,value in row.items():
                        if key=='date': continue
                        actual=found[0].get(key)
                        if value is None or actual is None:
                            if value is not actual: raise ValueError('指标与浏览器表格不一致：'+key)
                        elif not math.isclose(value,actual,rel_tol=0,abs_tol=1e-9):
                            raise ValueError('指标与浏览器表格不一致：'+key)
                    text_decoded=page_text
                if day not in text_decoded: raise ValueError('页面原文未包含本行日期')
                if not allowed_names.intersection(line.strip() for line in text_decoded.splitlines()): raise ValueError('页面原文未包含独立且完全匹配的已确认店名')
                text_evidence={'path':str(text_path),'sha256':digest(text_raw)}
            except (OSError,ValueError,UnicodeError,KeyError,TypeError) as exc: fail('EVIDENCE_INVALID',str(exc),date=day)
        paths=provenance.get('screenshots') or ([{'path':provenance.get('screenshotPath'),'sha256':provenance.get('screenshotSha256')}] if provenance.get('screenshotPath') else [])
        if not isinstance(paths,list) or (not paths and text_evidence is None): fail('EVIDENCE_MISSING','需要经哈希校验的页面文本或证据截图',date=day)
        verified=[]
        for item in paths:
            try:
                p=Path(item['path'])
                if not p.is_absolute() or not p.is_file() or p.suffix.lower() not in {'.png','.jpg','.jpeg','.webp'}: raise ValueError('截图路径无效')
                expected=item['sha256'].lower()
                actual=digest(p.read_bytes())
                if actual!=expected: raise ValueError('截图 SHA256 不符')
            except (KeyError,TypeError,ValueError,OSError) as exc: fail('EVIDENCE_INVALID',str(exc),date=day)
            verified.append({'path':str(p),'sha256':actual})
        row['provenance']={'storeId':store_id,'storeName':provenance['storeName'],'pageDate':day,'screenshots':verified,'capturedAt':provenance.get('capturedAt'),'method':provenance.get('method','执手RPA页面采集')}
        if browser:
            row['provenance'].update(profileName=provenance['profileName'],sourceUrl=provenance['sourceUrl'])
        else:
            row['provenance']['targetAccount']=store_config['targetAccount']
        if text_evidence: row['provenance']['text']=text_evidence
        if row.get('averageStayDuration') is not None and provenance.get('averageStayDurationUnit') not in {'seconds','秒'}:
            fail('UNIT_REQUIRED','平均停留时长必须按页面确认并转换成秒，在证据中注明单位',date=day)
        if 'averageStayDurationUnit' in provenance: row['provenance']['averageStayDurationUnit']=provenance['averageStayDurationUnit']
        if is_douyin: row['paidItemCount'] = None
        normalized.append(row)
    return sorted(normalized,key=lambda row:row['date'])

def merge(ledger, rows, store_id='neutcut', store_config=None):
    result=copy.deepcopy(ledger)
    store=next((s for s in result['stores'] if s['id']==store_id),None)
    if store is None:
        if not store_config or store_config.get('confirmed') is not True or store_config.get('allowCreate') is not True: fail('STORE_NOT_REGISTERED','新店尚未获准写入；请先确认映射和新店登记',storeId=store_id)
        if not rows: return result,[],[]
        store={'id':store_id,'name':store_config['storeName'],'number':max((s.get('number',0) for s in result['stores']),default=0)+1,'status':'verified','source':{'kind':'执手RPA页面采集'},'history':[],'latestDetails':{}}
        result['stores'].append(store)
    existing={r['date']:r for r in store['history']}
    inserted=[]; skipped=[]; conflicts=[]
    for row in rows:
        old=existing.get(row['date'])
        if old is not None:
            differences=[]
            for field in set(row)&METRICS:
                left,right=old.get(field),row[field]
                if left is None or right is None: same=left is right and field in old
                else: same=isinstance(left,(int,float)) and not isinstance(left,bool) and math.isclose(left,right,rel_tol=0,abs_tol=1e-9)
                if not same: differences.append(field)
            if differences: conflicts.append({'date':row['date'],'fields':sorted(differences)})
            else: skipped.append(row['date'])
        else:
            existing[row['date']]=row; inserted.append(row['date'])
    if conflicts: fail('CONFLICT','已有日期指标不同；整批未写入，请人工核对',conflicts=conflicts)
    if not inserted: return result,inserted,skipped
    store['history']=sorted(existing.values(),key=lambda row:row['date'])
    latest=store['history'][-1]
    if latest['date'] in inserted:
        store['latestDetails']={field:latest.get(field) for field in METRICS}
        store['latestDetailsDate']=latest['date']
        evidence=latest['provenance'].get('text') or latest['provenance']['screenshots'][0]
        kind='ego浏览器页面采集' if latest['provenance'].get('method')=='ego-browser' else '执手RPA页面采集'
        store['source']={'kind':kind,'fileName':Path(evidence['path']).name,'sha256':evidence['sha256'].upper(),'capturedAt':datetime.now(SHANGHAI).date().isoformat(),'periodStart':min(inserted),'periodEnd':latest['date'],'evidence':latest['provenance']}
    store['sync']={**store.get('sync',{}),'anchorDate':min([store.get('sync',{}).get('anchorDate',min(inserted)),min(inserted)]),'lastImportedAt':datetime.now(SHANGHAI).isoformat(),'lastImportedDate':max(inserted)}
    result['revision']=int(result.get('revision',0))+1
    result['generatedAt']=datetime.now(timezone.utc).isoformat()
    result['reportDate']=max(r['date'] for s in result['stores'] for r in s['history'])
    assert [s for s in result['stores'] if s['id']!=store_id]==[s for s in ledger['stores'] if s['id']!=store_id]
    return result,inserted,skipped

def app_running():
    if os.name!='nt': return False
    from ctypes import wintypes
    class PROCESSENTRY32W(ctypes.Structure):
        _fields_=[('dwSize',wintypes.DWORD),('cntUsage',wintypes.DWORD),('th32ProcessID',wintypes.DWORD),('th32DefaultHeapID',ctypes.c_size_t),('th32ModuleID',wintypes.DWORD),('cntThreads',wintypes.DWORD),('th32ParentProcessID',wintypes.DWORD),('pcPriClassBase',wintypes.LONG),('dwFlags',wintypes.DWORD),('szExeFile',wintypes.WCHAR*260)]
    kernel=ctypes.WinDLL('kernel32',use_last_error=True)
    kernel.CreateToolhelp32Snapshot.restype=wintypes.HANDLE
    kernel.Process32FirstW.argtypes=[wintypes.HANDLE,ctypes.POINTER(PROCESSENTRY32W)]
    kernel.Process32NextW.argtypes=[wintypes.HANDLE,ctypes.POINTER(PROCESSENTRY32W)]
    kernel.CloseHandle.argtypes=[wintypes.HANDLE]
    handle=kernel.CreateToolhelp32Snapshot(2,0)
    if handle==ctypes.c_void_p(-1).value: fail('PROCESS_CHECK_FAILED','无法检查店脉运行状态')
    item=PROCESSENTRY32W();item.dwSize=ctypes.sizeof(item)
    try:
        found=kernel.Process32FirstW(handle,ctypes.byref(item))
        while found:
            if item.szExeFile.casefold()=='taobaopulse.exe': return True
            found=kernel.Process32NextW(handle,ctypes.byref(item))
        return False
    finally: kernel.CloseHandle(handle)

def commit(path, original, updated):
    if app_running(): fail('APP_RUNNING','请正常退出店脉后重试写入；不会强制关闭应用')
    lock=path.with_name(path.name+'.rpa-lock')
    try: descriptor=os.open(lock,os.O_CREAT|os.O_EXCL|os.O_WRONLY)
    except FileExistsError: fail('IMPORT_LOCKED','已有导入正在执行，或需要检查遗留导入锁')
    temp=None
    try:
        os.write(descriptor,str(os.getpid()).encode());os.close(descriptor)
        if path.read_bytes()!=original: fail('LEDGER_CHANGED','账本已变化，请重新 dry-run')
        backup_dir=path.parent/'backups';backup_dir.mkdir(exist_ok=True)
        stamp=datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S.%fZ')
        backup=backup_dir/f'dashboard-history.before-store-rpa.{stamp}.{os.getpid()}.json'
        with backup.open('xb') as stream: stream.write(original);stream.flush();os.fsync(stream.fileno())
        assert backup.read_bytes()==original
        encoded=(json.dumps(updated,ensure_ascii=False,indent=2)+'\n').encode('utf-8')
        fd,name=tempfile.mkstemp(prefix='.dashboard-history.rpa-',suffix='.json',dir=path.parent);temp=Path(name)
        with os.fdopen(fd,'wb') as stream: stream.write(encoded);stream.flush();os.fsync(stream.fileno())
        if path.read_bytes()!=original or app_running(): fail('LEDGER_CHANGED','写入前应用或账本状态变化，已保留备份并停止')
        os.replace(temp,path);temp=None
        if path.read_bytes()!=encoded: fail('VERIFY_FAILED','写入后校验失败，请从备份恢复',backupPath=str(backup))
        return str(backup),digest(encoded)
    finally:
        if temp is not None and temp.exists(): temp.unlink()
        lock.unlink(missing_ok=True)

def main():
    if hasattr(sys.stdout,'reconfigure'): sys.stdout.reconfigure(encoding='utf-8')
    parser=argparse.ArgumentParser(description=__doc__)
    sub=parser.add_subparsers(dest='command',required=True)
    p=sub.add_parser('plan');p.add_argument('--start-date');p.add_argument('--end-date');p.add_argument('--days',type=int,default=30)
    p.add_argument('--store',default='neutcut')
    for command in ['dry-run','apply']:
        p=sub.add_parser(command);p.add_argument('--input',required=True);p.add_argument('--store',default='neutcut')
    args=parser.parse_args()
    try:
        original,ledger=read_ledger()
        store_config=load_store(args.store)
        if args.command=='plan': out=plan(ledger,digest(original),args.start_date,args.end_date,args.days,store_id=args.store)
        else:
            try: payload=json.loads(Path(args.input).read_text(encoding='utf-8-sig'))
            except (OSError,ValueError) as exc: fail('INPUT_READ_FAILED',str(exc))
            if not isinstance(payload,dict): fail('INVALID_INPUT','输入必须是 JSON 对象')
            if payload.get('expectedLedgerSha256') and payload['expectedLedgerSha256'].lower()!=digest(original): fail('LEDGER_CHANGED','采集后账本已变化，请重新规划')
            rows=normalize_input(payload,store_config);updated,inserted,skipped=merge(ledger,rows,store_id=args.store,store_config=store_config)
            out={'ok':True,'mode':args.command,'storeId':args.store,'insertedDates':inserted,'skippedDates':skipped,'backupPath':None,'ledgerSha256':digest(original),'writesUserData':False}
            if args.command=='apply' and inserted:
                out['backupPath'],out['ledgerSha256']=commit(LEDGER,original,updated);out['writesUserData']=True
        print(json.dumps(out,ensure_ascii=False));return 0
    except ImportFailure as exc:
        print(json.dumps({'ok':False,'code':exc.code,'message':str(exc),**exc.details},ensure_ascii=False));return 2

if __name__=='__main__': raise SystemExit(main())
