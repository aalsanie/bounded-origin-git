import json
import os
import signal
import socket
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from campaign import SERVICE_PORTS, wait_http
from common import Process, wait_ready


@unittest.skipUnless(os.name == "posix", "process and signal evidence requires POSIX")
class LifecycleTest(unittest.TestCase):
    def group(self):
        return SimpleNamespace(name="test-only", enter=lambda: None, snapshot=lambda: {"pids": 0})

    def test_startup_exit_preserves_code_command_and_does_not_probe_another_server(self):
        with tempfile.TemporaryDirectory() as directory:
            process = Process([sys.executable, "-c", "import sys; sys.stderr.write('ordinary warning'); sys.exit(7)"],
                              self.group(), directory, "server")
            try:
                process.process.wait(timeout=5)
                with patch("campaign.request") as probe:
                    with self.assertRaisesRegex(RuntimeError, "exited 7"):
                        wait_http("http://127.0.0.1:1/health", process)
                    probe.assert_not_called()
            finally:
                process.close()
            evidence = json.loads(process.record_path.read_text())
            self.assertEqual(7, evidence["exit_code"])
            self.assertEqual(7, evidence["exit_before_cleanup"])
            self.assertEqual([], evidence["cleanup_signals"])
            self.assertIn("sys.exit(7)", evidence["command"][-1])
            self.assertTrue(process.stderr.closed)

    def test_successful_exit_is_not_server_readiness(self):
        with tempfile.TemporaryDirectory() as directory:
            ready = Path(directory) / "ready.json"
            ready.write_text('{"port": 1}')
            process = Process([sys.executable, "-c", "pass"], self.group(), directory, "server")
            try:
                process.process.wait(timeout=5)
                with self.assertRaisesRegex(RuntimeError, "exited 0"):
                    wait_ready(ready, process)
            finally:
                process.close()

    def test_forced_shutdown_preserves_signal_and_reaps_child(self):
        with tempfile.TemporaryDirectory() as directory:
            ready = Path(directory) / "ready.json"
            program = ("import signal,time; from pathlib import Path; "
                       "signal.signal(signal.SIGTERM, signal.SIG_IGN); "
                       f"Path({str(ready)!r}).write_text('{{}}'); time.sleep(60)")
            process = Process([sys.executable, "-c", program], self.group(), directory, "server")
            try:
                wait_ready(ready, process)
            finally:
                process.close(timeout=.05)
            evidence = json.loads(process.record_path.read_text())
            self.assertIsNone(evidence["exit_before_cleanup"])
            self.assertEqual(["SIGTERM", "SIGKILL"], evidence["cleanup_signals"])
            self.assertEqual(-signal.SIGKILL, evidence["exit_code"])
            self.assertEqual(signal.SIGKILL, evidence["exit_signal"])
            self.assertIsNotNone(process.process.returncode)

    def test_spawn_failure_is_durable(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(FileNotFoundError):
                Process([str(Path(directory) / "missing")], self.group(), directory, "server")
            evidence = json.loads((Path(directory) / "server.process.json").read_text())
            self.assertEqual("spawn-failed", evidence["state"])
            self.assertIn("FileNotFoundError", evidence["error"])


class ReadinessTest(unittest.TestCase):
    def test_unexpected_health_body_is_rejected(self):
        process = SimpleNamespace(ensure_running=lambda: None)
        with patch("campaign.request", return_value=b"unrelated service"):
            with self.assertRaisesRegex(ValueError, "unexpected readiness"):
                wait_http("http://127.0.0.1:1/health", process, b"ok")

    @unittest.skipUnless(os.environ.get("BO_BENCHMARK_NATIVE_TESTS") == "1", "requires Linux namespace privileges")
    def test_service_ports_are_isolated_from_live_host_listeners(self):
        self.assertEqual(len(SERVICE_PORTS), len(set(SERVICE_PORTS.values())))
        with socket.socket() as host:
            host.bind(("127.0.0.1", SERVICE_PORTS["anubis"]))
            host.listen()
            program = ("import json,socket; from campaign import isolate_network,SERVICE_PORTS; "
                       "state=isolate_network(); server=socket.socket(); "
                       "server.bind(('127.0.0.1',SERVICE_PORTS['anubis'])); server.listen(); "
                       "print(json.dumps(state))")
            result = subprocess.run([sys.executable, "-c", program], cwd=Path(__file__).parent,
                                    capture_output=True, text=True, timeout=10, check=True)
            state = json.loads(result.stdout)
            self.assertNotEqual(state["parent"], state["campaign"])
            self.assertEqual(os.readlink("/proc/self/ns/net"), state["parent"])


if __name__ == "__main__":
    unittest.main()
