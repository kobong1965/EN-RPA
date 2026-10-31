import copy
import json
from pathlib import Path
import tempfile
import unittest
import douyin_sync as d

SAMPLE=Path(__file__).parent/'ego-20261029/sample-2026-10-27/source.json'

class SourceChecks(unittest.TestCase):
    def setUp(self):
        self.source=json.loads(SAMPLE.read_text(encoding='utf-8'));self.store=d.config('douyin_sikepai')
    def test_real_amount_order_units_are_distinct(self):
        row=d.parse_source(self.source,self.store,'2026-10-27',SAMPLE)
        self.assertEqual((row['paymentAmount'],row['douyinUserPaymentAmount'],row['douyinOrderCount'],row['promotionSpend']),(346,319.75,4,283.33))
        self.assertIsNone(row['paidItemCount']);self.assertIsNone(row['orderRefundRate'])
    def test_previous_day_is_never_relabelled_as_yesterday(self):
        with self.assertRaisesRegex(ValueError,'目标单日'):d.parse_source(self.source,self.store,'2026-10-28',SAMPLE)
    def test_store_and_source_and_duplicate_metrics_rejected(self):
        for key,value in [('storeName','其他店'),('sourceUrl','https://example.com'),('selectedPeriod','近30天')]:
            changed=copy.deepcopy(self.source);changed[key]=value
            with self.assertRaises(ValueError):d.parse_source(changed,self.store,'2026-10-27',SAMPLE)
        changed=copy.deepcopy(self.source);changed['cards'].append(changed['cards'][0])
        with self.assertRaisesRegex(ValueError,'重复'):d.parse_source(changed,self.store,'2026-10-27',SAMPLE)
    def test_unpublished_source_cannot_be_imported(self):
        changed=copy.deepcopy(self.source);changed['status']='waiting_for_data'
        with self.assertRaises(ValueError):d.parse_source(changed,self.store,'2026-10-27',SAMPLE)
if __name__=='__main__':unittest.main()
