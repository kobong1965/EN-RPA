"""采集已登录千牛的日度表格，按本次缺日过滤并绑定原始证据。"""
from __future__ import annotations

import argparse
from datetime import date
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

from daily_sync import SyncError, emit, execute, read_json, write_json
from table_parser import parse_table_text
from store_registry import validated_request_store, verify_page_store
from process_guard import protect_process_tree

BASE = Path(__file__).resolve().parent
FLOW = BASE.parent
WORKSPACE = FLOW.parent
JAVA = WORKSPACE / "runtime/java/jdk-21.0.12.1+1/bin/java.exe"
JAVAC = JAVA.with_name("javac.exe")
API = WORKSPACE / "runtime/oculixapi-4.0.0-windows.jar"
BUILD = BASE / "build"


def ensure_compiled(log_path: Path) -> None:
    sources = [FLOW / "SycmFlow.java", BASE / "DailyCapture.java", BASE / "LedgerWindow.java"]
    for required in [JAVA, JAVAC, API] + sources:
        if not required.is_file():
            raise SyncError("缺少经过验证的采集组件：" + str(required))
    BUILD.mkdir(exist_ok=True)
    classes = [BUILD / (source.stem + ".class") for source in sources]
    if all(item.exists() for item in classes) and min(item.stat().st_mtime for item in classes) >= max(source.stat().st_mtime for source in sources):
        return
    command = [str(JAVAC), "-J-Dfile.encoding=UTF-8", "-encoding", "UTF-8", "-cp", str(API), "-d", str(BUILD)] + [str(path) for path in sources]
    code, _ = execute(command, log_path, "编译本地采集组件")
    if code:
        raise SyncError("本地采集组件编译失败，请查看本次 compile.log")


def java_command(main_class: str, arguments: list[str]) -> list[str]:
    return [str(JAVA), "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
            "-Duser.dir=" + str(FLOW), "-cp", str(BUILD) + ";" + str(API), main_class] + arguments


def assemble_payload(text: str, request: dict, text_path: Path, capture: dict) -> tuple[dict, dict]:
    try:
        store = validated_request_store(request)
        verify_page_store(text, store)
    except ValueError as exc:
        raise SyncError(str(exc)) from exc
    parsed = parse_table_text(text, store_id=store["storeId"], store_name=store["pageStoreName"] + "主店")
    rows = parsed.get("days", [])
    if not rows:
        raise SyncError("页面原文没有可用日度表格")
    available = {row["date"] for row in rows}
    if max(available) != request["endDate"]:
        raise SyncError("页面最新日期为 " + max(available) + "，与目标结束日期 " + request["endDate"] + " 不一致；未自动猜测日期")
    missing = request["missingDates"]
    if len(set(missing)) != len(missing) or not set(missing).issubset(available):
        raise SyncError("当前可见表格未完整包含待补日期：" + ",".join(sorted(set(missing) - available)))
    digest = hashlib.sha256(text_path.read_bytes()).hexdigest()
    collected = []
    consistency = []
    for original in rows:
        if original["date"] not in missing:
            continue
        row = dict(original)
        row["provenance"] = {"storeName": store["storeName"], "pageDate": row["date"], "textPath": str(text_path.resolve()),
                             "textSha256": digest, "targetAccount": request["targetAccount"], "captureMethod": "native-ui-clipboard"}
        screenshot = capture.get("screenshotPath")
        if screenshot and Path(screenshot).is_file():
            row["provenance"]["screenshots"] = [{"path": str(Path(screenshot).resolve()),
                                                    "sha256": hashlib.sha256(Path(screenshot).read_bytes()).hexdigest()}]
            row["provenance"]["screenshotScope"] = "采集时的可见表格窗口；完整日期数据以复制原文为主证据"
        # Record source differences; never adjust website values to satisfy an equation.
        values = [row.get(key) for key in ("paymentAmount", "refundAmountByPaymentDate", "netPaymentAmount")]
        if all(isinstance(value, (int, float)) and not isinstance(value, bool) for value in values):
            difference = round(values[0] - values[1] - values[2], 2)
            if abs(difference) > 0.01:
                consistency.append({"date": row["date"], "difference": difference,
                                    "message": "支付金额减支付时间退款与净支付金额不一致；保留平台各字段原值"})
        collected.append(row)
    return {"storeId": store["storeId"], "timezone": "Asia/Shanghai", "days": collected,
            "expectedLedgerSha256": request.get("ledgerSha256"), "sourceConsistency": consistency}, parsed


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--request", type=Path)
    parser.add_argument("--discover-account", help="只核对完整账号所属店铺，保存原文截图，不生成导入数据")
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--owner-pid", type=int, help=argparse.SUPPRESS)
    args = parser.parse_args()
    try:
        if bool(args.request) == bool(args.discover_account):
            raise SyncError("请指定 --request 或 --discover-account 中的一个")
        if args.discover_account:
            output = args.output.resolve()
            output.parent.mkdir(parents=True, exist_ok=True)
            request = {"schemaVersion": 1, "mode": "discover-account", "targetAccount": args.discover_account,
                       "missingDates": [], "evidenceDir": str(output.parent / "evidence")}
            args.request = output.parent / "discovery-request.json"
            if args.request.exists():
                raise SyncError("核对目录已有请求，请为本次账号核对选择新目录")
            write_json(args.request, request)
        else:
            request = read_json(args.request)
        evidence_dir = Path(request["evidenceDir"]).resolve()
        evidence_dir.mkdir(parents=True, exist_ok=True)
        discovery = request.get("mode") == "discover-account"
        if not discovery and not request.get("missingDates"):
            raise SyncError("采集器收到空缺日列表，应由编排直接返回无需采集")
        if not discovery:
            validated_request_store(request)
        protect_process_tree(args.owner_pid)
        ensure_compiled(evidence_dir / "compile.log")
        code, _ = execute(java_command("DailyCapture", [str(args.request.resolve())]),
                          evidence_dir / "capture.log", "定位并复制生意参谋日度表格")
        if code:
            raise SyncError("千牛页面采集未完成，请查看本次 capture.log 和截图")
        capture = read_json(evidence_dir / "capture.json")
        if capture.get("targetAccount") != request["targetAccount"] or capture.get("discoveryOnly") is not discovery:
            raise SyncError("本次采集证据的账号或模式与请求不同，已停止")
        text_path = Path(capture["textPath"])
        if discovery:
            write_json(args.output.resolve(), {"mode": "discover-account", "importable": False,
                       "targetAccount": request["targetAccount"], "capture": capture,
                       "textSha256": hashlib.sha256(text_path.read_bytes()).hexdigest()})
            emit("OCULIX_PROGRESS", "账号核对原文与截图已保存；须确认店铺映射后才能采集导入")
            return 0
        payload, parsed = assemble_payload(text_path.read_text(encoding="utf-8-sig"), request, text_path, capture)
        write_json(evidence_dir / "parsed-table.json", parsed)
        write_json(evidence_dir / "source-consistency.json", payload["sourceConsistency"])
        write_json(args.output.resolve(), payload)
        emit("OCULIX_PROGRESS", "原文表格已核对店铺与日期，本次保留 " + str(len(payload["days"])) + " 个缺失日期")
        return 0
    except (SyncError, OSError, ValueError, KeyError, TypeError) as exc:
        emit("OCULIX_ERROR", str(exc))
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
