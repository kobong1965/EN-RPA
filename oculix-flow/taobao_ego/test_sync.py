import contextlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

path=Path(__file__).with_name('sync.py')
spec=importlib.util.spec_from_file_location('taobao_ego_sync',path)
sync=importlib.util.module_from_spec(spec);spec.loader.exec_module(sync)

class SyncTests(unittest.TestCase):
    def test_invalid_store_and_future_date_rejected(self):
        with self.assertRaises(ValueError):sync.validate_args('unknown','2026-10-29')
        with self.assertRaises(ValueError):sync.validate_args('neutcut','2999-01-01')
    def test_existing_record_never_opens_browser_or_writes(self):
        plan={'ok':True,'storeId':'neutcut','missingDates':[]}
        with tempfile.TemporaryDirectory() as folder, patch.object(sys,'argv',['sync','--store','neutcut','--date','2026-10-29','--apply','--output-root',folder]), patch.object(sync,'run_step',return_value=json.dumps(plan)) as step, contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(sync.main(),0)
            self.assertEqual(step.call_count,1)
            result=json.loads(next(Path(folder).rglob('result.json')).read_text(encoding='utf-8'))
            self.assertEqual(result['status'],'already_registered')
    def test_wrong_plan_stops_before_browser(self):
        plan={'ok':True,'storeId':'amgx','missingDates':['2026-10-29']}
        with tempfile.TemporaryDirectory() as folder, patch.object(sys,'argv',['sync','--store','neutcut','--date','2026-10-29','--apply','--output-root',folder]), patch.object(sync,'run_step',return_value=json.dumps(plan)) as step, contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(sync.main(),2)
            self.assertEqual(step.call_count,1)

if __name__=='__main__':unittest.main()
