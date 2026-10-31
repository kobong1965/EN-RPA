"""本机每日调度器：每天北京时间 06:00 启动 daily_workflow。

常驻模式由执手的 Windows 登录任务启动；调度器自身用 Asia/Shanghai 计算时间，
因此不依赖 Windows 当前时区。--next 可在安装后做无副作用的入口验证，--once 会立即执行工作流。
"""
from __future__ import annotations

import argparse
import subprocess
import sys
import time
from datetime import datetime, timedelta, time as day_time
from pathlib import Path
from datetime import timezone

BASE = Path(__file__).resolve().parent
# China Standard Time has a fixed UTC+08:00 offset and no DST.  A fixed
# offset keeps the bundled Python runtime self-contained (it has no tzdata).
TZ = timezone(timedelta(hours=8), name="Asia/Shanghai")
LOG = BASE / "scheduler.log"
WORKFLOW = BASE / "daily_workflow.py"


def log(message: str) -> None:
    stamp = datetime.now(TZ).isoformat(timespec="seconds")
    with LOG.open("a", encoding="utf-8") as fh:
        fh.write(f"{stamp} {message}\n")


def next_run(now: datetime | None = None) -> datetime:
    now = (now or datetime.now(TZ)).astimezone(TZ)
    target = datetime.combine(now.date(), day_time(6, 0), tzinfo=TZ)
    if now >= target:
        target += timedelta(days=1)
    return target


def run_workflow() -> int:
    command = [sys.executable, "-B", str(WORKFLOW), "--mode", "run", "--apply"]
    log("START " + " ".join(command))
    completed = subprocess.run(command, cwd=BASE, check=False)
    log(f"END exit={completed.returncode}")
    return completed.returncode


def daemon() -> None:
    log("READY timezone=Asia/Shanghai time=06:00")
    while True:
        target = next_run()
        log("NEXT " + target.isoformat(timespec="seconds"))
        while True:
            remaining = (target - datetime.now(TZ)).total_seconds()
            if remaining <= 0:
                break
            time.sleep(min(remaining, 60))
        run_workflow()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--daemon", action="store_true", help="常驻等待每天 06:00")
    parser.add_argument("--once", action="store_true", help="立即启动一次工作流")
    parser.add_argument("--next", action="store_true", help="打印下次北京时间 06:00，不执行")
    args = parser.parse_args()
    if args.next:
        print(next_run().isoformat(timespec="seconds"))
        return 0
    if args.once:
        return run_workflow()
    if args.daemon:
        daemon()
        return 0
    parser.error("请指定 --daemon、--once 或 --next")
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
