import io,json,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
import daily_workflow as w

class Steps(unittest.TestCase):
    def test_one_store_failure_does_not_skip_other_stores_or_ego(self):
        with tempfile.TemporaryDirectory() as directory:
            steps=[(2,None,'模拟第一店失败',None),
                   (0,{'rows':[],'storeId':'amgx','date':'2026-10-30','status':'already_registered'},'',None),
                   (0,{'rows':[],'storeId':'fangmianqiu','date':'2026-10-30','status':'registered'},'',None),
                   (3,{'rows':[{'状态':'平台尚未开放昨日数据'}],'storeId':'douyin_sikepai'},'',None)]
            class Api:
                def CloseHandle(self,h):pass
            with patch.object(w,'BASE',Path(directory)),patch.object(w,'protect_process_tree'),patch.object(w,'exclusive_run',return_value=(Api(),1)),patch.object(w,'run_step',side_effect=steps) as run,patch('sys.argv',['daily_workflow.py','--mode','run','--date','2026-10-30','--apply']),patch('sys.stdout',new_callable=io.StringIO):
                status=w.main()
            self.assertEqual(status,3);self.assertEqual(run.call_count,4)
            result=json.loads(next(Path(directory).rglob('result.json')).read_text(encoding='utf-8'))
            self.assertEqual([s['status'] for s in result['steps']],['failed','completed','completed','waiting_for_data'])
            self.assertEqual(result['status'],'partial')
            self.assertEqual([row['状态'] for row in result['rows'][:3]],['未完成','已齐全，无需重复采集','已登记'])
            self.assertEqual(result['rows'][0]['原因'],'模拟第一店失败')
            for call in run.call_args_list[:3]:
                command=call.args[0];self.assertIn(str(Path(directory)/'taobao_ego/sync.py'),command)
                self.assertEqual(command[command.index('--date')+1],'2026-10-30');self.assertIn('--apply',command)
    def test_plan_keeps_existing_planner_and_does_not_launch_browser(self):
        with tempfile.TemporaryDirectory() as directory:
            result=(0,{'rows':[],'plan':{'missingDates':['2026-10-30']}},'',None)
            class Api:
                def CloseHandle(self,h):pass
            with patch.object(w,'BASE',Path(directory)),patch.object(w,'protect_process_tree'),patch.object(w,'exclusive_run',return_value=(Api(),1)),patch.object(w,'run_step',return_value=result) as run,patch('sys.argv',['daily_workflow.py','--mode','plan','--only','taobao','--date','2026-10-30']),patch('sys.stdout',new_callable=io.StringIO):
                self.assertEqual(w.main(),0)
            self.assertEqual(run.call_count,3)
            for call in run.call_args_list:
                command=call.args[0];self.assertIn(str(Path(directory)/'daily/daily_sync.py'),command)
                self.assertIn('plan',command);self.assertNotIn('--apply',command)
            rows=json.loads(next(Path(directory).rglob('result.json')).read_text(encoding='utf-8'))['rows']
            self.assertEqual([r['状态'] for r in rows],['待采集']*3)
    def test_wrong_store_or_date_is_not_reported_as_success(self):
        with tempfile.TemporaryDirectory() as directory:
            bad=(0,{'rows':[],'storeId':'other','date':'2026-10-29','status':'registered'},'',None)
            class Api:
                def CloseHandle(self,h):pass
            with patch.object(w,'BASE',Path(directory)),patch.object(w,'protect_process_tree'),patch.object(w,'exclusive_run',return_value=(Api(),1)),patch.object(w,'run_step',return_value=bad),patch('sys.argv',['daily_workflow.py','--mode','run','--only','taobao','--date','2026-10-30','--apply']),patch('sys.stdout',new_callable=io.StringIO):
                self.assertEqual(w.main(),3)
            result=json.loads(next(Path(directory).rglob('result.json')).read_text(encoding='utf-8'))
            self.assertEqual([s['status'] for s in result['steps']],['failed']*3)
if __name__=='__main__':unittest.main()
