"""Prepare a dry-run input from an actual ego page capture; never writes the ledger."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import sys

DAILY = Path(__file__).resolve().parent.parent / 'daily'
sys.path.insert(0, str(DAILY))
from table_parser import parse_table_matrix
from import_daily import load_store, normalize_input, METRICS

def prepare(path: Path, store_id: str, day: str) -> dict:
    raw = path.resolve().read_bytes()
    evidence = json.loads(raw.decode('utf-8-sig'))
    store = load_store(store_id)
    parsed = parse_table_matrix(evidence['matrix'], store_id=store_id, store_name=store['storeName'])
    days = [d for d in parsed['days'] if d['date'] == day]
    if len(days) != 1 or max(d['date'] for d in parsed['days']) != day:
        raise ValueError('页面表格最新日期与目标日期不符')
    row = {k: v for k, v in days[0].items() if k in METRICS or k == 'date'}
    row['provenance'] = {'method': 'ego-browser', 'storeName': store['storeName'],
        'pageDate': day, 'profileName': evidence['profileName'], 'sourceUrl': evidence['url'],
        'capturedAt': evidence.get('capturedAt'), 'textPath': str(path.resolve()),
        'textSha256': hashlib.sha256(raw).hexdigest()}
    payload = {'storeId': store_id, 'timezone': 'Asia/Shanghai', 'days': [row]}
    normalize_input(payload, store)
    return payload

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--evidence', type=Path, required=True)
    p.add_argument('--store', required=True)
    p.add_argument('--date', required=True)
    p.add_argument('--output', type=Path, required=True)
    args=p.parse_args()
    payload=prepare(args.evidence, args.store, args.date)
    if args.output.exists(): raise ValueError('输出文件已存在')
    args.output.write_text(json.dumps(payload,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')

if __name__ == '__main__': main()
