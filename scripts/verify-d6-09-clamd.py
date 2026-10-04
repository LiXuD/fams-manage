#!/usr/bin/env python3
"""Run real clamd + HTTP + MySQL attachment checks in owned disposable containers."""

from __future__ import annotations

import importlib.util
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
IMAGE = "clamav/clamav:1.4.6"
CONTAINER = "fams-d6-09-clamd-" + secrets.token_hex(8)


def checked(*args: str) -> str:
    result = subprocess.run(["rtk", *args], cwd=ROOT, capture_output=True, text=True, timeout=420, check=False)
    if result.returncode:
        raise RuntimeError(f"{' '.join(args[:3])} failed: " + result.stderr[-2000:])
    return result.stdout.strip()


def cleanup(container: str) -> None:
    if checked("docker", "ps", "-a", "--filter", f"name=^{container}$", "--format", "{{.Names}}"):
        checked("docker", "rm", "-f", container)


def main() -> None:
    checked("docker", "run", "-d", "--name", CONTAINER, "--platform", "linux/amd64", "--memory", "4g",
            "-e", "CLAMAV_NO_FRESHCLAMD=true", "-e", "CLAMAV_NO_MILTERD=true",
            "-p", "127.0.0.1::3310", IMAGE)
    port = int(checked("docker", "port", CONTAINER, "3310/tcp").rsplit(":", 1)[-1])
    deadline = time.monotonic() + 240
    while time.monotonic() < deadline:
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=2) as connection:
                connection.sendall(b"zPING\0")
                if connection.recv(32).rstrip(b"\0\n") == b"PONG":
                    break
        except OSError:
            pass
        if checked("docker", "inspect", "--format", "{{.State.Running}}", CONTAINER) != "true":
            raise RuntimeError("Owned clamd container stopped before readiness")
        time.sleep(1)
    else:
        raise RuntimeError("Clamd did not become ready within 240 seconds")
    print("Clamd ready: " + checked("docker", "exec", CONTAINER, "clamd", "--version"), flush=True)
    os.environ.update({"D6_09_REAL_CLAMD": "true", "D6_09_CLAMD_PORT": str(port),
                       "D6_09_CLAMD_CONTAINER": CONTAINER})
    source = ROOT / "scripts/verify-d6-09.py"
    spec = importlib.util.spec_from_file_location("d6_09_real_clamd_mysql", source)
    if spec is None or spec.loader is None:
        raise RuntimeError("MySQL attachment runner could not be loaded")
    runner = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(runner)
    try:
        runner.main()
    finally:
        cleanup(runner.CONTAINER)


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
        print(f"D6-09 Clamd FAILED: {error}", file=sys.stderr)
        raise SystemExit(1)
    finally:
        cleanup(CONTAINER)
