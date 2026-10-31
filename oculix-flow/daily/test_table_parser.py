"""Offline tests: synthetic corruption cases plus the captured real table when present."""
from copy import deepcopy
from pathlib import Path
import unittest

from table_parser import TableParseError, date_range, parse_table_matrix, parse_table_text, split_combined


def fixture():
    return [
        ["指标名称", "2026-10-27较上一周期", "2026-10-26较上一周期"],
        ["支付金额", "38,680.43-7.56%", "41,842.43+0.34%"],
        ["全站推广花费", "3,685.26-5.05%", "0.00-"],
        ["支付件数", "295-7.23%", "00.00%"],
        ["退款金额（支付时间）", "11,608.75+18.07%", "--"],
        ["订单退款率", "24.82%+7.43%", "0.00%0.00%"],
    ]


def dom_fixture(matrix=None, duplicate=False):
    matrix = matrix or fixture()
    lines = ["生意参谋", "示例店铺A主店", matrix[0][0], *matrix[0][1:]]
    for row in matrix[1:]:
        lines.extend(row)
    if duplicate:
        lines += ["指标名称", *[row[0] for row in matrix[1:]], "页面版权页脚"]
    return "\n".join(lines)


class ParserTests(unittest.TestCase):
    def test_observed_dom_layout(self):
        result = parse_table_text(dom_fixture(duplicate=True), expected_dates=["2026-10-26", "2026-10-27"])
        self.assertEqual(len(result["days"]), 2)
        latest = result["days"][-1]
        self.assertEqual(latest["paymentAmount"], 38680.43)
        self.assertEqual(latest["promotionSpend"], 3685.26)
        self.assertEqual(latest["paidItemCount"], 295)
        self.assertEqual(latest["orderRefundRate"], 0.2482)
        self.assertEqual(result["rawMetrics"]["支付金额"]["values"]["2026-10-27"]["change"], -0.0756)
        self.assertFalse(result["shopIdentityVerified"])

    def test_zero_not_missing(self):
        earliest = parse_table_text(dom_fixture())["days"][0]
        self.assertEqual(earliest["promotionSpend"], 0)
        self.assertEqual(earliest["paidItemCount"], 0)
        self.assertEqual(earliest["orderRefundRate"], 0)
        self.assertIsNone(earliest["refundAmountByPaymentDate"])

    def test_no_order_refund_rate_stays_missing_but_label_required(self):
        matrix = fixture()
        matrix[1][1] = "0.00-100.00%"
        matrix[3][1] = "0-100.00%"
        matrix[-1][1] = "--"
        result = parse_table_matrix(matrix)
        latest = result["days"][-1]
        self.assertEqual(latest["paymentAmount"], 0)
        self.assertEqual(latest["paidItemCount"], 0)
        self.assertIsNone(latest["orderRefundRate"])
        self.assertEqual(result["rawMetrics"]["订单退款率"]["values"]["2026-10-27"]["rawCell"], "--")
        with self.assertRaises(TableParseError):
            parse_table_matrix(matrix[:-1])

    def test_missing_promotion_spend_stays_null_but_label_required(self):
        matrix = fixture()
        matrix[2][1] = "--"
        result = parse_table_matrix(matrix)
        self.assertIsNone(result["days"][-1]["promotionSpend"])
        self.assertEqual(result["days"][0]["promotionSpend"], 0)
        with self.assertRaises(TableParseError):
            parse_table_matrix([row for row in matrix if row[0] != "全站推广花费"])

    def test_default_store_identity_remains_unverified(self):
        result = parse_table_text(dom_fixture())
        self.assertEqual(result["storeId"], "neutcut")
        self.assertEqual(result["observedStoreLabels"], [])
        self.assertFalse(result["shopIdentityVerified"])
        self.assertTrue(result["evidenceRequired"])

    def test_explicit_store_context_changes_metadata_only(self):
        baseline = parse_table_text(dom_fixture())
        for store_id, store_name in [("store-two", "示例二号主店"), ("store-three", "示例三号主店")]:
            text = dom_fixture().replace("示例店铺A主店", store_name)
            with self.subTest(store_id=store_id):
                result = parse_table_text(text, store_id=store_id, store_name=store_name)
                self.assertEqual(result["storeId"], store_id)
                self.assertEqual(result["observedStoreLabels"], [store_name])
                self.assertEqual(result["days"], baseline["days"])
                self.assertEqual(result["rawMetrics"], baseline["rawMetrics"])
                self.assertFalse(result["shopIdentityVerified"])

    def test_store_name_must_match_complete_header(self):
        text = "其他店铺\n" + dom_fixture()
        result = parse_table_text(text, store_id="store-two", store_name="示例店铺A")
        self.assertEqual(result["observedStoreLabels"], [])
        self.assertEqual(result["storeId"], "store-two")
        exact = parse_table_text(text, store_name="示例店铺A主店")
        self.assertEqual(exact["observedStoreLabels"], ["示例店铺A主店"])

    def test_store_context_is_carried_through_tsv(self):
        matrix = [["生意参谋"], ["示例二号主店"], *fixture()]
        text = "\n".join("\t".join(row) for row in matrix)
        result = parse_table_text(text, store_id="store-two", store_name="示例二号主店")
        self.assertEqual(result["storeId"], "store-two")
        self.assertEqual(result["observedStoreLabels"], ["示例二号主店"])
        self.assertFalse(result["shopIdentityVerified"])
        direct = parse_table_matrix(fixture(), store_id="store-three")
        self.assertEqual(direct["storeId"], "store-three")
        self.assertEqual(direct["observedStoreLabels"], [])

    def test_invalid_store_context_rejected(self):
        for kwargs in [{"store_id": ""}, {"store_id": None}, {"store_name": " "}, {"store_name": 3}]:
            with self.subTest(kwargs=kwargs), self.assertRaises(TableParseError):
                parse_table_text(dom_fixture(), **kwargs)

    def test_explicit_zero_change_boundary(self):
        for raw, actual in [("140.00%", "14"), ("5,6300.00%", "5,630"), ("52.290.00%", "52.29"), ("0.04%0.00%", "0.04%")]:
            with self.subTest(raw=raw):
                self.assertEqual(split_combined(raw), (actual, "0.00%"))

    def test_signed_and_missing_changes(self):
        self.assertEqual(split_combined("0.00-"), ("0.00", "-"))
        self.assertEqual(split_combined("--"), ("-", "-"))
        self.assertEqual(split_combined("-+1.00%"), ("-", "+1.00%"))
        self.assertEqual(split_combined("60.74%-4.29%"), ("60.74%", "-4.29%"))

    def test_unknown_positive_boundary_rejected(self):
        for raw in ["14.015.00%", "95.00%", "", "38,680.43", "33.93万-1.00%"]:
            with self.subTest(raw=raw), self.assertRaises(TableParseError):
                split_combined(raw)

    def test_tsv_separate_change_columns(self):
        matrix = [["指标名称", "2026-10-27", "较上一周期", "2026-10-26", "较上一周期"]]
        for row in fixture()[1:]:
            first, second = split_combined(row[1]), split_combined(row[2])
            matrix.append([row[0], *first, *second])
        result = parse_table_text("\n".join("\t".join(row) for row in matrix))
        self.assertEqual(result["days"][-1]["paidItemCount"], 295)

    def test_value_only_date_columns_do_not_strip_zero_suffix(self):
        result = parse_table_matrix([["指标名称", "2026-10-27"], ["支付金额", "140.00"]], required_fields={"paymentAmount"})
        self.assertEqual(result["days"][0]["paymentAmount"], 140)

    def test_dates_rejected(self):
        for replacement in ["2026-10-26较上一周期", "2026-02-30较上一周期"]:
            matrix = fixture()
            matrix[0][1] = replacement
            with self.subTest(replacement=replacement), self.assertRaises(TableParseError):
                parse_table_matrix(matrix)
        with self.assertRaises(TableParseError):
            parse_table_matrix(fixture(), expected_dates=["2026-10-27"])

    def test_missing_and_duplicate_metrics(self):
        matrix = fixture()
        with self.assertRaises(TableParseError):
            parse_table_matrix(matrix[:-1])
        with self.assertRaises(TableParseError):
            parse_table_matrix(matrix + [matrix[1]])

    def test_alignment_and_duplicate_footer_rejected(self):
        lines = dom_fixture().splitlines()
        del lines[6]
        with self.assertRaises(TableParseError):
            parse_table_text("\n".join(lines))
        with self.assertRaises(TableParseError):
            parse_table_text(dom_fixture(duplicate=True).replace("页面版权页脚", "x").replace("指标名称\n支付金额", "指标名称\n错误指标"))

    def test_amount_units_and_number_corruption_rejected(self):
        for raw in ["3.50万+1.00%", "3,68.43+1.00%", "NaN+1.00%", "Infinity+1.00%", "USD3.50+1.00%"]:
            matrix = fixture()
            matrix[1][1] = raw
            with self.subTest(raw=raw), self.assertRaises(TableParseError):
                parse_table_matrix(matrix)

    def test_null_required_values_and_rate_range(self):
        for metric_index in (1, 3):
            matrix = fixture()
            matrix[metric_index][1] = "--"
            with self.subTest(metric=matrix[metric_index][0]), self.assertRaises(TableParseError):
                parse_table_matrix(matrix)
        matrix = fixture()
        matrix[-1][1] = "100.01%+1.00%"
        with self.assertRaises(TableParseError):
            parse_table_matrix(matrix)

    def test_unknown_unit_is_raw_only(self):
        matrix = fixture() + [["平均停留时长", "6.08+3.25%", "5.88-11.52%"]]
        result = parse_table_matrix(matrix)
        self.assertNotIn("averageStayDuration", result["days"][0])
        self.assertEqual(result["rawMetrics"]["平均停留时长"]["values"]["2026-10-27"]["value"], 6.08)
        self.assertTrue(result["warnings"])

    def test_actual_captured_table(self):
        workspace = Path(__file__).resolve().parents[2]
        captured = workspace / "验证记录/示例店铺A每日数据-20261028/生意参谋页面原文.txt"
        if not captured.is_file():
            self.skipTest("Captured local evidence is not distributed with parser")
        result = parse_table_text(captured.read_text(encoding="utf-8-sig"), expected_dates=date_range("2026-09-28", "2026-10-27"))
        self.assertEqual(len(result["days"]), 30)
        self.assertEqual(result["metricCount"], 30)
        self.assertEqual(result["days"][-1]["paymentAmount"], 38680.43)
        self.assertEqual(result["days"][-1]["promotionSpend"], 3685.26)
        self.assertEqual(result["days"][-1]["paidItemCount"], 295)
        self.assertEqual(result["days"][-1]["refundAmountByPaymentDate"], 11608.75)
        self.assertEqual(result["days"][-1]["orderRefundRate"], 0.2482)
        self.assertEqual(result["days"][0]["paymentAmount"], 21466.30)
        self.assertEqual(result["days"][0]["promotionSpend"], 1507.26)
        self.assertEqual(result["days"][0]["audiencePromotionSpend"], 42.93)

    def test_actual_amgx_no_order_days_keep_null_refund_rate(self):
        captured = Path(__file__).resolve().parent / "runs/20261028-232122-53d17c0d/evidence/table.txt"
        if not captured.is_file():
            self.skipTest("Captured local AMGX evidence is not distributed with parser")
        result = parse_table_text(
            captured.read_text(encoding="utf-8-sig"),
            expected_dates=date_range("2026-09-28", "2026-10-27"),
            store_id="amgx",
        )
        self.assertEqual(len(result["days"]), 30)
        latest = result["days"][-1]
        self.assertEqual(latest["date"], "2026-10-27")
        self.assertEqual(latest["paymentAmount"], 0)
        self.assertEqual(latest["paidItemCount"], 0)
        self.assertIsNone(latest["orderRefundRate"])
        self.assertEqual(latest["promotionSpend"], 52.36)
        self.assertEqual(sum(day["paymentAmount"] for day in result["days"]), 6834)
        self.assertTrue(all(day["paidItemCount"] is not None for day in result["days"]))
        self.assertTrue(all(day["promotionSpend"] is None for day in result["days"][:-1]))
        self.assertEqual(sum(day["refundAmountByPaymentDate"] is None for day in result["days"]), 14)
        empty_dates = {"2026-10-27", "2026-10-25", "2026-10-14", "2026-10-11", "2026-10-07", "2026-10-04", "2026-09-30"}
        self.assertEqual({day["date"] for day in result["days"] if day["orderRefundRate"] is None}, empty_dates)
        for day in empty_dates:
            raw = result["rawMetrics"]["订单退款率"]["values"][day]
            self.assertEqual(raw["rawCell"], "--")
            self.assertIsNone(raw["value"])


if __name__ == "__main__":
    unittest.main()
