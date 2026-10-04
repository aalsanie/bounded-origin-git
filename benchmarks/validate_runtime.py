#!/usr/bin/env python3
"""Exercise native baseline startup, failure evidence, cleanup and gzip challenges."""

import argparse
import json
import os
import socket
from pathlib import Path

from campaign import Cell, SERVICE_PORTS, isolate_network
from common import write_json
from prepare import workload


def validate(config, output):
    output.mkdir(parents=True, exist_ok=False)
    manifest = json.loads(Path(config["manifest"]).read_text())
    network = isolate_network()
    write_json(output / "network.json", network)
    with socket.socket() as busy:
        busy.bind(("127.0.0.1", SERVICE_PORTS["anubis"]))
        busy.listen()
        cell = Cell(config, manifest, output / "busy/work", output / "busy/results", None,
                    "anubis-d5-solve", manifest["head"])
        try:
            try:
                cell.start(None)
                raise AssertionError("occupied listener was accepted")
            except RuntimeError as error:
                cell.failure(error)
                assert "exited 1" in str(error) and "address already in use" in str(error), error
        finally:
            cell.close()
    record = json.loads((output / "busy/results/anubis.process.json").read_text())
    assert record["exit_code"] == record["exit_before_cleanup"] == 1, record
    assert (output / "busy/results/failure.json").is_file()
    assert not json.loads((output / "busy/results/shutdown.json").read_text())["errors"]

    counts = {}
    for index in range(200):
        mode = "nginx-cold" if index % 5 == 0 else "anubis-d5-solve"
        directory = output / f"restart-{index:03d}"
        cell = Cell(config, manifest, directory / "work", directory / "results", None, mode, manifest["head"])
        try:
            cell.start(None)
            if index == 199:
                cell.mode = "anubis-d5-nosolve"
                rows = cell.measure(workload(manifest, "repeat-burst", 2048, 424242))
                assert len(rows) == 2048 and all(row["outcome"] == "challenge" for row in rows)
                counts["gzip_challenges"] = len(rows)
        except BaseException as error:
            cell.failure(error)
            raise
        finally:
            cell.close()
        shutdown = json.loads((directory / "results/shutdown.json").read_text())
        assert not shutdown["errors"], shutdown
        for value in shutdown["groups"].values():
            assert not value["remaining_members"] and not value["errors"], value
        for file in (directory / "results").glob("*.process.json"):
            value = json.loads(file.read_text())
            assert value["exit_code"] == 0, value
        log = directory / "results/anubis.stderr.log"
        if log.exists():
            assert "does not in fact support gzip" not in log.read_text()
        counts[mode] = counts.get(mode, 0) + 1
        if index % 25 == 0:
            print(f"validated {index + 1} startup/shutdown cycles", flush=True)
    remaining = list(Path("/sys/fs/cgroup/pids").glob(f"bounded-origin-git-{os.getpid()}-*"))
    assert not remaining, remaining
    write_json(output / "verification.json", {"passed": True, "counts": counts,
        "forced_startup_exit": 1, "remaining_cgroups": [], "network": network})
    print(json.dumps(counts), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    validate(json.loads(args.config.read_text()), args.output.resolve())
