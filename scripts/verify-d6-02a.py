#!/usr/bin/env python3
"""Verify the D6-02A contract generation and real-HTTP probe boundary."""

from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
JAVA_TARGET = ROOT / "backend" / "target"
JAVA_GENERATED = ROOT / "backend" / "target" / "generated-sources" / "openapi" / "java"
TS_TARGET = ROOT / "packages" / "api-types" / "target"
TS_GENERATED = TS_TARGET / "generated-sources" / "openapi" / "typescript"
TS_PACKAGE_SOURCE = ROOT / "packages" / "api-types" / "src"


class VerificationFailure(RuntimeError):
    """A generated-output policy assertion failed."""


def run_rtk(command: str) -> subprocess.CompletedProcess[str]:
    """Run every external verification command through rtk."""

    return subprocess.run(
        ["rtk", "run", command],
        cwd=ROOT,
        env=os.environ.copy(),
        capture_output=True,
        text=True,
        check=False,
    )


def focused_output(output: str) -> list[str]:
    patterns = (
        r"Java version",
        r"21\.0\.7",
        r"v24\.18\.0",
        r"11\.16\.0",
        r"Apache Maven",
        r"OpenAPI Generator",
        r"Spec has no errors",
        r"No validation issues",
        r"Tests run:",
        r"BUILD SUCCESS",
        r"TypeScript:",
        r"up to date",
        r"error",
        r"warning",
    )
    lines = [line.strip() for line in output.splitlines() if line.strip()]
    selected = [line for line in lines if any(re.search(pattern, line, re.I) for pattern in patterns)]
    return selected[-16:]


def require_step(label: str, command: str, expected: int = 0) -> str:
    result = run_rtk(command)
    print(f"[{label}] exit={result.returncode}: rtk run {command}")
    output = result.stdout + result.stderr
    lines = [line.strip() for line in output.splitlines() if line.strip()]
    selected = lines if label == "final status" else focused_output(output)
    for line in selected[-16:]:
        print(f"  {line}")
    if result.returncode != expected:
        print(f"Verification failed at {label}.", file=sys.stderr)
        sys.exit(1)
    return output


def manifest(root: Path) -> dict[str, str]:
    if not root.is_dir():
        raise VerificationFailure(f"Generated root is missing: {root}")
    result: dict[str, str] = {}
    for path in sorted(path for path in root.rglob("*") if path.is_file()):
        relative = path.relative_to(root).as_posix()
        result[relative] = hashlib.sha256(path.read_bytes()).hexdigest()
    return result


def manifest_or_empty(root: Path) -> dict[str, str]:
    return manifest(root) if root.exists() else {}


def clear_generated_outputs() -> None:
    """Remove only the two task-owned build targets before generation."""

    for root in (JAVA_TARGET, TS_TARGET):
        if root.is_dir() and not root.is_symlink():
            shutil.rmtree(root)
        elif root.exists():
            root.unlink()


def assert_generated_roots_empty() -> None:
    for root in (JAVA_TARGET, TS_TARGET):
        if root.exists():
            raise VerificationFailure(
                f"Generated target was not empty before clean generation: {root}"
            )


def preexisting_differences(expected: dict[str, str], candidate: Path) -> list[str]:
    actual = manifest_or_empty(candidate)
    differences = sorted(set(expected) ^ set(actual))
    differences.extend(
        sorted(path for path in set(expected) & set(actual) if expected[path] != actual[path])
    )
    return differences


def assert_preexisting_equivalent(expected: dict[str, str], candidate: Path, label: str) -> None:
    differences = preexisting_differences(expected, candidate)
    if differences:
        raise VerificationFailure(f"{label} differs from authoritative generation: {differences}")


def expect_policy_rejection(expected: dict[str, str], candidate: Path, label: str) -> None:
    try:
        assert_preexisting_equivalent(expected, candidate, label)
    except VerificationFailure:
        print(f"[generated negative case] {label}=REJECTED")
        return
    raise VerificationFailure(f"Generated-output negative case was accepted: {label}")


def generated_policy_negative_cases(java_expected: dict[str, str], ts_expected: dict[str, str]) -> None:
    with tempfile.TemporaryDirectory(prefix="d6-02a-generated-policy-") as temporary:
        fixture_root = Path(temporary)
        java_edit = fixture_root / "java-edit"
        shutil.copytree(JAVA_GENERATED, java_edit)
        java_file = next(java_edit.rglob("*.java"))
        java_file.write_text(java_file.read_text(encoding="utf-8") + "\n// manual patch\n", encoding="utf-8")
        expect_policy_rejection(java_expected, java_edit, "Java content edit")

        ts_edit = fixture_root / "typescript-edit"
        shutil.copytree(TS_TARGET, ts_edit)
        ts_file = next(ts_edit.rglob("*.ts"))
        ts_file.write_text(
            ts_file.read_text(encoding="utf-8") + "\n// manual patch\n", encoding="utf-8"
        )
        expect_policy_rejection(ts_expected, ts_edit, "TypeScript content edit")

        ts_stale = fixture_root / "typescript-stale"
        shutil.copytree(TS_TARGET, ts_stale)
        (
            ts_stale
            / "generated-sources"
            / "openapi"
            / "typescript"
            / "models"
            / "StaleGeneratedModel.ts"
        ).write_text(
            "// stale generated file\n", encoding="utf-8"
        )
        expect_policy_rejection(ts_expected, ts_stale, "TypeScript stale extra file")

        empty = fixture_root / "empty-no-preexisting-tree"
        assert_preexisting_equivalent({}, empty, "empty pre-existing tree")
        print("[generated negative case] empty pre-existing tree=ACCEPTED")


def compare_generation() -> None:
    with tempfile.TemporaryDirectory(prefix="d6-02a-preexisting-generated-") as temporary:
        preserved = Path(temporary)
        existing: dict[str, Path] = {}
        for label, root in (("java", JAVA_GENERATED), ("typescript", TS_TARGET)):
            if root.is_dir() and any(root.iterdir()):
                existing[label] = preserved / label
                shutil.copytree(root, existing[label])

        clear_generated_outputs()
        assert_generated_roots_empty()
        require_step(
            "clean generation 1",
            "./mvnw -pl backend clean generate-resources -DskipTests",
        )
        first_java = manifest(JAVA_GENERATED)
        first_typescript = manifest(TS_GENERATED)
        first_typescript_target = manifest(TS_TARGET)

        if existing:
            for label, candidate in existing.items():
                expected = first_java if label == "java" else first_typescript_target
                authoritative = JAVA_GENERATED if label == "java" else TS_TARGET
                assert_preexisting_equivalent(expected, candidate, f"pre-existing {label} tree")
                print(f"[pre-existing generated tree] {label}=equivalent before discard ({authoritative})")
        else:
            print("[pre-existing generated tree] none; empty-tree path accepted")

        generated_policy_negative_cases(first_java, first_typescript_target)

        clear_generated_outputs()
        assert_generated_roots_empty()
        require_step(
            "clean generation 2",
            "./mvnw -pl backend clean generate-resources -DskipTests",
        )

    java_differences = preexisting_differences(first_java, JAVA_GENERATED)
    typescript_differences = preexisting_differences(first_typescript_target, TS_TARGET)
    print(
        "[determinism] Java files="
        f"{len(first_java)}, TypeScript files={len(first_typescript)}, "
        f"differences={len(java_differences) + len(typescript_differences)}"
    )
    if java_differences or typescript_differences:
        print(f"  Java differences: {java_differences}", file=sys.stderr)
        print(f"  TypeScript differences: {typescript_differences}", file=sys.stderr)
        sys.exit(1)


def generated_output_policy() -> None:
    tracked = require_step(
        "generated output policy",
        "git ls-files backend/target packages/api-types/target",
    ).strip()
    if tracked:
        print(f"Generated files are versioned and must not be authoritative:\n{tracked}", file=sys.stderr)
        sys.exit(1)

    java_sources = sorted(JAVA_GENERATED.rglob("*.java"))
    typescript_sources = sorted(TS_GENERATED.rglob("*.ts"))
    if not java_sources or not typescript_sources:
        print("Expected generated Java and TypeScript source files are missing.", file=sys.stderr)
        sys.exit(1)
    if not all(
        "org.openapitools.codegen.languages.SpringCodegen" in source.read_text(encoding="utf-8")
        for source in java_sources
        if source.name != "package-info.java"
    ):
        print("A generated Java source lacks the SpringCodegen marker.", file=sys.stderr)
        sys.exit(1)
    generated_typescript_sources = [
        source
        for source in typescript_sources
        if source.relative_to(TS_GENERATED).as_posix() not in {"index.ts", "models/index.ts"}
    ]
    if not all(
        "auto generated by OpenAPI Generator" in source.read_text(encoding="utf-8")
        for source in generated_typescript_sources
    ):
        print("A generated TypeScript source lacks the OpenAPI Generator marker.", file=sys.stderr)
        sys.exit(1)
    contract_types = (TS_GENERATED / "models" / "ContractProbe.ts").read_text(encoding="utf-8")
    if "businessDate: string;" not in contract_types or "occurredAt: string;" not in contract_types:
        print("Shared TypeScript dates must remain wire strings.", file=sys.stderr)
        sys.exit(1)
    if list(TS_TARGET.rglob("*Api.ts")):
        print("Generated TypeScript API transport files are out of scope.", file=sys.stderr)
        sys.exit(1)
    if (TS_TARGET / "runtime.ts").exists():
        print("Generated TypeScript runtime transport is out of scope.", file=sys.stderr)
        sys.exit(1)
    package_typescript_sources = sorted(TS_PACKAGE_SOURCE.rglob("*.ts"))
    transport_pattern = re.compile(r"\b(?:BaseAPI|FetchAPI|RequestInit|Response|FormData|fetch)\b")
    for source in [*typescript_sources, *package_typescript_sources]:
        if transport_pattern.search(source.read_text(encoding="utf-8")):
            print(f"TypeScript transport token found in model surface: {source}", file=sys.stderr)
            sys.exit(1)
    ts_config = json.loads(
        (ROOT / "packages" / "api-types" / "tsconfig.json").read_text(encoding="utf-8")
    )
    if ts_config.get("compilerOptions", {}).get("lib") != ["ES2022"]:
        print("TypeScript verification must use an explicit ES2022-only lib without DOM.", file=sys.stderr)
        sys.exit(1)
    print(
        "[generated roots] Java="
        f"{JAVA_GENERATED.relative_to(ROOT)}, TypeScript={TS_GENERATED.relative_to(ROOT)}; "
        "targets ignored, no generated files tracked, markers present, wire dates are strings, no transport/runtime"
    )


def main() -> None:
    java_version = require_step("Java runtime", "java -version")
    if not re.search(r'(?:java|openjdk) version "21\.', java_version, re.I):
        print("Java 21 is required for D6-02A.", file=sys.stderr)
        sys.exit(1)
    node_version = require_step("Node runtime", "node --version")
    if not re.search(r"\bv24\.18\.0\b", node_version):
        print("Node 24.18.0 is required for D6-02A.", file=sys.stderr)
        sys.exit(1)
    npm_version = require_step("npm runtime", "npm --version")
    if not re.search(r"\b11\.16\.0\b", npm_version):
        print("npm 11.16.0 is required for D6-02A.", file=sys.stderr)
        sys.exit(1)
    maven_version = require_step("Maven wrapper", "./mvnw --version")
    if not re.search(r"Apache Maven 3\.9\.16", maven_version):
        print("Maven distribution 3.9.16 is required for D6-02A.", file=sys.stderr)
        sys.exit(1)
    require_step("OpenAPI validation", "./mvnw -pl backend validate -DskipTests")
    compare_generation()
    generated_output_policy()
    require_step("Java compile", "./mvnw -pl backend clean compile -DskipTests")
    require_step("locked Node install", "npm ci --ignore-scripts")
    require_step("TypeScript typecheck", "npm --workspace packages/api-types run typecheck")
    require_step("real-socket HTTP tests", "./mvnw -pl backend test")
    require_step("commit-message suite", "python3 tools/git/test_commit_message_validator.py")
    require_step("diff check", "git diff --check")
    require_step("final status", "git status --short --branch")
    print("D6-02A verification passed.")


if __name__ == "__main__":
    main()
