from __future__ import annotations

import argparse
import contextlib
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import daily_sync as sync
import store_registry as registry


STORES = [{"storeId": "neutcut", "storeName": "示例店铺A", "pageStoreName": "示例店铺A", "targetAccount": "示例账号:店铺", "confirmed": True},
          {"storeId": "amgx", "storeName": "示例店铺C", "pageStoreName": "示例店铺C", "targetAccount": "示例账号二:店铺", "confirmed": True},
          {"storeId": "example3", "storeName": "测试店三", "pageStoreName": "测试店三", "targetAccount": "示例账号三:店铺", "confirmed": True}]


class MultiStoreTests(unittest.TestCase):
    def args(self, root, **changes):
        args = argparse.Namespace(mode="plan", store=None, all_stores=True, start_date=None, end_date=None, days=30,
                                  input=None, apply=False, bridge=root / "bridge", collector=root / "collector", output_root=root / "runs")
        for key, value in changes.items():
            setattr(args, key, value)
        return args

    def test_account_and_exact_shop_line_must_match_same_binding(self):
        with patch.object(registry, "configured_stores", return_value=STORES):
            store = registry.validated_request_store({"storeId": "amgx", "targetAccount": "示例账号二:店铺"})
            registry.verify_page_store("生意参谋\n示例店铺C主店\n指标名称", store)
            for bad in ({"storeId": "amgx", "targetAccount": "示例账号:店铺"},
                        {"storeId": "amgx", "targetAccount": "示例账号二:店铺", "storeName": "示例店铺A"}):
                with self.assertRaises(registry.StoreConfigurationError):
                    registry.validated_request_store(bad)
            with self.assertRaises(registry.StoreConfigurationError):
                registry.verify_page_store("不是示例店铺C主店", store)

    def test_unconfirmed_binding_stops_before_any_store_runs(self):
        unconfirmed = [STORES[0], dict(STORES[1], confirmed=False)]
        with tempfile.TemporaryDirectory() as folder, patch.object(sync, "configured_stores", return_value=unconfirmed), patch.object(registry, "configured_stores", return_value=unconfirmed), patch.object(sync, "synchronize") as run:
            with self.assertRaises(registry.StoreConfigurationError):
                sync.synchronize_all(self.args(Path(folder)))
            run.assert_not_called()

    def test_each_store_is_planned_in_order_and_noop_without_capture(self):
        order = []
        def bridge(_bridge, args, _directory, _label):
            store_id = args[args.index("--store") + 1]
            order.append(store_id)
            return {"ok": True, "storeId": store_id, "startDate": "2026-09-28", "endDate": "2026-10-27", "missingDates": [], "ledgerSha256": "a" * 64}
        with tempfile.TemporaryDirectory() as folder, patch.object(sync, "configured_stores", return_value=STORES), patch.object(registry, "configured_stores", return_value=STORES), patch.object(sync, "bridge_call", side_effect=bridge), patch.object(sync, "execute") as capture, contextlib.redirect_stdout(io.StringIO()):
            result = sync.read_json(sync.synchronize_all(self.args(Path(folder))))
            self.assertEqual(order, [store["storeId"] for store in STORES])
            self.assertEqual(result["executionOrder"], order)
            self.assertEqual([row["店铺"] for row in result["rows"]], [store["storeName"] for store in STORES])
            self.assertTrue(result["sequential"])
            capture.assert_not_called()

    def test_mid_batch_failure_keeps_receipt_and_does_not_start_next_store(self):
        calls = []
        def bridge(_bridge, args, _directory, _label):
            store_id = args[args.index("--store") + 1]
            calls.append(store_id)
            if store_id == "amgx":
                raise sync.SyncError("账户识别失败")
            return {"ok": True, "storeId": store_id, "startDate": "2026-09-28", "endDate": "2026-10-27", "missingDates": []}
        with tempfile.TemporaryDirectory() as folder, patch.object(sync, "configured_stores", return_value=STORES), patch.object(registry, "configured_stores", return_value=STORES), patch.object(sync, "bridge_call", side_effect=bridge), contextlib.redirect_stdout(io.StringIO()):
            root = Path(folder)
            with self.assertRaisesRegex(sync.SyncError, "示例店铺C"):
                sync.synchronize_all(self.args(root))
            self.assertEqual(calls, ["neutcut", "amgx"])
            receipt = sync.read_json(next((root / "runs").glob("*/incomplete.json")))
            self.assertEqual(receipt["failedStore"], "amgx")
            self.assertEqual([store["storeId"] for store in receipt["completedStores"]], ["neutcut"])


if __name__ == "__main__":
    unittest.main()
