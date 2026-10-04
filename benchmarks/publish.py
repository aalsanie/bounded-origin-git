#!/usr/bin/env python3
"""Regenerate publication tables and figures from the audited campaign export."""

import argparse
from collections import Counter, defaultdict
import csv
import gzip
import hashlib
import json
from pathlib import Path
import statistics

from summarize import interval, percentile

MODES = ["cgit", "nginx-cold", "nginx-warm", "anubis-d4-solve", "anubis-d5-solve",
         "anubis-d5-nosolve", "bounded-cold", "bounded-warm"]
LABELS = ["cgit", "nginx cold", "nginx warm", "Anubis d4 solve", "Anubis d5 solve",
          "Anubis d5 no solve", "BO cold", "BO prepared"]
COLORS = ["#596579", "#71a9ce", "#266c9e", "#e5ab52", "#b66b17", "#a3988c", "#a583ac", "#087f75"]
TEXT_FIELDS = {"repetition", "workload", "mode", "phase", "metric", "cell", "control"}


def csv_rows(path):
    with path.open(encoding="utf-8", newline="") as stream:
        return [{k: v if k in TEXT_FIELDS else float(v) if v else None for k, v in row.items()}
                for row in csv.DictReader(stream)]


def records(path):
    with gzip.open(path, "rt", encoding="utf-8") as stream:
        for line in stream:
            yield json.loads(line)


def table(headers, rows):
    return "\n".join(["| " + " | ".join(headers) + " |", "| " + " | ".join(["---"] * len(headers)) + " |",
                      *("| " + " | ".join(map(str, row)) + " |" for row in rows)])


def number(value, digits=2):
    return f"{value:,.{digits}f}"


def update_block(path, name, content):
    text = path.read_text(encoding="utf-8")
    begin, end = f"<!-- generated:{name} -->", f"<!-- /generated:{name} -->"
    start, stop = text.index(begin) + len(begin), text.index(end)
    path.write_text(text[:start] + "\n\n" + content + "\n\n" + text[stop:], encoding="utf-8", newline="\n")


class Campaign:
    def __init__(self, root):
        self.root = root
        inventory = json.loads((root / "export-inventory.json").read_text())
        for name, digest in inventory.items():
            with (root / name).open("rb") as stream:
                if hashlib.file_digest(stream, "sha256").hexdigest() != digest:
                    raise ValueError("publication evidence changed: " + name)
        self.cells = csv_rows(root / "cells.csv")
        self.aggregates = {(r["workload"], r["scale"], r["mode"], r["phase"], r["metric"]): r
                           for r in csv_rows(root / "aggregates.csv")}
        self.preparations = csv_rows(root / "preparation.csv")
        self.ingestions = csv_rows(root / "ingestion.csv")
        self.audit = json.loads((root / "audit.json").read_text())
        self.errors = Counter()
        self.comparisons = defaultdict(Counter)
        request_groups = defaultdict(list)
        for row in records(root / "requests.jsonl.gz"):
            if row["cell"].startswith("run-"):
                request_groups[(row["cell"], row["phase"])].append(row)
                if "comparisons-bounded-warm" in row["cell"]:
                    self.comparisons[row["operation"]][row.get("error", row["outcome"])] += 1
                if "error" in row:
                    self.errors[row["error"]] += 1
        if len(request_groups) != 1210:
            raise ValueError("incomplete request-level export")
        for cell in self.cells:
            rows = request_groups[(cell["repetition"] + "/" + cell["cell"], cell["phase"])]
            if len(rows) != cell["attempted"] or sum(r["outcome"] == "delivered" for r in rows) != cell["delivered"]:
                raise ValueError("request-level totals disagree with tables")
            for q in (50, 95, 99):
                actual = percentile([r["latency_ms"] for r in rows], q / 100)
                if abs(actual - cell[f"p{q}_ms"]) > 1e-6:
                    raise ValueError("request-level latency disagrees with tables")
        for key, aggregate in self.aggregates.items():
            values = [r[key[4]] for r in self.rows(*key[:3], phase=key[3]) if r.get(key[4]) is not None]
            if len(values) != 10 or abs(statistics.mean(values) - aggregate["mean"]) > max(1e-7, abs(aggregate["mean"]) * 1e-12):
                raise ValueError("inconsistent repetition-level statistic: " + str(key))

    def rows(self, workload, scale, mode, phase="requests"):
        return [r for r in self.cells if (r["workload"], r["scale"], r["mode"], r["phase"]) == (workload, scale, mode, phase)]

    def mean(self, workload, scale, mode, metric, phase="requests"):
        return statistics.mean(r[metric] for r in self.rows(workload, scale, mode, phase))

    def estimate(self, workload, scale, mode, metric, phase="requests", digits=2):
        row = self.aggregates[(workload, scale, mode, phase, metric)]
        return f'{number(row["mean"], digits)} [{number(row["ci95_lower"], digits)}, {number(row["ci95_upper"], digits)}]'

    def preparation(self, workload, scale, mode):
        return [r for r in self.preparations if r["cell"][4:] == f"n{scale}-{workload}-{mode}" and r["phase"] == "preparation"]


def tables(campaign, repo):
    c = campaign
    hero = table(["Configuration", "Native cgit executions", "Native CPU s / 1,000 attempts (95% CI)", "Delivered", "p95 latency (ms)"],
                 [[label, number(c.mean("unique-crawl", 512, mode, "origin_executions"), 0),
                   c.estimate("unique-crawl", 512, mode, "origin_cpu_seconds_per_1000_attempts", digits=3),
                   number(100 * c.mean("unique-crawl", 512, mode, "delivered_fraction"), 0) + "%",
                   number(c.mean("unique-crawl", 512, mode, "p95_ms"), 1)] for mode, label in zip(MODES, LABELS)])
    update_block(repo / "README.md", "unique", hero)
    unfavorable = table(["Workload / configuration", "Delivered", "Server CPU (s)", "Client CPU (s)", "p95 latency (ms)"],
        [[w + " / " + label,
          number(c.mean(w, n, m, "delivered"), 1) + " / " + number(c.mean(w, n, m, "attempted"), 0),
          number(c.mean(w, n, m, "server_cpu_seconds")), number(c.mean(w, n, m, "client_cpu_seconds")),
          number(c.mean(w, n, m, "p95_ms"), 1)]
         for w, n in [("comparisons", 256), ("human-mix", 256), ("large-plain", 256)]
         for m, label in [("cgit", "cgit"), ("nginx-warm", "nginx warm"), ("bounded-warm", "BO prepared")]])
    update_block(repo / "README.md", "tradeoffs", unfavorable)
    prep_rows = []
    for w in ("repeat-burst", "alias-flood", "unique-crawl"):
        for m, label in [("nginx-warm", "nginx warm"), ("bounded-warm", "BO prepared")]:
            p = c.preparation(w, 512, m)
            prep_rows.append([w + " / " + label, number(statistics.mean(r["origin_executions"] for r in p), 0),
                              number(statistics.mean(r["wall_seconds"] for r in p)),
                              number(statistics.mean(r["origin_cpu_seconds"] for r in p), 3),
                              number(statistics.mean(r["server_cpu_seconds"] for r in p), 3)])
    update_block(repo / "README.md", "preparation", table(["Preparation for 512-request cell", "CGI executions", "Wall time (s)", "Native CPU (s)", "Server CPU (s)"], prep_rows))
    ingestion = c.ingestions
    update_block(repo / "README.md", "ingestion",
        f'Ingesting {int(ingestion[0]["objects"]):,} objects into a fresh store averaged '
        f'**{statistics.mean(r["wall_seconds"] for r in ingestion)/60:.2f} minutes** '
        f'({min(r["wall_seconds"] for r in ingestion)/60:.2f}–{max(r["wall_seconds"] for r in ingestion)/60:.2f}) '
        f'and {statistics.mean(r["cpu_seconds"] for r in ingestion):.2f} server CPU seconds, '
        f'using **{ingestion[0]["file_allocated_bytes"]/1024**3:.3f} GiB** of allocated disk space before page preparation.')
    full = []
    for workload, scale, phase in sorted({(r["workload"], r["scale"], r["phase"]) for r in c.cells}):
        rows = []
        for mode, label in zip(MODES, LABELS):
            if not c.rows(workload, scale, mode, phase):
                continue
            rows.append([label, number(c.mean(workload, scale, mode, "attempted", phase), 0),
                         number(c.mean(workload, scale, mode, "delivered_fraction", phase) * 100, 2) + "%",
                         c.estimate(workload, scale, mode, "origin_cpu_seconds_per_1000_attempts", phase, 3),
                         number(c.mean(workload, scale, mode, "origin_executions", phase), 1),
                         number(c.mean(workload, scale, mode, "server_cpu_seconds", phase), 3),
                         number(c.mean(workload, scale, mode, "client_cpu_seconds", phase), 3),
                         number(c.mean(workload, scale, mode, "p95_ms", phase), 1),
                         number(c.mean(workload, scale, mode, "delivered_per_second", phase), 2)])
        full += [f"### {workload}, scale label {int(scale)}, {phase}\n",
                 table(["Mode", "Attempts", "Delivered", "Native CPU s / 1,000 (95% CI)", "CGI executions", "Server CPU s", "Client CPU s", "p95 ms", "Delivered/s"], rows), ""]
    update_block(repo / "BENCHMARKS.md", "matrix", "\n".join(full))
    memory_rows = []
    for mode, label in zip(MODES, LABELS):
        rows = [r for r in c.cells if r["mode"] == mode]
        memory_rows.append([label, number(max(r["server_peak_sampled_rss_bytes"] for r in rows) / 1024**2, 1),
                            number(max(r["server_cgroup_memory_high_water_bytes"] for r in rows) / 1024**2, 1),
                            number(max(r["client_cgroup_memory_high_water_bytes"] for r in rows) / 1024**2, 1)])
    update_block(repo / "BENCHMARKS.md", "memory", table(["Configuration", "Max sampled server RSS (MiB)", "Max server cgroup high water (MiB)", "Max client cgroup high water (MiB)"], memory_rows))
    bounded = [r for r in c.cells if r["mode"].startswith("bounded")]
    direct = [r for r in c.cells if r["mode"] == "cgit"]
    update_block(repo / "README.md", "resources",
                 f'Peak server memory: **{max(r["server_cgroup_memory_high_water_bytes"] for r in bounded)/1024**2:.1f} MiB for BO**, '
                 f'**{max(r["server_cgroup_memory_high_water_bytes"] for r in direct)/1024**2:.1f} MiB for cgit**; '
                 f'BO client peak: **{max(r["client_cgroup_memory_high_water_bytes"] for r in bounded)/1024**2:.1f} MiB**. '
                 'These are observed cgroup maxima across workloads, including preparation and page cache, excluding ingestion.')
    comparison_rows = []
    for operation, outcomes in sorted(c.comparisons.items()):
        _, _, older, newer, mode = operation.split(":")
        comparison_rows.append([older[:12] + " → " + newer[:12], mode, ", ".join(f"{k}: {v}" for k, v in sorted(outcomes.items()))])
    update_block(repo / "BENCHMARKS.md", "comparisons", table(["Pair (old → new)", "Presentation", "Outcomes over all ten repetitions"], comparison_rows))
    paired = []
    for metric in ("origin_cpu_seconds_per_1000_attempts", "server_cpu_seconds", "client_cpu_seconds", "p95_ms", "delivered_fraction"):
        for w, n in [("unique-crawl", 512), ("repeat-burst", 512), ("comparisons", 256), ("human-mix", 256)]:
            a = {r["repetition"]: r[metric] for r in c.rows(w, n, "bounded-warm")}
            b = {r["repetition"]: r[metric] for r in c.rows(w, n, "cgit")}
            values = [a[r] - b[r] for r in sorted(a)]
            seed = int.from_bytes(hashlib.sha256((w + metric).encode()).digest()[:8])
            low, high = interval(values, seed)
            paired.append({"workload": w, "scale": n, "metric": metric, "n": 10,
                           "mean_difference": statistics.mean(values), "ci95_lower": low, "ci95_upper": high})
    path = c.root / "generated"
    path.mkdir(exist_ok=True)
    (path / "paired-effects.json").write_text(json.dumps(paired, indent=2) + "\n", encoding="utf-8", newline="\n")
    update_block(repo / "BENCHMARKS.md", "paired", table(["Workload", "BO prepared − cgit", "Mean difference [95% CI]"],
                 [[r["workload"], r["metric"], f'{number(r["mean_difference"], 3)} [{number(r["ci95_lower"], 3)}, {number(r["ci95_upper"], 3)}]'] for r in paired]))


def figures(c):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    import numpy as np
    from matplotlib.colors import LinearSegmentedColormap
    plt.rcParams.update({"font.family": "DejaVu Sans", "font.size": 10, "axes.spines.top": False,
                         "axes.spines.right": False, "axes.titleweight": "bold", "figure.facecolor": "#ffffff",
                         "axes.facecolor": "#ffffff", "text.color": "#172735", "axes.labelcolor": "#172735",
                         "svg.hashsalt": "bounded-origin-git-2026-10-03", "svg.fonttype": "none"})
    out = c.root / "generated"
    out.mkdir(exist_ok=True)
    def save(fig, name, description):
        svg = out / (name + ".svg")
        with svg.open("w", encoding="utf-8", newline="\n") as stream:
            fig.savefig(stream, format="svg", bbox_inches="tight", metadata={"Date": None, "Description": description})
        svg.write_text("\n".join(line.rstrip() for line in svg.read_text(encoding="utf-8").splitlines()) + "\n",
                       encoding="utf-8", newline="\n")
        fig.savefig(out / (name + ".png"), bbox_inches="tight", dpi=145, metadata={"Description": description})
        plt.close(fig)

    fig, axes = plt.subplots(1, 2, figsize=(12, 4.7), gridspec_kw={"width_ratios": [2, 1]})
    ys = np.arange(8)
    for ax, metric, title, multiplier in [(axes[0], "origin_cpu_seconds_per_1000_attempts", "Native cgit CPU seconds / 1,000 attempts", 1),
                                           (axes[1], "delivered_fraction", "Requested content delivered", 100)]:
        values = [c.mean("unique-crawl", 512, m, metric) * multiplier for m in MODES]
        ax.barh(ys, values, color=COLORS, height=.66)
        ax.set_yticks(ys, LABELS if ax is axes[0] else [""] * 8)
        ax.invert_yaxis()
        ax.set_title(title, loc="left", fontsize=11, pad=12)
        ax.set_xlim(0, max(values) * 1.25)
        if multiplier == 100:
            ax.set_xticks([0, 25, 50, 75, 100])
        ax.grid(axis="x", alpha=.15)
        ax.set_axisbelow(True)
        for y, value, mode in zip(ys, values, MODES):
            text = f"{value:.2f}" if multiplier == 1 else f"{value:.0f}%"
            ax.text(value + max(values) * .025, y, text, va="center", fontsize=10)
            if multiplier == 1:
                a = c.aggregates[("unique-crawl", 512, mode, "requests", metric)]
                ax.errorbar(value, y, xerr=[[max(0, value - a["ci95_lower"])], [max(0, a["ci95_upper"] - value)]],
                            color="#152433", capsize=3, linewidth=1)
    fig.suptitle("512 distinct commit-page requests · 10 measured repetitions", x=.01, ha="left", fontsize=15, fontweight="bold")
    fig.text(.01, -.01, "BO = Bounded Origin Git. Prepared work is charged separately. Cold misses and unsolved challenges deliver no Git content.", fontsize=9)
    fig.tight_layout()
    save(fig, "origin-and-delivery", "All eight configurations; means and 95% bootstrap confidence intervals over repetitions.")

    fig, axes = plt.subplots(1, 3, figsize=(12, 3.8), sharey=True)
    line_modes = [("cgit", "cgit / solving Anubis", "#596579", "o"), ("nginx-cold", "nginx cold", "#71a9ce", "s"),
                  ("nginx-warm", "nginx warm", "#266c9e", "^"), ("bounded-warm", "BO prepared", "#087f75", "D")]
    for ax, w in zip(axes, ("repeat-burst", "alias-flood", "unique-crawl")):
        for mode, label, color, marker in line_modes:
            values = [c.mean(w, n, mode, "origin_executions") for n in [32, 128, 512]]
            if mode == "cgit":
                for solved in ("anubis-d4-solve", "anubis-d5-solve"):
                    assert values == [c.mean(w, n, solved, "origin_executions") for n in [32, 128, 512]]
            ax.plot([32, 128, 512], values, label=label, color=color, marker=marker, linewidth=2,
                    linestyle="--" if mode == "bounded-warm" else "-")
        ax.set_title(w.replace("-", " "), loc="left")
        ax.set_xticks([32, 128, 512])
        ax.set_xlabel("Anonymous requests")
        ax.grid(alpha=.18)
    axes[0].set_ylabel("Native cgit executions during requests")
    fig.legend(*axes[0].get_legend_handles_labels(), loc="lower center", ncol=4, bbox_to_anchor=(.5, -.08), frameon=False)
    fig.suptitle("Origin executions across the three tested request counts", x=.01, ha="left", fontsize=14, fontweight="bold")
    fig.tight_layout()
    save(fig, "scaling", "Ten repetitions per point; counts are identical across repetitions. BO cold and unsolved Anubis also execute zero but deliver zero here.")

    specs = [("repeat-burst", 512, "requests", "Repeated / 512"), ("alias-flood", 512, "requests", "Aliases / 512"),
             ("unique-crawl", 512, "requests", "Unique / 512"), ("comparisons", 256, "requests", "Comparisons / 64"),
             ("human-mix", 256, "requests", "Human mix / 40"), ("large-plain", 256, "requests", "Large plain / 32"),
             ("session-churn", 256, "requests", "Session churn / 128"), ("ref-update", 256, "after-publication", "After ref publication / 32")]
    data = np.array([[100 * c.mean(w, n, m, "delivered_fraction", phase) for m in MODES] for w, n, phase, _ in specs])
    fig, ax = plt.subplots(figsize=(12, 5.3))
    ax.imshow(data, aspect="auto", cmap=LinearSegmentedColormap.from_list("delivery", ["#f8e1d6", "#fcf3d0", "#b3ded7"]), vmin=0, vmax=100)
    ax.set_xticks(range(8), ["cgit", "nginx\ncold", "nginx\nwarm", "Anubis\nd4 solve", "Anubis\nd5 solve", "Anubis\nno solve", "BO\ncold", "BO\nprepared"])
    ax.set_yticks(range(len(specs)), [s[3] for s in specs])
    for i in range(len(specs)):
        for j in range(8):
            ax.text(j, i, f"{data[i,j]:g}%", ha="center", va="center", fontsize=10)
    ax.set_title("Delivery includes every attempt, including rejection and stale content", loc="left", pad=16)
    fig.text(.01, -.005, "Means of ten repetitions. BO delivers the new ref after trusted preparation; nginx uses the recorded one-hour TTL without purge.", fontsize=9)
    fig.tight_layout()
    save(fig, "delivery", "Complete delivery matrix at largest scaled workloads, including comparison budget failures and the mutable-ref control.")

    modes = ["cgit", "nginx-warm", "bounded-warm"]
    fig, axes = plt.subplots(1, 3, figsize=(12, 3.8))
    for ax, metric, title in zip(axes, ("server_cpu_seconds", "client_cpu_seconds", "p95_ms"),
                               ("Total server CPU / cell (s)", "Total client CPU / cell (s)", "p95 request latency (seconds)")):
        divisor = 1000 if metric == "p95_ms" else 1
        values = [c.mean("comparisons", 256, m, metric) / divisor for m in modes]
        ax.bar(range(3), values, color=[COLORS[MODES.index(m)] for m in modes], width=.62)
        ax.set_xticks(range(3), ["cgit\n64/64", "nginx warm\n64/64", "BO prepared\n28/64"])
        ax.set_title(title, loc="left", fontsize=11)
        ax.set_ylim(0, max(values) * 1.2)
        for x, value in enumerate(values):
            ax.text(x, value + max(values) * .025, f"{value:.2f}", ha="center")
        ax.grid(axis="y", alpha=.15)
        ax.set_axisbelow(True)
    fig.suptitle("Comparison tradeoff · 64 attempts per cell, including all budget failures", x=.01, ha="left", fontsize=14, fontweight="bold")
    fig.text(.01, -.005, "Means over ten repetitions. Labels show delivered/attempted. These bars compare cost and coverage, not equivalent successful work.", fontsize=9)
    fig.tight_layout()
    save(fig, "comparison-tradeoff", "Bounded client comparison saves origin work but delivers only 43.75% of this comparison matrix and has a high latency tail.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("evidence", type=Path)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--tables-only", action="store_true")
    args = parser.parse_args()
    campaign = Campaign(args.evidence)
    tables(campaign, args.repo)
    if not args.tables_only:
        figures(campaign)
    print("Verified request totals, percentiles and aggregate means; generated publication material.")


if __name__ == "__main__":
    main()
