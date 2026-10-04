import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from campaign import campaign_cells, plan_text, run, safe_remove, verify_outcomes
from common import origin_summary
from origin import capture_cgi, parse_cgi
from prepare import build_manifest, git, workload
from summarize import interval, percentile


def fixture(path):
    subprocess.run(["git", "init", "--bare", str(path)], check=True, capture_output=True)
    stream = bytearray()
    for number in range(10):
        content = (f"revision {number}\n" + "unchanged\n" * 2000).encode()
        mark = number + 1
        stream.extend(f"blob\nmark :{mark}\ndata {len(content)}\n".encode() + content + b"\n")
        message = f"fixture {number}\n".encode()
        stream.extend(f"commit refs/heads/main\ncommitter Fixture <fixture@example.com> {1700000000 + number} +0000\ndata {len(message)}\n".encode())
        stream.extend(message + f"M 100644 :{mark} file.txt\n\n".encode())
    stream.extend(b"done\n")
    subprocess.run(["git", "--git-dir=" + str(path), "fast-import", "--quiet"], input=stream, check=True)
    subprocess.run(["git", "--git-dir=" + str(path), "symbolic-ref", "HEAD", "refs/heads/main"], check=True)


class CampaignTest(unittest.TestCase):
    def test_native_git_catalogue_and_aliases(self):
        with tempfile.TemporaryDirectory() as directory:
            dataset = Path(directory) / "fixture.git"
            fixture(dataset)
            manifest = build_manifest(dataset, allow_fixture=True)
            self.assertEqual(10, manifest["commit_count"])
            self.assertNotEqual(manifest["update"]["before"]["expected_sha256"], manifest["update"]["after"]["expected_sha256"])
            first = workload(manifest, "alias-flood", 64, 42)
            self.assertEqual(first, workload(manifest, "alias-flood", 64, 42))
            self.assertEqual(4, len(first["prepare"]))
            self.assertGreater(len({row["target"] for row in first["requests"]}), 4)
            self.assertEqual(4, len(plan_text(first["prepare"]).splitlines()))
            comparisons = workload(manifest, "comparisons", 32, 42)
            self.assertEqual(len(manifest["comparisons"]), len(comparisons["prepare"]))
            with self.assertRaises(ValueError):
                build_manifest(dataset)

    def test_scaling_keeps_each_configuration_distinct(self):
        cells = campaign_cells({})
        self.assertEqual(112, len(cells))
        self.assertEqual(len(cells), len(set(cells)))
        self.assertEqual({32, 128, 512}, {size for name, _, size in cells if name == "alias-flood"})
        with self.assertRaises(ValueError):
            campaign_cells({"request_scales": [32, 32]})
        with self.assertRaises(ValueError):
            campaign_cells({"request_scales": [0]})
        with self.assertRaises(ValueError):
            campaign_cells({"modes": []})

    def test_shallow_boundary_is_excluded_even_when_parent_is_reachable_elsewhere(self):
        with tempfile.TemporaryDirectory() as directory:
            dataset = Path(directory) / "fixture.git"
            fixture(dataset)
            commits = git(dataset, "rev-list", "HEAD").decode().splitlines()
            boundary, parent = commits[4:6]
            environment = {**os.environ, "GIT_AUTHOR_NAME": "Fixture", "GIT_AUTHOR_EMAIL": "fixture@example.com",
                           "GIT_COMMITTER_NAME": "Fixture", "GIT_COMMITTER_EMAIL": "fixture@example.com"}
            merge = subprocess.check_output(["git", "--git-dir=" + str(dataset), "commit-tree", "HEAD^{tree}",
                                             "-p", commits[0], "-p", parent], input=b"join fixture histories\n", env=environment).decode().strip()
            git(dataset, "update-ref", "refs/heads/main", merge)
            (dataset / "shallow").write_text(boundary + "\n")
            manifest = build_manifest(dataset, allow_fixture=True)
            self.assertIn(parent, git(dataset, "rev-list", "HEAD").decode().splitlines())
            self.assertIn(boundary, manifest["shallow_boundaries"])
            self.assertNotIn(boundary, manifest["selected_commits"])

    @unittest.skipUnless(os.name == "posix", "native CGI transport requires POSIX pipes")
    def test_native_transport_stops_at_streaming_byte_limit(self):
        with subprocess.Popen([sys.executable, "-c", "import sys; sys.stdout.buffer.write(b'x' * 65536)"],
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE) as process:
            try:
                with patch("origin.MAX_BODY", 128), self.assertRaisesRegex(ValueError, "exceeds limit"):
                    capture_cgi(process)
            finally:
                process.kill()
                process.wait()

    def test_publication_rejects_reused_objects_and_wrong_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaises(ValueError):
                run({"validation_objects": str(root)}, root / "output", root / "mirror")
            (root / "source.tar.gz").write_bytes(b"unverified snapshot")
            with self.assertRaises(ValueError):
                run({"provenance": str(root), "source": {"archive_sha256": "0" * 64}},
                    root / "output", root / "mirror")
            self.assertFalse((root / "output").exists())

    def test_only_the_known_old_ref_content_is_an_allowed_stale_response(self):
        rows = [{"outcome": "semantic_mismatch", "body_sha256": "old-content"}]
        verify_outcomes(rows, "old-content")
        with self.assertRaises(AssertionError):
            verify_outcomes(rows)
        with self.assertRaises(AssertionError):
            verify_outcomes(rows, "different-content")
        with self.assertRaises(AssertionError):
            verify_outcomes([{"outcome": "client_error"}], "old-content")

    def test_origin_accounting_rejects_unclosed_work(self):
        with tempfile.TemporaryDirectory() as directory:
            file = Path(directory) / "events.jsonl"
            records = [{"kind": "start", "id": "a", "start_ns": 1},
                       {"kind": "start", "id": "b", "start_ns": 2}]
            file.write_text("\n".join(json.dumps(value) for value in records))
            with self.assertRaises(ValueError):
                origin_summary(file)
            for key, start, end in [("a", 1, 3), ("b", 2, 4)]:
                records.append({"kind": "end", "id": key, "start_ns": start, "end_ns": end,
                                "user_cpu_seconds": .2, "system_cpu_seconds": .1, "exit_code": 0,
                                "io": {"rchar": 7, "wchar": 11}})
            file.write_text("\n".join(json.dumps(value) for value in records))
            result = origin_summary(file)
            self.assertEqual(2, result["max_concurrency"])
            self.assertAlmostEqual(.6, result["cpu_seconds"])
            self.assertEqual(22, result["logical_written_bytes"])

    def test_cleanup_stays_inside_owned_scratch(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "scratch"
            root.mkdir()
            child = root / "run"
            child.mkdir()
            with self.assertRaises(ValueError):
                safe_remove(child, root)
            (root / ".campaign-owned").touch()
            with self.assertRaises(ValueError):
                safe_remove(root, root)
            with self.assertRaises(ValueError):
                safe_remove(Path(directory), root)
            safe_remove(child, root)
            self.assertFalse(child.exists())

    def test_cgi_and_statistics_edges(self):
        status, headers, body = parse_cgi(b"Status: 404 Missing\r\nContent-Type: text/plain\r\n\r\nmissing")
        self.assertEqual((404, b"missing"), (status, body))
        self.assertIn(("Content-Type", "text/plain"), headers)
        with self.assertRaises(ValueError):
            parse_cgi(b"unterminated")
        self.assertEqual(2.5, percentile([1, 2, 3, 4], .5))
        self.assertEqual((7.0, 7.0), interval([7] * 10, samples=100))
        self.assertEqual(interval([1, 2, 3], samples=100), interval([1, 2, 3], samples=100))


if __name__ == "__main__":
    unittest.main()
