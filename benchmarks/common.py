"""Small Linux measurement and process-isolation helpers for the campaign."""

import hashlib
import json
import os
import signal
import subprocess
import threading
import time
from pathlib import Path


def write_json(path, value):
    path = Path(path)
    temporary = path.with_name(path.name + ".new")
    temporary.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")
    temporary.replace(path)


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_jsonl(path):
    if not Path(path).exists():
        return []
    return [json.loads(line) for line in Path(path).read_text().splitlines() if line]


def file_footprint(directory):
    count = logical = allocated = 0
    for path in Path(directory).rglob("*"):
        if path.is_file() and not path.is_symlink():
            stat = path.stat()
            count += 1
            logical += stat.st_size
            allocated += getattr(stat, "st_blocks", 0) * 512
    return {"file_count": count, "file_bytes": logical, "file_allocated_bytes": allocated}


class Group:
    CONTROLLERS = ("cpu", "cpuacct", "memory", "pids", "blkio")

    def __init__(self, name, cpus, memory_bytes):
        if os.geteuid() != 0:
            raise PermissionError("campaign requires root only to own isolated cgroups")
        if not name.startswith("bounded-origin-git-") or "/" in name:
            raise ValueError("invalid private cgroup name")
        self.name = name
        self.cpus = set(cpus)
        self.paths = {controller: Path("/sys/fs/cgroup") / controller / name
                      for controller in self.CONTROLLERS}
        for path in self.paths.values():
            path.mkdir()
        (self.paths["cpu"] / "cpu.cfs_period_us").write_text("100000")
        (self.paths["cpu"] / "cpu.cfs_quota_us").write_text(str(100000 * len(cpus)))
        (self.paths["memory"] / "memory.limit_in_bytes").write_text(str(memory_bytes))
        swap_limit = self.paths["memory"] / "memory.memsw.limit_in_bytes"
        if swap_limit.exists():
            swap_limit.write_text(str(memory_bytes))
        (self.paths["pids"] / "pids.max").write_text("512")

    def enter(self):
        os.sched_setaffinity(0, self.cpus)
        for path in self.paths.values():
            (path / "cgroup.procs").write_text(str(os.getpid()))

    def snapshot(self):
        result = {"cpu_seconds": int((self.paths["cpuacct"] / "cpuacct.usage").read_text()) / 1e9,
                  "memory_bytes": int((self.paths["memory"] / "memory.usage_in_bytes").read_text()),
                  "memory_peak_bytes": int((self.paths["memory"] / "memory.max_usage_in_bytes").read_text()),
                  "memory_failures": int((self.paths["memory"] / "memory.failcnt").read_text()),
                  "pids": int((self.paths["pids"] / "pids.current").read_text())}
        result["cpu_throttle"] = dict(line.split() for line in (self.paths["cpu"] / "cpu.stat").read_text().splitlines())
        counters = self.paths["blkio"] / "blkio.throttle.io_service_bytes"
        result["block_io"] = counters.read_text() if counters.exists() else None
        for name, path in {"memory_oom": self.paths["memory"] / "memory.oom_control",
                           "pid_events": self.paths["pids"] / "pids.events"}.items():
            result[name] = dict(line.split() for line in path.read_text().splitlines()) if path.exists() else None
        return result

    def members(self):
        return {int(pid) for pid in (self.paths["pids"] / "cgroup.procs").read_text().split()}

    def close(self):
        result = {"before": self.snapshot(), "signals": [], "errors": []}
        members = self.members()
        for pid in members:
            try:
                os.kill(pid, signal.SIGTERM)
                result["signals"].append({"pid": pid, "signal": "SIGTERM"})
            except ProcessLookupError:
                pass
        deadline = time.monotonic() + 5
        while self.members() and time.monotonic() < deadline:
            time.sleep(0.05)
        for pid in self.members():
            try:
                os.kill(pid, signal.SIGKILL)
                result["signals"].append({"pid": pid, "signal": "SIGKILL"})
            except ProcessLookupError:
                pass
        deadline = time.monotonic() + 5
        while self.members() and time.monotonic() < deadline:
            time.sleep(0.05)
        result["after"] = self.snapshot()
        result["remaining_members"] = sorted(self.members())
        for path in reversed(list(self.paths.values())):
            try:
                path.rmdir()
            except OSError as error:
                result["errors"].append(f"{path}: {error}")
        return result


class Process:
    def __init__(self, command, group, directory, name, env=None):
        self.group = group
        self.record_path = Path(directory) / (name + ".process.json")
        self.record = {"command": [str(value) for value in command], "cwd": os.getcwd(),
                       "cgroup": group.name, "started_unix_ns": time.time_ns(),
                       "started_monotonic_ns": time.monotonic_ns(), "state": "spawning"}
        write_json(self.record_path, self.record)
        self.stdout_path = Path(directory) / (name + ".stdout.log")
        self.stderr_path = Path(directory) / (name + ".stderr.log")
        self.stdout = self.stdout_path.open("wb")
        self.stderr = self.stderr_path.open("wb")
        try:
            self.process = subprocess.Popen(command, stdout=self.stdout, stderr=self.stderr,
                                            env=env, start_new_session=True, preexec_fn=group.enter)
        except BaseException as error:
            self.record.update(state="spawn-failed", error=repr(error))
            write_json(self.record_path, self.record)
            self.stdout.close()
            self.stderr.close()
            raise
        self.record.update(pid=self.process.pid, state="running")
        write_json(self.record_path, self.record)

    def observe(self):
        code = self.process.poll()
        self.record.update(observed_unix_ns=time.time_ns(), exit_code=code,
                           exit_signal=-code if code is not None and code < 0 else None,
                           state="running" if code is None else "exited", resources=self.group.snapshot())
        write_json(self.record_path, self.record)
        return code

    def ensure_running(self):
        code = self.process.poll()
        if code is not None:
            self.observe()
            raise RuntimeError(f"process {self.process.pid} exited {code}; evidence: {self.record_path}\n"
                               + self.stderr_path.read_text()[-4000:])

    def wait(self, timeout, heartbeat=None):
        deadline = time.monotonic() + timeout
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise subprocess.TimeoutExpired(self.process.args, timeout)
            try:
                code = self.process.wait(timeout=min(10, remaining))
                break
            except subprocess.TimeoutExpired:
                if heartbeat:
                    heartbeat()
        self.stdout.flush()
        self.stderr.flush()
        self.observe()
        if code != 0:
            raise RuntimeError(f"process exited {code}: {self.stderr_path}\n{self.stderr_path.read_text()[-3000:]}")

    def close(self, timeout=8):
        self.record["exit_before_cleanup"] = self.process.poll()
        self.record["cleanup_signals"] = []
        try:
            if self.process.poll() is None:
                self.record["cleanup_signals"].append("SIGTERM")
                self.process.terminate()
                try:
                    self.process.wait(timeout=timeout)
                except subprocess.TimeoutExpired:
                    self.record["cleanup_signals"].append("SIGKILL")
                    os.killpg(self.process.pid, signal.SIGKILL)
                    self.process.wait(timeout=5)
        finally:
            self.observe()
            self.stdout.close()
            self.stderr.close()


class Sampler:
    def __init__(self, group, path):
        self.group = group
        self.path = Path(path)
        self.stop = threading.Event()
        self.thread = threading.Thread(target=self.run, daemon=True)

    def run(self):
        with self.path.open("w") as output:
            while not self.stop.is_set():
                rss = 0
                processes = []
                for pid in self.group.members():
                    try:
                        fields = Path(f"/proc/{pid}/statm").read_text().split()
                        resident = int(fields[1]) * os.sysconf("SC_PAGE_SIZE")
                        rss += resident
                        io = dict(line.split(": ") for line in Path(f"/proc/{pid}/io").read_text().splitlines())
                        processes.append({"pid": pid, "rss_bytes": resident, "io": io})
                    except (FileNotFoundError, ProcessLookupError):
                        pass
                output.write(json.dumps({"time_ns": time.monotonic_ns(), "rss_sum_bytes": rss,
                                         "group": self.group.snapshot(), "processes": processes}) + "\n")
                output.flush()
                self.stop.wait(0.1)

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *_args):
        self.stop.set()
        self.thread.join(timeout=5)


def wait_ready(path, process, seconds=30):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        process.ensure_running()
        try:
            return json.loads(Path(path).read_text())
        except (FileNotFoundError, json.JSONDecodeError):
            time.sleep(0.05)
    raise TimeoutError(f"server did not publish readiness: {path}")


def origin_summary(path):
    events = read_jsonl(path)
    starts = {event["id"]: event for event in events if event["kind"] == "start"}
    ends = {event["id"]: event for event in events if event["kind"] == "end"}
    if starts.keys() != ends.keys():
        raise ValueError("incomplete CGI lifecycle evidence")
    intervals = []
    for event in ends.values():
        intervals.extend([(event["start_ns"], 1), (event["end_ns"], -1)])
    active = maximum = 0
    for _, delta in sorted(intervals):
        active += delta
        maximum = max(maximum, active)
    return {"executions": len(ends), "max_concurrency": maximum,
            "cpu_seconds": sum(event["user_cpu_seconds"] + event["system_cpu_seconds"] for event in ends.values()),
            "failures": sum(event["exit_code"] != 0 for event in ends.values()),
            "logical_read_bytes": sum(event["io"].get("rchar", 0) for event in ends.values()),
            "logical_written_bytes": sum(event["io"].get("wchar", 0) for event in ends.values()),
            "disk_read_bytes": sum(event["io"].get("read_bytes", 0) for event in ends.values()),
            "disk_written_bytes": sum(event["io"].get("write_bytes", 0) for event in ends.values())}
