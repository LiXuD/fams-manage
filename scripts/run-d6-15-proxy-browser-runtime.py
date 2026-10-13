#!/usr/bin/env python3
"""Run proxy confirmation UI with owned real Clamd, MySQL and attachment storage."""

import importlib.util
import os
from pathlib import Path
import runpy
import socket
import time
import tempfile

if __name__ == "__main__":
    root = Path(__file__).resolve().parent
    spec = importlib.util.spec_from_file_location("d6_09_browser_clamd", root / "verify-d6-09-clamd.py")
    if spec is None or spec.loader is None:
        raise RuntimeError("Clamd fixture could not be loaded")
    clamd = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(clamd)
    try:
        java = clamd.checked("/usr/libexec/java_home", "-v", "21")
        os.environ["JAVA_HOME"] = java
        os.environ["PATH"] = java + "/bin:" + os.environ.get("PATH", "")
        # Build current source once; an existing jar is not evidence of current functionality.
        clamd.checked("./mvnw", "-q", "-pl", "backend", "-DskipTests", "package")
        clamd.checked("docker", "run", "-d", "--name", clamd.CONTAINER, "--platform", "linux/amd64",
                      "--memory", "4g", "-e", "CLAMAV_NO_FRESHCLAMD=true", "-e", "CLAMAV_NO_MILTERD=true",
                      "-p", "127.0.0.1::3310", clamd.IMAGE)
        port = int(clamd.checked("docker", "port", clamd.CONTAINER, "3310/tcp").rsplit(":", 1)[-1])
        deadline = time.monotonic() + 240
        while time.monotonic() < deadline:
            try:
                with socket.create_connection(("127.0.0.1", port), timeout=2) as connection:
                    connection.sendall(b"zPING\0")
                    if connection.recv(32).rstrip(b"\0\n") == b"PONG":
                        break
            except OSError:
                pass
            if clamd.checked("docker", "inspect", "--format", "{{.State.Running}}", clamd.CONTAINER) != "true":
                raise RuntimeError("Clamd stopped before readiness")
            time.sleep(1)
        else:
            raise RuntimeError("Clamd readiness timed out")
        print("Browser Clamd ready: " + clamd.checked("docker", "exec", clamd.CONTAINER, "clamd", "--version"), flush=True)
        os.environ["FAMS_ATTACHMENTS_CLAMD_PORT"] = str(port)
        with tempfile.TemporaryDirectory(prefix="fams-d6-15-proxy-evidence-") as quarantine:
            os.environ["FAMS_ATTACHMENTS_QUARANTINE_ROOT"] = quarantine
            business = os.environ.get("FAMS_PROXY_BROWSER_BUSINESS", "REQUISITION")
            if business not in {"REQUISITION", "TRANSFER"}:
                raise ValueError("Unsupported proxy browser business")
            runner = "run-d6-15-browser-runtime.py" if business == "TRANSFER" else "run-d6-15-requisition-browser-runtime.py"
            runpy.run_path(str(root / runner), run_name="__main__")
    finally:
        clamd.cleanup(clamd.CONTAINER)
