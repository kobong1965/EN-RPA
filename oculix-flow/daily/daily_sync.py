"""已配置店铺的日度同步编排；多店在同一桌面依次执行，独立规划缺日。"""
from __future__ import annotations

import argparse
from datetime import date, datetime
import json
from pathlib import Path
import queue
import subprocess
import sys
import threading
import time
import uuid

from process_guard import protect_process_tree, terminate_process_tree
from store_registry import configured_stores, get_store

BASE = Path(__file__).resolve().parent
WORKSPACE = BASE.parents[1]
DEFAULT_BRIDGE = WORKSPACE / "oculix-flow" / "bridge" / "import_daily.py"
DEFAULT_COLLECTOR = BASE / "collector.py"
STORE_ID = "neutcut"
TARGET_ACCOUNT = "示例账号:店铺"


class SyncError(RuntimeError):
    pass


def emit(kind: str, message: str) -> None:
    print(kind + " " + message.replace("\r", " ").replace("\n", " "), flush=True)


def write_json(path: Path, value) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    pending = path.with_suffix(path.suffix + ".tmp")
    pending.write_text(json.dumps(value, ensure_ascii=False, allow_nan=False, indent=2) + "\n", encoding="utf-8")
    pending.replace(path)


def read_json(path: Path):
    try:
        return json.loads(path.read_text(encoding="utf-8-sig"))
    except (OSError, ValueError) as exc:
        raise SyncError("无法读取有效的结果文件：" + str(path)) from exc


def execute(command: list[str], log_path: Path, label: str) -> tuple[int, str]:
    """Keep the executor responsive, forward progress, and keep this run's logs."""
    lines: list[str] = []
    events: queue.Queue = queue.Queue()
    process = subprocess.Popen(command, cwd=str(BASE), stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, text=True, encoding="utf-8",
                               errors="replace", creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    def reader():
        try:
            for line in process.stdout:
                events.put(line)
        finally:
            events.put(None)
    threading.Thread(target=reader, daemon=True).start()
    heartbeat = time.monotonic()
    try:
        with log_path.open("w", encoding="utf-8") as log:
            while True:
                try:
                    line = events.get(timeout=2)
                except queue.Empty:
                    if time.monotonic() - heartbeat >= 10:
                        emit("OCULIX_PROGRESS", label + "仍在进行，请保持任务窗口可见")
                        heartbeat = time.monotonic()
                    continue
                if line is None:
                    break
                log.write(line)
                log.flush()
                lines.append(line)
                if line.startswith(("OCULIX_PROGRESS ", "OCULIX_ERROR ", "CAPTURED ")):
                    print(line.rstrip(), flush=True)
            return process.wait(), "".join(lines)
    finally:
        if process.poll() is None:
            terminate_process_tree(process)
        if process.stdout:
            process.stdout.close()


def bridge_call(bridge: Path, arguments: list[str], run_dir: Path, label: str) -> dict:
    if not bridge.is_file():
        raise SyncError("未找到店脉同步桥接程序：" + str(bridge))
    code, output = execute([sys.executable, str(bridge)] + arguments,
                           run_dir / (arguments[0] + "-bridge.log"), label)
    try:
        payload = json.loads(output)
    except ValueError as exc:
        raise SyncError(label + "没有返回有效的 JSON，请查看本次桥接日志") from exc
    if code != 0 or not isinstance(payload, dict) or payload.get("ok") is not True:
        error = payload.get("error", payload) if isinstance(payload, dict) else payload
        if isinstance(error, dict):
            error = error.get("message") or error.get("code") or json.dumps(error, ensure_ascii=False)
        raise SyncError(label + "失败：" + str(error))
    return payload


def validate_plan(plan: dict, store_id: str = STORE_ID) -> list[str]:
    if plan.get("storeId") != store_id:
        raise SyncError("登记计划中的店铺与指定店铺不一致，已停止")
    try:
        start, end = date.fromisoformat(plan["startDate"]), date.fromisoformat(plan["endDate"])
        missing = plan["missingDates"]
        if not isinstance(missing, list) or any(not isinstance(item, str) for item in missing):
            raise ValueError("invalid dates")
        dates = [date.fromisoformat(item) for item in missing]
        if start > end or len(set(dates)) != len(dates) or any(day < start or day > end for day in dates):
            raise ValueError("date coverage")
    except (KeyError, TypeError, ValueError) as exc:
        raise SyncError("登记计划的日期范围或缺日列表无效") from exc
    return sorted(missing)


def validate_collection(payload: dict, missing: list[str], store_id: str = STORE_ID) -> None:
    if payload.get("storeId") != store_id or payload.get("timezone") != "Asia/Shanghai":
        raise SyncError("采集结果的店铺或时区不正确，未写入账本")
    days = payload.get("days")
    if not isinstance(days, list) or not all(isinstance(row, dict) for row in days):
        raise SyncError("采集结果缺少 days 日度记录数组")
    captured = [row.get("date") for row in days]
    if any(not isinstance(day, str) for day in captured) or len(set(captured)) != len(captured):
        raise SyncError("采集结果包含无效或重复日期，未写入账本")
    if set(captured) != set(missing):
        absent = sorted(set(missing) - set(captured))
        unexpected = sorted(set(captured) - set(missing))
        raise SyncError("采集日期与计划不一致；缺失：" + ",".join(absent) + "；多出：" + ",".join(unexpected))


def result_rows(payload: dict, imported: bool, store_name: str = "示例店铺A") -> list[dict]:
    return [{"日期": row["date"], "店铺": store_name, "支付金额": row["paymentAmount"],
             "全站推广花费": row["promotionSpend"], "支付件数": row["paidItemCount"],
             "退款金额（支付时间）": row["refundAmountByPaymentDate"], "订单退款率": row["orderRefundRate"],
             "登记状态": "已写入店脉" if imported else "校验通过，尚未写入"}
            for row in sorted(payload["days"], key=lambda item: item["date"])]


def ledger_window(action: str, run_dir: Path) -> bool:
    from collector import ensure_compiled, java_command
    ensure_compiled(run_dir / "compile-ledger.log")
    code, output = execute(java_command("LedgerWindow", [action]), run_dir / ("ledger-" + action + ".log"),
                           "正常退出店脉" if action == "close" else "重新打开店脉")
    if code:
        raise SyncError("店脉未能正常退出，账本没有写入；请查看 ledger-close.log" if action == "close"
                        else "数据已处理，但店脉未能重新打开，可使用桌面快捷方式打开")
    return "LEDGER_WAS_RUNNING true" in output


def synchronize(args, store: dict | None = None) -> Path:
    store = store or get_store(getattr(args, "store", None) or STORE_ID)
    store_id, store_name = store["storeId"], store["storeName"]
    run_dir = args.output_root.resolve() / (datetime.now().strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:8])
    run_dir.mkdir(parents=True, exist_ok=False)
    emit("OCULIX_RUN_DIR", str(run_dir))
    emit("OCULIX_PROGRESS", "正在检查 " + store_name + " 已登记日期，仅规划需要补齐的日期")
    bridge_args = ["plan", "--store", store_id, "--days", str(args.days)]
    if args.start_date:
        bridge_args += ["--start-date", args.start_date]
    if args.end_date:
        bridge_args += ["--end-date", args.end_date]
    plan = bridge_call(args.bridge.resolve(), bridge_args, run_dir, "读取登记计划")
    write_json(run_dir / "plan.json", plan)
    missing = validate_plan(plan, store_id)
    result_path = run_dir / "result.json"
    if not missing:
        write_json(result_path, {"rows": [{"店铺": store_name, "结果": "截至目标日期已登记齐全，无需重复采集", "新增天数": 0}],
                                "storeId": store_id, "plan": plan, "imported": False})
        return result_path
    emit("OCULIX_PROGRESS", "需要补齐 " + str(len(missing)) + " 天：" + missing[0] + " 至 " + missing[-1])
    if args.mode == "plan":
        write_json(result_path, {"rows": [{"日期": day, "店铺": store_name, "登记状态": "待采集"} for day in missing],
                                "storeId": store_id, "plan": plan, "imported": False})
        return result_path
    collected = run_dir / "collected.json"
    if args.input:
        payload = read_json(args.input.resolve())
        write_json(collected, payload)
    else:
        collector = args.collector.resolve()
        if not collector.is_file():
            raise SyncError("采集器尚未安装，已保存待补日期；不会把未采集数据写入店脉")
        evidence_dir = run_dir / "evidence"
        evidence_dir.mkdir()
        request = {"schemaVersion": 1, "storeId": store_id, "storeName": store_name,
                   "pageStoreName": store["pageStoreName"], "targetAccount": store["targetAccount"],
                   "timezone": "Asia/Shanghai", "missingDates": missing,
                   "startDate": plan["startDate"], "endDate": plan["endDate"], "evidenceDir": str(evidence_dir),
                   "outputPath": str(collected), "ledgerSha256": plan.get("ledgerSha256")}
        request_path = run_dir / "request.json"
        write_json(request_path, request)
        emit("OCULIX_PROGRESS", "正在采集指定店铺的缺失日期，使用现有千牛登录会话")
        code, _ = execute([sys.executable, str(collector), "--request", str(request_path), "--output", str(collected)],
                          run_dir / "collector.log", "采集 " + store_name + " 数据")
        if code != 0:
            raise SyncError("页面数据采集未完成，请查看本次截图和 collector.log")
        payload = read_json(collected)
    if not isinstance(payload, dict):
        raise SyncError("采集结果必须是 JSON 对象")
    validate_collection(payload, missing, store_id)
    if payload.get("expectedLedgerSha256") not in (None, plan.get("ledgerSha256")):
        raise SyncError("采集结果绑定的账本基线与本次计划不同，请重新规划")
    payload["expectedLedgerSha256"] = plan.get("ledgerSha256")
    write_json(collected, payload)
    emit("OCULIX_PROGRESS", "正在校验日期、指标单位、截图或原文证据以及账本冲突")
    checked = bridge_call(args.bridge.resolve(), ["dry-run", "--store", store_id, "--input", str(collected)], run_dir, "校验采集数据")
    write_json(run_dir / "validation.json", checked)
    imported = False
    receipt = checked
    reopen_warning = None
    if args.apply:
        emit("OCULIX_PROGRESS", "正在正常退出店脉，避免读取中的账本被覆盖")
        was_running = ledger_window("close", run_dir)
        try:
            emit("OCULIX_PROGRESS", "正在把核对通过的缺失日期写入店脉")
            receipt = bridge_call(args.bridge.resolve(), ["apply", "--store", store_id, "--input", str(collected)], run_dir, "写入店脉账本")
            write_json(run_dir / "import-receipt.json", receipt)
            imported = True
        finally:
            if was_running:
                try:
                    ledger_window("reopen", run_dir)
                except (SyncError, OSError) as exc:
                    reopen_warning = str(exc)
                    emit("OCULIX_PROGRESS", reopen_warning)
    write_json(result_path, {"rows": result_rows(payload, imported, store_name), "storeId": store_id, "plan": plan,
                            "imported": imported, "receipt": receipt, "evidencePayload": str(collected),
                            "sourceConsistency": payload.get("sourceConsistency", []), "reopenWarning": reopen_warning})
    return result_path


def synchronize_all(args) -> Path:
    if args.input:
        raise SyncError("多店顺序同步不接受单店 --input，请明确指定 --store")
    # Validate every binding before the first store can cause any desktop action.
    stores = [get_store(item["storeId"]) for item in configured_stores()]
    batch_dir = args.output_root.resolve() / (datetime.now().strftime("%Y%m%d-%H%M%S") + "-all-" + uuid.uuid4().hex[:8])
    batch_dir.mkdir(parents=True, exist_ok=False)
    completed, rows = [], []
    try:
        for index, store in enumerate(stores, 1):
            emit("OCULIX_PROGRESS", "依次同步店铺 " + str(index) + "/" + str(len(stores)) + "：" + store["storeName"])
            child_args = argparse.Namespace(**vars(args))
            child_args.output_root = batch_dir / store["storeId"]
            child_args.store = store["storeId"]
            result_path = synchronize(child_args, store)
            result = read_json(result_path)
            completed.append({"storeId": store["storeId"], "storeName": store["storeName"], "resultPath": str(result_path),
                              "imported": result.get("imported", False)})
            rows.extend(result["rows"])
    except (SyncError, OSError, ValueError) as exc:
        write_json(batch_dir / "incomplete.json", {"completedStores": completed, "failedStore": store["storeId"], "error": str(exc)})
        raise SyncError("多店同步在 " + store["storeName"] + " 停止；已完成店铺记录保留于 " + str(batch_dir) + "。" + str(exc)) from exc
    result_path = batch_dir / "result.json"
    write_json(result_path, {"rows": rows, "stores": completed, "executionOrder": [store["storeId"] for store in stores], "sequential": True})
    emit("OCULIX_RUN_DIR", str(batch_dir))
    return result_path


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("--mode", choices=["plan", "run"], default="plan")
    stores = result.add_mutually_exclusive_group()
    stores.add_argument("--store", help="stores.json 中已确认的店铺ID；默认兼容 示例店铺A")
    stores.add_argument("--all-stores", action="store_true", help="按配置顺序逐店规划和执行，同一桌面不并发")
    result.add_argument("--start-date")
    result.add_argument("--end-date")
    result.add_argument("--days", type=int, default=30)
    result.add_argument("--input", type=Path, help="使用已有的真实采集结果，跳过点击采集")
    result.add_argument("--apply", action="store_true", help="校验通过后写入店脉，否则仅校验")
    result.add_argument("--bridge", type=Path, default=DEFAULT_BRIDGE)
    result.add_argument("--collector", type=Path, default=DEFAULT_COLLECTOR)
    result.add_argument("--output-root", type=Path, default=BASE / "runs")
    result.add_argument("--owner-pid", type=int, help=argparse.SUPPRESS)
    return result


def main() -> int:
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")
    try:
        args = parser().parse_args()
        if not 1 <= args.days <= 366 or (args.apply and args.mode != "run"):
            raise SyncError("日期窗口须为 1 至 366 天；仅运行模式允许写入")
        protect_process_tree(args.owner_pid)
        path = synchronize_all(args) if args.all_stores else synchronize(args)
        emit("OCULIX_PROGRESS", "本次处理完成，正在返回结果")
        emit("OCULIX_RESULT", str(path.resolve()))
        return 0
    except (SyncError, OSError, ValueError) as exc:
        emit("OCULIX_ERROR", str(exc))
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
