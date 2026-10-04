#!/usr/bin/env python3
"""Reuse the isolated inbound browser stack with private temporary attachment storage."""

import importlib.util
import os
from pathlib import Path
import tempfile


if __name__ == "__main__":
    source = Path(__file__).with_name("run-d6-07-browser-runtime.py")
    spec = importlib.util.spec_from_file_location("d6_09_inbound_runtime", source)
    if spec is None or spec.loader is None:
        raise RuntimeError("Inbound runtime entry could not be loaded")
    runtime = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(runtime)
    with tempfile.TemporaryDirectory(prefix="fams-d6-09-quarantine-") as quarantine:
        os.environ["FAMS_ATTACHMENTS_QUARANTINE_ROOT"] = quarantine
        # Clamd is a real configured endpoint; without a daemon files stay UNAVAILABLE.
        # This wrapper supplies no mock scanner and does not claim actual malware detection.
        raise SystemExit(runtime.main())
