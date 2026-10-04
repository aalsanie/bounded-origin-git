#!/usr/bin/env python3
"""Loopback CGI transport used unchanged by direct, nginx and Anubis baselines."""

import argparse
import json
import os
import selectors
import signal
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote, urlsplit

from cgi_meter import append

MAX_BODY = 128 * 1024 * 1024


def capture_cgi(process, timeout=30):
    body, errors = bytearray(), bytearray()
    deadline = time.monotonic() + timeout
    with selectors.DefaultSelector() as streams:
        streams.register(process.stdout, selectors.EVENT_READ, "stdout")
        streams.register(process.stderr, selectors.EVENT_READ, "stderr")
        while streams.get_map():
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("native CGI deadline exceeded")
            for key, _ in streams.select(min(remaining, .1)):
                chunk = os.read(key.fileobj.fileno(), 65536)
                if not chunk:
                    streams.unregister(key.fileobj)
                elif key.data == "stdout":
                    if len(body) + len(chunk) > MAX_BODY + 16384:
                        raise ValueError("CGI response exceeds limit")
                    body.extend(chunk)
                elif len(errors) < 16384:
                    errors.extend(chunk[:16384 - len(errors)])
    process.wait(timeout=max(.01, deadline - time.monotonic()))
    return bytes(body), bytes(errors)


def parse_cgi(data):
    if b"\r\n\r\n" in data:
        headers, body = data.split(b"\r\n\r\n", 1)
    elif b"\n\n" in data:
        headers, body = data.split(b"\n\n", 1)
    else:
        raise ValueError("CGI response has no header terminator")
    if len(headers) > 16384 or len(body) > MAX_BODY:
        raise ValueError("CGI response exceeds limit")
    result = []
    status = 200
    for line in headers.decode("latin1").splitlines():
        name, value = line.split(":", 1)
        if name.lower() == "status":
            status = int(value.strip().split()[0])
        elif name.lower() not in {"content-length", "connection", "transfer-encoding"}:
            result.append((name, value.strip()))
    return status, result, body


class Server(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 256


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("config", type=Path)
    args = parser.parse_args()
    config = json.loads(args.config.read_text())
    capacity = threading.BoundedSemaphore(2)
    clients = threading.BoundedSemaphore(128)

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *_args):
            pass

        def do_GET(self):
            started = time.monotonic_ns()
            if self.path == "/health":
                self.send_response(200)
                self.send_header("Content-Length", "2")
                self.end_headers()
                self.wfile.write(b"ok")
                return
            if not clients.acquire(blocking=False):
                self.send_error(503)
                return
            status, body = 500, b""
            acquired = False
            try:
                acquired = capacity.acquire(timeout=60)
                if not acquired:
                    self.send_error(503)
                    status = 503
                    return
                url = urlsplit(self.path)
                environment = {
                    "CGIT_CONFIG": config["cgit_config"], "QUERY_STRING": url.query,
                    "REQUEST_METHOD": "GET", "PATH_INFO": unquote(url.path),
                    "SCRIPT_NAME": "", "HTTP_HOST": "localhost", "SERVER_NAME": "localhost",
                    "SERVER_PORT": "80", "GIT_CONFIG_NOSYSTEM": "1", "HOME": config["root"],
                }
                with subprocess.Popen([config["wrapper"]], env=environment,
                                      stdout=subprocess.PIPE, stderr=subprocess.PIPE) as process:
                    try:
                        output, stderr = capture_cgi(process)
                    except (TimeoutError, subprocess.TimeoutExpired, ValueError):
                        process.terminate()
                        try:
                            process.wait(timeout=2)
                        except subprocess.TimeoutExpired:
                            process.kill()
                            process.wait(timeout=2)
                        raise
                    if process.returncode != 0:
                        raise RuntimeError(f"CGI exit {process.returncode}: {stderr[:1024]!r}")
                status, headers, body = parse_cgi(output)
                self.send_response(status)
                for name, value in headers:
                    self.send_header(name, value)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError):
                status = 499
            except Exception as error:
                status = 502
                try:
                    self.send_error(status, str(error))
                except (BrokenPipeError, ConnectionResetError):
                    pass
            finally:
                if acquired:
                    capacity.release()
                clients.release()
                append(config["requests"], {"start_ns": started, "end_ns": time.monotonic_ns(),
                       "target": self.path, "status": status, "body_bytes": len(body)})

    server = Server(("127.0.0.1", 0), Handler)
    Path(config["ready"]).write_text(json.dumps({"port": server.server_port, "pid": os.getpid()}))
    signal.signal(signal.SIGTERM, lambda *_: os._exit(0))
    server.serve_forever(poll_interval=0.1)


if __name__ == "__main__":
    main()
