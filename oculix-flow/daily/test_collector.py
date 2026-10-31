from __future__ import annotations
import unittest
from pathlib import Path

from collector import assemble_payload
from daily_sync import SyncError

FIXTURE = Path(__file__).resolve().parents[2] / "验证记录/示例店铺A每日数据-20261028/生意参谋页面原文.txt"


@unittest.skipUnless(FIXTURE.is_file(), "本机真实采集证据不可用")
class CollectorPayloadTests(unittest.TestCase):
    def request(self, dates=None, end="2026-10-27"):
        return {"storeId": "neutcut", "targetAccount": "示例账号:店铺", "endDate": end,
                "missingDates": dates or ["2026-10-27"], "ledgerSha256": "a" * 64}

    def test_only_missing_date_is_returned_and_evidence_is_bound(self):
        payload, parsed = assemble_payload(FIXTURE.read_text(encoding="utf-8-sig"), self.request(), FIXTURE, {})
        self.assertEqual(len(parsed["days"]), 30)
        self.assertEqual([row["date"] for row in payload["days"]], ["2026-10-27"])
        self.assertEqual(payload["days"][0]["promotionSpend"], 3685.26)
        self.assertEqual(len(payload["days"][0]["provenance"]["textSha256"]), 64)

    def test_wrong_shop_and_stale_latest_date_are_rejected(self):
        text = FIXTURE.read_text(encoding="utf-8-sig")
        with self.assertRaisesRegex(SyncError, "主店"):
            assemble_payload(text.replace("示例店铺A主店", "另一店铺主店"), self.request(), FIXTURE, {})
        with self.assertRaisesRegex(SyncError, "最新日期"):
            assemble_payload(text, self.request(end="2026-10-28"), FIXTURE, {})

    def test_source_consistency_differences_are_warnings_only(self):
        text = FIXTURE.read_text(encoding="utf-8-sig")
        payload, _ = assemble_payload(text, self.request(["2026-10-05", "2026-10-10"]), FIXTURE, {})
        self.assertEqual(len(payload["days"]), 2)
        self.assertEqual({item["date"] for item in payload["sourceConsistency"]}, {"2026-10-05", "2026-10-10"})
        self.assertEqual({item["difference"] for item in payload["sourceConsistency"]}, {5.78, 6.10})


if __name__ == "__main__":
    unittest.main()
