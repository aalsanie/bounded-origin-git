#!/usr/bin/env python3
"""Audit a completed campaign and export compact, traceable publication evidence."""

import argparse
from collections import Counter
from contextlib import contextmanager
import gzip
import hashlib
import json
import math
from pathlib import Path
import random
import shutil
import tarfile

from campaign import MODES, WORKLOADS, SCALED_WORKLOADS
from common import sha256
from prepare import build_manifest, workload
from summarize import cell_row, csv_file


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def lines(path):
    if path.exists():
        with path.open(encoding="utf-8") as stream:
            for line in stream:
                yield json.loads(line)


def write(path, data):
    path.write_text(json.dumps(data, sort_keys=True, indent=2) + "\n", encoding="utf-8")


@contextmanager
def compressed(path):
    with path.open("wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as stream:
            yield lambda value: stream.write((json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n").encode())


def verify_inventory(root, inventory):
    total = 0
    for name, digest in inventory.items():
        path = root / name
        require(path.resolve().is_relative_to(root.resolve()), "inventory escapes result directory")
        require(path.is_file() and sha256(path) == digest, "evidence digest mismatch: " + name)
        total += path.stat().st_size
    actual = {p.relative_to(root).as_posix() for p in root.rglob("*")
              if p.is_file() and not p.relative_to(root).as_posix().startswith("summaries/")}
    require(actual == set(inventory) | {"result-inventory.json"}, "unrecorded or missing evidence files")
    return total


def healthy(snapshot, limit):
    require(snapshot["memory_failures"] == 0, "memory allocation failure")
    require(int(snapshot["memory_oom"]["oom_kill"]) == 0, "OOM kill")
    require(int(snapshot["pid_events"]["max"]) == 0, "task quota reached")
    require(0 <= snapshot["memory_peak_bytes"] <= limit and 0 <= snapshot["pids"] <= 512,
            "resource bound exceeded")


def verify_shutdown(record):
    groups = record.get("groups", {"server": record})
    require(not record.get("errors"), "shutdown error")
    for name, group in groups.items():
        require(not group["errors"] and not group["remaining_members"], "orphaned processes")
        require(group["after"]["pids"] == 0 and not group["signals"], "late process cleanup required")
        healthy(group["after"], (2 if name == "client" else 4) * 1024**3)


def verify_process(record, name):
    require(record["state"] == "exited" and record["exit_signal"] is None, "unexpected process termination")
    if name == "gateway.process.json":
        require(record["exit_code"] == 143 and record["exit_before_cleanup"] is None
                and record["cleanup_signals"] == ["SIGTERM"], "unexpected gateway exit")
    else:
        require(record["exit_code"] == 0, "nonzero process exit")
        if name in {"origin.process.json", "anubis.process.json", "nginx.process.json"}:
            require(record["exit_before_cleanup"] is None and record["cleanup_signals"] == ["SIGTERM"],
                    "service exited before cleanup")
        else:
            require(not record["cleanup_signals"], "client/ingestion required termination")


def verify_origin(events, control=None):
    starts = {e["id"]: e for e in events if e["kind"] == "start"}
    ends = [e for e in events if e["kind"] == "end"]
    require(len(events) == len(starts) * 2 == len(ends) * 2 and {e["id"] for e in ends} == set(starts),
            "unpaired native process events")
    active = maximum = 0
    for end in ends:
        require(end["start_ns"] == starts[end["id"]]["start_ns"] < end["end_ns"], "invalid native interval")
        expected = -15 if control == "timeout" else 7 if control == "failure" else 0
        require(end["exit_code"] == expected, "unexpected native exit")
        require(set(end["io"]) >= {"rchar", "wchar", "read_bytes", "write_bytes"}, "missing native I/O evidence")
    for _, delta in sorted(pair for e in ends for pair in [(e["start_ns"], 1), (e["end_ns"], -1)]):
        active += delta
        maximum = max(active, maximum)
        require(0 <= active <= 2, "native concurrency exceeded")
    require(active == 0, "native process remains active")
    return ends, maximum


def verify_requests(rows, entries, mode, phase, dataset):
    expected = {e["index"]: e for e in entries}
    require(len(rows) == len(expected) and {r["index"] for r in rows} == set(expected), "request coverage mismatch")
    for row in rows:
        entry = expected[row["index"]]
        require((row["operation"], row["target"], row["kind"]) ==
                (entry["semantic"], entry["target"], entry["kind"]), "request plan mismatch")
        outcome = row["outcome"]
        require(outcome in {"delivered", "challenge", "artifact_miss", "comparison_limit", "semantic_mismatch"},
                "unexpected request outcome: " + outcome)
        if outcome == "delivered":
            require(row["status"] == 200, "delivery without HTTP success")
            if "expected_sha256" in entry:
                require(row["body_sha256"] == entry["expected_sha256"], "incorrect plain content")
            if mode.startswith("bounded") and entry["kind"] == "comparison":
                fields = ("path", "oldOid", "newOid", "oldMode", "newMode")
                project = lambda changes: sorted(tuple(c[k] for k in fields) for c in changes)
                require(project(row["comparison"]["changes"]) == project(entry["expected_changes"]),
                        "comparison differs from native Git oracle")
        elif outcome == "semantic_mismatch":
            require(mode.startswith("nginx") and phase == "after-publication" and
                    row["body_sha256"] == dataset["update"]["before"]["expected_sha256"], "unexpected stale content")
        elif outcome == "artifact_miss":
            require(mode.startswith("bounded") and row["status"] == 404 and entry["kind"] != "comparison",
                    "invalid artifact miss")
        elif outcome == "comparison_limit":
            require(mode.startswith("bounded") and entry["kind"] == "comparison" and
                    row["error_code"] == "LIMIT_EXCEEDED", "invalid comparison rejection")
        else:
            require(mode == "anubis-d5-nosolve" and row["challenges"] == 1, "unexpected challenge outcome")
        require(math.isfinite(row["latency_ms"]) and row["latency_ms"] >= 0, "invalid latency")


def collect(root, output):
    require(not output.exists(), "use a new export directory")
    status, config, dataset = (read(root / name) for name in ("status.json", "config.json", "dataset.json"))
    require(status["status"] == "completed" and not status["validation"] and
            status["completed_cells"] == status["total_cells"] == 1344, "not a complete publication campaign")
    require((config["warmups"], config["repetitions"], config["requests"], config["request_scales"]) ==
            (2, 10, 256, [32, 128, 512]), "unexpected publication design")
    inventory = read(root / "result-inventory.json")
    total_bytes = verify_inventory(root, inventory)
    source = read(root / "provenance/source.json")
    require(sha256(root / "provenance/source.tar.gz") == source["archive_sha256"], "source archive changed")
    source_files = read(root / "provenance/source-files.json")
    with tarfile.open(root / "provenance/source.tar.gz") as archive:
        members = [m for m in archive.getmembers() if m.isfile()]
        require({m.name.removeprefix("source/") for m in members} == set(source_files), "source inventory incomplete")
        for member in members:
            name = member.name.removeprefix("source/")
            require(not any(part.startswith(".codex") or part == "AGENTS.md" for part in Path(name).parts),
                    "private context in source archive")
            require(hashlib.sha256(archive.extractfile(member).read()).hexdigest() == source_files[name], "source file changed")
    bundle = read(root / "provenance/bundle-files.json")
    for name, digest in bundle.items():
        require(sha256(Path(config["bundle"]) / name) == digest, "frozen bundle changed: " + name)
    environment = read(root / "environment.json")
    for name, binary in environment["binaries"].items():
        require(sha256(Path(binary["path"])) == binary["sha256"], "runtime changed: " + name)
    for name, digest in environment["linked_library_sha256"].items():
        require(sha256(Path(name)) == digest, "linked library changed: " + name)
    jdk = read(root / "provenance/jdk-files.json")
    java_root = Path(config["java"]).resolve().parent.parent
    for name, digest in jdk.items():
        require(sha256(java_root / name) == digest, "JDK changed: " + name)
    current_dataset = build_manifest(Path(config["dataset"]))
    comparable = lambda value: {k: v for k, v in value.items() if k != "preparation_seconds"}
    require(comparable(current_dataset) == comparable(dataset), "dataset/native Git oracle changed")
    expected_reps = {f"warmup-{i:02d}" for i in range(2)} | {f"run-{i:02d}" for i in range(10)}
    require({p.name for p in (root / "raw").iterdir()} == expected_reps, "missing/extra repetition")
    output.mkdir(parents=True)
    counts = Counter(files_verified=len(inventory), bytes_verified=total_bytes, bundle_files_verified=len(bundle),
                     jdk_files_verified=len(jdk), linked_libraries_verified=len(environment["linked_library_sha256"]),
                     dataset_files_verified=len(dataset["inventory"]))
    cells, catalogue, processes = [], {}, Counter()
    controls, preparations, ingestions = [], [], []
    measured_outcomes, errors = Counter(), Counter()
    bounded_attempts = 0
    with compressed(output / "requests.jsonl.gz") as request_out, compressed(output / "native.jsonl.gz") as native_out, \
            compressed(output / "support.jsonl.gz") as support_out:
        for repetition in sorted((root / "raw").iterdir()):
            measured = repetition.name.startswith("run-")
            rep = int(repetition.name[-2:]) if measured else int(repetition.name[-2:]) - 2
            combinations = [(w, m, n) for w in WORKLOADS for n in ([32, 128, 512] if w in SCALED_WORKLOADS else [256]) for m in MODES]
            random.Random(config["seed"] + rep).shuffle(combinations)
            expected_dirs = {f"{i:03d}-n{n}-{w}-{m}" for i, (w, m, n) in enumerate(combinations)}
            require({p.name for p in repetition.iterdir() if p.is_dir()} == expected_dirs | {"controls"}, "cell matrix/order mismatch")
            ingestion = read(repetition / "ingestion.json")
            require(ingestion["objects"]["created"] == ingestion["objects"]["objects"] == dataset["object_count"] ==
                    ingestion["storage"]["file_count"], "ingestion is incomplete or reused")
            healthy(ingestion["after"], 4 * 1024**3)
            ingestions.append({"repetition": repetition.name, **ingestion})
            counts["ingestions"] += 1
            for directory in sorted(repetition.iterdir()):
                if not directory.is_dir() or directory.name == "controls":
                    continue
                identity = directory.relative_to(root / "raw").as_posix()
                plan = read(directory / "plan.json")
                mode = directory.name.split("-" + plan["name"] + "-", 1)[1]
                require(plan == workload(dataset, plan["name"], plan["scale"], config["seed"] + max(0, rep)), "plan differs from frozen design")
                compact = {k: v for k, v in plan.items() if k not in {"requests", "prepare"}}
                for key in ("requests", "prepare"):
                    compact[key] = []
                    for entry in plan[key]:
                        item = {k: v for k, v in entry.items() if k != "index"}
                        digest = hashlib.sha256(json.dumps(item, sort_keys=True).encode()).hexdigest()
                        catalogue[digest] = item
                        compact[key].append({"entry": digest, "index": entry.get("index")})
                support_out({"path": identity + "/plan.json", "value": compact})
                events = list(lines(directory / "origin.jsonl"))
                ends, maximum = verify_origin(events)
                for event in events:
                    native_out({"cell": identity, **event})
                counts["traffic_and_preparation_native_executions"] += len(ends)
                expected_phases = {"requests"}
                if plan["name"] == "ref-update":
                    expected_phases.add("after-publication")
                    if mode == "bounded-warm":
                        expected_phases.add("after-update-preparation")
                require({f.name.removesuffix("-measurement.json") for f in directory.glob("*-measurement.json")} == expected_phases,
                        "missing/extra request phase")
                for phase in sorted(expected_phases):
                    measurement = read(directory / (phase + "-measurement.json"))
                    rows = list(lines(directory / (phase + ".stdout.log")))
                    entries = plan["requests"] if phase == "requests" else [dict(dataset["update"]["after"], index=i) for i in range(len(plan["requests"]))]
                    verify_requests(rows, entries, mode, phase, dataset)
                    require(Counter(r["outcome"] for r in rows) == measurement["outcomes"] and len(rows) == measurement["request_count"], "incorrect outcome summary")
                    native = [e for e in ends if measurement["start_ns"] <= e["start_ns"] < e["end_ns"] <= measurement["end_ns"]]
                    require(len(native) == measurement["origin_delta"]["executions"], "incorrect native execution delta")
                    cpu = sum(e["user_cpu_seconds"] + e["system_cpu_seconds"] for e in native)
                    require(math.isclose(cpu, measurement["origin_delta"]["cpu_seconds"], abs_tol=1e-8), "incorrect native CPU delta")
                    for group, limit in (("server", 4), ("client", 2)):
                        healthy(measurement[group + "_after"], limit * 1024**3)
                        require(measurement[group + "_after"]["cpu_seconds"] >= measurement[group + "_before"]["cpu_seconds"], "CPU counter decreased")
                    if mode.startswith("bounded"):
                        before, after = (read(directory / (phase + suffix + "-adapter.json")) for suffix in ("-before", "-after"))
                        require(not native and before["render_calls"] == after["render_calls"] and after["in_flight"] == 0, "anonymous render work")
                        if measured:
                            bounded_attempts += len(rows)
                    samples = list(lines(directory / (phase + "-resources.jsonl")))
                    require(samples and [s["time_ns"] for s in samples] == sorted(s["time_ns"] for s in samples), "missing/unordered resource samples")
                    for sample in samples:
                        healthy(sample["group"], 4 * 1024**3)
                    summary = read(directory / (phase + "-client-summary.json"))
                    require(len(summary["workers"]) == min(plan["concurrency"], len(rows)), "missing client worker summary")
                    for row in rows:
                        request_out({"cell": identity, "phase": phase, **row})
                    if measured:
                        result = cell_row(repetition.name, directory, phase)
                        result["cell"] = directory.name
                        result["client_cgroup_memory_high_water_bytes"] = measurement["client_after"]["memory_peak_bytes"]
                        result["native_peak_rss_kib"] = max((e["max_rss_kib"] for e in native), default=0)
                        result["pow_ms"] = sum(r["pow_ms"] for r in rows)
                        cells.append(result)
                        measured_outcomes.update(r["outcome"] for r in rows)
                        errors.update(r["error"] for r in rows if "error" in r)
                    counts["request_phases"] += 1
                    counts["requests"] += len(rows)
                counts["cells"] += 1
                for f in sorted(directory.glob("*preparation.json")):
                    value = read(f)
                    require(all(r["result"] == "success" for r in value["results"]), "preparation failed")
                    preparations.append({"cell": identity, "phase": f.stem, **value})
            require({d.name for d in (repetition / "controls").iterdir()} == {"same-key", "distinct-queue", "timeout", "failure"}, "missing control")
            for directory in sorted((repetition / "controls").iterdir()):
                identity = directory.relative_to(root / "raw").as_posix()
                ends, maximum = verify_origin(list(lines(directory / "origin.jsonl")), directory.name)
                for event in lines(directory / "origin.jsonl"):
                    native_out({"cell": identity, **event})
                verification = read(directory / "verification.json")
                stats = verification["stats"]
                require(verification["passed"] and (stats["active"], stats["queued"], stats["in_flight"]) == (0, 0, 0), "control not quiescent")
                admission = read(directory / "admission.json")
                outcomes = Counter(r["result"] for r in admission["results"])
                joined = sum(r["joined"] for r in admission["results"])
                if directory.name == "same-key":
                    require(outcomes == {"success": 64} and joined == 63 and len(ends) == 1 and stats["artifact_entries"] == 1, "single-flight failed")
                elif directory.name == "distinct-queue":
                    require(outcomes["success"] == len(ends) == stats["artifact_entries"] == 18 and
                            sum(outcomes[k] for k in ("GLOBAL_QUEUE_LIMIT", "POLICY_QUEUE_LIMIT")) == 46 and maximum == 2, "admission bound failed")
                else:
                    require(stats["artifact_entries"] == 0, "failed control published artifact")
                    if directory.name == "timeout":
                        require(outcomes == {"TIMEOUT": 4} and len(ends) == 4, "timeout control failed")
                    else:
                        require(outcomes == {"MATERIALIZATION_FAILED": 4} and len(ends) == 1 and joined == 3, "failure control failed")
                        require(Counter(r["result"] for r in read(directory / "cooldown.json")["results"]) == {"COOLDOWN": 4}, "cooldown failed")
                controls.append({"repetition": repetition.name, "control": directory.name, "outcomes": outcomes,
                                 "joined": joined, "native_executions": len(ends), "native_max_concurrency": maximum,
                                 "wall_seconds": admission["wall_ns"] / 1e9, "stats": stats})
                counts["controls"] += 1
                counts["control_native_executions"] += len(ends)
            for f in sorted(repetition.rglob("*.json")):
                if f.name in {"plan.json", "endpoints.json"}:
                    continue
                value = read(f)
                if f.name.endswith("shutdown.json"):
                    verify_shutdown(value)
                    counts["clean_shutdowns"] += 1
                if f.name.endswith(".process.json"):
                    verify_process(value, f.name)
                    processes[f.name + ":" + str(value["exit_code"])] += 1
                    value = {k: v for k, v in value.items() if k not in {"command", "cwd", "cgroup"}}
                support_out({"path": f.relative_to(root / "raw").as_posix(), "value": value})
            for f in sorted(repetition.rglob("*resources.jsonl")):
                previous = -1
                for sample in lines(f):
                    require(sample["time_ns"] > previous, "unordered resource samples")
                    previous = sample["time_ns"]
                    healthy(sample["group"], 4 * 1024**3)
                    support_out({"path": f.relative_to(root / "raw").as_posix(), "value": sample})
                    counts["resource_samples"] += 1
            print("verified", repetition.name, flush=True)
    require((counts["cells"], counts["request_phases"], counts["controls"], len(cells)) == (1344, 1452, 48, 1210), "incomplete audit")
    with compressed(output / "catalogue.json.gz") as out:
        out(catalogue)
    with compressed(output / "result-inventory.json.gz") as out:
        out(inventory)
    csv_file(output / "cells.csv", cells)
    for name in ("aggregates", "preparation", "ingestion", "controls", "paired"):
        shutil.copyfile(root / "summaries" / (name + ".csv"), output / (name + ".csv"))
    write(output / "controls-detail.json", controls)
    write(output / "ingestion-detail.json", ingestions)
    environment = {k: v for k, v in environment.items() if k not in {"boot_id", "cpuinfo", "meminfo", "linked_libraries", "runtime_isolation", "source"}}
    environment["binaries"] = {k: {"sha256": v["sha256"]} for k, v in environment["binaries"].items()}
    environment["network"] = {k: v for k, v in environment["network"].items() if k not in {"parent", "campaign"}}
    write(output / "environment.json", environment)
    write(output / "dataset.json", {k: v for k, v in dataset.items() if k not in {"inventory", "comparisons", "preparation_seconds"}})
    for name in ("source.tar.gz", "source.json", "source-files.json", "bundle-files.json", "packages.json", "jdk-files.json"):
        shutil.copyfile(root / "provenance" / name, output / name)
    audit = {"passed": True, **counts, "measured_request_phases": len(cells), "measured_outcomes": measured_outcomes,
             "measured_comparison_errors": errors, "measured_bounded_attempts": bounded_attempts,
             "measured_bounded_native_executions": 0, "process_exits": processes,
             "result_inventory_sha256": sha256(root / "result-inventory.json"),
             "source_archive_sha256": source["archive_sha256"],
             "bundle_inventory_sha256": sha256(root / "provenance/bundle-files.json"),
             "started_utc": status["started_utc"], "finished_utc": status["finished_utc"]}
    write(output / "audit.json", audit)
    write(output / "export-inventory.json", {f.name: sha256(f) for f in sorted(output.iterdir()) if f.is_file()})
    print(json.dumps(audit, sort_keys=True), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("results", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    collect(args.results.resolve(), args.output.resolve())
