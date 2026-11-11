#!/usr/bin/env python3
"""Fail-closed unit tests for the executable D6-02C verifier."""

from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


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

    def test_main_refuses_preexisting_outputs_before_commands_or_cleanup(self) -> None:
        with tempfile.TemporaryDirectory(prefix="d6-02c-main-preexisting-output-") as temporary:
            root = Path(temporary)
            java_target = root / "java-target"
            typescript_target = root / "typescript-target"
            java_marker = java_target / "ManualPatch.java"
            typescript_marker = typescript_target / "StaleGeneratedModel.ts"
            java_target.mkdir()
            typescript_target.mkdir()
            java_marker.write_text("// manual patch\n", encoding="utf-8")
            typescript_marker.write_text("// stale generated model\n", encoding="utf-8")

            with (
                mock.patch.object(
                    VERIFIER,
                    "OWNED_GENERATED_TARGETS",
                    (java_target, typescript_target),
                ),
                mock.patch.object(
                    VERIFIER,
                    "load_contract",
                    wraps=VERIFIER.load_contract,
                ) as load_contract,
                mock.patch.object(VERIFIER, "require_command") as command,
            ):
                with self.assertRaises(VERIFIER.VerificationFailure):
                    VERIFIER.main()

            load_contract.assert_not_called()
            command.assert_not_called()
            self.assertEqual("// manual patch\n", java_marker.read_text(encoding="utf-8"))
            self.assertEqual(
                "// stale generated model\n",
                typescript_marker.read_text(encoding="utf-8"),
            )

    def test_preflight_refuses_preexisting_outputs_without_deleting_them(self) -> None:
        with tempfile.TemporaryDirectory(prefix="d6-02c-preexisting-output-") as temporary:
            root = Path(temporary)
            java_target = root / "java-target"
            typescript_target = root / "typescript-target"
            java_marker = java_target / "ManualPatch.java"
            typescript_marker = typescript_target / "StaleGeneratedModel.ts"
            java_target.mkdir()
            typescript_target.mkdir()
            java_marker.write_text("// manual patch\n", encoding="utf-8")
            typescript_marker.write_text("// stale generated model\n", encoding="utf-8")

            with (
                mock.patch.object(
                    VERIFIER,
                    "OWNED_GENERATED_TARGETS",
                    (java_target, typescript_target),
                ),
            ):
                with self.assertRaises(VERIFIER.VerificationFailure):
                    VERIFIER.assert_generated_outputs_absent()

            self.assertEqual("// manual patch\n", java_marker.read_text(encoding="utf-8"))
            self.assertEqual(
                "// stale generated model\n",
                typescript_marker.read_text(encoding="utf-8"),
            )

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


class WriteContractRuleTest(unittest.TestCase):
    """The write gate reads its rules from the contract, so each failure surface needs its own fixture."""

    PARAMETERS = {
        "IdempotencyKey": {
            "name": "Idempotency-Key",
            "in": "header",
            "required": True,
            "schema": {"type": "string", "minLength": 16, "maxLength": 128},
        },
        "IdempotencyKeyUuid": {
            "name": "Idempotency-Key",
            "in": "header",
            "required": True,
            "schema": {"type": "string", "format": "uuid"},
        },
        "CsrfToken": {
            "name": "X-CSRF-Token",
            "in": "header",
            "required": True,
            "schema": {"type": "string", "minLength": 16},
        },
    }

    def document(self, operations: dict[str, dict[str, object]], registry: list[str]) -> dict:
        return {
            "components": {"parameters": self.PARAMETERS},
            "paths": {f"/{name}": {"post": operation} for name, operation in operations.items()},
            "x-non-business-mutations": registry,
        }

    def business_write(self, **overrides: object) -> dict[str, object]:
        operation: dict[str, object] = {
            "operationId": "doWrite",
            "parameters": [
                {"$ref": "#/components/parameters/IdempotencyKey"},
                {"$ref": "#/components/parameters/CsrfToken"},
            ],
            "responses": {"200": {}, "400": {}, "409": {}},
            "x-business-write": True,
            "x-actor": "Current operator",
            "x-version": "Aggregate version",
            "x-transaction": "Single commit",
            "x-audit-event": "WRITE_DONE",
            "x-error-codes": ["WRITE_INVALID"],
            "x-required-error-statuses": [400, 409],
        }
        operation.update(overrides)
        return operation

    def auth_operation(self, **overrides: object) -> dict[str, object]:
        operation: dict[str, object] = {
            "operationId": "login",
            "parameters": [],
            "responses": {"200": {}},
            "x-exception-rationale": "Establishes the session credential itself.",
        }
        operation.update(overrides)
        return operation

    def test_conforming_business_write_and_registered_auth_mutation_pass(self) -> None:
        document = self.document(
            {"write": self.business_write(), "login": self.auth_operation()}, ["login"]
        )
        VERIFIER.assert_write_contracts(document)

    def test_uuid_idempotency_component_satisfies_the_requirement(self) -> None:
        document = self.document(
            {"write": self.business_write(
                parameters=[
                    {"$ref": "#/components/parameters/IdempotencyKeyUuid"},
                    {"$ref": "#/components/parameters/CsrfToken"},
                ]
            )},
            [],
        )
        VERIFIER.assert_write_contracts(document)

    def test_declared_csrf_exception_replaces_the_cookie_header_requirement(self) -> None:
        document = self.document(
            {"write": self.business_write(
                parameters=[{"$ref": "#/components/parameters/IdempotencyKey"}],
                **{"x-non-applicable": ["csrf"], "x-exception-rationale": "Header-only session."},
            )},
            [],
        )
        VERIFIER.assert_write_contracts(document)

    def test_unregistered_non_business_mutation_is_rejected(self) -> None:
        document = self.document({"login": self.auth_operation()}, [])
        with self.assertRaises(VERIFIER.VerificationFailure) as failure:
            VERIFIER.assert_write_contracts(document)
        self.assertIn("unclassified non-business mutation", str(failure.exception))

    def test_registered_mutation_without_rationale_is_rejected(self) -> None:
        document = self.document({"login": self.auth_operation(**{"x-exception-rationale": ""})}, ["login"])
        with self.assertRaises(VERIFIER.VerificationFailure) as failure:
            VERIFIER.assert_write_contracts(document)
        self.assertIn("missing mutation exception rationale", str(failure.exception))

    def test_registry_that_keeps_a_reclassified_operation_is_rejected(self) -> None:
        document = self.document({"login": self.business_write(operationId="login")}, ["login"])
        with self.assertRaises(VERIFIER.VerificationFailure) as failure:
            VERIFIER.assert_write_contracts(document)
        self.assertIn("keeps stale entries", str(failure.exception))

    def test_registry_referring_to_an_unknown_operation_is_rejected(self) -> None:
        document = self.document({"write": self.business_write()}, ["gone"])
        with self.assertRaises(VERIFIER.VerificationFailure) as failure:
            VERIFIER.assert_write_contracts(document)
        self.assertIn("references undeclared operations", str(failure.exception))

    def test_business_write_missing_a_boundary_extension_is_rejected(self) -> None:
        for extension in VERIFIER.WRITE_BOUNDARY_EXTENSIONS:
            with self.subTest(extension=extension):
                document = self.document({"write": self.business_write(**{extension: None})}, [])
                with self.assertRaises(VERIFIER.VerificationFailure) as failure:
                    VERIFIER.assert_write_contracts(document)
                self.assertIn(f"is missing {extension}", str(failure.exception))

    def test_unknown_exception_name_is_rejected(self) -> None:
        document = self.document(
            {"write": self.business_write(
                **{"x-non-applicable": ["cookies"], "x-exception-rationale": "Invented."}
            )},
            [],
        )
        with self.assertRaises(VERIFIER.VerificationFailure) as failure:
            VERIFIER.assert_write_contracts(document)
        self.assertIn("unknown non-applicable exception", str(failure.exception))

    def test_exception_without_rationale_is_rejected(self) -> None:
        document = self.document(
            {"write": self.business_write(
                parameters=[{"$ref": "#/components/parameters/IdempotencyKey"}],
                **{"x-non-applicable": ["csrf"]},
            )},
            [],
        )
        with self.assertRaises(VERIFIER.VerificationFailure) as failure:
            VERIFIER.assert_write_contracts(document)
        self.assertIn("declared exception without rationale", str(failure.exception))

    def test_inline_header_that_diverges_from_the_component_is_rejected(self) -> None:
        document = self.document(
            {"write": self.business_write(
                parameters=[
                    {"$ref": "#/components/parameters/IdempotencyKey"},
                    {"name": "X-CSRF-Token", "in": "header", "required": True, "schema": {"type": "string"}},
                ]
            )},
            [],
        )
        with self.assertRaises(VERIFIER.VerificationFailure) as failure:
            VERIFIER.assert_write_contracts(document)
        self.assertIn("must reuse the shared parameter component", str(failure.exception))

    def test_inline_header_that_only_adds_a_description_is_accepted(self) -> None:
        document = self.document(
            {"write": self.business_write(
                parameters=[
                    {"$ref": "#/components/parameters/IdempotencyKey"},
                    dict(self.PARAMETERS["CsrfToken"], description="Cookie paired token."),
                ]
            )},
            [],
        )
        VERIFIER.assert_write_contracts(document)

    def test_business_write_without_a_required_header_is_rejected(self) -> None:
        document = self.document(
            {"write": self.business_write(
                parameters=[{"$ref": "#/components/parameters/CsrfToken"}]
            )},
            [],
        )
        with self.assertRaises(VERIFIER.VerificationFailure) as failure:
            VERIFIER.assert_write_contracts(document)
        self.assertIn("doWrite is missing Idempotency-Key", str(failure.exception))

    def test_required_error_status_without_a_response_is_rejected(self) -> None:
        document = self.document(
            {"write": self.business_write(responses={"200": {}, "400": {}})}, []
        )
        with self.assertRaises(VERIFIER.VerificationFailure) as failure:
            VERIFIER.assert_write_contracts(document)
        self.assertIn("requires undeclared HTTP status", str(failure.exception))


if __name__ == "__main__":
    unittest.main()
