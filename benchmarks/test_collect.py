import copy
import hashlib
import tempfile
import unittest
from pathlib import Path

from collect import verify_inventory, verify_origin, verify_process, verify_requests, verify_shutdown


class EvidenceVerificationTest(unittest.TestCase):
    def test_inventory_rejects_changed_missing_extra_and_escaping_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "sample").write_bytes(b"original")
            (root / "result-inventory.json").write_text("{}")
            inventory = {"sample": hashlib.sha256(b"original").hexdigest()}
            self.assertEqual(8, verify_inventory(root, inventory))
            (root / "sample").write_bytes(b"changed")
            with self.assertRaisesRegex(ValueError, "digest mismatch"):
                verify_inventory(root, inventory)
            (root / "sample").unlink()
            with self.assertRaisesRegex(ValueError, "digest mismatch"):
                verify_inventory(root, inventory)
            (root / "sample").write_bytes(b"original")
            (root / "extra").write_bytes(b"extra")
            with self.assertRaisesRegex(ValueError, "unrecorded"):
                verify_inventory(root, inventory)
            with self.assertRaisesRegex(ValueError, "escapes"):
                verify_inventory(root, {"../escape": "unused"})

    def test_native_events_require_complete_lifetimes_and_bounded_concurrency(self):
        events = []
        for identifier in range(2):
            events.extend([{"id": identifier, "kind": "start", "start_ns": 1},
                           {"id": identifier, "kind": "end", "start_ns": 1, "end_ns": 10,
                            "exit_code": 0, "io": {k: 0 for k in ("rchar", "wchar", "read_bytes", "write_bytes")}}])
        self.assertEqual(2, verify_origin(events)[1])
        with self.assertRaisesRegex(ValueError, "unpaired"):
            verify_origin(events[:-1])
        third = [dict(row, id=2) for row in events[:2]]
        with self.assertRaisesRegex(ValueError, "concurrency"):
            verify_origin(events + third)
        failed = copy.deepcopy(events)
        failed[1]["exit_code"] = 7
        with self.assertRaisesRegex(ValueError, "native exit"):
            verify_origin(failed)

    def test_stale_content_is_only_accepted_in_the_declared_cache_control(self):
        entry = {"index": 0, "semantic": "ref-after", "target": "/plain?h=main", "kind": "plain", "expected_sha256": "new"}
        row = {"index": 0, "operation": "ref-after", "target": entry["target"], "kind": "plain",
               "status": 200, "body_sha256": "old", "outcome": "semantic_mismatch", "latency_ms": 1}
        dataset = {"update": {"before": {"expected_sha256": "old"}}}
        verify_requests([row], [entry], "nginx-warm", "after-publication", dataset)
        for mode, phase in [("bounded-warm", "after-publication"), ("nginx-warm", "requests")]:
            with self.assertRaisesRegex(ValueError, "stale"):
                verify_requests([row], [entry], mode, phase, dataset)
        with self.assertRaisesRegex(ValueError, "plain content"):
            verify_requests([dict(row, outcome="delivered")], [entry], "nginx-warm", "after-publication", dataset)
        with self.assertRaisesRegex(ValueError, "coverage"):
            verify_requests([row, row], [entry], "nginx-warm", "after-publication", dataset)

    def test_gateway_exit_must_follow_requested_shutdown(self):
        record = {"state": "exited", "exit_signal": None, "exit_code": 143,
                  "exit_before_cleanup": None, "cleanup_signals": ["SIGTERM"]}
        verify_process(record, "gateway.process.json")
        with self.assertRaisesRegex(ValueError, "gateway exit"):
            verify_process(dict(record, exit_before_cleanup=143), "gateway.process.json")
        with self.assertRaisesRegex(ValueError, "nonzero"):
            verify_process(record, "requests.process.json")

    def test_shutdown_rejects_orphans_even_when_recorded_as_success(self):
        with self.assertRaisesRegex(ValueError, "orphaned"):
            verify_shutdown({"errors": [], "remaining_members": [42]})


if __name__ == "__main__":
    unittest.main()
