#!/usr/bin/env python3
"""Behavior tests for the D6-02C design-package verifier."""

from __future__ import annotations

import csv
import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "contracts/fams-core-v1.yaml"
EXAMPLES = ROOT / "contracts/examples/d6-02c"
DOCS = ROOT / "docs/development"


class DesignPackageContractTest(unittest.TestCase):

    def load_contract(self) -> dict[str, object]:
        self.assertTrue(CONTRACT.is_file(), f"missing authoritative contract: {CONTRACT}")
        try:
            import yaml

            document = yaml.safe_load(CONTRACT.read_text(encoding="utf-8"))
        except ImportError as exc:  # pragma: no cover - environment setup failure
            self.fail(f"PyYAML is required for the semantic test: {exc}")
        self.assertIsInstance(document, dict)
        return document

    def test_contract_declares_the_four_first_slice_domains(self) -> None:
        document = self.load_contract()
        self.assertEqual("3.0.4", document.get("openapi"))
        self.assertEqual("/api/v1", document["servers"][0]["url"])
        tags = {tag["name"] for tag in document["tags"]}
        self.assertTrue({"Auth", "MasterData", "Assets", "Inbound"}.issubset(tags))
        paths = document["paths"]
        self.assertGreaterEqual(len(paths), 20)
        operations = {
            operation["operationId"]
            for item in paths.values()
            for method, operation in item.items()
            if method in {"get", "post", "patch"}
        }
        self.assertIn("login", operations)
        self.assertIn("createAsset", operations)
        self.assertIn("submitInboundReceipt", operations)
        self.assertIn("decideInboundReceipt", operations)

    def test_mutations_declare_identity_version_idempotency_transaction_audit_and_errors(self) -> None:
        document = self.load_contract()
        for path, item in document["paths"].items():
            for method, operation in item.items():
                if method not in {"post", "patch", "delete"}:
                    continue
                with self.subTest(method=method, path=path):
                    if not operation.get("x-business-write", False):
                        self.assertIn(operation["operationId"], {"login", "logout"})
                        self.assertTrue(operation.get("x-exception-rationale"))
                        continue
                    self.assertTrue(operation.get("x-actor"), "missing actor boundary")
                    self.assertTrue(operation.get("x-version"), "missing version boundary")
                    self.assertTrue(operation.get("x-transaction"), "missing transaction boundary")
                    self.assertTrue(operation.get("x-audit-event"), "missing audit boundary")
                    required_errors = operation.get("x-required-error-statuses")
                    self.assertIsInstance(required_errors, list)
                    for status in required_errors:
                        self.assertIn(str(status), operation["responses"])
                    parameter_names = {
                        parameter["name"]
                        for parameter in operation.get("parameters", [])
                        if parameter.get("in") == "header"
                    }
                    self.assertIn("Idempotency-Key", parameter_names)
                    self.assertIn("X-CSRF-Token", parameter_names)

    def test_identifier_time_money_and_state_shapes_are_explicit(self) -> None:
        document = self.load_contract()
        schemas = document["components"]["schemas"]
        for name in ("Organization", "Employee", "Asset", "InboundReceipt"):
            with self.subTest(schema=name):
                self.assertEqual("string", schemas[name]["properties"]["id"]["type"])
                self.assertEqual("^[0-9]+$", schemas[name]["properties"]["id"]["pattern"])
        self.assertEqual("string", schemas["Asset"]["properties"]["businessDate"]["type"])
        self.assertEqual("date", schemas["Asset"]["properties"]["businessDate"]["format"])
        self.assertEqual("date-time", schemas["AuditEvent"]["properties"]["occurredAt"]["format"])
        self.assertEqual("string", schemas["Money"]["type"])
        self.assertEqual("^[0-9]+\\.[0-9]{2}$", schemas["Money"]["pattern"])
        self.assertIn("PERSONAL", schemas["UsageMode"]["enum"])
        self.assertIn("SHARED", schemas["UsageMode"]["enum"])


class DesignPackageEvidenceTest(unittest.TestCase):

    def test_examples_are_machine_readable_and_named(self) -> None:
        self.assertTrue(EXAMPLES.is_dir(), f"missing examples directory: {EXAMPLES}")
        files = sorted(EXAMPLES.glob("*.json"))
        self.assertGreaterEqual(len(files), 12)
        kinds: set[str] = set()
        for path in files:
            with self.subTest(path=path.name):
                value = json.loads(path.read_text(encoding="utf-8"))
                self.assertIn(value.get("kind"), {"positive", "rejection"})
                self.assertTrue(value.get("operationId"))
                self.assertIsInstance(value.get("status"), int)
                self.assertIn("payload", value)
                kinds.add(value["kind"])
        self.assertEqual({"positive", "rejection"}, kinds)

    def test_required_design_docs_and_traceability_are_present(self) -> None:
        required = (
            "D6-02C-domain-data-design.md",
            "D6-02C-permission-error-contract.md",
            "D6-02C-traceability.csv",
        )
        missing = [filename for filename in required if not (DOCS / filename).is_file()]
        self.assertFalse(missing, f"missing design evidence: {missing}")
        if missing:
            return

        rows = list(csv.DictReader((DOCS / "D6-02C-traceability.csv").open(encoding="utf-8")))
        self.assertGreaterEqual(len(rows), 20)
        self.assertTrue({"PRD", "Page", "AC", "operationId", "schema", "table", "verification"}.issubset(rows[0]))
        self.assertTrue(any(row["AC"] == "AC-10" for row in rows))
        self.assertTrue(any(row["AC"] == "AC-18" for row in rows))


if __name__ == "__main__":
    unittest.main()
