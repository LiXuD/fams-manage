#!/usr/bin/env python3
"""Verify approved target migration and real HTTP receipt checks against an owned disposable MySQL."""
import os
import secrets
import socket
import subprocess
import time
from pathlib import Path
import xml.etree.ElementTree as ElementTree

ROOT = Path(__file__).resolve().parents[1]
IMAGE = "mysql:8.4.11@sha256:3466ba4a4828aa8d46fb7c3bc16b67b781c98413cf4ea0fac6feaa6e881faa26"


def main():
    suffix = secrets.token_hex(8)
    name = "fams-d6-15-target-runtime-" + suffix
    database = "fams-d6-04-db-" + suffix
    password = secrets.token_hex(32)
    environment = os.environ.copy()
    environment.update(MYSQL_ROOT_PASSWORD=password, MYSQL_PASSWORD=password)
    output = ROOT / "output/d6-15-target-runtime.log"
    output.parent.mkdir(exist_ok=True)
    with output.open("w") as log:
        def run(args, **kwargs):
            return subprocess.run(["rtk", "proxy", *args], cwd=ROOT, env=environment, check=True,
                                  stdout=log, stderr=subprocess.STDOUT, **kwargs)
        try:
            run(["docker", "run", "--rm", "-d", "--name", name, "-e", "MYSQL_ROOT_PASSWORD",
                 "-e", "MYSQL_PASSWORD", "-e", "MYSQL_USER=d6_04_runtime", "-e", "MYSQL_DATABASE=" + database,
                 "-p", "127.0.0.1::3306", IMAGE, "--log-bin-trust-function-creators=1"])
            deadline = time.monotonic() + 120
            while subprocess.run(["rtk", "proxy", "docker", "exec", name, "mysqladmin", "ping", "-uroot",
                                  "-p" + password, "--silent"], stdout=log, stderr=log).returncode:
                if time.monotonic() > deadline:
                    raise RuntimeError("Owned MySQL startup timed out")
                time.sleep(1)
            port = subprocess.check_output(["rtk", "proxy", "docker", "port", name, "3306/tcp"], text=True).strip().rsplit(":", 1)[-1]
            with socket.create_connection(("127.0.0.1", int(port)), timeout=5) as connection:
                connection.recv(4)
            java_home = subprocess.check_output(["rtk", "proxy", "/usr/libexec/java_home", "-v", "21"], text=True).strip()
            environment.update(JAVA_HOME=java_home, PATH=java_home + "/bin:" + environment["PATH"],
                D6_04_TEST_DATASOURCE_URL=f"jdbc:mysql://127.0.0.1:{port}/{database}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                D6_04_TEST_DATASOURCE_USERNAME="d6_04_runtime", D6_04_TEST_DATASOURCE_PASSWORD=password)
            for flag, selection in [("d6.15.proxy.upgrade.runtime", "ProxyConfirmationUpgradeRuntimeCase"),
                                    ("d6.12.handover.runtime", "HandoverHttpTest#approvedItemTargetChange*+approvedTransferTargetChange*+receivingAndRecoveryRaceAllowsOnlyOneOutcomeAndSharedTargetIsExact")]:
                run(["./mvnw", "-pl", "backend", "-Pquality", "-D" + flag + "=true", "-Dd6.15.proxy.runtime=true", "-Dtest=" + selection, "test"], timeout=600)
                report = ROOT / "backend/target/surefire-reports" / ("TEST-com.fams.auth." + selection.split("#")[0] + ".xml")
                suite = ElementTree.parse(report).getroot()
                if int(suite.get("tests", "0")) < 1 or any(int(suite.get(field, "0")) for field in ("errors", "failures", "skipped")):
                    raise RuntimeError("Required runtime suite did not execute cleanly: " + selection)
                print(selection + " PASS tests=" + suite.get("tests"), flush=True)
        finally:
            removed = subprocess.run(["rtk", "proxy", "docker", "rm", "-f", "-v", name], stdout=log, stderr=log)
            remaining = subprocess.run(["rtk", "proxy", "docker", "ps", "-a", "--filter", "name=^" + name + "$", "--format", "{{.Names}}"], capture_output=True, text=True)
            if removed.returncode or remaining.returncode or remaining.stdout.strip():
                raise RuntimeError("Owned MySQL cleanup failed: " + name)
    print("Owned runtime removed; evidence: " + str(output), flush=True)


if __name__ == "__main__":
    main()
