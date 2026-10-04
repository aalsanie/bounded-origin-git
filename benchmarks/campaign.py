#!/usr/bin/env python3
"""Run the isolated, repeated cgit/Anubis/cache/Bounded Origin campaign."""

import argparse
import json
import os
import platform
import random
import re
import secrets
import shutil
import signal
import subprocess
import sys
import time
import traceback
import urllib.request
from collections import Counter
from pathlib import Path
from urllib.parse import urlsplit

from common import Group, Process, Sampler, file_footprint, origin_summary, read_jsonl, sha256, wait_ready, write_json
from prepare import git, workload

MODES = ["cgit", "nginx-cold", "nginx-warm", "anubis-d4-solve", "anubis-d5-solve",
         "anubis-d5-nosolve", "bounded-cold", "bounded-warm"]
WORKLOADS = ["human-mix", "repeat-burst", "alias-flood", "unique-crawl", "comparisons",
             "large-plain", "session-churn", "ref-update"]
SCALED_WORKLOADS = {"repeat-burst", "alias-flood", "unique-crawl"}
SERVICE_PORTS = {"nginx": 18080, "anubis": 18081, "metrics": 18082}


def campaign_cells(config):
    modes = config.get("modes", MODES)
    workloads = config.get("workloads", WORKLOADS)
    if not modes or not workloads:
        raise ValueError("campaign must contain modes and workloads")
    default = config.get("requests", 256)
    scales = config.get("request_scales", [32, 128, 512])
    if not scales or any(type(size) is not int or size < 1 or size > 2048 for size in [default, *scales]):
        raise ValueError("request counts must be between 1 and 2048")
    if len(set(scales)) != len(scales) or len(set(modes)) != len(modes) or len(set(workloads)) != len(workloads):
        raise ValueError("duplicate campaign configurations")
    if set(modes) - set(MODES) or set(workloads) - set(WORKLOADS):
        raise ValueError("unknown campaign configuration")
    return [(name, mode, size) for name in workloads
            for size in (scales if name in SCALED_WORKLOADS else [default]) for mode in modes]


def verify_outcomes(rows, expected_stale_digest=None):
    for row in rows:
        if row["outcome"] == "client_error":
            raise AssertionError("unexpected client/protocol error")
        if row["outcome"] == "semantic_mismatch":
            if expected_stale_digest is None or row.get("body_sha256") != expected_stale_digest:
                raise AssertionError("response semantic validation failed")


def request(url, data=None, token=None, timeout=180):
    headers = {"User-Agent": "benchmark-control"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=data.encode() if data is not None else None, headers=headers)
    with urllib.request.urlopen(req, timeout=timeout) as response:
        return response.read()


def isolate_network():
    previous = os.readlink("/proc/self/ns/net")
    os.unshare(os.CLONE_NEWNET)
    subprocess.run(["ip", "link", "set", "lo", "up"], check=True, capture_output=True)
    current = os.readlink("/proc/self/ns/net")
    low, high = map(int, Path("/proc/sys/net/ipv4/ip_local_port_range").read_text().split())
    if current == previous or any(low <= port <= high for port in SERVICE_PORTS.values()):
        raise RuntimeError("campaign requires a private network and service ports outside the ephemeral range")
    return {"parent": previous, "campaign": current, "service_ports": SERVICE_PORTS,
            "ephemeral_range": [low, high]}


def properties(path, values):
    for key, value in values.items():
        if any(character in str(value) for character in "\r\n\\"):
            raise ValueError("property value cannot be represented safely: " + key)
    Path(path).write_text("".join(f"{key}={value}\n" for key, value in values.items()))
    Path(path).chmod(0o600)


def plan_text(entries):
    lines = []
    for entry in entries:
        url = urlsplit(entry["target"])
        lines.append(url.path + "\t" + url.query)
    return "\n".join(lines) + "\n"


def wrapper(directory, output, config, fault=None):
    configuration = {"events": str(output / "origin.jsonl"), "cgit": config["cgit"],
                     "library_path": config["cgit_library_path"], **(fault or {})}
    path = directory / "cgi.json"
    write_json(path, configuration)
    executable = directory / "cgit-wrapper"
    executable.write_text("#!/bin/sh\nexec /usr/bin/python3 " +
                          shell_quote(str(Path(__file__).with_name("cgi_meter.py"))) + " " +
                          shell_quote(str(path)) + "\n")
    executable.chmod(0o755)
    return executable


def shell_quote(value):
    return "'" + value.replace("'", "'\"'\"'") + "'"


def java_command(config, command, file):
    return [config["java"], "-Xms128m", "-Xmx768m", "-XX:ActiveProcessorCount=2",
            "-XX:+UseG1GC", "-cp", str(Path(config["bundle"]) / "classes") + ":" +
            str(Path(config["bundle"]) / "lib" / "*"),
            "io.github.aalsanie.boundedorigingit.benchmark.BenchmarkApplication", command, str(file)]


def bare_views(root, dataset, repositories, head):
    views = {}
    for name in repositories:
        path = root / name
        path.mkdir()
        (path / "objects" / "info").mkdir(parents=True)
        (path / "refs" / "heads").mkdir(parents=True)
        (path / "objects" / "info" / "alternates").write_text(str(dataset / "objects") + "\n")
        (path / "HEAD").write_text("ref: refs/heads/main\n")
        (path / "config").write_text("[core]\nrepositoryformatversion = 0\nbare = true\n")
        (path / "refs" / "heads" / "main").write_text(head + "\n")
        if (dataset / "shallow").exists():
            shutil.copyfile(dataset / "shallow", path / "shallow")
        views[name] = path
    return views


def wait_http(url, process, expected=None):
    last_error = None
    for _ in range(200):
        process.ensure_running()
        try:
            body = request(url, timeout=1)
            if expected is not None and body != expected:
                raise ValueError("unexpected readiness response: " + url)
            process.ensure_running()
            return body
        except (OSError, urllib.error.URLError) as error:
            last_error = str(error)
            time.sleep(0.05)
    raise TimeoutError(f"HTTP service did not become ready: {url}: {last_error}")


def nginx_config(path, root, upstream, port):
    path.write_text(f"""daemon off;
master_process off;
worker_processes 1;
error_log {root}/nginx-error.log warn;
pid {root}/nginx.pid;
events {{ worker_connections 512; }}
http {{
  access_log off;
  client_body_temp_path {root}/client-temp;
  fastcgi_temp_path {root}/fastcgi-temp;
  uwsgi_temp_path {root}/uwsgi-temp;
  scgi_temp_path {root}/scgi-temp;
  proxy_temp_path {root}/proxy-temp;
  proxy_cache_path {root}/cache levels=1:2 keys_zone=html:16m max_size=2g inactive=1h use_temp_path=off;
  server {{
    listen 127.0.0.1:{port};
    location / {{
      proxy_pass http://127.0.0.1:{upstream};
      proxy_http_version 1.1;
      proxy_set_header Connection "";
      proxy_cache html;
      proxy_cache_key $request_uri;
      proxy_cache_valid 200 1h;
      proxy_cache_lock on;
      proxy_cache_lock_timeout 60s;
      proxy_cache_lock_age 60s;
      proxy_read_timeout 60s;
      proxy_ignore_headers Cache-Control Expires Set-Cookie;
      proxy_hide_header Cache-Control;
      proxy_hide_header Expires;
      add_header Cache-Control public;
      add_header X-Benchmark-Cache $upstream_cache_status always;
      proxy_buffers 8 64k;
      proxy_buffer_size 64k;
      proxy_busy_buffers_size 128k;
      proxy_max_temp_file_size 128m;
      gzip off;
    }}
  }}
}}
""")


class Cell:
    def __init__(self, config, manifest, work, output, objects, mode, head, fault=None, heartbeat=None):
        self.config, self.manifest, self.work, self.output = config, manifest, work, output
        self.mode = mode
        self.heartbeat = heartbeat
        self.processes = []
        self.services = []
        self.server = Group(f"bounded-origin-git-{os.getpid()}-{secrets.token_hex(4)}-server", [0, 1], 4 * 1024 ** 3)
        self.client = Group(f"bounded-origin-git-{os.getpid()}-{secrets.token_hex(4)}-client", [2, 3], 2 * 1024 ** 3)
        self.token = secrets.token_hex(32)
        self.control = None
        self.admin = None
        self.metrics = None
        self.assets = None
        self.views = None
        self.head = head
        work.mkdir(parents=True)
        output.mkdir(parents=True)
        self.cgi = wrapper(work, output, config, fault)

    def spawn(self, command, name, group=None, env=None):
        process = Process(command, group or self.server, self.output, name, env)
        self.processes.append(process)
        if group is None or group is self.server:
            self.services.append(process)
        return process

    def check_services(self):
        for process in self.services:
            process.ensure_running()

    def failure(self, error):
        processes = {}
        for process in self.processes:
            process.observe()
        for pid in self.server.members() | self.client.members():
            values = {}
            for name in ("status", "stat", "io", "limits"):
                try:
                    values[name] = Path(f"/proc/{pid}/{name}").read_text()
                except (FileNotFoundError, ProcessLookupError):
                    pass
            processes[pid] = values
        diagnostics = {}
        for name, command in {"listeners": ["ss", "-lntp"], "kernel": ["dmesg", "--ctime"]}.items():
            result = subprocess.run(command, capture_output=True, text=True, timeout=10)
            diagnostics[name] = {"exit_code": result.returncode,
                                 "output": "\n".join((result.stdout + result.stderr).splitlines()[-200:])}
        write_json(self.output / "failure.json", {"error": repr(error), "traceback": traceback.format_exc(),
            "unix_ns": time.time_ns(), "boot_id": Path("/proc/sys/kernel/random/boot_id").read_text().strip(),
            "network_namespace": os.readlink("/proc/self/ns/net"), "server": self.server.snapshot(),
            "client": self.client.snapshot(), "processes": processes, "diagnostics": diagnostics,
            "meminfo": Path("/proc/meminfo").read_text(), "vmstat": Path("/proc/vmstat").read_text()})

    def start(self, objects):
        started = time.monotonic()
        if self.mode.startswith("bounded"):
            values = {"root": self.work / "bounded", "objects": objects,
                      "token": self.token, "repositories": ",".join(self.manifest["repositories"]),
                      "head": self.head, "cgi.wrapper": self.cgi,
                      "client.modules": Path(self.config["bundle"]) / "classes/io/github/aalsanie/boundedorigingit/client"}
            file = self.work / "application.properties"
            properties(file, values)
            process = self.spawn(java_command(self.config, "serve", file), "gateway")
            ready = wait_ready(self.work / "bounded" / "ready.json", process, 60)
            self.base = f"http://127.0.0.1:{ready['gateway']}"
            self.assets = f"http://127.0.0.1:{ready['assets']}"
            self.control = f"http://127.0.0.1:{ready['control']}"
            self.admin = f"http://127.0.0.1:{ready['admin']}"
            wait_http(self.admin + "/ready", process)
        else:
            self.views = bare_views(self.work, Path(self.config["dataset"]), self.manifest["repositories"], self.head)
            cgit_config = self.work / "cgitrc"
            content = "cache-size=0\nenable-http-clone=0\nenable-index-owner=0\n"
            for name, view in self.views.items():
                content += f"repo.url={name}\nrepo.path={view}\n"
            cgit_config.write_text(content)
            transport = self.work / "transport.json"
            write_json(transport, {"cgit_config": str(cgit_config), "root": str(self.work),
                                  "wrapper": str(self.cgi), "ready": str(self.work / "origin-ready.json"),
                                  "requests": str(self.output / "origin-http.jsonl")})
            process = self.spawn(["/usr/bin/python3", str(Path(__file__).with_name("origin.py")), str(transport)], "origin")
            ready = wait_ready(self.work / "origin-ready.json", process)
            self.base = f"http://127.0.0.1:{ready['port']}"
            wait_http(self.base + "/health", process)
            if self.mode.startswith("nginx"):
                port = SERVICE_PORTS["nginx"]
                file = self.work / "nginx.conf"
                nginx_config(file, self.work, ready["port"], port)
                shutil.copyfile(file, self.output / "nginx.conf")
                process = self.spawn([self.config["nginx"], "-p", str(self.work) + "/", "-c", str(file)], "nginx")
                self.base = f"http://127.0.0.1:{port}"
                wait_http(self.base + "/health", process)
            elif self.mode.startswith("anubis"):
                difficulty = 4 if "d4" in self.mode else 5
                port, metrics = SERVICE_PORTS["anubis"], SERVICE_PORTS["metrics"]
                policy = self.work / "anubis.yaml"
                policy.write_text(f"bots:\n  - name: local-browser-workload\n    user_agent_regex: Mozilla\n    action: CHALLENGE\n    challenge:\n      difficulty: {difficulty}\n      algorithm: fast\n")
                shutil.copyfile(policy, self.output / "anubis.yaml")
                process = self.spawn([self.config["anubis"], "--bind", f"127.0.0.1:{port}",
                                      "--metrics-bind", f"127.0.0.1:{metrics}", "--target", self.base,
                                      "--policy-fname", str(policy), "--difficulty", str(difficulty),
                                      "--cookie-secure=false", "--use-remote-address=true", "--slog-level=INFO"], "anubis")
                self.base = f"http://127.0.0.1:{port}"
                self.metrics = f"http://127.0.0.1:{metrics}"
                wait_http(self.metrics + "/healthz", process, b"OK\n")
                wait_http(self.base + "/health", process, b"ok")
        self.check_services()
        write_json(self.output / "endpoints.json", {"base": self.base, "metrics": self.metrics,
                   "assets": self.assets, "control": self.control, "admin": self.admin})
        write_json(self.output / "startup.json", {"wall_seconds": time.monotonic() - started,
                                                   "resources": self.server.snapshot()})

    def metrics_snapshot(self, name):
        if self.control:
            result = json.loads(request(self.control + "/stats", token=self.token))
            write_json(self.output / (name + "-adapter.json"), result)
            (self.output / (name + "-gateway.prom")).write_bytes(request(self.admin + "/metrics"))
        if self.metrics:
            (self.output / (name + "-anubis.prom")).write_bytes(request(self.metrics + "/metrics"))

    def prepare(self, entries, name="preparation", concurrent=False):
        start = time.monotonic_ns()
        resources = self.server.snapshot()
        before = origin_summary(self.output / "origin.jsonl")
        if self.control:
            result = json.loads(request(self.control + ("/flood" if concurrent else "/prepare"),
                                        plan_text(entries), self.token,
                                        timeout=max(180, len(entries) * 6 + 30)))
            deadline = time.monotonic() + 30
            while json.loads(request(self.control + "/stats", token=self.token))["in_flight"]:
                if time.monotonic() > deadline:
                    raise AssertionError("materializer did not finish cleanup")
                time.sleep(0.05)
        else:
            result = []
            for entry in entries:
                try:
                    body = request(self.base + entry["target"])
                    result.append({"result": "success", "bytes": len(body)})
                except urllib.error.HTTPError as error:
                    result.append({"result": "http_error", "status": error.code})
        after = origin_summary(self.output / "origin.jsonl")
        record = {"wall_ns": time.monotonic_ns() - start, "resources_before": resources,
                  "resources_after": self.server.snapshot(), "origin_before": before,
                  "origin_after": after, "results": result}
        write_json(self.output / (name + ".json"), record)
        return result

    def publish(self, newer):
        if self.control:
            result = request(self.control + "/publish", "\n".join(name + "\t" + newer for name in self.manifest["repositories"]), self.token)
            (self.output / "publication.json").write_bytes(result)
        else:
            for view in self.views.values():
                target = view / "refs/heads/main"
                temporary = target.with_suffix(".new")
                temporary.write_text(newer + "\n")
                temporary.replace(target)

    def measure(self, plan, phase="requests"):
        self.check_services()
        self.metrics_snapshot(phase + "-before")
        server_before = self.server.snapshot()
        client_before = self.client.snapshot()
        origin_before = origin_summary(self.output / "origin.jsonl")
        configuration = {"base": self.base, "assets": self.assets, "bounded": self.control is not None,
                         "solve": self.mode.endswith("-solve"), "requests": plan["requests"],
                         "concurrency": plan["concurrency"], "interval_ms": plan["interval_ms"],
                         "session_requests": plan["session_requests"], "directory": str(self.work),
                         "summary": str(self.output / (phase + "-client-summary.json")), "timeout_ms": 600000}
        file = self.work / (phase + "-client.json")
        write_json(file, configuration)
        start = time.monotonic_ns()
        process = self.spawn([self.config["node"], str(Path(__file__).with_name("client.mjs")), str(file)], phase, self.client)
        with Sampler(self.server, self.output / (phase + "-resources.jsonl")):
            process.wait(660, self.heartbeat)
        stop = time.monotonic_ns()
        self.check_services()
        self.metrics_snapshot(phase + "-after")
        rows = read_jsonl(process.stdout_path)
        if len(rows) != len(plan["requests"]):
            raise ValueError("missing request outcomes")
        if {row["index"] for row in rows} != {entry["index"] for entry in plan["requests"]}:
            raise ValueError("duplicate or mismatched request indexes")
        outcomes = Counter(row["outcome"] for row in rows)
        after = origin_summary(self.output / "origin.jsonl")
        origin_delta = {key: after[key] - origin_before[key] for key in after if key != "max_concurrency"}
        record = {"start_ns": start, "end_ns": stop, "request_count": len(rows), "outcomes": dict(outcomes),
                  "server_before": server_before, "server_after": self.server.snapshot(),
                  "client_before": client_before, "client_after": self.client.snapshot(),
                  "origin_before": origin_before, "origin_after": after, "origin_delta": origin_delta}
        write_json(self.output / (phase + "-measurement.json"), record)
        if self.control and origin_delta["executions"] != 0:
            raise AssertionError("anonymous HTTP caused native cgit execution")
        if record["server_after"]["memory_failures"] or record["client_after"]["memory_failures"]:
            raise AssertionError("a memory quota was exceeded")
        return rows

    def close(self):
        errors = []
        for process in reversed(self.processes):
            try:
                process.close()
            except Exception as error:
                errors.append(repr(error))
        groups = {}
        for name, group in (("client", self.client), ("server", self.server)):
            try:
                groups[name] = group.close()
                errors.extend(groups[name]["errors"])
                if groups[name]["remaining_members"]:
                    errors.append(name + " cgroup retained processes")
            except Exception as error:
                errors.append(repr(error))
        write_json(self.output / "shutdown.json", {"groups": groups, "errors": errors})
        if errors:
            raise RuntimeError("cell cleanup failed: " + "; ".join(errors))


def environment(config):
    paths = {name: config[name] for name in ("java", "node", "cgit", "nginx", "anubis")}
    binaries = {name: {"path": path, "sha256": sha256(Path(path).resolve())} for name, path in paths.items()}
    libraries = {}
    library_files = {}
    for name in ("java", "node", "cgit", "nginx", "anubis"):
        result = subprocess.run(["ldd", paths[name]], capture_output=True, text=True,
                                env={**os.environ, "LD_LIBRARY_PATH": config["cgit_library_path"]})
        libraries[name] = result.stdout + result.stderr
        for library in re.findall(r"(/[^\s()]+)", libraries[name]):
            path = Path(library)
            if path.is_file():
                library_files[str(path.resolve())] = sha256(path)
    versions = {}
    for name, command in {"java": [config["java"], "-version"],
                          "node": [config["node"], "--version"],
                          "nginx": [config["nginx"], "-v"],
                          "anubis": [config["anubis"], "--version"],
                          "git": ["git", "--version"]}.items():
        result = subprocess.run(command, capture_output=True, text=True, timeout=15)
        versions[name] = {"exit_code": result.returncode, "output": result.stdout + result.stderr}
    bundle_files = {str(path.relative_to(config["bundle"])): sha256(path)
                    for path in sorted(Path(config["bundle"]).rglob("*")) if path.is_file()}
    return {"schema": 1, "utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "kernel": platform.platform(), "os_release": Path("/etc/os-release").read_text(),
            "cpuinfo": Path("/proc/cpuinfo").read_text(), "meminfo": Path("/proc/meminfo").read_text(),
            "cpu_online": Path("/sys/devices/system/cpu/online").read_text().strip(),
            "python": sys.version, "binaries": binaries, "versions": versions,
            "linked_libraries": libraries, "linked_library_sha256": library_files,
            "bundle_sha256": bundle_files, "source": config["source"],
            "server_cpus": [0, 1], "client_cpus": [2, 3], "cpu_quota_cores_per_group": 2,
            "server_memory_bytes": 4 * 1024 ** 3, "client_memory_bytes": 2 * 1024 ** 3,
            "java_flags": ["-Xms128m", "-Xmx768m", "-XX:ActiveProcessorCount=2", "-XX:+UseG1GC"],
            "cgit_active_limit": 2, "trusted_render_active_limit": 2, "trusted_render_queue_limit": 16,
            "trusted_render_timeout_seconds": 5, "artifact_bytes_limit": 128 * 1024 * 1024,
            "client_cache_bytes_per_session": 8 * 1024 * 1024,
            "page_cache": "OS page cache is not dropped; cold/warm describes application artifact/cache state",
            "container": None, "runtime_isolation": "native Linux cgroup v1 and CPU affinity; binary/JAR/bundle hashes pinned"}


def ingest(config, root, output, progress=None):
    group = Group(f"bounded-origin-git-{os.getpid()}-{secrets.token_hex(4)}-ingest", [0, 1], 4 * 1024 ** 3)
    file = root / "ingest.properties"
    properties(file, {"objects": root / "objects", "dataset": config["dataset"]})
    before = group.snapshot()
    start = time.monotonic_ns()
    process = Process(java_command(config, "ingest", file), group, output, "ingestion")
    def heartbeat():
        if progress:
            lines = process.stderr_path.read_text().splitlines()
            progress(detail=lines[-1] if lines else "ingestion process is active")
    try:
        with Sampler(group, output / "ingestion-resources.jsonl"):
            process.wait(3600, heartbeat)
        end = time.monotonic_ns()
        storage = file_footprint(root / "objects")
        write_json(output / "ingestion.json", {"start_ns": start, "end_ns": end, "storage": storage,
                                               "before": before, "after": group.snapshot(),
                                               "objects": json.loads(process.stdout_path.read_text().splitlines()[-1])})
    finally:
        try:
            process.close()
        finally:
            lifecycle = group.close()
            write_json(output / "ingestion-shutdown.json", lifecycle)
            if lifecycle["errors"] or lifecycle["remaining_members"]:
                raise RuntimeError("ingestion cleanup failed")
    return root / "objects"


def safe_remove(path, root):
    path, root = Path(path).resolve(), Path(root).resolve()
    if path == root or root not in path.parents or not (root / ".campaign-owned").is_file():
        raise ValueError("refusing to remove a directory outside campaign scratch space")
    shutil.rmtree(path)


def check_space(paths, minimum_bytes=12 * 1024 ** 3):
    for path in paths:
        if shutil.disk_usage(path).free < minimum_bytes:
            raise OSError("insufficient free disk space for campaign: " + str(path))


def run(config, output, mirror, validation=False):
    if config.get("validation_objects") and not validation:
        raise ValueError("publication campaigns must measure fresh object ingestion")
    if not validation:
        provenance = Path(config["provenance"])
        if sha256(provenance / "source.tar.gz") != config["source"]["archive_sha256"]:
            raise ValueError("source archive digest mismatch")
    manifest = json.loads(Path(config["manifest"]).read_text())
    network = isolate_network()
    output.mkdir(parents=True, exist_ok=False)
    mirror.mkdir(parents=True, exist_ok=True)
    scratch = Path(config["scratch"]).resolve()
    scratch.mkdir(parents=True, exist_ok=False)
    (scratch / ".campaign-owned").write_text("bounded-origin-git benchmark scratch\n")
    write_json(output / "environment.json", {**environment(config), "network": network,
               "boot_id": Path("/proc/sys/kernel/random/boot_id").read_text().strip()})
    shutil.copyfile(config["manifest"], output / "dataset.json")
    shutil.copyfile(Path(__file__).with_name("protocol.md"), output / "protocol.md")
    write_json(output / "config.json", config)
    for name in ("environment.json", "dataset.json", "protocol.md", "config.json"):
        shutil.copyfile(output / name, mirror / name)
    if not validation:
        shutil.copytree(provenance, output / "provenance")
        shutil.copytree(provenance, mirror / "provenance", dirs_exist_ok=True)
    planned_cells = campaign_cells(config)
    repetitions = config.get("repetitions", 10)
    warmups = config.get("warmups", 2)
    if not validation and (repetitions < 10 or warmups < 2):
        raise ValueError("publication campaign requires two warmups and at least ten repetitions")
    status = {"status": "running", "pid": os.getpid(), "output": str(output), "scratch": str(scratch),
              "completed_cells": 0, "total_cells": (repetitions + warmups) * len(planned_cells),
              "validation": validation, "started_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}

    def progress(**updates):
        if "detail" not in updates:
            status.pop("detail", None)
        status.update(updates)
        status["updated_utc"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        write_json(output / "status.json", status)
        write_json(mirror / "status.json", status)
        print(json.dumps({key: status.get(key) for key in ("status", "phase", "detail", "repetition", "workload", "mode", "scale", "completed_cells", "total_cells")}), flush=True)

    progress(phase="initializing")
    try:
        for repetition in range(-warmups, repetitions):
            check_space([scratch, output, mirror])
            label = f"warmup-{repetition + warmups:02d}" if repetition < 0 else f"run-{repetition:02d}"
            repeat_output = output / "raw" / label
            repeat_output.mkdir(parents=True)
            repeat_work = scratch / label
            repeat_work.mkdir()
            progress(phase="ingestion", repetition=label, workload=None, mode=None)
            if config.get("validation_objects"):
                objects = Path(config["validation_objects"])
                if not objects.is_dir():
                    raise ValueError("validation object store does not exist")
                write_json(repeat_output / "ingestion-reused.json", {"validation_only": True, "objects": str(objects)})
            else:
                objects = ingest(config, repeat_work, repeat_output, progress)
            shutil.copytree(repeat_output, mirror / "raw" / label, dirs_exist_ok=True)
            cells = list(planned_cells)
            random.Random(config.get("seed", 424242) + repetition).shuffle(cells)
            for ordinal, (name, mode, scale) in enumerate(cells):
                identifier = f"{ordinal:03d}-n{scale}-{name}-{mode}"
                cell_output = repeat_output / identifier
                cell_work = repeat_work / identifier
                plan = workload(manifest, name, scale, config.get("seed", 424242) + max(0, repetition))
                head = manifest["update"]["older"] if name == "ref-update" else manifest["head"]
                progress(phase="starting", workload=name, mode=mode, scale=scale)
                cell = Cell(config, manifest, cell_work, cell_output, objects, mode, head, heartbeat=progress)
                try:
                    write_json(cell_output / "plan.json", plan)
                    cell.start(objects)
                    if mode in ("bounded-warm", "nginx-warm"):
                        progress(phase="trusted-preparation")
                        entries = [entry for entry in plan["prepare"]
                                   if mode == "nginx-warm" or entry["kind"] != "comparison"]
                        prepared = cell.prepare(entries)
                        if any(row["result"] != "success" for row in prepared):
                            raise AssertionError("normal materialization failed; see preparation.json")
                    progress(phase="measuring")
                    rows = cell.measure(plan)
                    verify_outcomes(rows)
                    if name == "ref-update":
                        cell.publish(manifest["update"]["newer"])
                        changed = {**plan, "requests": [{**manifest["update"]["after"], "index": index}
                                                      for index in range(len(plan["requests"]))]}
                        progress(phase="measuring-after-ref-publication")
                        changed_rows = cell.measure(changed, "after-publication")
                        verify_outcomes(changed_rows, manifest["update"]["before"]["expected_sha256"]
                                        if mode.startswith("nginx") else None)
                        write_json(cell_output / "update-outcomes.json", dict(Counter(row["outcome"] for row in changed_rows)))
                        if mode == "bounded-warm":
                            cell.prepare([manifest["update"]["after"]], "update-preparation")
                            final_rows = cell.measure(changed, "after-update-preparation")
                            if any(row["outcome"] != "delivered" for row in final_rows):
                                raise AssertionError("published and materialized ref returned incorrect content")
                    write_json(cell_output / "origin-summary.json", origin_summary(cell_output / "origin.jsonl"))
                    storage_root = cell_work / ("bounded/artifacts" if cell.control else "cache")
                    write_json(cell_output / "cache-storage.json", file_footprint(storage_root))
                except BaseException as error:
                    cell.failure(error)
                    raise
                finally:
                    cell.close()
                shutil.copytree(cell_output, mirror / "raw" / label / identifier, dirs_exist_ok=True)
                safe_remove(cell_work, scratch)
                progress(completed_cells=status["completed_cells"] + 1, phase="cell-complete")
            if not validation or config.get("validate_controls", False):
                progress(phase="failure-and-admission-controls", workload="trusted-controls", mode="bounded")
                stress(config, manifest, repeat_work, repeat_output, objects)
                shutil.copytree(repeat_output / "controls", mirror / "raw" / label / "controls", dirs_exist_ok=True)
            safe_remove(repeat_work, scratch)
        progress(status="completed", phase="awaiting-owner-result-review", finished_utc=time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()))
    except BaseException as error:
        interrupted = isinstance(error, (KeyboardInterrupt, SystemExit))
        progress(status="interrupted" if interrupted else "failed", failed_phase=status.get("phase"),
                 phase="stopped", error=repr(error))
        (output / "failure.txt").write_text(traceback.format_exc())
        shutil.copytree(output, mirror, dirs_exist_ok=True)
        raise
    finally:
        write_json(output / "result-inventory.json", {str(path.relative_to(output)): sha256(path)
                   for path in output.rglob("*") if path.is_file() and path.name != "result-inventory.json"})
        shutil.copyfile(output / "result-inventory.json", mirror / "result-inventory.json")


def stress(config, manifest, repeat_work, repeat_output, objects):
    from prepare import commit_request
    configurations = [
        ("same-key", {"delay_seconds": 0.3}, True),
        ("distinct-queue", {"delay_seconds": 0.3}, False),
        ("timeout", {"delay_seconds": 10}, False),
        ("failure", {"fail": True}, True),
    ]
    for name, fault, same in configurations:
        work = repeat_work / "controls" / name
        output = repeat_output / "controls" / name
        cell = Cell(config, manifest, work, output, objects, "bounded-warm", manifest["head"], fault)
        try:
            cell.start(objects)
            size = 4 if name in ("timeout", "failure") else 64
            commits = manifest["selected_commits"]
            repositories = manifest["repositories"]
            entries = [commit_request(repositories[0 if same else (index // len(commits)) % len(repositories)],
                                      commits[0 if same else index % len(commits)])
                       for index in range(size)]
            with Sampler(cell.server, output / "resources.jsonl"):
                result = cell.prepare(entries, "admission", concurrent=True)
            deadline = time.monotonic() + 15
            while True:
                stats = json.loads(request(cell.control + "/stats", token=cell.token))
                if stats["in_flight"] == 0:
                    break
                if time.monotonic() > deadline:
                    raise AssertionError("origin work did not terminate")
                time.sleep(0.1)
            summary = origin_summary(output / "origin.jsonl")
            if summary["max_concurrency"] > 2:
                raise AssertionError("trusted render exceeded configured concurrency")
            if name == "same-key" and summary["executions"] != 1:
                raise AssertionError("same-key materialization was not shared")
            if name == "distinct-queue" and not any(row["result"] in ("GLOBAL_QUEUE_LIMIT", "POLICY_QUEUE_LIMIT") for row in result):
                raise AssertionError("queue exhaustion did not reject excess distinct work")
            if name == "timeout" and not any(row["result"] == "TIMEOUT" for row in result):
                raise AssertionError("slow origin did not time out")
            if name in ("timeout", "failure") and stats["artifact_entries"] != 0:
                raise AssertionError("failed work published an artifact")
            if name == "failure":
                cooldown = cell.prepare(entries, "cooldown", concurrent=True)
                if not all(row["result"] == "COOLDOWN" for row in cooldown):
                    raise AssertionError("failed key was retried within its cooldown")
            if list((work / "bounded").rglob("*.body")):
                raise AssertionError("temporary CGI response leaked")
            write_json(output / "verification.json", {"passed": True, "stats": stats, "origin": summary})
        except BaseException as error:
            cell.failure(error)
            raise
        finally:
            cell.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--mirror", required=True, type=Path)
    parser.add_argument("--validation", action="store_true")
    args = parser.parse_args()
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
    run(json.loads(args.config.read_text()), args.output.resolve(), args.mirror.resolve(), args.validation)
