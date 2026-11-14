#!/usr/bin/env python3
"""Run the D6-08 durable job path against an isolated MySQL container."""

from __future__ import annotations

import os
from pathlib import Path
import secrets
import signal
import subprocess
import sys
import tempfile
import time


ROOT = Path(__file__).resolve().parents[1]
IMAGE = "mysql:8.4.11@sha256:3466ba4a4828aa8d46fb7c3bc16b67b781c98413cf4ea0fac6feaa6e881faa26"
CONTAINER = "fams-d6-08-" + secrets.token_hex(8)
PASSWORD = secrets.token_hex(32)
ROOT_PASSWORD = secrets.token_hex(32)


def run(*args: str, env: dict[str, str] | None = None) -> str:
    result = subprocess.run(["rtk", *args], cwd=ROOT, env=env, text=True,
                            capture_output=True, timeout=420, check=False)
    if result.returncode:
        output = (result.stdout + result.stderr).replace(PASSWORD, "<redacted>")
        output = output.replace(ROOT_PASSWORD, "<redacted>")
        raise RuntimeError(f"{' '.join(args[:4])} exited {result.returncode}:\n"
                           + "\n".join(output.splitlines()[-50:]))
    return result.stdout.strip()


def main() -> None:
    java = run("/usr/libexec/java_home", "-v", "21")
    env = os.environ.copy()
    env["JAVA_HOME"] = java
    env["PATH"] = java + "/bin:" + env.get("PATH", "")
    run("docker", "run", "-d", "--name", CONTAINER,
        "-e", "MYSQL_ROOT_PASSWORD=" + ROOT_PASSWORD,
        "-e", "MYSQL_DATABASE=fams_d6_08", "-e", "MYSQL_USER=fams_d6_08",
        "-e", "MYSQL_PASSWORD=" + PASSWORD, "-p", "127.0.0.1::3306", IMAGE,
        "--log-bin-trust-function-creators=1")
    for _ in range(60):
        ping = subprocess.run(["rtk", "docker", "exec", CONTAINER, "mysqladmin",
                               "ping", "-uroot", "-p" + ROOT_PASSWORD, "--silent"],
                              cwd=ROOT, capture_output=True, timeout=15, check=False)
        if ping.returncode == 0:
            break
        time.sleep(1)
    else:
        raise RuntimeError("MySQL did not become ready")
    port = run("docker", "port", CONTAINER, "3306/tcp").rsplit(":", 1)[-1]
    env["D6_08_TEST_DATASOURCE_URL"] = (
        f"jdbc:mysql://127.0.0.1:{port}/fams_d6_08"
        "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC")
    env["D6_08_TEST_DATASOURCE_USERNAME"] = "fams_d6_08"
    env["D6_08_TEST_DATASOURCE_PASSWORD"] = PASSWORD
    if "--capacity" in sys.argv:
        env["D6_08_TEST_DATASOURCE_URL"] += "&rewriteBatchedStatements=true"
        output = run("./mvnw", "-pl", "backend", "-Dd6.08.capacity.runtime=true",
                     "-Dtest=AssetImportCapacityRuntimeCase", "test", env=env)
        metrics = [line.strip() for line in output.splitlines() if "D6_08_CAPACITY " in line]
        if len(metrics) != 3:
            raise RuntimeError("capacity measurement or boundary output was not produced")
        print("\n".join(metrics))
        return
    linked_restart = "--restart-linked-import" in sys.argv
    linked_mini = "--mini-linked-import" in sys.argv
    if linked_restart or linked_mini or "--restart-import" in sys.argv or "--failure-import" in sys.argv:
        run("./mvnw", "-pl", "backend", "-Dd6.08.job.runtime=true",
            "-Dtest=DurableJobRuntimeCase#mysqlSubmissionClaimFencingBackoffAndBusinessRollback",
            "test", env=env)
        run("./mvnw", "-pl", "backend", "-Dd6.08.import.runtime=true",
            "-Dtest=AssetImportBatchRuntimeCase", "test", env=env)
    if "--failure-import" in sys.argv:
        env["D6_08_INJECT_PUBLICATION_FAILURE"] = "1"
        output = run("./mvnw", "-pl", "backend", "-Dd6.08.import.http.runtime=true",
                     "-Dtest=AssetImportHttpRuntimeCase#warehouseUploadPreviewAndErrorRowsUseRealSessionAndMysql",
                     "test", env=env)
        metrics = [line.strip() for line in output.splitlines()
                   if "D6_08_PUBLICATION_FAILURE " in line]
        if len(metrics) != 1:
            raise RuntimeError("publication failure measurement was not produced")
        print(metrics[0])
        return
    if linked_mini:
        output = run("./mvnw", "-pl", "backend", "-Dd6.08.import.http.runtime=true", "-Dd6.23.mini.runtime=true",
                     "-Dtest=AssetImportHttpRuntimeCase#warehouseUploadPreviewAndErrorRowsUseRealSessionAndMysql",
                     "test", env=env)
        metrics = [line.strip() for line in output.splitlines() if "D6_23_NATIVE_INITIAL_SCRAP " in line]
        if len(metrics) != 1:
            raise RuntimeError("native initial scrap verification did not produce its checkpoint")
        print(metrics[0])
        return
    if linked_restart or "--restart-import" in sys.argv:
        first_marker = "D6_23_IMPORT_COMMITTED_MARKER" if linked_restart else "D6_08_IMPORT_PHASE1_MARKER"
        second_marker = "D6_23_IMPORT_REPLAY_MARKER" if linked_restart else "D6_08_IMPORT_PHASE2_MARKER"
        second_test = ("restartedJvmPreservesCommittedLinkedPublication" if linked_restart
                       else "processTwoPublishesClaimedImportAfterPreviousJvmExited")
        metric = "D6_23_LINKED_IMPORT_RESTART " if linked_restart else "D6_08_IMPORT_RESTART "
        with tempfile.TemporaryDirectory(prefix="fams-d6-08-import-restart-") as temporary:
            marker = Path(temporary) / "publication.json"
            log = Path(temporary) / "initial-process.log"
            env[first_marker] = str(marker)
            with log.open("w", encoding="utf-8") as output:
                process = subprocess.Popen(
                    ["rtk", "proxy", "./mvnw", "-pl", "backend", "-Dd6.08.import.http.runtime=true",
                     "-Dtest=AssetImportHttpRuntimeCase#warehouseUploadPreviewAndErrorRowsUseRealSessionAndMysql",
                     "test"], cwd=ROOT, env=env, stdout=output,
                    stderr=subprocess.STDOUT, text=True, start_new_session=True)
                try:
                    deadline = time.monotonic() + 180
                    while not marker.is_file():
                        if process.poll() is not None or time.monotonic() >= deadline:
                            diagnostic = log.read_text(encoding="utf-8", errors="replace")
                            diagnostic = diagnostic.replace(PASSWORD, "<redacted>").replace(ROOT_PASSWORD, "<redacted>")
                            raise RuntimeError("import JVM did not reach the expected publication checkpoint:\n"
                                               + "\n".join(diagnostic.splitlines()[-30:]))
                        time.sleep(0.2)
                    if process.poll() is not None:
                        raise RuntimeError("import JVM exited before the forced stop")
                finally:
                    if process.poll() is None:
                        os.killpg(process.pid, signal.SIGKILL)
                    process.wait(timeout=15)
            env.pop(first_marker)
            env[second_marker] = str(marker)
            if not linked_restart:
                time.sleep(4)
            output = run("./mvnw", "-pl", "backend", "-Dd6.08.import.http.runtime=true",
                         f"-Dtest=AssetImportHttpRuntimeCase#{second_test}",
                         "test", env=env)
            metrics = [line.strip() for line in output.splitlines()
                       if metric in line]
            if len(metrics) != 1:
                raise RuntimeError("import restart measurement was not produced")
            print(metrics[0])
        return
    run("./mvnw", "-pl", "backend", "-Dd6.08.job.runtime=true",
        "-Dtest=DurableJobRuntimeCase#mysqlSubmissionClaimFencingBackoffAndBusinessRollback",
        "test", env=env)
    run("./mvnw", "-pl", "backend", "-Dd6.08.import.runtime=true",
        "-Dtest=AssetImportBatchRuntimeCase", "test", env=env)
    http_output = run("./mvnw", "-pl", "backend", "-Dd6.08.import.http.runtime=true",
                      "-Dtest=AssetImportHttpRuntimeCase#warehouseUploadPreviewAndErrorRowsUseRealSessionAndMysql",
                      "test", env=env)
    publication_metrics = [line.strip() for line in http_output.splitlines()
                           if "D6_08_PUBLICATION " in line]
    if len(publication_metrics) != 1:
        raise RuntimeError("publication transaction measurement was not produced")
    print(publication_metrics[0])
    with tempfile.TemporaryDirectory(prefix="fams-d6-08-") as temporary:
        marker = Path(temporary) / "claimed"
        env["D6_08_PHASE1_MARKER"] = str(marker)
        process = subprocess.Popen(["rtk", "./mvnw", "-pl", "backend", "-Dd6.08.job.runtime=true",
                                    "-Dtest=DurableJobRuntimeCase#processOneClaimsBeforeForcedStop", "test"],
                                   cwd=ROOT, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   text=True, start_new_session=True)
        try:
            for _ in range(900):
                if marker.is_file():
                    break
                if process.poll() is not None:
                    raise RuntimeError("claiming JVM exited before recording its committed lease")
                time.sleep(0.2)
            else:
                raise RuntimeError("claiming JVM did not commit a lease in time")
        finally:
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGKILL)
            process.communicate(timeout=15)
    env.pop("D6_08_PHASE1_MARKER")
    run("./mvnw", "-pl", "backend", "-Dd6.08.job.runtime=true",
        "-Dtest=DurableJobRuntimeCase#processTwoReclaimsAfterPreviousJvmExited", "test", env=env)
    print("D6-08 real MySQL durable job and import preflight paths passed")


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
        print(f"D6-08 FAILED: {error}", file=sys.stderr)
        raise SystemExit(1)
    finally:
        subprocess.run(["rtk", "docker", "rm", "-f", "-v", CONTAINER], cwd=ROOT,
                       capture_output=True, check=False)
