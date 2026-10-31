"""Strict, offline parser for copied 生意参谋 / 全部指标 / 表格 text.

Supported layouts:
1. The observed DOM innerText: 指标名称, N lines YYYY-MM-DD较上一周期,
   then each metric label followed by exactly N combined value/change lines.
   A second 指标名称 starts the duplicated fixed-column labels, not more data.
2. TSV: one metric per row, with either date columns containing combined cells,
   or explicit adjacent 较上一周期 columns, or date-only value columns.

Combined cells require a signed change, the literal zero change 0.00%, or a
missing-change dash. Thus 140.00% means value 14 + zero change ONLY when its
column explicitly declares 较上一周期. Positive nonzero unsigned changes are
rejected. No OCR correction, date guessing, amount scaling, or ledger writes.
Known money metrics are the table's yuan amounts. 万/千/亿 and currency labels
not in the verified yuan schema are rejected. 平均停留时长 has no observed unit:
it remains in rawMetrics, and is NOT assigned to a typed ledger field.

CLI: python table_parser.py INPUT --output OUTPUT --start 2026-09-28 --end 2026-10-27
     [--store-id ID] [--store-name EXACT_HEADER]
The default store ID stays neutcut for existing callers. Store labels are only
reported when a store_name is explicitly supplied, using complete-line equality.
The collector must independently confirm shop identity and attach evidence.
"""
from __future__ import annotations

import argparse
import csv
from datetime import date, timedelta
from decimal import Decimal, InvalidOperation
import io
import json
import re
from pathlib import Path
import sys
import unicodedata


class TableParseError(ValueError):
    """Copied table is incomplete, ambiguous, or does not match its contract."""


# field, type, source unit. Percent fields are returned as fractions, not % points.
METRICS = {
    "支付金额": ("paymentAmount", "decimal", "元"),
    "净支付金额": ("netPaymentAmount", "decimal", "元"),
    "退款金额(完结时间)": ("refundAmountByCompletionDate", "decimal", "元"),
    "退款金额(支付时间)": ("refundAmountByPaymentDate", "decimal", "元"),
    "金额退款率": ("amountRefundRate", "rate", "比例"),
    "支付件数": ("paidItemCount", "integer", "件"),
    "客单价": ("averageOrderValue", "decimal", "元"),
    "全站推广花费": ("promotionSpend", "decimal", "元"),
    "支付转化率": ("paymentConversionRate", "rate", "比例"),
    "支付子订单数": ("paidSubOrderCount", "integer", "笔"),
    "访客数": ("visitors", "integer", "人"),
    "加购件数": ("addToCartItems", "integer", "件"),
    "加购人数": ("addToCartBuyers", "integer", "人"),
    "订单退款率": ("orderRefundRate", "rate", "比例"),
    "浏览量": ("pageViews", "integer", "次"),
    "商品收藏人数": ("productCollectors", "integer", "人"),
    "咨询率": ("consultationRate", "rate", "比例"),
    "平均停留时长": (None, "decimal", None),
    "老客复购金额": ("repeatCustomerAmount", "decimal", "元"),
    "老客复购人数": ("repeatCustomerCount", "integer", "人"),
    "老客复购率": ("repeatCustomerRate", "rate", "比例"),
    "支付买家数": ("paidBuyers", "integer", "人"),
    "旺旺人工响应时长(秒)": ("wangwangResponseSeconds", "decimal", "秒"),
    "纠纷投诉商责率": ("disputeLiabilityRate", "rate", "比例"),
    "物流到货时长(小时)": ("logisticsArrivalHours", "decimal", "小时"),
    "48小时揽收及时率": ("collectionWithin48hRate", "rate", "比例"),
    "退款处理时长(天)": ("refundProcessingDays", "decimal", "天"),
    "签收退款率": ("signedReturnRate", "rate", "比例"),
    "关键词推广花费": ("keywordPromotionSpend", "decimal", "元"),
    "智能场景花费": ("smartSceneSpend", "decimal", "元"),
    "淘宝客佣金": ("affiliateCommission", "decimal", "元"),
    "精准人群推广花费": ("audiencePromotionSpend", "decimal", "元"),
}
REQUIRED_FIELDS = frozenset({"paymentAmount", "promotionSpend", "paidItemCount", "refundAmountByPaymentDate", "orderRefundRate"})
# The page may show "--" for promotion spend or refund rate; retain None, not 0.
NON_NULL_FIELDS = frozenset({"paymentAmount", "paidItemCount"})
MISSING = {"-", "—", "–", "--", "——"}
NUMBER = r"(?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d+)?"
BASE = rf"(?:{NUMBER}%?|[-—–])"
SIGNED_CHANGE = re.compile(rf"^({BASE})\s*([+-]{NUMBER}%)$")
DATE_HEADER = re.compile(r"^(\d{4}-\d{2}-\d{2})(较上一周期)?$")


def _normalized(value: str) -> str:
    return unicodedata.normalize("NFKC", str(value)).strip().replace("\ufeff", "")


def _label(value: str) -> str:
    return re.sub(r"\s+", "", _normalized(value))


def _valid_date(value: str) -> str:
    try:
        if date.fromisoformat(value).isoformat() != value:
            raise ValueError()
    except ValueError as exc:
        raise TableParseError(f"日期无效：{value}") from exc
    return value


def date_range(start: str, end: str) -> list[str]:
    first, last = date.fromisoformat(_valid_date(start)), date.fromisoformat(_valid_date(end))
    if first > last:
        raise TableParseError("开始日期晚于结束日期")
    return [(first + timedelta(days=n)).isoformat() for n in range((last - first).days + 1)]


def _numeric(raw: str, kind: str, *, change: bool = False):
    raw = _normalized(raw)
    if raw in MISSING:
        return None
    if not raw:
        raise TableParseError("数值单元格为空；不能把丢失的单元格当作零或缺失指标")
    expression = (r"[+-]?" if change else "") + NUMBER + ("%" if kind == "rate" else "")
    if re.fullmatch(expression, raw) is None:
        raise TableParseError(f"不符合{kind}数值格式（不猜测单位或分隔符）：{raw!r}")
    try:
        number = Decimal(raw.rstrip("%").replace(",", ""))
    except InvalidOperation as exc:
        raise TableParseError(f"数字无效：{raw}") from exc
    if not number.is_finite() or (not change and number < 0):
        raise TableParseError(f"数值越界：{raw}")
    if kind == "rate":
        number /= 100
        if not change and not 0 <= number <= 1:
            raise TableParseError(f"比例不在0%至100%：{raw}")
    if kind == "integer":
        if "." in raw or number != number.to_integral_value():
            raise TableParseError(f"计数必须为整数：{raw}")
        return int(number)
    return float(number)


def split_combined(raw: str) -> tuple[str, str]:
    """Split an explicitly declared value/previous-period combined cell."""
    value = _normalized(raw)
    match = SIGNED_CHANGE.fullmatch(value)
    if match:
        return match.group(1), match.group(2)
    # The captured UI omits the sign only for exactly zero percentage change.
    if value.endswith("0.00%"):
        base = value[:-5].strip()
        if re.fullmatch(BASE, base):
            return base, "0.00%"
    if value and value[-1] in "-—–":
        base = value[:-1].strip()
        if re.fullmatch(BASE, base):
            return base, value[-1]
    raise TableParseError(f"无法唯一分离实际值和较上一周期：{raw!r}")


def _check_dates(dates: list[str], expected_dates=None) -> None:
    if not dates or len(dates) != len(set(dates)):
        raise TableParseError("日期列为空或重复")
    for value in dates:
        _valid_date(value)
    if expected_dates is not None:
        expected = [_valid_date(str(value)) for value in expected_dates]
        if len(expected) != len(set(expected)):
            raise TableParseError("预期日期重复")
        if set(expected) != set(dates):
            raise TableParseError(f"日期与预期不符；缺少={sorted(set(expected)-set(dates))}；多出={sorted(set(dates)-set(expected))}")


def _store_context(store_id, store_name):
    if not isinstance(store_id, str) or not store_id.strip():
        raise TableParseError("店铺标识必须是非空字符串")
    if store_name is not None and (not isinstance(store_name, str) or not _normalized(store_name)):
        raise TableParseError("明确提供的店铺名称必须是非空字符串")
    return store_id.strip(), _normalized(store_name) if store_name is not None else None


def _observed_store_labels(lines, store_name):
    # Metadata only: matching text is not proof of current shop identity.
    if store_name is None:
        return []
    return [line for line in lines if _normalized(line) == store_name]


def _assemble(dates, metric_rows, *, expected_dates=None, required_fields=REQUIRED_FIELDS, store_id="neutcut"):
    _check_dates(dates, expected_dates)
    days = {value: {"date": value} for value in dates}
    raw_metrics, warnings, fields_seen = {}, [], set()
    for source_label, cells in metric_rows:
        label = _label(source_label)
        if not label or label in raw_metrics:
            raise TableParseError(f"指标标签为空或重复：{source_label!r}")
        if len(cells) != len(dates):
            raise TableParseError(f"{source_label} 的值数量不等于日期数")
        contract = METRICS.get(label)
        field, kind, unit = contract if contract else (None, None, None)
        if field and field in fields_seen:
            raise TableParseError(f"多个指标对应同一字段：{field}")
        if field:
            fields_seen.add(field)
        if not contract:
            warnings.append(f"未知指标 {source_label} 仅保留原文，未写入账本字段")
        elif not unit:
            warnings.append(f"{source_label} 的单位未在已观察表头中标明，仅保留原文")
        values = {}
        for day, (raw_cell, raw_value, raw_change) in zip(dates, cells):
            cell_kind = kind or ("rate" if raw_value.endswith("%") else "decimal")
            parsed = _numeric(raw_value, cell_kind)
            delta = None if raw_change is None else _numeric(raw_change, "rate", change=True)
            values[day] = {"rawCell": raw_cell, "rawValue": raw_value, "rawChange": raw_change,
                           "value": parsed, "change": delta}
            if field:
                days[day][field] = parsed
        raw_metrics[label] = {"sourceLabel": source_label, "field": field, "unit": unit, "values": values}
    missing = set(required_fields) - fields_seen
    if missing:
        raise TableParseError(f"缺少必要指标：{', '.join(sorted(missing))}")
    for day, row in days.items():
        nulls = [field for field in NON_NULL_FIELDS & set(required_fields) if row.get(field) is None]
        if nulls:
            raise TableParseError(f"{day} 必要指标缺值：{', '.join(sorted(nulls))}")
    ordered = [days[day] for day in sorted(days)]
    return {"storeId": store_id, "days": ordered, "rows": ordered, "dates": sorted(dates),
            "sourceDateOrder": dates, "metricCount": len(raw_metrics), "rawMetrics": raw_metrics,
            "warnings": warnings, "shopIdentityVerified": False, "evidenceRequired": True}


def parse_table_matrix(matrix: list[list[str]], *, expected_dates=None, required_fields=REQUIRED_FIELDS,
                       store_id="neutcut", store_name=None):
    """Parse explicit row/cell boundaries, e.g. TSV, without guessing columns."""
    store_id, store_name = _store_context(store_id, store_name)
    matrix = [[_normalized(cell) for cell in row] for row in matrix]
    starts = [i for i, row in enumerate(matrix) if row and _label(row[0]) == "指标名称" and len(row) > 1]
    if len(starts) != 1:
        raise TableParseError("必须有唯一的指标名称日期表头")
    start, header = starts[0], matrix[starts[0]]
    dates, columns = [], []
    index = 1
    while index < len(header):
        match = DATE_HEADER.fullmatch(_label(header[index]))
        if not match:
            raise TableParseError(f"无法识别日期表头：{header[index]!r}")
        dates.append(match.group(1))
        separate = index + 1 < len(header) and _label(header[index + 1]) == "较上一周期"
        columns.append((index, index + 1 if separate else None, bool(match.group(2))))
        index += 2 if separate else 1
    metric_rows = []
    for row in matrix[start + 1:]:
        if not row or all(not cell for cell in row):
            continue
        if len(row) != len(header):
            raise TableParseError(f"行宽与日期表头不符：{row[0]!r}")
        cells = []
        for actual, comparison, combined in columns:
            raw = row[actual]
            if comparison is not None:
                raw_value, raw_change = raw, row[comparison]
            elif combined:
                raw_value, raw_change = split_combined(raw)
            else:
                raw_value, raw_change = raw, None
            cells.append((raw, raw_value, raw_change))
        metric_rows.append((row[0], cells))
    result = _assemble(dates, metric_rows, expected_dates=expected_dates, required_fields=required_fields,
                       store_id=store_id)
    result["layout"] = "explicit-cell-matrix"
    result["observedStoreLabels"] = _observed_store_labels(
        [cell for row in matrix[:start] for cell in row], store_name)
    return result


def parse_table_text(text: str, *, expected_dates=None, required_fields=REQUIRED_FIELDS,
                     store_id="neutcut", store_name=None):
    """Parse the observed copied page text; leaves shop/evidence checks to collector."""
    store_id, store_name = _store_context(store_id, store_name)
    if not isinstance(text, str) or not text.strip():
        raise TableParseError("表格文本为空")
    if "\t" in text:
        try:
            matrix = list(csv.reader(io.StringIO(text), delimiter="\t", strict=True))
        except csv.Error as exc:
            raise TableParseError(f"TSV格式错误：{exc}") from exc
        return parse_table_matrix(matrix, expected_dates=expected_dates, required_fields=required_fields,
                                  store_id=store_id, store_name=store_name)
    lines = [_normalized(line) for line in text.splitlines()]
    try:
        start = next(i for i, line in enumerate(lines) if line == "指标名称")
    except StopIteration as exc:
        raise TableParseError("没有找到指标名称表头") from exc
    cursor, dates = start + 1, []
    while cursor < len(lines):
        match = DATE_HEADER.fullmatch(_label(lines[cursor]))
        if not match:
            break
        if not match.group(2):
            raise TableParseError("逐行DOM格式必须明确带较上一周期表头")
        dates.append(match.group(1))
        cursor += 1
    _check_dates(dates, expected_dates)
    metric_rows = []
    while cursor < len(lines) and lines[cursor] != "指标名称":
        if not any(lines[cursor:]):
            break
        source_label = lines[cursor]
        if not source_label or re.fullmatch(r"[\d\s,%.+\-—–]+", source_label):
            raise TableParseError(f"预期指标标题，实际为：{source_label!r}")
        cursor += 1
        values = lines[cursor:cursor + len(dates)]
        if len(values) != len(dates):
            raise TableParseError(f"{source_label} 的值数量不足")
        cells = []
        for day, raw in zip(dates, values):
            try:
                raw_value, raw_change = split_combined(raw)
            except TableParseError as exc:
                raise TableParseError(f"{source_label} / {day}：{exc}") from exc
            cells.append((raw, raw_value, raw_change))
        metric_rows.append((source_label, cells))
        cursor += len(dates)
    if cursor < len(lines) and lines[cursor] == "指标名称":
        # The actual page duplicates fixed labels after the complete table.
        duplicate_labels = lines[cursor + 1:cursor + 1 + len(metric_rows)]
        if [_label(v) for v in duplicate_labels] != [_label(v[0]) for v in metric_rows]:
            raise TableParseError("固定列重复标签与表格指标顺序不一致，可能复制不完整")
    result = _assemble(dates, metric_rows, expected_dates=expected_dates, required_fields=required_fields,
                       store_id=store_id)
    result["layout"] = "observed-dom-innertext"
    result["observedStoreLabels"] = _observed_store_labels(lines[:start], store_name)
    return result


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("input", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--start")
    parser.add_argument("--end")
    parser.add_argument("--store-id", default="neutcut")
    parser.add_argument("--store-name")
    args = parser.parse_args(argv)
    try:
        if bool(args.start) != bool(args.end):
            raise TableParseError("--start 和 --end 必须同时提供")
        expected = date_range(args.start, args.end) if args.start else None
        payload = parse_table_text(args.input.read_text(encoding="utf-8-sig"), expected_dates=expected,
                                   store_id=args.store_id, store_name=args.store_name)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(payload, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")
        print(json.dumps({"ok": True, "days": len(payload["days"]), "metrics": payload["metricCount"],
                          "output": str(args.output.resolve()), "warnings": payload["warnings"]}, ensure_ascii=False))
        return 0
    except (TableParseError, OSError, UnicodeError) as exc:
        print(json.dumps({"ok": False, "error": str(exc)}, ensure_ascii=False), file=sys.stderr)
        return 2


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    raise SystemExit(main())
