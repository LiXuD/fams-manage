#!/usr/bin/env python3
"""Fail-closed unit tests for the executable D6-02C verifier."""

from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("verify-d6-02c.py")
SPEC = importlib.util.spec_from_file_location("verify_d6_02c", MODULE_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Cannot load verifier from {MODULE_PATH}")
VERIFIER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = VERIFIER
SPEC.loader.exec_module(VERIFIER)


class SchemaValidationTest(unittest.TestCase):

    def setUp(self) -> None:
        self.document = {
            "components": {
                "schemas": {
                    "Identifier": {"type": "string", "pattern": "^[0-9]+$"},
                    "Record": {
                        "type": "object",
                        "additionalProperties": False,
                        "required": ["id", "mode"],
                        "properties": {
                            "id": {"$ref": "#/components/schemas/Identifier"},
                            "mode": {"type": "string", "enum": ["A", "B"]},
                        },
                    },
                }
            }
        }

    def test_accepts_a_conforming_referenced_object(self) -> None:
        VERIFIER.validate_instance(
            {"id": "9007199254740993", "mode": "A"},
            {"$ref": "#/components/schemas/Record"},
            self.document,
            "fixture",
        )

    def test_rejects_missing_required_and_unknown_properties(self) -> None:
        with self.assertRaises(VERIFIER.VerificationFailure):
            VERIFIER.validate_instance(
                {"id": "1", "extra": True},
                {"$ref": "#/components/schemas/Record"},
                self.document,
                "fixture",
            )

    def test_rejects_an_invalid_pattern_or_enum(self) -> None:
        with self.assertRaises(VERIFIER.VerificationFailure):
            VERIFIER.validate_instance(
                {"id": "unsafe-id", "mode": "C"},
                {"$ref": "#/components/schemas/Record"},
                self.document,
                "fixture",
            )


class ContractEvidenceTest(unittest.TestCase):

    def test_current_contract_has_resolved_refs_and_schema_aligned_examples(self) -> None:
        document = VERIFIER.load_contract()
        VERIFIER.assert_all_local_refs(document)
        VERIFIER.assert_unique_operation_ids(document)
        VERIFIER.assert_write_contracts(document)
        VERIFIER.validate_examples(document)
        VERIFIER.assert_traceability(document)

    def test_missing_local_ref_is_rejected(self) -> None:
        document = {"broken": {"$ref": "#/components/schemas/Missing"}, "components": {"schemas": {}}}
        with self.assertRaises(VERIFIER.VerificationFailure):
            VERIFIER.assert_all_local_refs(document)

    def test_pyyaml_version_check_is_exact(self) -> None:
        VERIFIER.assert_pyyaml_version("6.0.2")
        with self.assertRaises(VERIFIER.VerificationFailure):
            VERIFIER.assert_pyyaml_version("6.0.3")

    def test_traceability_rejects_unknown_contract_symbols(self) -> None:
        document = VERIFIER.load_contract()
        row = {
            "PRD": "PRD-TEST",
            "Page": "P01",
            "AC": "",
            "operationId": "unknownOperation",
            "schema": "UnknownSchema",
            "table": "fams_missing_table",
            "verification": "test",
            "status": "DESIGNED",
            "notes": "negative fixture",
        }
        with self.assertRaises(VERIFIER.VerificationFailure):
            VERIFIER.assert_traceability_row(document, row, "", 2)


class GeneratedTreeTest(unittest.TestCase):

    def test_transport_free_template_imports_referenced_models_as_types(self) -> None:
        template = (
            VERIFIER.ROOT
            / "packages/api-types/openapi-templates/typescript-fetch/modelGeneric.mustache"
        ).read_text(encoding="utf-8")
        self.assertIn("{{#tsImports}}", template)
        self.assertIn("import type { {{{classname}}} }", template)
        self.assertNotIn("../runtime", template)

        one_of_template = (
            VERIFIER.ROOT
            / "packages/api-types/openapi-templates/typescript-fetch/modelOneOf.mustache"
        )
        self.assertTrue(one_of_template.is_file())
        one_of_text = one_of_template.read_text(encoding="utf-8")
        self.assertIn("{{>modelOneOfInterfaces}}", one_of_text)
        self.assertIn("import type", one_of_text)
        self.assertNotIn("FromJSON", one_of_text)
        self.assertNotIn("instanceOf", one_of_text)

    def test_manifest_detects_content_and_stale_file_drift(self) -> None:
        with tempfile.TemporaryDirectory(prefix="d6-02c-manifest-test-") as temporary:
            root = Path(temporary)
            first = root / "first"
            second = root / "second"
            first.mkdir()
            second.mkdir()
            (first / "model.ts").write_text("export type A = string;\n", encoding="utf-8")
            (second / "model.ts").write_text("export type A = string;\n", encoding="utf-8")
            self.assertEqual(VERIFIER.manifest(first), VERIFIER.manifest(second))

            (second / "model.ts").write_text("export type A = number;\n", encoding="utf-8")
            (second / "stale.ts").write_text("stale\n", encoding="utf-8")
            self.assertNotEqual(VERIFIER.manifest(first), VERIFIER.manifest(second))


if __name__ == "__main__":
    unittest.main()
