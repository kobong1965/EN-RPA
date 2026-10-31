"""Windows lifecycle tests using inert waiting processes; never touches desktop/data."""
from __future__ import annotations

import ctypes
from ctypes import wintypes
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import tempfile
import unittest

HERE = Path(__file__).resolve()


def fixture(kind, owner=None):
    if kind == "leaf":
        print(json.dumps({"leaf": os.getpid()}), flush=True)
        time.sleep(60)
        return
    if kind == "owner":
        child = subprocess.Popen([sys.executable, "-B", str(HERE), "--fixture", "guard", str(os.getpid())],
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        values = json.loads(child.stdout.readline())
        print(json.dumps(dict(values, owner=os.getpid())), flush=True)
        time.sleep(60)
        return
    if kind == "collector":
        # Run the real standalone discovery entry point, replacing only the
        # compiler boundary with an inert waiting descendant (no UI or data).
        import collector
        def fake_compile(_log):
            child = subprocess.Popen([sys.executable, "-B", str(HERE), "--fixture", "leaf"],
                                     stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            values = json.loads(child.stdout.readline())
            print(json.dumps(dict(values, guard=os.getpid(), leafLauncher=child.pid)), flush=True)
            time.sleep(60)
        collector.ensure_compiled = fake_compile
        folder = Path(tempfile.gettempdir()) / ("oculix-discovery-guard-test-" + str(os.getpid()))
        sys.argv = ["collector.py", "--discover-account", "test-account", "--owner-pid", str(owner),
                    "--output", str(folder / "discovery.json")]
        collector.main()
        return
    from process_guard import protect_process_tree
    protect_process_tree(int(owner))
    if kind == "nested-collector":
        child = subprocess.Popen([sys.executable, "-B", str(HERE), "--fixture", "collector", str(os.getpid())],
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        line = child.stdout.readline()
        if not line:
            raise RuntimeError(child.stderr.read())
        values = json.loads(line)
        print(json.dumps(dict(values, nestedOwner=os.getpid())), flush=True)
        time.sleep(60)
        return
    child = subprocess.Popen([sys.executable, "-B", str(HERE), "--fixture", "leaf"],
                             stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    values = json.loads(child.stdout.readline())
    print(json.dumps(dict(values, guard=os.getpid(), leafLauncher=child.pid)), flush=True)
    time.sleep(1 if kind == "normal-exit" else 60)


@unittest.skipUnless(os.name == "nt", "Windows Job Objects")
class ProcessGuardTests(unittest.TestCase):
    def setUp(self):
        self.kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        self.kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
        self.kernel.OpenProcess.restype = wintypes.HANDLE
        self.kernel.TerminateProcess.argtypes = [wintypes.HANDLE, wintypes.UINT]
        self.kernel.WaitForSingleObject.argtypes = [wintypes.HANDLE, wintypes.DWORD]
        self.kernel.WaitForSingleObject.restype = wintypes.DWORD
        self.kernel.CloseHandle.argtypes = [wintypes.HANDLE]
        self.handles = []
        self.processes = []

    def tearDown(self):
        for handle in self.handles:
            if self.kernel.WaitForSingleObject(handle, 0) == 258:
                self.kernel.TerminateProcess(handle, 131)
                self.kernel.WaitForSingleObject(handle, 3000)
            self.kernel.CloseHandle(handle)
        for process in self.processes:
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
            process.stdout.close()
            process.stderr.close()

    def start_fixture(self, mode):
        process = subprocess.Popen([sys.executable, "-B", str(HERE), "--fixture", mode, str(os.getpid())],
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                                   creationflags=subprocess.CREATE_NO_WINDOW)
        self.processes.append(process)
        line = process.stdout.readline()
        if not line:
            raise AssertionError(process.stderr.read())
        values = json.loads(line)
        handles = {}
        for name, pid in values.items():
            handle = self.kernel.OpenProcess(0x00100001, False, pid)  # SYNCHRONIZE | TERMINATE
            self.assertTrue(handle, "Cannot observe " + name)
            handles[name] = handle
            self.handles.append(handle)
        return handles

    def assert_stopped(self, handle):
        self.assertEqual(self.kernel.WaitForSingleObject(handle, 5000), 0, "Owned process survived cancellation")

    def test_forced_guard_exit_kills_all_descendants(self):
        handles = self.start_fixture("guard")
        self.assertTrue(self.kernel.TerminateProcess(handles["guard"], 130))
        for handle in handles.values():
            self.assert_stopped(handle)

    def test_dead_entry_owner_stops_guard_and_descendants(self):
        handles = self.start_fixture("owner")
        self.assertTrue(self.kernel.TerminateProcess(handles["owner"], 130))
        for handle in handles.values():
            self.assert_stopped(handle)

    def test_normal_guard_exit_also_cleans_leftover_descendants(self):
        handles = self.start_fixture("normal-exit")
        for handle in handles.values():
            self.assert_stopped(handle)

    def test_standalone_discovery_entry_kills_inert_descendants(self):
        handles = self.start_fixture("collector")
        self.assertTrue(self.kernel.TerminateProcess(handles["guard"], 130))
        for handle in handles.values():
            self.assert_stopped(handle)

    def test_collector_guard_can_nest_inside_orchestrator_guard(self):
        handles = self.start_fixture("nested-collector")
        self.assertTrue(self.kernel.TerminateProcess(handles["nestedOwner"], 130))
        for handle in handles.values():
            self.assert_stopped(handle)


if __name__ == "__main__":
    if len(sys.argv) > 2 and sys.argv[1] == "--fixture":
        fixture(sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else None)
    else:
        unittest.main()
