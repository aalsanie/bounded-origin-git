#!/usr/bin/env python3
"""Generate tables from completed raw runs; never manufacture benchmark inputs."""

import argparse
import csv
import hashlib
import json
import random
import statistics
from collections import defaultdict
from pathlib import Path

from common import read_jsonl, sha256, write_json


def percentile(values, quantile):
    ordered = sorted(values)
    if not ordered:
        return None
    position = (len(ordered) - 1) * quantile
    left = int(position)
    right = min(left + 1, len(ordered) - 1)
    return ordered[left] + (ordered[right] - ordered[left]) * (position - left)


def interval(values, seed=424242, samples=10000):
    if not values:
        return None, None
    generator = random.Random(seed)
    means = [statistics.mean(generator.choices(values, k=len(values))) for _ in range(samples)]
    return percentile(means, 0.025), percentile(means, 0.975)


def csv_file(path, rows):
    if not rows:
        return
    columns = list(dict.fromkeys(key for row in rows for key in row))
    with path.open("w", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=columns)
        writer.writeheader()
        writer.writerows(rows)


def cell_row(repetition, directory, phase):
    measurement = json.loads((directory / (phase + "-measurement.json")).read_text())
    plan = json.loads((directory / "plan.json").read_text())
    requests = read_jsonl(directory / (phase + ".stdout.log"))
    mode = directory.name.split("-" + plan["name"] + "-", 1)[1]
    count = len(requests)
    elapsed = (measurement["end_ns"] - measurement["start_ns"]) / 1e9
    origin = measurement["origin_delta"]
    successful = sum(row["outcome"] == "delivered" for row in requests)
    resources = read_jsonl(directory / (phase + "-resources.jsonl"))
    server_cpu = measurement["server_after"]["cpu_seconds"] - measurement["server_before"]["cpu_seconds"]
    client_cpu = measurement["client_after"]["cpu_seconds"] - measurement["client_before"]["cpu_seconds"]
    origins = [event for event in read_jsonl(directory / "origin.jsonl") if event["kind"] == "end"
               and measurement["start_ns"] <= event["start_ns"] and event["end_ns"] <= measurement["end_ns"]]
    active = maximum = 0
    for _, delta in sorted([pair for event in origins for pair in [(event["start_ns"], 1), (event["end_ns"], -1)]]):
        active += delta
        maximum = max(maximum, active)
    client_summary = json.loads((directory / (phase + "-client-summary.json")).read_text())
    storage = json.loads((directory / "cache-storage.json").read_text())
    artifact_attempts = sum(row["kind"] != "comparison" for row in requests)
    artifact_deliveries = sum(row["kind"] != "comparison" and row["outcome"] == "delivered" for row in requests)
    result = {"repetition": repetition, "workload": plan["name"], "scale": plan["scale"], "mode": mode, "phase": phase,
              "attempted": count, "delivered": successful, "delivered_fraction": successful / count,
              "wall_seconds": elapsed, "attempted_per_second": count / elapsed,
              "delivered_per_second": successful / elapsed,
              "origin_cpu_seconds_per_1000_attempts": origin["cpu_seconds"] * 1000 / count,
              "origin_cpu_seconds": origin["cpu_seconds"], "origin_executions": origin["executions"],
              "origin_max_concurrency": maximum,
              "origin_executions_per_unique_operation": origin["executions"] / len({row["operation"] for row in requests}),
              "server_cpu_seconds": server_cpu, "client_cpu_seconds": client_cpu,
              "server_overhead_cpu_seconds": server_cpu - origin["cpu_seconds"],
              "origin_to_client_cpu_ratio": origin["cpu_seconds"] / client_cpu if client_cpu else None,
              "server_peak_sampled_rss_bytes": max((sample["rss_sum_bytes"] for sample in resources), default=0),
              "server_cgroup_memory_high_water_bytes": measurement["server_after"]["memory_peak_bytes"],
              "cell_final_cache_file_bytes": storage["file_bytes"],
              "cell_final_cache_allocated_bytes": storage["file_allocated_bytes"],
              "p50_ms": percentile([row["latency_ms"] for row in requests], .5),
              "p95_ms": percentile([row["latency_ms"] for row in requests], .95),
              "p99_ms": percentile([row["latency_ms"] for row in requests], .99),
              "request_wire_read_bytes": sum(row["wire_read_bytes"] for row in requests),
              "request_wire_written_bytes": sum(row["wire_written_bytes"] for row in requests),
              "total_client_wire_read_bytes": sum(worker["wire_read_bytes"] for worker in client_summary["workers"]),
              "total_client_wire_written_bytes": sum(worker["wire_written_bytes"] for worker in client_summary["workers"]),
              "module_body_bytes": sum(worker["module_body_bytes"] for worker in client_summary["workers"]),
              "origin_logical_read_bytes": origin["logical_read_bytes"],
              "origin_logical_written_bytes": origin["logical_written_bytes"],
              "origin_disk_read_bytes": origin["disk_read_bytes"], "origin_disk_written_bytes": origin["disk_written_bytes"],
              "challenges": sum(row["challenges"] for row in requests),
              "pow_hashes": sum(row["pow_hashes"] for row in requests),
              "nginx_hits": sum(row.get("cache") == "HIT" for row in requests),
              "nginx_hit_fraction": sum(row.get("cache") == "HIT" for row in requests) / count,
              "object_requests": sum(row["object_requests"] for row in requests),
              "object_cache_hits": sum(row["object_cache_hits"] for row in requests)}
    if mode.startswith("bounded"):
        result["artifact_attempts"] = artifact_attempts
        result["artifact_hits"] = artifact_deliveries
        result["artifact_hit_fraction"] = artifact_deliveries / artifact_attempts if artifact_attempts else None
    for outcome in sorted({row["outcome"] for row in requests}):
        result["outcome_" + outcome] = sum(row["outcome"] == outcome for row in requests)
    before = directory / (phase + "-before-adapter.json")
    after = directory / (phase + "-after-adapter.json")
    if before.exists() and after.exists():
        result["anonymous_render_calls"] = json.loads(after.read_text())["render_calls"] - json.loads(before.read_text())["render_calls"]
    return result


def summarize(root):
    status = json.loads((root / "status.json").read_text())
    if status["status"] != "completed" or status["validation"]:
        raise ValueError("only a completed publication campaign can produce publication summaries")
    if status["completed_cells"] != status["total_cells"]:
        raise ValueError("incomplete campaign")
    for name, digest in json.loads((root / "result-inventory.json").read_text()).items():
        if sha256(root / name) != digest:
            raise ValueError("raw evidence digest mismatch: " + name)
    output = root / "summaries"
    output.mkdir(exist_ok=True)
    cells = []
    preparations = []
    ingestions = []
    controls = []
    for repetition in sorted((root / "raw").glob("run-*")):
        ingestion = json.loads((repetition / "ingestion.json").read_text())
        ingestions.append({"repetition": repetition.name,
                           "wall_seconds": (ingestion["end_ns"] - ingestion["start_ns"]) / 1e9,
                           "cpu_seconds": ingestion["after"]["cpu_seconds"] - ingestion["before"]["cpu_seconds"],
                           **ingestion["storage"],
                           **ingestion["objects"]})
        for directory in sorted(repetition.iterdir()):
            if not directory.is_dir() or directory.name == "controls":
                continue
            for file in directory.glob("*-measurement.json"):
                cells.append(cell_row(repetition.name, directory, file.name.removesuffix("-measurement.json")))
            for file in directory.glob("*preparation.json"):
                record = json.loads(file.read_text())
                preparations.append({"repetition": repetition.name, "cell": directory.name, "phase": file.stem,
                                     "wall_seconds": record["wall_ns"] / 1e9,
                                     "server_cpu_seconds": record["resources_after"]["cpu_seconds"] - record["resources_before"]["cpu_seconds"],
                                     "origin_cpu_seconds": record["origin_after"]["cpu_seconds"] - record["origin_before"]["cpu_seconds"],
                                     "origin_executions": record["origin_after"]["executions"] - record["origin_before"]["executions"],
                                     "failures": sum(row["result"] != "success" for row in record["results"]),
                                     "joined": sum(row.get("joined", False) for row in record["results"])})
        for directory in sorted((repetition / "controls").iterdir()):
            result = json.loads((directory / "admission.json").read_text())["results"]
            controls.append({"repetition": repetition.name, "control": directory.name,
                             "submitted": len(result), "joined": sum(row["joined"] for row in result),
                             "joined_fraction": sum(row["joined"] for row in result) / len(result),
                             **{outcome: sum(row["result"] == outcome for row in result) for outcome in {row["result"] for row in result}}})
    groups = defaultdict(list)
    for row in cells:
        groups[(row["workload"], row["scale"], row["mode"], row["phase"])].append(row)
    aggregates = []
    keys = ["origin_cpu_seconds_per_1000_attempts", "origin_executions", "origin_max_concurrency",
            "server_cpu_seconds", "client_cpu_seconds", "delivered_fraction", "delivered_per_second",
            "attempted_per_second", "p50_ms", "p95_ms", "p99_ms", "server_peak_sampled_rss_bytes",
            "total_client_wire_read_bytes", "total_client_wire_written_bytes", "nginx_hit_fraction",
            "artifact_hit_fraction"]
    for group, rows in sorted(groups.items()):
        if len(rows) < 10:
            raise ValueError("fewer than ten measured repetitions for " + str(group))
        for key in keys:
            values = [row[key] for row in rows if row.get(key) is not None]
            if not values:
                continue
            seed = int.from_bytes(hashlib.sha256(repr((group, key)).encode()).digest()[:8])
            lower, upper = interval(values, seed)
            aggregates.append({"workload": group[0], "scale": group[1], "mode": group[2], "phase": group[3], "metric": key,
                               "n": len(values), "mean": statistics.mean(values), "median": statistics.median(values),
                               "standard_deviation": statistics.stdev(values), "ci95_lower": lower, "ci95_upper": upper})
    paired = []
    index = {(row["repetition"], row["workload"], row["scale"], row["mode"], row["phase"]): row for row in cells}
    for row in cells:
        if row["mode"] != "bounded-warm" or row["phase"] != "requests":
            continue
        baseline = index[(row["repetition"], row["workload"], row["scale"], "cgit", "requests")]
        paired.append({"repetition": row["repetition"], "workload": row["workload"], "scale": row["scale"],
                       "origin_cpu_seconds_per_1000_difference": row["origin_cpu_seconds_per_1000_attempts"] - baseline["origin_cpu_seconds_per_1000_attempts"],
                       "delivered_fraction_difference": row["delivered_fraction"] - baseline["delivered_fraction"]})
    for name, rows in [("cells", cells), ("aggregates", aggregates), ("preparation", preparations),
                       ("ingestion", ingestions), ("controls", controls), ("paired", paired)]:
        csv_file(output / (name + ".csv"), rows)
        write_json(output / (name + ".json"), rows)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("results", type=Path)
    summarize(parser.parse_args().results.resolve())
