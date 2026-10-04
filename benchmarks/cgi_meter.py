#!/usr/bin/env python3
"""Run native cgit and retain its own wait4 CPU, RSS and Linux I/O counters."""

import json
import os
import signal
import sys
import time
from pathlib import Path


def append(path, value):
    encoded = (json.dumps(value, separators=(",", ":")) + "\n").encode()
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
    try:
        if os.write(descriptor, encoded) != len(encoded):
            raise OSError("short metric write")
    finally:
        os.close(descriptor)


def main():
    config = json.loads(Path(sys.argv[1]).read_text())
    started = time.monotonic_ns()
    invocation = f"{os.getpid()}-{started}"
    event = {"id": invocation, "wrapper_pid": os.getpid(), "start_ns": started,
             "path": os.environ.get("PATH_INFO", ""),
             "query": os.environ.get("QUERY_STRING", ""), "kind": "start"}
    append(config["events"], event)
    child = os.fork()
    if child == 0:
        try:
            if config.get("delay_seconds", 0):
                time.sleep(config["delay_seconds"])
            if config.get("fail", False):
                os.write(1, b"Status: 500 Internal Server Error\nContent-Type: text/plain\n\nfault injected\n")
                os._exit(7)
            environment = dict(os.environ)
            environment["LD_LIBRARY_PATH"] = config["library_path"]
            os.execve(config["cgit"], [config["cgit"]], environment)
        except BaseException:
            os._exit(127)

    def terminate(_signum, _frame):
        try:
            os.kill(child, signal.SIGTERM)
        except ProcessLookupError:
            pass

    signal.signal(signal.SIGTERM, terminate)
    signal.signal(signal.SIGINT, terminate)
    os.waitid(os.P_PID, child, os.WEXITED | os.WNOWAIT)
    io = {}
    try:
        for line in Path(f"/proc/{child}/io").read_text().splitlines():
            key, value = line.split(":")
            io[key] = int(value)
    except (FileNotFoundError, PermissionError):
        pass
    _, status, usage = os.wait4(child, 0)
    code = os.waitstatus_to_exitcode(status)
    append(config["events"], {"kind": "end", "id": invocation,
           "start_ns": started, "end_ns": time.monotonic_ns(), "exit_code": code,
           "user_cpu_seconds": usage.ru_utime, "system_cpu_seconds": usage.ru_stime,
           "max_rss_kib": usage.ru_maxrss, "input_blocks": usage.ru_inblock,
           "output_blocks": usage.ru_oublock, "io": io})
    return code if code >= 0 else 128 - code


if __name__ == "__main__":
    sys.exit(main())
