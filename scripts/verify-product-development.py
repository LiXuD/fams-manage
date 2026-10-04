#!/usr/bin/env python3
"""Run current product checks without replaying historical governance audits."""

from pathlib import Path
import runpy
import xml.etree.ElementTree as ElementTree


if __name__ == "__main__":
    gate = runpy.run_path(str(Path(__file__).with_name("verify-d6-03a.py")))
    context = None
    application = None
    try:
        gate["assert_clean_start"]()
        context = gate["create_runtime_context"]()
        gate["start_compose"](context)
        gate["verify_java_quality"](context)
        gate["verify_typescript_quality"]()
        gate["verify_dependency_integrity"]()
        application = gate["verify_runtime_recovery"](context)
        print("Current product development checks passed; historical governance audits not rerun.")
    finally:
        # Preserve useful failure identities before the existing gate removes generated reports.
        for report in gate["SUREFIRE_REPORTS"].glob("TEST-*.xml"):
            suite = ElementTree.parse(report).getroot()
            for case in suite.findall("testcase"):
                for result in case:
                    if result.tag in {"failure", "error"}:
                        print(report.name, case.attrib.get("name"),
                              gate["redact"](result.attrib.get("message", "")[:4000]))
                        print(gate["redact"]((result.text or "")[:1800]))
        try:
            if application is not None:
                gate["stop_application"](application)
        finally:
            try:
                if context is not None:
                    gate["cleanup_compose"](context)
            finally:
                gate["verify_final_worktree"]()
