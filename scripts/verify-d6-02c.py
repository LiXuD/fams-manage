#!/usr/bin/env python3
"""Verify the D6-02C design package, generated types, and retained gates."""

from __future__ import annotations

import csv
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import uuid
from datetime import date, datetime
from pathlib import Path
from typing import Any, Iterator

import yaml


ROOT = Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "contracts/fams-core-v1.yaml"
EXAMPLES = ROOT / "contracts/examples/d6-02c"
DOMAIN_DESIGN = ROOT / "docs/development/D6-02C-domain-data-design.md"
PERMISSION_CONTRACT = ROOT / "docs/development/D6-02C-permission-error-contract.md"
TRACEABILITY = ROOT / "docs/development/D6-02C-traceability.csv"
JAVA_TARGET = ROOT / "backend/target"
JAVA_GENERATED = JAVA_TARGET / "generated-sources/openapi/java"
TS_TARGET = ROOT / "packages/api-types/target"
TS_GENERATED = TS_TARGET / "generated-sources/openapi/typescript"
PY_YAML_VERSION = "6.0.2"
OPENAPI_GENERATOR_VERSION = "7.25.0"
METHODS = {"get", "post", "put", "patch", "delete"}
OWNED_GENERATED_TARGETS = (JAVA_TARGET, TS_TARGET)
RETAINED_GATES = ("scripts/verify-d6-02a.py", "scripts/verify-d6-02b.py")


class VerificationFailure(RuntimeError):
    """A fail-closed D6-02C assertion was not satisfied."""


class UniqueKeyLoader(yaml.SafeLoader):
    """YAML loader that rejects duplicate mapping keys."""


def construct_unique_mapping(
    loader: UniqueKeyLoader, node: yaml.MappingNode, deep: bool = False
) -> dict[Any, Any]:
    mapping: dict[Any, Any] = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in mapping:
            raise VerificationFailure(f"duplicate YAML key: {key!r}")
        mapping[key] = loader.construct_object(value_node, deep=deep)
    return mapping


UniqueKeyLoader.add_constructor(
    yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG,
    construct_unique_mapping,
)


def assert_pyyaml_version(version: str) -> None:
    if version != PY_YAML_VERSION:
        raise VerificationFailure(
            f"PyYAML must be exactly {PY_YAML_VERSION}; observed {version}"
        )


def load_contract() -> dict[str, Any]:
    assert_pyyaml_version(yaml.__version__)
    if not CONTRACT.is_file():
        raise VerificationFailure(f"missing authoritative contract: {CONTRACT}")
    document = yaml.load(CONTRACT.read_text(encoding="utf-8"), Loader=UniqueKeyLoader)
    if not isinstance(document, dict) or document.get("openapi") != "3.0.4":
        raise VerificationFailure("the authoritative contract must be OpenAPI 3.0.4")
    return document


def decode_pointer_token(token: str) -> str:
    return token.replace("~1", "/").replace("~0", "~")


def resolve_local_ref(document: dict[str, Any], reference: str) -> Any:
    if not reference.startswith("#/"):
        raise VerificationFailure(f"only local OpenAPI references are allowed: {reference}")
    current: Any = document
    for raw_token in reference[2:].split("/"):
        token = decode_pointer_token(raw_token)
        if not isinstance(current, dict) or token not in current:
            raise VerificationFailure(f"unresolved local reference: {reference}")
        current = current[token]
    return current


def walk_values(value: Any) -> Iterator[Any]:
    yield value
    if isinstance(value, dict):
        for child in value.values():
            yield from walk_values(child)
    elif isinstance(value, list):
        for child in value:
            yield from walk_values(child)


def assert_all_local_refs(document: dict[str, Any]) -> None:
    for value in walk_values(document):
        if isinstance(value, dict) and "$ref" in value:
            reference = value["$ref"]
            if not isinstance(reference, str):
                raise VerificationFailure("OpenAPI $ref must be a string")
            resolve_local_ref(document, reference)


def operations(document: dict[str, Any]) -> dict[str, tuple[str, str, dict[str, Any]]]:
    result: dict[str, tuple[str, str, dict[str, Any]]] = {}
    for path, item in document.get("paths", {}).items():
        if not isinstance(item, dict):
            continue
        for method, operation in item.items():
            if method not in METHODS or not isinstance(operation, dict):
                continue
            operation_id = operation.get("operationId")
            if not isinstance(operation_id, str) or not operation_id:
                raise VerificationFailure(f"missing operationId: {method.upper()} {path}")
            if operation_id in result:
                raise VerificationFailure(f"duplicate operationId: {operation_id}")
            result[operation_id] = (path, method, operation)
    return result


def assert_unique_operation_ids(document: dict[str, Any]) -> None:
    operations(document)


def parameter_ref_names(operation: dict[str, Any]) -> set[str]:
    return {
        parameter.get("$ref", "").rsplit("/", 1)[-1]
        for parameter in operation.get("parameters", [])
        if isinstance(parameter, dict)
    }


def assert_write_contracts(document: dict[str, Any]) -> None:
    for operation_id, (path, method, operation) in operations(document).items():
        if method not in {"post", "put", "patch", "delete"}:
            continue
        if not operation.get("x-business-write", False):
            if operation_id not in {"login", "logout"}:
                raise VerificationFailure(
                    f"unclassified non-business mutation: {method.upper()} {path}"
                )
            if not operation.get("x-exception-rationale"):
                raise VerificationFailure(f"missing mutation exception rationale: {operation_id}")
            continue
        for extension in (
            "x-actor",
            "x-version",
            "x-transaction",
            "x-audit-event",
            "x-error-codes",
            "x-required-error-statuses",
        ):
            if not operation.get(extension):
                raise VerificationFailure(f"{operation_id} is missing {extension}")
        refs = parameter_ref_names(operation)
        if not {"IdempotencyKey", "CsrfToken"}.issubset(refs):
            raise VerificationFailure(f"{operation_id} is missing idempotency or CSRF")
        for status in operation["x-required-error-statuses"]:
            if str(status) not in operation.get("responses", {}):
                raise VerificationFailure(
                    f"{operation_id} requires undeclared HTTP status {status}"
                )


def validate_format(value: str, format_name: str, location: str) -> None:
    try:
        if format_name == "uuid":
            uuid.UUID(value)
        elif format_name == "date":
            if date.fromisoformat(value).isoformat() != value:
                raise ValueError("date is not canonical")
        elif format_name == "date-time":
            parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
            if parsed.tzinfo is None:
                raise ValueError("date-time has no offset")
    except ValueError as exc:
        raise VerificationFailure(f"{location}: invalid {format_name}: {value!r}") from exc


def validate_instance(
    value: Any,
    schema: dict[str, Any],
    document: dict[str, Any],
    location: str,
) -> None:
    if "$ref" in schema:
        resolved = resolve_local_ref(document, schema["$ref"])
        if not isinstance(resolved, dict):
            raise VerificationFailure(f"{location}: schema reference is not an object")
        validate_instance(value, resolved, document, location)
        remaining = {key: item for key, item in schema.items() if key != "$ref"}
        if remaining:
            validate_instance(value, remaining, document, location)
        return

    if value is None:
        if schema.get("nullable"):
            return
        raise VerificationFailure(f"{location}: null is not allowed")

    for index, branch in enumerate(schema.get("allOf", [])):
        validate_instance(value, branch, document, f"{location}.allOf[{index}]")

    if "oneOf" in schema:
        matches = 0
        failures: list[str] = []
        for index, branch in enumerate(schema["oneOf"]):
            try:
                validate_instance(value, branch, document, f"{location}.oneOf[{index}]")
                matches += 1
            except VerificationFailure as exc:
                failures.append(str(exc))
        if matches != 1:
            raise VerificationFailure(
                f"{location}: expected exactly one oneOf match, got {matches}; {failures[:2]}"
            )
        return

    if "enum" in schema and value not in schema["enum"]:
        raise VerificationFailure(f"{location}: {value!r} is outside enum {schema['enum']!r}")

    type_name = schema.get("type")
    if type_name == "object" or "properties" in schema:
        if not isinstance(value, dict):
            raise VerificationFailure(f"{location}: expected object")
        required = schema.get("required", [])
        missing = [name for name in required if name not in value]
        if missing:
            raise VerificationFailure(f"{location}: missing required properties {missing}")
        properties = schema.get("properties", {})
        additional = schema.get("additionalProperties", True)
        for name, child in value.items():
            if name in properties:
                validate_instance(child, properties[name], document, f"{location}.{name}")
            elif additional is False:
                raise VerificationFailure(f"{location}: unknown property {name!r}")
            elif isinstance(additional, dict):
                validate_instance(child, additional, document, f"{location}.{name}")
        if len(value) < int(schema.get("minProperties", 0)):
            raise VerificationFailure(f"{location}: too few properties")
        return

    if type_name == "array":
        if not isinstance(value, list):
            raise VerificationFailure(f"{location}: expected array")
        if len(value) < int(schema.get("minItems", 0)):
            raise VerificationFailure(f"{location}: too few items")
        if "maxItems" in schema and len(value) > int(schema["maxItems"]):
            raise VerificationFailure(f"{location}: too many items")
        if schema.get("uniqueItems"):
            canonical = [json.dumps(item, sort_keys=True) for item in value]
            if len(canonical) != len(set(canonical)):
                raise VerificationFailure(f"{location}: duplicate array items")
        item_schema = schema.get("items")
        if isinstance(item_schema, dict):
            for index, item in enumerate(value):
                validate_instance(item, item_schema, document, f"{location}[{index}]")
        return

    if type_name == "string":
        if not isinstance(value, str):
            raise VerificationFailure(f"{location}: expected string")
        if len(value) < int(schema.get("minLength", 0)):
            raise VerificationFailure(f"{location}: string is too short")
        if "maxLength" in schema and len(value) > int(schema["maxLength"]):
            raise VerificationFailure(f"{location}: string is too long")
        if "pattern" in schema and re.search(schema["pattern"], value) is None:
            raise VerificationFailure(f"{location}: string does not match pattern")
        if "format" in schema:
            validate_format(value, schema["format"], location)
        return

    if type_name == "integer":
        if isinstance(value, bool) or not isinstance(value, int):
            raise VerificationFailure(f"{location}: expected integer")
    elif type_name == "number":
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            raise VerificationFailure(f"{location}: expected number")
    elif type_name == "boolean":
        if not isinstance(value, bool):
            raise VerificationFailure(f"{location}: expected boolean")
        return
    elif type_name is None:
        return
    else:
        raise VerificationFailure(f"{location}: unsupported schema type {type_name!r}")

    if "minimum" in schema and value < schema["minimum"]:
        raise VerificationFailure(f"{location}: value is below minimum")
    if "maximum" in schema and value > schema["maximum"]:
        raise VerificationFailure(f"{location}: value is above maximum")


def resolved_response_schema(
    document: dict[str, Any], operation: dict[str, Any], status: int
) -> dict[str, Any] | None:
    response = operation.get("responses", {}).get(str(status))
    if not isinstance(response, dict):
        raise VerificationFailure(f"operation does not declare example status {status}")
    if "$ref" in response:
        response = resolve_local_ref(document, response["$ref"])
    if not isinstance(response, dict):
        return None
    content = response.get("content", {})
    media = content.get("application/json") if isinstance(content, dict) else None
    schema = media.get("schema") if isinstance(media, dict) else None
    return schema if isinstance(schema, dict) else None


def request_schema(operation: dict[str, Any]) -> dict[str, Any] | None:
    body = operation.get("requestBody")
    if not isinstance(body, dict):
        return None
    media = body.get("content", {}).get("application/json")
    schema = media.get("schema") if isinstance(media, dict) else None
    return schema if isinstance(schema, dict) else None


def validate_examples(document: dict[str, Any]) -> None:
    if not EXAMPLES.is_dir():
        raise VerificationFailure(f"missing example directory: {EXAMPLES}")
    operation_map = operations(document)
    files = sorted(EXAMPLES.glob("*.json"))
    if not files:
        raise VerificationFailure("no D6-02C examples were found")
    for path in files:
        try:
            example = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as exc:
            raise VerificationFailure(f"{path.name}: invalid JSON") from exc
        if not isinstance(example, dict):
            raise VerificationFailure(f"{path.name}: example wrapper must be an object")
        operation_id = example.get("operationId")
        if operation_id not in operation_map:
            if example.get("evidenceType") and example.get("implemented") is False:
                continue
            raise VerificationFailure(f"{path.name}: unknown operationId {operation_id!r}")
        if example.get("kind") not in {"positive", "rejection"}:
            raise VerificationFailure(f"{path.name}: invalid example kind")
        if not isinstance(example.get("status"), int):
            raise VerificationFailure(f"{path.name}: status must be an integer")
        if "payload" not in example:
            raise VerificationFailure(f"{path.name}: missing payload")

        operation = operation_map[operation_id][2]
        body_schema = request_schema(operation)
        if body_schema is not None and example["kind"] == "positive":
            validate_instance(example["payload"], body_schema, document, f"{path.name}.payload")

        response_schema = resolved_response_schema(document, operation, example["status"])
        response_value = example.get("response")
        if response_schema is None:
            if response_value is not None:
                raise VerificationFailure(f"{path.name}: bodyless response contains a value")
        else:
            if "response" not in example or response_value is None:
                raise VerificationFailure(f"{path.name}: missing schema-aligned response")
            validate_instance(
                response_value,
                response_schema,
                document,
                f"{path.name}.response",
            )


def split_traceability_symbols(value: str) -> list[str]:
    return [symbol.strip() for symbol in value.split(";") if symbol.strip()]


def assert_traceability_row(
    document: dict[str, Any],
    row: dict[str, str],
    domain_text: str,
    row_number: int,
) -> None:
    operation_ids = set(operations(document))
    schema_names = set(document.get("components", {}).get("schemas", {}))
    deferred = row.get("status", "").startswith("DEFERRED_")

    if not row.get("PRD") or not row.get("Page") or not row.get("verification"):
        raise VerificationFailure(f"traceability row {row_number} lacks a required value")
    for symbol in split_traceability_symbols(row.get("operationId", "")):
        if symbol == "NOT_DEFINED" and deferred:
            continue
        if symbol not in operation_ids:
            raise VerificationFailure(
                f"traceability row {row_number} has unknown operationId: {symbol}"
            )
    for symbol in split_traceability_symbols(row.get("schema", "")):
        if symbol == "NOT_DEFINED" and deferred:
            continue
        if symbol not in schema_names:
            raise VerificationFailure(
                f"traceability row {row_number} has unknown schema: {symbol}"
            )
    for symbol in split_traceability_symbols(row.get("table", "")):
        if symbol == "NOT_DEFINED" and deferred:
            continue
        if symbol not in domain_text:
            raise VerificationFailure(
                f"traceability row {row_number} has undocumented table: {symbol}"
            )
    if deferred and "D6-25" not in row.get("verification", ""):
        raise VerificationFailure(
            f"traceability row {row_number} has no deferred verification owner"
        )


def assert_traceability(document: dict[str, Any]) -> None:
    for path in (DOMAIN_DESIGN, PERMISSION_CONTRACT, TRACEABILITY):
        if not path.is_file():
            raise VerificationFailure(f"missing design evidence: {path}")
    with TRACEABILITY.open(encoding="utf-8", newline="") as source:
        reader = csv.DictReader(source)
        required = {
            "PRD",
            "Page",
            "AC",
            "operationId",
            "schema",
            "table",
            "verification",
            "status",
            "notes",
        }
        if reader.fieldnames is None or not required.issubset(reader.fieldnames):
            raise VerificationFailure("traceability CSV headers are incomplete")
        rows = list(reader)
    if len(rows) < 20:
        raise VerificationFailure("traceability CSV must contain at least 20 mappings")
    domain_text = DOMAIN_DESIGN.read_text(encoding="utf-8")
    for row_number, row in enumerate(rows, start=2):
        assert_traceability_row(document, row, domain_text, row_number)


def manifest(root: Path) -> dict[str, str]:
    if not root.is_dir():
        raise VerificationFailure(f"generated root is missing: {root}")
    return {
        path.relative_to(root).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
        for path in sorted(candidate for candidate in root.rglob("*") if candidate.is_file())
    }


def manifest_difference(first: dict[str, str], second: dict[str, str]) -> list[str]:
    return [
        path
        for path in sorted(first.keys() | second.keys())
        if first.get(path) != second.get(path)
    ]


def clear_generated_outputs() -> None:
    for target in OWNED_GENERATED_TARGETS:
        if target.is_symlink():
            target.unlink()
        elif target.is_dir():
            shutil.rmtree(target)
        elif target.exists():
            target.unlink()


def run_rtk(args: list[str], timeout: float = 600) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["rtk", *args],
        cwd=ROOT,
        env=os.environ.copy(),
        capture_output=True,
        text=True,
        check=False,
        timeout=timeout,
    )


def output_excerpt(output: str, limit: int = 12) -> list[str]:
    markers = re.compile(
        r"(BUILD SUCCESS|Tests run:|Spec has no errors|No validation issues|"
        r"OpenAPI Generator|TypeScript|verification passed|PASSED|FAILED|error)",
        re.I,
    )
    lines = [line.strip() for line in output.splitlines() if line.strip()]
    selected = [line for line in lines if markers.search(line)]
    return (selected or lines)[-limit:]


def require_command(label: str, args: list[str], timeout: float = 600) -> str:
    result = run_rtk(args, timeout=timeout)
    output = result.stdout + result.stderr
    command = " ".join(args).replace(str(ROOT), "<workspace>").replace(
        str(Path.home()), "<home>"
    )
    if len(command) > 320:
        command = f"{command[:317]}..."
    print(f"[{label}] exit={result.returncode}: rtk {command}")
    for line in output_excerpt(output):
        print(f"  {line}")
    if result.returncode != 0:
        raise VerificationFailure(f"{label} failed with exit {result.returncode}")
    return output


def assert_generator_pom() -> None:
    text = (ROOT / "backend/pom.xml").read_text(encoding="utf-8")
    if f"<openapi-generator.version>{OPENAPI_GENERATOR_VERSION}</openapi-generator.version>" not in text:
        raise VerificationFailure("OpenAPI Generator version drifted")
    if text.count("<inputSpec>${openapi.input-spec}</inputSpec>") != 3:
        raise VerificationFailure("all three generator executions must share openapi.input-spec")
    if "contracts/representative-probe.yaml" not in text:
        raise VerificationFailure("the D6-02A representative default contract was removed")


def assert_generated_policy() -> None:
    java_sources = sorted(JAVA_GENERATED.rglob("*.java"))
    ts_sources = sorted(TS_GENERATED.rglob("*.ts"))
    if not java_sources or not ts_sources:
        raise VerificationFailure("real contract did not generate both Java and TypeScript")
    java_text = "\n".join(path.read_text(encoding="utf-8") for path in java_sources)
    ts_text = "\n".join(path.read_text(encoding="utf-8") for path in ts_sources)
    for marker in ("Asset", "InboundReceipt", "Organization", "SystemAccount"):
        if marker not in java_text or marker not in ts_text:
            raise VerificationFailure(f"generated surface is missing {marker}")
    if list(TS_GENERATED.rglob("*Api.ts")) or list(TS_GENERATED.rglob("runtime.ts")):
        raise VerificationFailure("TypeScript output includes transport/runtime files")
    forbidden = re.compile(r"\b(?:BaseAPI|FetchAPI|RequestInit|Response|FormData|fetch)\b")
    if forbidden.search(ts_text):
        raise VerificationFailure("TypeScript model output contains a transport token")
    tracked = require_command(
        "generated output policy",
        ["git", "ls-files", "backend/target", "packages/api-types/target"],
    ).strip()
    if tracked:
        raise VerificationFailure("generated output is tracked and could become authoritative")


def compile_generated_java_in_isolation() -> None:
    sources = sorted(JAVA_GENERATED.rglob("*.java"))
    if not sources:
        raise VerificationFailure("generated Java source set is empty")
    with tempfile.TemporaryDirectory(prefix="d6-02c-java-compile-") as temporary:
        temporary_root = Path(temporary)
        classpath_file = temporary_root / "classpath.txt"
        classes = temporary_root / "classes"
        classes.mkdir()
        require_command(
            "generated Java classpath",
            [
                "./mvnw",
                "-pl",
                "backend",
                "dependency:build-classpath",
                f"-Dmdep.outputFile={classpath_file}",
            ],
        )
        classpath = classpath_file.read_text(encoding="utf-8").strip()
        if not classpath:
            raise VerificationFailure("generated Java dependency classpath is empty")
        source_list = temporary_root / "sources.txt"
        source_list.write_text(
            "\n".join(str(source) for source in sources) + "\n",
            encoding="utf-8",
        )
        require_command(
            "real contract Java compile",
            [
                "javac",
                "--release",
                "21",
                "-cp",
                classpath,
                "-d",
                str(classes),
                f"@{source_list}",
            ],
        )


def compile_generated_typescript_in_isolation() -> None:
    sources = sorted(TS_GENERATED.rglob("*.ts"))
    if not sources:
        raise VerificationFailure("generated TypeScript source set is empty")
    with tempfile.TemporaryDirectory(prefix="d6-02c-ts-compile-") as temporary:
        config = Path(temporary) / "tsconfig.json"
        config.write_text(
            json.dumps(
                {
                    "compilerOptions": {
                        "target": "ES2022",
                        "lib": ["ES2022"],
                        "module": "ES2022",
                        "moduleResolution": "Bundler",
                        "strict": True,
                        "noEmit": True,
                        "skipLibCheck": True,
                    },
                    "files": [str(source) for source in sources],
                },
                indent=2,
            )
            + "\n",
            encoding="utf-8",
        )
        require_command(
            "real contract TypeScript compile",
            ["./node_modules/.bin/tsc", "--project", str(config)],
        )


def verify_real_contract_generation() -> None:
    override = f"-Dopenapi.input-spec={CONTRACT}"
    clear_generated_outputs()
    require_command(
        "real contract generation 1",
        ["./mvnw", "-pl", "backend", "clean", "generate-resources", "-DskipTests", override],
    )
    first_java = manifest(JAVA_GENERATED)
    first_ts = manifest(TS_TARGET)
    assert_generated_policy()

    clear_generated_outputs()
    require_command(
        "real contract generation 2",
        ["./mvnw", "-pl", "backend", "clean", "generate-resources", "-DskipTests", override],
    )
    second_java = manifest(JAVA_GENERATED)
    second_ts = manifest(TS_TARGET)
    if first_java != second_java or first_ts != second_ts:
        java_drift = manifest_difference(first_java, second_java)
        ts_drift = manifest_difference(first_ts, second_ts)
        raise VerificationFailure(
            "real-contract generation determinism check failed; "
            f"Java drift={java_drift[:8]}, TypeScript drift={ts_drift[:8]}"
        )
    print(
        "[determinism] real contract Java files="
        f"{len(first_java)}, TypeScript target files={len(first_ts)}, differences=0"
    )
    compile_generated_java_in_isolation()
    require_command("locked Node install", ["npm", "ci", "--ignore-scripts"])
    compile_generated_typescript_in_isolation()


def main() -> None:
    document = load_contract()
    assert_all_local_refs(document)
    assert_unique_operation_ids(document)
    assert_write_contracts(document)
    validate_examples(document)
    assert_traceability(document)
    assert_generator_pom()
    print(
        f"[static contract] operations={len(operations(document))}, "
        f"examples={len(list(EXAMPLES.glob('*.json')))}, refs/examples=PASSED"
    )

    require_command(
        "D6-02C unit tests",
        [
            "python3",
            "-m",
            "unittest",
            "scripts.test_verify_d6_02c",
            "scripts.test_verify_d6_02c_verifier",
        ],
    )
    override = f"-Dopenapi.input-spec={CONTRACT}"
    require_command(
        "real contract OpenAPI validation",
        ["./mvnw", "-pl", "backend", "validate", "-DskipTests", override],
    )
    try:
        verify_real_contract_generation()
        require_command(
            "D6-02A retained gate",
            ["run", "python3 scripts/verify-d6-02a.py"],
            timeout=900,
        )
        require_command(
            "D6-02B retained gate",
            ["run", "python3 scripts/verify-d6-02b.py"],
            timeout=1200,
        )
    finally:
        clear_generated_outputs()

    require_command("git diff --check", ["git", "diff", "--check"])
    require_command("final status", ["git", "status", "--short", "--branch"])
    print("D6-02C verification passed.")


if __name__ == "__main__":
    try:
        main()
    except (VerificationFailure, subprocess.TimeoutExpired) as exc:
        print(f"D6-02C verification failed: {exc}", file=sys.stderr)
        sys.exit(1)
