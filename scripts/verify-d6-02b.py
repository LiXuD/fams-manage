#!/usr/bin/env python3
"""Run the D6-02B real-runtime compatibility gate.

The gate deliberately uses only task-owned, randomly named Docker resources.
Every external command is invoked through ``rtk``; HTTP checks use Python's
standard library so the process and cookie behaviour remain observable.
"""

from __future__ import annotations

import json
import os
import re
import secrets
import socket
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from http import HTTPStatus
from pathlib import Path
from typing import Callable
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


ROOT = Path(__file__).resolve().parents[1]
IMAGE = "mysql:8.4.11@sha256:3466ba4a4828aa8d46fb7c3bc16b67b781c98413cf4ea0fac6feaa6e881faa26"
IMAGE_DIGEST = "sha256:3466ba4a4828aa8d46fb7c3bc16b67b781c98413cf4ea0fac6feaa6e881faa26"
RESOURCE_PREFIX = "fams-d6-02b-"
WRAPPER_VERSION = "3.3.4"
MAVEN_VERSION = "3.9.16"
JAVA_VERSION = "21.0.7"
NODE_VERSION = "v24.18.0"
NPM_VERSION = "11.16.0"
REPORT = ROOT / ".superpowers/sdd/2026-09-12-fams-stage6-development-plan/task-d6-02b-report.md"
LEDGER = ROOT / ".superpowers/sdd/2026-09-12-fams-stage6-development-plan/task-d6-02b-ledger.md"


class VerificationFailure(RuntimeError):
    """A runtime compatibility assertion failed."""


@dataclass
class CommandObservation:
    label: str
    command: str
    returncode: int
    excerpt: str


OBSERVATIONS: list[CommandObservation] = []
PROOFS: list[str] = []
SECRETS: list[str] = []
MYSQL_CONTAINER: str | None = None
MYSQL_VOLUME: str | None = None
MYSQL_ROOT_PASSWORD: str | None = None
MYSQL_USER_PASSWORD: str | None = None
MYSQL_DATABASE: str | None = None
SENSITIVE_PATTERNS = (
    re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    re.compile(r"(?i)(?<![A-Za-z0-9_])(?:AKIA|ASIA)[0-9A-Z]{16}(?![0-9A-Z])"),
    re.compile(r"(?<![A-Za-z0-9_])ghp_[A-Za-z0-9]{20,}(?![A-Za-z0-9])"),
    re.compile(r"(?<![A-Za-z0-9_])github_pat_[A-Za-z0-9_]{20,}(?![A-Za-z0-9_])"),
    re.compile(r"(?<![A-Za-z0-9_])sk-[A-Za-z0-9_-]{20,}(?![A-Za-z0-9_-])"),
    re.compile(r"(?<![A-Za-z0-9_])xox[bp]-[A-Za-z0-9_-]{20,}(?![A-Za-z0-9_-])"),
    re.compile(
        r"(?<![A-Za-z0-9_])/(?:Users|home)/[A-Za-z0-9._-]+/"
        r"(?:Library|IdeaProjects|Documents|\.m2|\.nvm)(?:/|$)"
    ),
)


def redact(value: str) -> str:
    """Remove credentials and machine-specific paths from reportable output."""

    for secret in SECRETS:
        if secret:
            value = value.replace(secret, "<redacted>")
    value = value.replace(str(ROOT), "<workspace>")
    value = re.sub(r"/Users/[A-Za-z0-9._-]+", "<machine-user>", value)
    value = re.sub(r"/home/[A-Za-z0-9._-]+", "<machine-user>", value)
    return value


def command_text(args: list[str]) -> str:
    return "rtk " + " ".join(redact(arg) for arg in args)


def excerpt(output: str, limit: int = 14) -> str:
    lines = [redact(line.strip()) for line in output.splitlines() if line.strip()]
    return "\n".join(lines[-limit:])


def record_proof(label: str, detail: str) -> None:
    proof = f"{label}: {detail}"
    PROOFS.append(proof)
    print(f"[{label}] {detail}")


def contains_sensitive_or_machine_path(content: str) -> bool:
    return any(pattern.search(content) for pattern in SENSITIVE_PATTERNS)


def run_rtk(
    args: list[str],
    *,
    env: dict[str, str] | None = None,
    timeout: float = 180,
) -> subprocess.CompletedProcess[str]:
    """Invoke an external command through rtk and retain non-sensitive output."""

    completed = subprocess.run(
        ["rtk", *args],
        cwd=ROOT,
        env=env or os.environ.copy(),
        capture_output=True,
        text=True,
        check=False,
        timeout=timeout,
    )
    return completed


def require_command(
    label: str,
    args: list[str],
    *,
    expected: int = 0,
    env: dict[str, str] | None = None,
    timeout: float = 180,
    show: bool = True,
) -> str:
    result = run_rtk(args, env=env, timeout=timeout)
    output = result.stdout + result.stderr
    observation = CommandObservation(label, command_text(args), result.returncode, excerpt(output))
    OBSERVATIONS.append(observation)
    if show:
        print(f"[{label}] exit={result.returncode}")
        if observation.excerpt:
            for line in observation.excerpt.splitlines()[-8:]:
                print(f"  {line}")
    if result.returncode != expected:
        raise VerificationFailure(f"{label} returned {result.returncode}, expected {expected}")
    return output


def choose_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


def random_resource(kind: str) -> str:
    return f"{RESOURCE_PREFIX}{kind}-{secrets.token_hex(8)}"


def assert_expected_failure(label: str, action: Callable[[], None]) -> None:
    try:
        action()
    except VerificationFailure:
        print(f"[{label}] REJECTED")
        return
    raise VerificationFailure(f"{label} was accepted")


def assert_java_version_output(output: str) -> None:
    """Accept only the exact frozen Java patch from a real version banner."""

    java_match = re.search(
        r'(?im)^\s*(?:openjdk|java)\s+version\s+"([0-9]+(?:\.[0-9]+){1,2})"',
        output,
    )
    if not java_match or java_match.group(1) != JAVA_VERSION:
        raise VerificationFailure(f"Java version is not {JAVA_VERSION}")


def assert_docker_image_metadata(docker_output: str, image_output: str) -> tuple[str, str]:
    """Require the pinned multi-arch image to match the current Linux engine."""

    docker_parts = docker_output.strip().split("|")
    if len(docker_parts) != 4:
        raise VerificationFailure(f"Unexpected Docker engine metadata: {docker_output}")
    _, _, engine_os, engine_arch = docker_parts
    if engine_os != "linux" or engine_arch not in {"arm64", "amd64"}:
        raise VerificationFailure(f"Unsupported Docker engine platform: {engine_os}/{engine_arch}")

    try:
        digest_json, image_os, image_arch = image_output.strip().rsplit("|", 2)
        repo_digests = json.loads(digest_json)
    except (ValueError, json.JSONDecodeError) as exc:
        raise VerificationFailure("Docker image metadata was not parseable") from exc
    if not isinstance(repo_digests, list) or not any(
        isinstance(digest, str) and digest.endswith("@" + IMAGE_DIGEST)
        for digest in repo_digests
    ):
        raise VerificationFailure("Pinned MySQL image digest is not present")
    if (image_os, image_arch) != (engine_os, engine_arch):
        raise VerificationFailure(
            "Pinned MySQL image platform does not match the Docker engine: "
            f"image={image_os}/{image_arch}, engine={engine_os}/{engine_arch}"
        )
    return image_os, image_arch


def validate_versions() -> None:
    java_output = require_command("Java version", ["java", "-version"], show=False)
    assert_java_version_output(java_output)
    print(f"[Java version] {JAVA_VERSION}")

    node_output = require_command("Node version", ["node", "--version"], show=False).strip()
    if node_output != NODE_VERSION:
        raise VerificationFailure(f"Node version is not {NODE_VERSION}")
    print(f"[Node version] {NODE_VERSION}")

    npm_output = require_command("npm version", ["npm", "--version"], show=False).strip()
    if npm_output != NPM_VERSION:
        raise VerificationFailure(f"npm version is not {NPM_VERSION}")
    print(f"[npm version] {NPM_VERSION}")

    maven_output = require_command("Maven version", ["./mvnw", "--version"], show=False)
    if f"Apache Maven {MAVEN_VERSION}" not in maven_output:
        raise VerificationFailure(f"Maven version is not {MAVEN_VERSION}")
    wrapper = (ROOT / ".mvn/wrapper/maven-wrapper.properties").read_text(encoding="utf-8")
    if f"wrapperVersion={WRAPPER_VERSION}" not in wrapper:
        raise VerificationFailure(f"Maven Wrapper version is not {WRAPPER_VERSION}")
    print(f"[Maven version] wrapper={WRAPPER_VERSION}, Maven={MAVEN_VERSION}")

    docker_output = require_command(
        "Docker engine",
        ["docker", "version", "--format", "{{.Client.Version}}|{{.Server.Version}}|{{.Server.Os}}|{{.Server.Arch}}"],
        show=False,
    ).strip()
    print(f"[Docker engine] {docker_output}")

    image_output = require_command(
        "Docker image metadata",
        ["docker", "image", "inspect", IMAGE, "--format", "{{json .RepoDigests}}|{{.Os}}|{{.Architecture}}"],
        show=False,
    ).strip()
    image_os, image_arch = assert_docker_image_metadata(docker_output, image_output)
    print(f"[Docker image] digest={IMAGE_DIGEST}, os={image_os}, architecture={image_arch}")

    def wrong_java() -> None:
        assert_java_version_output('openjdk version "25.0.2"')

    def wrong_image() -> None:
        assert_docker_image_metadata(
            docker_output,
            f'["mysql@sha256:deadbeef"]|{image_os}|{image_arch}',
        )

    assert_expected_failure("version mismatch fixture", wrong_java)
    assert_expected_failure("image mismatch fixture", wrong_image)


def verify_retained_d6_02a_gate() -> None:
    """Reject pre-existing generated drift before D6-02B can regenerate it."""

    require_command(
        "D6-02A complete gate",
        ["run", "python3 scripts/verify-d6-02a.py"],
        timeout=300,
    )


def verify_resolved_dependencies() -> None:
    output = require_command(
        "resolved dependency versions",
        ["./mvnw", "-pl", "backend", "dependency:tree", "-Dscope=runtime"],
        timeout=180,
    )
    markers = {
        "Spring Boot": "org.springframework.boot:spring-boot-flyway:jar:4.1.1",
        "MyBatis Starter": "org.mybatis.spring.boot:mybatis-spring-boot-starter:jar:4.1.0",
        "MyBatis core": "org.mybatis:mybatis:jar:3.5.19",
        "MyBatis Spring": "org.mybatis:mybatis-spring:jar:4.1.0",
        "Flyway core": "org.flywaydb:flyway-core:jar:12.4.0",
        "Spring Session JDBC": "org.springframework.session:spring-session-jdbc:jar:4.1.1",
        "MySQL Connector/J": "com.mysql:mysql-connector-j:jar:9.7.0",
        "Jackson 3": "tools.jackson.core:jackson-databind:jar:3.1.5",
    }
    for label, marker in markers.items():
        if marker not in output:
            raise VerificationFailure(f"{label} did not resolve to the pinned version")
    print("[resolved dependency versions] all pinned Boot/MyBatis/Flyway/Session/MySQL/Jackson markers present")

    pom = (ROOT / "backend/pom.xml").read_text(encoding="utf-8")
    if re.search(r"hibernate|spring-boot-starter-data-jpa|jakarta.persistence", pom, re.I):
        raise VerificationFailure("JPA/Hibernate dependency or ORM auto-DDL leaked into the probe")
    profile = (ROOT / "backend/src/main/resources/application-runtime-compatibility.properties").read_text(
        encoding="utf-8"
    )
    if "spring.session.jdbc.initialize-schema=never" not in profile:
        raise VerificationFailure("Spring Session schema auto-initialization is not explicitly disabled")
    if "spring.flyway.locations=classpath:db/migration/runtime_compatibility" not in profile:
        raise VerificationFailure("Compatibility-only Flyway location is not explicit")


def build_and_package() -> Path:
    require_command("Java package", ["./mvnw", "-pl", "backend", "-DskipTests", "package"], timeout=240)
    jars = sorted(
        path
        for path in (ROOT / "backend/target").glob("*.jar")
        if not path.name.endswith(".original")
    )
    if len(jars) != 1:
        raise VerificationFailure(f"Expected one executable jar, found {[path.name for path in jars]}")
    return jars[0]


def start_mysql() -> None:
    global MYSQL_CONTAINER, MYSQL_VOLUME, MYSQL_ROOT_PASSWORD, MYSQL_USER_PASSWORD, MYSQL_DATABASE

    MYSQL_CONTAINER = random_resource("mysql")
    MYSQL_VOLUME = random_resource("volume")
    MYSQL_DATABASE = random_resource("db")
    MYSQL_ROOT_PASSWORD = secrets.token_urlsafe(32)
    MYSQL_USER_PASSWORD = secrets.token_urlsafe(32)
    SECRETS.extend([MYSQL_ROOT_PASSWORD, MYSQL_USER_PASSWORD])
    host_port = choose_port()
    os.environ["D6_02B_MYSQL_HOST_PORT"] = str(host_port)

    require_command("create task volume", ["docker", "volume", "create", MYSQL_VOLUME])
    require_command(
        "start pinned MySQL",
        [
            "docker",
            "run",
            "--detach",
            "--name",
            MYSQL_CONTAINER,
            "--publish",
            f"127.0.0.1:{host_port}:3306",
            "--mount",
            f"type=volume,source={MYSQL_VOLUME},destination=/var/lib/mysql",
            "--env",
            f"MYSQL_ROOT_PASSWORD={MYSQL_ROOT_PASSWORD}",
            "--env",
            "MYSQL_DATABASE=" + MYSQL_DATABASE,
            "--env",
            "MYSQL_USER=d6_02b_runtime",
            "--env",
            f"MYSQL_PASSWORD={MYSQL_USER_PASSWORD}",
            IMAGE,
        ],
        timeout=60,
    )
    wait_for_mysql()


def wait_for_mysql(timeout: float = 90) -> None:
    if not MYSQL_CONTAINER or not MYSQL_ROOT_PASSWORD:
        raise VerificationFailure("MySQL task resources were not initialized")
    deadline = time.monotonic() + timeout
    last_error = ""
    while time.monotonic() < deadline:
        result = run_rtk(
            [
                "docker",
                "exec",
                "--env",
                f"MYSQL_PWD={MYSQL_ROOT_PASSWORD}",
                MYSQL_CONTAINER,
                "mysqladmin",
                "ping",
                "--protocol=TCP",
                "--host=127.0.0.1",
                "--user=root",
                "--silent",
            ],
            timeout=15,
        )
        if result.returncode == 0:
            print("[MySQL readiness] mysqld is alive")
            return
        last_error = redact(result.stderr.strip())
        time.sleep(1)
    raise VerificationFailure(f"MySQL did not become ready: {last_error}")


def mysql_sql(sql: str, *, user: str = "root", password: str | None = None) -> str:
    if not MYSQL_CONTAINER:
        raise VerificationFailure("MySQL container is unavailable")
    effective_password = password or MYSQL_ROOT_PASSWORD
    if not effective_password:
        raise VerificationFailure("MySQL password is unavailable")
    args = [
        "docker",
        "exec",
        "--env",
        f"MYSQL_PWD={effective_password}",
        MYSQL_CONTAINER,
        "mysql",
        "--protocol=TCP",
        "--batch",
        "--skip-column-names",
        "--raw",
        "--user=" + user,
        "--execute",
        sql,
    ]
    result = run_rtk(args, timeout=30)
    output = result.stdout + result.stderr
    OBSERVATIONS.append(CommandObservation("MySQL query", command_text(args), result.returncode, excerpt(output)))
    if result.returncode != 0:
        raise VerificationFailure("A task-scoped MySQL query failed")
    return result.stdout.strip()


def mysql_database_sql(sql: str) -> str:
    if not MYSQL_DATABASE:
        raise VerificationFailure("MySQL database is unavailable")
    return mysql_sql(
        f"USE `{MYSQL_DATABASE}`; {sql}",
        user="d6_02b_runtime",
        password=MYSQL_USER_PASSWORD,
    )


def start_app(jar: Path, profiles: str | None, port: int) -> tuple[subprocess.Popen[str], Path]:
    env = os.environ.copy()
    env["SERVER_PORT"] = str(port)
    env.pop("SPRING_PROFILES_ACTIVE", None)
    if profiles:
        env["SPRING_PROFILES_ACTIVE"] = profiles
        env["SPRING_DATASOURCE_URL"] = (
            f"jdbc:mysql://127.0.0.1:{os.environ['D6_02B_MYSQL_HOST_PORT']}/{MYSQL_DATABASE}"
            "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
        )
        env["SPRING_DATASOURCE_USERNAME"] = "d6_02b_runtime"
        env["SPRING_DATASOURCE_PASSWORD"] = MYSQL_USER_PASSWORD or ""
    log_fd, log_path = tempfile.mkstemp(prefix="fams-d6-02b-app-", suffix=".log")
    os.close(log_fd)
    log_file = Path(log_path)
    log_handle = log_file.open("w", encoding="utf-8")
    try:
        process = subprocess.Popen(
            ["rtk", "java", "-jar", str(jar), f"--server.port={port}"],
            cwd=ROOT,
            env=env,
            stdout=log_handle,
            stderr=subprocess.STDOUT,
            text=True,
        )
    except BaseException:
        log_handle.close()
        log_file.unlink(missing_ok=True)
        raise
    process._d6_02b_log_handle = log_handle  # type: ignore[attr-defined]
    return process, log_file


def read_app_log(log_file: Path) -> str:
    try:
        return redact(log_file.read_text(encoding="utf-8", errors="replace"))
    except OSError:
        return ""


def stop_app(process: subprocess.Popen[str] | None, log_file: Path | None) -> str:
    if process is not None:
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)
        handle = getattr(process, "_d6_02b_log_handle", None)
        if handle is not None:
            handle.close()
    content = read_app_log(log_file) if log_file else ""
    if log_file:
        log_file.unlink(missing_ok=True)
    return content


def http_request(
    port: int,
    path: str,
    *,
    method: str = "GET",
    body: dict[str, object] | None = None,
    cookie: str | None = None,
    timeout: float = 8,
) -> tuple[int, dict[str, str], str]:
    headers = {"Accept": "application/json"}
    payload: bytes | None = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        payload = json.dumps(body, separators=(",", ":")).encode("utf-8")
    if cookie:
        headers["Cookie"] = cookie
    request = Request(f"http://127.0.0.1:{port}{path}", data=payload, headers=headers, method=method)
    try:
        with urlopen(request, timeout=timeout) as response:
            return response.status, {key: value for key, value in response.headers.items()}, response.read().decode()
    except HTTPError as error:
        return error.code, {key: value for key, value in error.headers.items()}, error.read().decode()
    except (URLError, TimeoutError, ConnectionError):
        raise


def wait_for_http(process: subprocess.Popen[str], port: int, expected: int, path: str, timeout: float = 45) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process.poll() is not None and expected != 404:
            raise VerificationFailure(f"Application exited before HTTP {expected} became available")
        try:
            status, _, _ = http_request(port, path)
            if status == expected:
                return
        except (URLError, TimeoutError, ConnectionError):
            pass
        time.sleep(0.5)
    raise VerificationFailure(f"Application did not return HTTP {expected} for {path}")


def parse_json(status: int, body: str) -> dict[str, object]:
    if not body:
        raise VerificationFailure(f"HTTP {status} response was empty")
    try:
        value = json.loads(body)
    except json.JSONDecodeError as exc:
        raise VerificationFailure("HTTP response was not JSON") from exc
    if not isinstance(value, dict):
        raise VerificationFailure("HTTP response was not an object")
    return value


def assert_runtime_test_execution(output: str, report: Path) -> None:
    """Reject a superficially green Maven run when the DB-backed suite skipped."""

    summaries = re.findall(
        r"Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)",
        output,
    )
    if not summaries:
        raise VerificationFailure("Maven emitted no test summary")
    total_run, total_failures, total_errors, total_skipped = map(int, summaries[-1])
    if total_run < 12 or (total_failures, total_errors, total_skipped) != (0, 0, 0):
        raise VerificationFailure(f"Runtime Maven total was not clean and zero-skipped: {summaries[-1]}")

    try:
        suite = ET.parse(report).getroot()
        runtime_counts = tuple(
            int(suite.attrib.get(name, "-1"))
            for name in ("tests", "failures", "errors", "skipped")
        )
    except (ET.ParseError, OSError, ValueError) as exc:
        raise VerificationFailure("Runtime Surefire report was missing or invalid") from exc
    if runtime_counts != (3, 0, 0, 0):
        raise VerificationFailure(
            "Runtime HTTP suite did not execute exactly three clean, zero-skipped tests: "
            f"{runtime_counts}"
        )


def quote_key(key: str) -> str:
    return key.replace("`", "``")


def validate_schema() -> tuple[str, str]:
    if not MYSQL_DATABASE:
        raise VerificationFailure("MySQL database is unavailable")
    tables = mysql_sql(
        "SELECT table_name, engine FROM information_schema.tables "
        f"WHERE table_schema = '{MYSQL_DATABASE}' "
        "AND table_name IN ('d6_02b_runtime_probe','SPRING_SESSION','SPRING_SESSION_ATTRIBUTES') "
        "ORDER BY table_name;"
    )
    expected = {
        "SPRING_SESSION": "InnoDB",
        "SPRING_SESSION_ATTRIBUTES": "InnoDB",
        "d6_02b_runtime_probe": "InnoDB",
    }
    actual: dict[str, str] = {}
    for line in tables.splitlines():
        fields = line.split("\t")
        if len(fields) == 2:
            actual[fields[0]] = fields[1]
    if actual != expected:
        raise VerificationFailure(f"Fresh Flyway schema/engine mismatch: {actual}")
    history = mysql_database_sql(
        "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank;"
    )
    history_lines = history.splitlines()
    if len(history_lines) != 2 or any("\t1" not in line for line in history_lines):
        raise VerificationFailure(f"Unexpected fresh Flyway history: {history_lines}")
    if not {line.split("\t", 1)[0] for line in history_lines} == {"1", "2"}:
        raise VerificationFailure(f"Fresh Flyway versions are not V1/V2: {history_lines}")
    record_proof("fresh migration", "V1/V2 applied once; probe and JDBC Session tables are InnoDB")
    return tables, history


def assert_probe_response(status: int, body: str, key: str, value: str) -> None:
    if status not in (200, 201):
        raise VerificationFailure(f"Expected probe success, got HTTP {status}")
    data = parse_json(status, body)
    if data.get("probeKey") != key or data.get("probeValue") != value:
        raise VerificationFailure(f"Probe response did not preserve key/value: {data}")


def exercise_runtime(jar: Path) -> None:
    app_port = choose_port()
    process, log_file = start_app(jar, "runtime-compatibility", app_port)
    try:
        wait_for_http(process, app_port, 200, "/runtime-compatibility/health")
        status, _, body = http_request(app_port, "/runtime-compatibility/health")
        health = parse_json(status, body)
        if health.get("profile") != "runtime-compatibility" or health.get("status") != "ready":
            raise VerificationFailure(f"Unexpected runtime health response: {health}")
        _, history_before = validate_schema()

        committed_key = f"{RESOURCE_PREFIX}committed-{secrets.token_hex(8)}"
        committed_value = "committed-through-real-mybatis"
        status, _, body = http_request(
            app_port,
            "/runtime-compatibility/probes",
            method="POST",
            body={"probeKey": committed_key, "probeValue": committed_value},
        )
        if status != HTTPStatus.CREATED:
            raise VerificationFailure(f"Committed write returned HTTP {status}")
        assert_probe_response(status, body, committed_key, committed_value)
        status, _, body = http_request(app_port, f"/runtime-compatibility/probes/{committed_key}")
        assert_probe_response(status, body, committed_key, committed_value)
        record_proof("MyBatis write/read", "committed value survived the real HTTP round trip")

        rollback_key = f"{RESOURCE_PREFIX}rollback-{secrets.token_hex(8)}"
        status, _, body = http_request(
            app_port,
            "/runtime-compatibility/probes/rollback",
            method="POST",
            body={"probeKey": rollback_key},
        )
        rejection = parse_json(status, body)
        if status != HTTPStatus.CONFLICT or rejection.get("code") != "RUNTIME_COMPATIBILITY_TRANSACTION_REJECTED":
            raise VerificationFailure(f"Rollback response was not an explicit rejection: {status} {rejection}")
        if rejection.get("probeKey") != rollback_key:
            raise VerificationFailure("Rollback response lost the probe key")
        for suffix in ("-first", "-second"):
            status, _, _ = http_request(app_port, f"/runtime-compatibility/probes/{rollback_key}{suffix}")
            if status != HTTPStatus.NOT_FOUND:
                raise VerificationFailure(f"Rolled-back row {suffix} remained visible: HTTP {status}")
        db_count = mysql_database_sql(
            "SELECT COUNT(*) FROM d6_02b_runtime_probe "
            f"WHERE probe_key IN ('{rollback_key}-first','{rollback_key}-second');"
        )
        if db_count.strip() != "0":
            raise VerificationFailure(f"Transactional failure left {db_count} matching rows")
        record_proof(
            "transaction rollback",
            "explicit 409 rejection and zero matching rows after partial work",
        )

        proof_value = f"{RESOURCE_PREFIX}session-proof-{secrets.token_hex(8)}"
        status, headers, body = http_request(
            app_port,
            "/runtime-compatibility/session/proof",
            method="POST",
            body={"proofValue": proof_value},
        )
        session_body = parse_json(status, body)
        if status != HTTPStatus.OK or session_body.get("proofValue") != proof_value:
            raise VerificationFailure(f"Session mutation response was not correct: {status} {session_body}")
        set_cookie = headers.get("Set-Cookie")
        if not set_cookie or not set_cookie.startswith("FAMS_D6_02B_SESSION="):
            raise VerificationFailure("Real JDBC session did not return the expected cookie")
        cookie = set_cookie.split(";", 1)[0]
        status, _, body = http_request(app_port, "/runtime-compatibility/session/proof", cookie=cookie)
        if status != HTTPStatus.OK or parse_json(status, body).get("proofValue") != proof_value:
            raise VerificationFailure("The session cookie could not read its persisted proof")
        status, _, body = http_request(app_port, "/runtime-compatibility/session/proof")
        if status != HTTPStatus.UNAUTHORIZED or parse_json(status, body).get("code") != "RUNTIME_COMPATIBILITY_SESSION_UNAVAILABLE":
            raise VerificationFailure("A missing session cookie was accepted")
        status, _, body = http_request(
            app_port,
            "/runtime-compatibility/session/proof",
            cookie="FAMS_D6_02B_SESSION=not-a-real-session",
        )
        if status != HTTPStatus.UNAUTHORIZED or parse_json(status, body).get("code") != "RUNTIME_COMPATIBILITY_SESSION_UNAVAILABLE":
            raise VerificationFailure("A wrong session cookie was accepted")
        if mysql_database_sql("SELECT COUNT(*) FROM SPRING_SESSION;").strip() != "1":
            raise VerificationFailure("Expected one JDBC-backed session row")
        record_proof(
            "JDBC session",
            "real cookie mutation/read works; missing and wrong cookies are rejected",
        )

        # A second clean application start must validate the same history and preserve data.
        first_log = stop_app(process, log_file)
        process = None  # type: ignore[assignment]
        process, log_file = start_app(jar, "runtime-compatibility", app_port)
        wait_for_http(process, app_port, 200, "/runtime-compatibility/health")
        _, history_after_app_restart = validate_schema()
        if history_after_app_restart != history_before:
            raise VerificationFailure("Second clean startup changed Flyway history")
        status, _, body = http_request(app_port, f"/runtime-compatibility/probes/{committed_key}")
        assert_probe_response(status, body, committed_key, committed_value)
        status, _, body = http_request(app_port, "/runtime-compatibility/session/proof", cookie=cookie)
        if status != HTTPStatus.OK or parse_json(status, body).get("proofValue") != proof_value:
            raise VerificationFailure("Application restart lost the JDBC session proof")
        second_log = stop_app(process, log_file)
        process = None  # type: ignore[assignment]
        record_proof(
            "application restart",
            "committed data, session, and unchanged migration history persisted",
        )

        # Restart only the exact task container while retaining its exact volume.
        require_command("restart task MySQL", ["docker", "restart", MYSQL_CONTAINER or ""], timeout=60)
        wait_for_mysql()
        process, log_file = start_app(jar, "runtime-compatibility", app_port)
        wait_for_http(process, app_port, 200, "/runtime-compatibility/health")
        _, history_after_db_restart = validate_schema()
        if history_after_db_restart != history_before:
            raise VerificationFailure("Database restart changed Flyway history")
        status, _, body = http_request(app_port, f"/runtime-compatibility/probes/{committed_key}")
        assert_probe_response(status, body, committed_key, committed_value)
        status, _, body = http_request(app_port, "/runtime-compatibility/session/proof", cookie=cookie)
        if status != HTTPStatus.OK or parse_json(status, body).get("proofValue") != proof_value:
            raise VerificationFailure("MySQL restart lost the JDBC session proof")
        record_proof(
            "database restart",
            "same volume retained Flyway history, probe row, and JDBC session",
        )
    finally:
        log = stop_app(process, log_file)
        if log:
            selected = [line for line in log.splitlines() if any(token in line for token in (
                "Started ", "Flyway", "Tomcat started", "migration", "ERROR", "Exception"
            ))]
            if selected:
                print("[application log excerpt]")
                for line in selected[-10:]:
                    print(f"  {line}")


def exercise_absent_profile(jar: Path) -> None:
    port = choose_port()
    process, log_file = start_app(jar, None, port)
    try:
        wait_for_http(process, port, 404, "/runtime-compatibility/health", timeout=30)
        status, _, _ = http_request(port, "/runtime-compatibility/probes/no-profile")
        if status != HTTPStatus.NOT_FOUND:
            raise VerificationFailure("Runtime probe was reachable without its explicit profile")
        record_proof(
            "profile isolation",
            "absent runtime profile cannot expose a falsely healthy probe",
        )
    finally:
        stop_app(process, log_file)


def assert_invalid_migration_failure(log: str) -> None:
    """Require the exact Flyway fixture and its SQL failure, not any startup error."""

    required_evidence = {
        "Flyway exception class": (
            r"\b(?:org\.flywaydb\.[A-Za-z0-9_.$]*Exception|"
            r"Flyway[A-Za-z0-9_]*Exception)\b"
        ),
        "V999 fixture": r"\bV999__intentionally_invalid_fixture\.sql\b",
        "SQL failure": r"(?:SQL State\s*:|Error Code\s*:|SQL syntax|syntax error)",
    }
    missing = [
        label
        for label, pattern in required_evidence.items()
        if not re.search(pattern, log, re.I)
    ]
    if missing:
        raise VerificationFailure(
            "Invalid migration startup failure lacked exact evidence: " + ", ".join(missing)
        )


def exercise_invalid_migration(jar: Path) -> None:
    port = choose_port()
    process, log_file = start_app(jar, "runtime-compatibility,invalid-migration", port)
    deadline = time.monotonic() + 45
    while process.poll() is None and time.monotonic() < deadline:
        time.sleep(0.5)
    if process.poll() is None:
        stop_app(process, log_file)
        raise VerificationFailure("Invalid migration process did not fail closed")
    log = stop_app(process, log_file)
    if process.returncode == 0:
        raise VerificationFailure("Invalid migration process exited successfully")
    assert_invalid_migration_failure(log)
    record_proof(
        "invalid migration",
        "startup refused the intentionally invalid V999 fixture",
    )

    # This recovery operation is deliberately limited to the random task database.
    if not MYSQL_DATABASE:
        raise VerificationFailure("Task database is unavailable for recovery")
    mysql_sql(f"DROP DATABASE `{quote_key(MYSQL_DATABASE)}`; CREATE DATABASE `{quote_key(MYSQL_DATABASE)}`;")
    record_proof(
        "invalid migration recovery",
        "dropped and recreated only the random task database",
    )


def run_final_checks() -> None:
    if not MYSQL_DATABASE or not MYSQL_USER_PASSWORD:
        raise VerificationFailure("Real MySQL resources are unavailable for the opt-in HTTP test")
    require_command(
        "verifier parser tests",
        ["python3", "scripts/test_verify_d6_02b.py"],
        timeout=30,
    )
    runtime_test_env = os.environ.copy()
    runtime_test_env["D6_02B_TEST_DATASOURCE_URL"] = (
        f"jdbc:mysql://127.0.0.1:{runtime_test_env['D6_02B_MYSQL_HOST_PORT']}/{MYSQL_DATABASE}"
        "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
    )
    runtime_test_env["D6_02B_TEST_DATASOURCE_USERNAME"] = "d6_02b_runtime"
    runtime_test_env["D6_02B_TEST_DATASOURCE_PASSWORD"] = MYSQL_USER_PASSWORD
    runtime_test_output = require_command(
        "real runtime HTTP tests",
        ["./mvnw", "-pl", "backend", "-Dd6.02b.runtime=true", "test"],
        env=runtime_test_env,
        timeout=300,
    )
    assert_runtime_test_execution(
        runtime_test_output,
        ROOT / "backend/target/surefire-reports/TEST-com.fams.runtime.RuntimeCompatibilityHttpTest.xml",
    )
    record_proof(
        "real runtime HTTP tests",
        "opt-in JUnit ran exactly three compatibility tests with zero skipped tests",
    )
    require_command("Java tests", ["./mvnw", "-pl", "backend", "test"], timeout=240)
    require_command("locked Node install", ["npm", "ci"], timeout=180)
    require_command("shared TypeScript typecheck", ["npm", "run", "typecheck", "--workspace", "@fams/api-types"], timeout=120)
    require_command("commit-message fixtures", ["python3", "tools/git/test_commit_message_validator.py"], timeout=120)
    require_command("git diff check", ["git", "diff", "--check"], timeout=30)

    changed = set(
        require_command("changed-file list", ["git", "diff", "--name-only", "c9069f10a5a58a7d101f66490bee3a2ed51cced0"], show=False).splitlines()
    )
    changed.update(
        require_command("untracked-file list", ["git", "ls-files", "--others", "--exclude-standard"], show=False).splitlines()
    )
    # Scan only high-confidence literal credentials and absolute machine paths.
    # Generic ``password=...`` matching self-matches configuration code and
    # produces false positives for placeholders and generated test inputs.
    violations: list[str] = []
    for relative in sorted(path for path in changed if path):
        path = ROOT / relative
        if not path.is_file() or ".superpowers" in path.parts:
            continue
        try:
            content = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        for pattern in SENSITIVE_PATTERNS:
            if pattern.search(content):
                violations.append(f"{relative}: {pattern.pattern}")
    if violations:
        raise VerificationFailure(f"Sensitive/machine-path scan failed: {violations}")
    print(f"[tracked-file scan] checked {len(changed)} changed/untracked paths; no sensitive or machine-path matches")


def cleanup_resources() -> None:
    failures: list[str] = []

    def attempt(label: str, step: str, args: list[str]) -> subprocess.CompletedProcess[str] | None:
        try:
            result = run_rtk(args, timeout=60)
        except (subprocess.TimeoutExpired, OSError) as exc:
            failures.append(f"{label} {step} raised {type(exc).__name__}")
            return None
        if result.returncode != 0:
            failures.append(f"{label} {step} returned {result.returncode}")
            return None
        return result

    def cleanup_exact(
        label: str,
        name: str,
        list_args: list[str],
        remove_args: list[str],
    ) -> None:
        before = attempt(label, "pre-cleanup enumeration", list_args)
        should_remove = before is None or name in {
            line.strip() for line in before.stdout.splitlines()
        }

        if should_remove:
            attempt(label, "removal", remove_args)

        after = attempt(label, "post-cleanup enumeration", list_args)
        if after is not None and name in {line.strip() for line in after.stdout.splitlines()}:
            failures.append(f"exact task {label} remained after cleanup")

    if MYSQL_CONTAINER:
        cleanup_exact(
            "container",
            MYSQL_CONTAINER,
            ["docker", "ps", "-a", "--format", "{{.Names}}"],
            ["docker", "rm", "--force", MYSQL_CONTAINER],
        )
    if MYSQL_VOLUME:
        cleanup_exact(
            "volume",
            MYSQL_VOLUME,
            ["docker", "volume", "ls", "--format", "{{.Name}}"],
            ["docker", "volume", "rm", MYSQL_VOLUME],
        )

    if failures:
        raise VerificationFailure("Docker cleanup could not be verified: " + "; ".join(failures))
    record_proof(
        "cleanup",
        "exact task container and volume removed; no matching resources remain",
    )


def write_report(status: str, error: str | None = None) -> None:
    REPORT.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        "# D6-02B implementation report (ignored local evidence)",
        "",
        f"- Status: `{status}`",
        f"- Base: `c9069f10a5a58a7d101f66490bee3a2ed51cced0`",
        "- Branch: `feat/D6-02B-runtime-compatibility`",
        "- Model: `gpt-5.6-luna`",
        "- Runtime resources: randomly named with the `fams-d6-02b-` prefix; credentials are omitted.",
        "",
        "## TDD boundary",
        "",
        "Before the runtime implementation existed, the three real-socket expectations were run and the ",
        "Surefire result was `Tests run: 3, Failures: 3, Errors: 0, Skipped: 0`; all expected routes returned HTTP 404.",
        "The raw report was inspected before GREEN, but the interrupted subagent did not preserve its exact shell invocation.",
        "Subsequent command observations below are the reproducible GREEN path.",
        "",
        "## Runtime proofs",
        "",
        *(f"- {proof}" for proof in PROOFS),
        *([] if PROOFS else ["- No runtime proof was recorded before failure."]),
        "",
        "## Command observations",
        "",
    ]
    for observation in OBSERVATIONS:
        lines.extend([
            f"### {observation.label}",
            f"- Command: `{observation.command}`",
            f"- Exit: `{observation.returncode}`",
            "- Excerpt:",
            "```text",
            observation.excerpt or "(no non-sensitive output)",
            "```",
            "",
        ])
    if error:
        lines.extend(["## Failure", "", f"`{redact(error)}`", ""])
    REPORT.write_text("\n".join(lines), encoding="utf-8")


def append_ledger(status: str, error: str | None) -> None:
    LEDGER.parent.mkdir(parents=True, exist_ok=True)
    timestamp = time.strftime("%Y-%m-%dT%H:%M:%S%z")
    detail = redact(error) if error else "verification completed"
    with LEDGER.open("a", encoding="utf-8") as ledger:
        ledger.write(f"- {timestamp} status={status}; {detail}; observations={len(OBSERVATIONS)}\n")


def main() -> int:
    jar: Path | None = None
    status = "FAILED"
    error: str | None = None
    exit_code = 1
    try:
        validate_versions()
        verify_retained_d6_02a_gate()
        verify_resolved_dependencies()
        jar = build_and_package()
        start_mysql()
        exercise_invalid_migration(jar)
        exercise_absent_profile(jar)
        exercise_runtime(jar)
        run_final_checks()
        status = "IN_REVIEW"
        print("D6-02B verification passed; implementation remains IN_REVIEW pending independent review.")
        exit_code = 0
    except (VerificationFailure, subprocess.TimeoutExpired, OSError) as exc:
        error = str(exc)
        print(f"D6-02B verification failed: {redact(error)}", file=sys.stderr)
    finally:
        try:
            cleanup_resources()
        except (VerificationFailure, subprocess.TimeoutExpired, OSError) as cleanup_error:
            status = "FAILED"
            error = f"{error or ''}; cleanup: {cleanup_error}"
            exit_code = 1
            print(f"D6-02B cleanup failed: {cleanup_error}", file=sys.stderr)
        write_report(status, error)
        append_ledger(status, error)
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
