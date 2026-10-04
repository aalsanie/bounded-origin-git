#!/usr/bin/env python3
"""Build a deterministic request catalogue from a pinned, local Linux Git slice."""

import argparse
import hashlib
import json
import subprocess
import time
from pathlib import Path
from urllib.parse import quote, urlencode

from common import sha256, write_json

PINNED_LINUX = "adc218676eef25575469234709c2d87185ca223a"
REPOSITORIES = ["linux", "linux-fork-1", "linux-fork-2", "linux-fork-3"]


def git(repository, *arguments):
    return subprocess.check_output(["git", "--git-dir=" + str(repository), *arguments])


def commit_request(repository, commit, alias=0):
    if alias % 4 == 0:
        target = f"/{repository}/commit?id={commit}"
    elif alias % 4 == 1:
        target = "/?" + urlencode({"r": repository, "p": "commit", "id": commit})
    elif alias % 4 == 2:
        target = "/?" + urlencode({"id": commit, "p": "commit", "r": repository})
    else:
        target = "/?" + urlencode({"url": repository + "/commit", "id": commit})
    return {"kind": "commit", "repository": repository, "target": target,
            "semantic": f"{repository}:commit:{commit}", "expected_oid": commit}


def raw_changes(dataset, older, newer):
    fields = git(dataset, "diff", "--raw", "--no-renames", "--no-abbrev", "-z", older, newer).split(b"\0")
    results = []
    for index in range(0, len(fields) - 1, 2):
        header = fields[index].decode("ascii").split()
        results.append({"path": fields[index + 1].decode("utf8"),
                        "oldMode": header[0][1:] if header[0] != ":000000" else None,
                        "newMode": header[1] if header[1] != "000000" else None,
                        "oldOid": header[2] if set(header[2]) != {"0"} else None,
                        "newOid": header[3] if set(header[3]) != {"0"} else None})
    return results


def build_manifest(dataset, allow_fixture=False):
    started = time.monotonic()
    head = git(dataset, "rev-parse", "HEAD").decode().strip()
    if not allow_fixture and head != PINNED_LINUX:
        raise ValueError("dataset is not the pinned Linux v6.12 revision")
    available = set(git(dataset, "rev-list", "HEAD").decode().splitlines())
    shallow = dataset / "shallow"
    boundaries = set(shallow.read_text().splitlines()) if shallow.exists() else set()
    candidates = git(dataset, "rev-list", "--no-merges", "--max-count=512", "HEAD").decode().splitlines()
    commits = []
    parents = {}
    for commit in candidates:
        if commit in boundaries:
            continue
        header = git(dataset, "cat-file", "commit", commit).split(b"\n\n", 1)[0]
        parent = next((line.split()[1].decode() for line in header.splitlines() if line.startswith(b"parent ")), None)
        if parent in available:
            commits.append(commit)
            parents[commit] = parent
        if len(commits) == 128:
            break
    if len(commits) < (2 if allow_fixture else 128):
        raise ValueError("insufficient complete commit/parent pairs")
    trees = git(dataset, "ls-tree", "-r", "-l", "-z", head).split(b"\0")
    large = []
    for entry in trees:
        if not entry:
            continue
        metadata, path = entry.split(b"\t", 1)
        mode, kind, oid, size = metadata.split()
        if kind == b"blob" and size != b"-":
            large.append((int(size), path.decode("utf8"), oid.decode()))
    eligible = [entry for entry in large if entry[0] <= 4 * 1024 * 1024]
    size, path, blob = sorted(eligible, key=lambda entry: (-entry[0], entry[1]))[0]
    plain = {"kind": "plain", "repository": "linux", "semantic": f"linux:plain:{head}:{path}",
             "target": "/linux/plain/" + quote(path, safe="/") + "?id=" + head,
             "expected_sha256": hashlib.sha256(git(dataset, "cat-file", "blob", blob)).hexdigest(),
             "blob": blob, "payload_bytes": size}
    comparisons = []
    for index, newer in enumerate(commits[:16]):
        older = parents[newer] if index < 8 else commits[(index + 5) % len(commits)]
        changes = raw_changes(dataset, older, newer)
        comparisons.append({"kind": "comparison", "repository": "linux",
                            "target": f"/linux/diff?id={newer}&id2={older}&dt={index % 3}",
                            "semantic": f"linux:diff:{older}:{newer}:mode{index % 3}",
                            "expected_oid": newer, "expected_paths": [change["path"] for change in changes],
                            "expected_changes": changes, "older": older, "newer": newer})
    update_old = parents[head] if head in parents else parents[commits[0]]
    update_new = head if head in parents else commits[0]
    update_paths = git(dataset, "diff", "--no-renames", "--name-only", "-z", update_old, update_new).split(b"\0")
    update_path = None
    for value in update_paths:
        if not value:
            continue
        decoded = value.decode("utf8")
        try:
            before = git(dataset, "show", f"{update_old}:{decoded}")
            after = git(dataset, "show", f"{update_new}:{decoded}")
        except subprocess.CalledProcessError:
            continue
        if before != after and len(before) < 4 * 1024 * 1024 and len(after) < 4 * 1024 * 1024:
            update_path = decoded
            break
    if update_path is None:
        raise ValueError("no shared file for ref-update correctness probe")
    update_target = "/linux/plain/" + quote(update_path, safe="/") + "?h=main"
    update = {"older": update_old, "newer": update_new, "path": update_path,
              "before": {"kind": "plain", "repository": "linux", "semantic": "linux:ref-before",
                         "target": update_target, "expected_sha256": hashlib.sha256(before).hexdigest()},
              "after": {"kind": "plain", "repository": "linux", "semantic": "linux:ref-after",
                        "target": update_target, "expected_sha256": hashlib.sha256(after).hexdigest()}}
    files = sorted(path for path in dataset.rglob("*") if path.is_file())
    inventory = [{"path": str(path.relative_to(dataset)), "bytes": path.stat().st_size,
                  "sha256": sha256(path)} for path in files]
    return {"schema": 1, "head": head, "tag": "v6.12" if not allow_fixture else "fixture",
            "history": "single bare shallow clone, depth 32; complete trees, selected non-boundary commits",
            "commit_count": len(available), "shallow_boundaries": sorted(boundaries),
            "selected_commits": commits, "parents": parents,
            "repositories": REPOSITORIES, "large_plain": plain, "comparisons": comparisons,
            "update": update, "object_count": int(git(dataset, "cat-file", "--batch-all-objects", "--batch-check=%(objectname)").count(b"\n")),
            "inventory": inventory, "preparation_seconds": time.monotonic() - started}


def workload(manifest, name, scale, seed):
    import random
    generator = random.Random(seed)
    commits = manifest["selected_commits"]
    repos = manifest["repositories"]
    catalog = [commit_request(repo, commit) for repo in repos for commit in commits]
    if name == "human-mix":
        choices = [*catalog[:16], *manifest["comparisons"][:4], manifest["large_plain"]]
        requests = [dict(generator.choice(choices)) for _ in range(min(scale, 40))]
        concurrency, interval, session = 2, 250, 0
    elif name == "repeat-burst":
        requests = [dict(catalog[index % 4]) for index in range(scale)]
        concurrency, interval, session = 16, 0, 0
    elif name == "alias-flood":
        requests = [commit_request(repos[index % len(repos)], commits[index % 4], index // len(repos))
                    for index in range(scale)]
        concurrency, interval, session = 16, 0, 0
    elif name == "unique-crawl":
        shuffled = list(catalog)
        generator.shuffle(shuffled)
        requests = [dict(entry) for entry in shuffled[:min(scale, len(shuffled))]]
        concurrency, interval, session = 16, 0, 0
    elif name == "comparisons":
        requests = [dict(manifest["comparisons"][index % len(manifest["comparisons"])]) for index in range(min(scale, 64))]
        concurrency, interval, session = 4, 0, 0
    elif name == "large-plain":
        requests = [dict(manifest["large_plain"]) for _ in range(min(scale, 32))]
        concurrency, interval, session = 8, 0, 0
    elif name == "session-churn":
        requests = [dict(catalog[index % 32]) for index in range(min(scale, 128))]
        concurrency, interval, session = 8, 0, 4
    elif name == "ref-update":
        requests = [dict(manifest["update"]["before"]) for _ in range(min(scale, 32))]
        concurrency, interval, session = 4, 0, 0
    else:
        raise ValueError("unknown workload")
    for index, request in enumerate(requests):
        request["index"] = index
    canonical = {}
    for request in requests:
        if request["kind"] == "commit":
            entry = commit_request(request["repository"], request["expected_oid"])
        else:
            entry = request
        canonical[request["semantic"]] = entry
    return {"name": name, "scale": scale, "seed": seed, "requests": requests,
            "prepare": list(canonical.values()), "concurrency": concurrency,
            "interval_ms": interval, "session_requests": session}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("dataset", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--allow-fixture", action="store_true")
    args = parser.parse_args()
    write_json(args.output, build_manifest(args.dataset.resolve(), args.allow_fixture))
