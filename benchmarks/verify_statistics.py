#!/usr/bin/env python3
"""Independently reconstruct every frozen aggregate, including bootstrap intervals."""

import argparse
from collections import defaultdict
import csv
import hashlib
import json
import math
from pathlib import Path
import random
import statistics


def quantile(values, fraction):
    values = sorted(values)
    position = (len(values) - 1) * fraction
    lower = math.floor(position)
    return values[lower] + (values[min(lower + 1, len(values) - 1)] - values[lower]) * (position - lower)


def verify(root):
    with (root / "cells.csv").open(newline="", encoding="utf-8") as stream:
        cells = list(csv.DictReader(stream))
    with (root / "aggregates.csv").open(newline="", encoding="utf-8") as stream:
        aggregates = list(csv.DictReader(stream))
    groups = defaultdict(list)
    key = lambda r: (r["workload"], int(float(r["scale"])), r["mode"], r["phase"])
    for row in cells:
        groups[key(row)].append(row)
    for row in aggregates:
        group, metric = key(row), row["metric"]
        values = [float(r[metric]) for r in groups[group] if r[metric]]
        if len(values) != int(float(row["n"])) or len(values) != 10:
            raise ValueError("not ten repetitions: " + str(group))
        def check(value, field):
            if not math.isclose(value, float(row[field]), rel_tol=1e-11, abs_tol=1e-7):
                raise ValueError(f"inconsistent {field}: {group}, {metric}")
        check(math.fsum(values) / 10, "mean")
        check(statistics.median(values), "median")
        check(statistics.stdev(values), "standard_deviation")
        if len(set(values)) == 1:
            check(values[0], "ci95_lower")
            check(values[0], "ci95_upper")
            continue
        seed = int.from_bytes(hashlib.sha256(repr((group, metric)).encode()).digest()[:8])
        rng = random.Random(seed)
        means = [math.fsum(rng.choices(values, k=10)) / 10 for _ in range(10000)]
        check(quantile(means, .025), "ci95_lower")
        check(quantile(means, .975), "ci95_upper")
    return {"aggregates_verified": len(aggregates), "groups": len(groups),
            "repetitions_per_group": 10, "resamples": 10000, "passed": True}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("evidence", type=Path)
    print(json.dumps(verify(parser.parse_args().evidence)))
