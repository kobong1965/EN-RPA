from __future__ import annotations
import argparse
import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import daily_sync as sync


def plan(dates):
    return {"ok": True, "storeId": "neutcut", "startDate": "2026-09-28", "endDate": "2026-10-27",
            "missingDates": dates, "existingDates": [], "ledgerSha256": "a" * 64}


def data(day="2026-10-27"):
    return {"storeId": "neutcut", "timezone": "Asia/Shanghai", "days": [{"date": day, "paymentAmount": 0,
            "promotionSpend": 0, "paidItemCount": 0, "refundAmountByPaymentDate": None, "orderRefundRate": 0}]}


class DailySyncTests(unittest.TestCase):
    def args(self, root, **changes):
        result = argparse.Namespace(mode="run", start_date=None, end_date=None, days=30, input=None, apply=False,
                                    bridge=root / "unused-bridge.py", collector=root / "must-not-click.py", output_root=root / "runs")
        for key, value in changes.items():
            setattr(result, key, value)
        return result

    def test_plan_requires_correct_store_and_unique_dates(self):
        self.assertEqual(sync.validate_plan(plan(["2026-10-27"])), ["2026-10-27"])
        for changed in [dict(plan([]), storeId="other"), plan(["2026-10-27", "2026-10-27"]), plan(["2026-10-28"])]:
            with self.assertRaises(sync.SyncError):
                sync.validate_plan(changed)

    def test_collection_must_match_only_missing_dates(self):
        sync.validate_collection(data(), ["2026-10-27"])
        for payload in [dict(data(), storeId="other"), data("2026-10-26"), dict(data(), days=data()["days"] * 2)]:
            with self.assertRaises(sync.SyncError):
                sync.validate_collection(payload, ["2026-10-27"])

    def test_up_to_date_never_launches_collector_or_ledger(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(sync, "bridge_call", return_value=plan([])) as bridge, patch.object(sync, "execute") as execute, patch.object(sync, "ledger_window") as window, contextlib.redirect_stdout(io.StringIO()):
            path = sync.synchronize(self.args(Path(temporary), apply=True))
            result = sync.read_json(path)
            self.assertEqual(result["rows"][0]["新增天数"], 0)
            self.assertFalse(result["imported"])
            bridge.assert_called_once()
            execute.assert_not_called()
            window.assert_not_called()

    def test_plan_only_does_not_collect(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(sync, "bridge_call", return_value=plan(["2026-10-27"])), patch.object(sync, "execute") as execute, contextlib.redirect_stdout(io.StringIO()):
            result = sync.read_json(sync.synchronize(self.args(Path(temporary), mode="plan")))
            self.assertEqual(result["rows"][0]["登记状态"], "待采集")
            execute.assert_not_called()

    def test_missing_collector_fails_without_import(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(sync, "bridge_call", return_value=plan(["2026-10-27"])) as bridge, contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaisesRegex(sync.SyncError, "采集器尚未安装"):
                sync.synchronize(self.args(Path(temporary)))
            bridge.assert_called_once()

    def test_dry_run_binds_ledger_and_preserves_null_zero(self):
        with tempfile.TemporaryDirectory() as temporary, contextlib.redirect_stdout(io.StringIO()):
            root = Path(temporary)
            incoming = root / "input.json"
            sync.write_json(incoming, data())
            with patch.object(sync, "bridge_call", side_effect=[plan(["2026-10-27"]), {"ok": True}]) as bridge, patch.object(sync, "ledger_window") as window:
                path = sync.synchronize(self.args(root, input=incoming))
            result = sync.read_json(path)
            self.assertFalse(result["imported"])
            self.assertEqual(result["rows"][0]["支付金额"], 0)
            self.assertIsNone(result["rows"][0]["退款金额（支付时间）"])
            self.assertEqual(sync.read_json(path.parent / "collected.json")["expectedLedgerSha256"], "a" * 64)
            self.assertEqual(bridge.call_count, 2)
            window.assert_not_called()

    def test_apply_closes_and_reopens_only_own_ledger_lifecycle(self):
        with tempfile.TemporaryDirectory() as temporary, contextlib.redirect_stdout(io.StringIO()):
            root = Path(temporary)
            incoming = root / "input.json"
            sync.write_json(incoming, data())
            with patch.object(sync, "bridge_call", side_effect=[plan(["2026-10-27"]), {"ok": True}, {"ok": True, "insertedDates": ["2026-10-27"]}]), patch.object(sync, "ledger_window", return_value=True) as window:
                result = sync.read_json(sync.synchronize(self.args(root, input=incoming, apply=True)))
            self.assertTrue(result["imported"])
            self.assertEqual([call.args[0] for call in window.call_args_list], ["close", "reopen"])

    def test_apply_failure_reopens_but_never_reports_result_success(self):
        with tempfile.TemporaryDirectory() as temporary, contextlib.redirect_stdout(io.StringIO()):
            root = Path(temporary)
            incoming = root / "input.json"
            sync.write_json(incoming, data())
            with patch.object(sync, "bridge_call", side_effect=[plan(["2026-10-27"]), {"ok": True}, sync.SyncError("账本冲突")]), patch.object(sync, "ledger_window", return_value=True) as window:
                with self.assertRaisesRegex(sync.SyncError, "账本冲突"):
                    sync.synchronize(self.args(root, input=incoming, apply=True))
            self.assertEqual([call.args[0] for call in window.call_args_list], ["close", "reopen"])
            self.assertEqual(list((root / "runs").glob("*/result.json")), [])


if __name__ == "__main__":
    unittest.main()
