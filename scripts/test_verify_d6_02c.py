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
PY_YAML_VERSION = "6.0.2"


class DesignPackageContractTest(unittest.TestCase):

    def load_contract(self) -> dict[str, object]:
        self.assertTrue(CONTRACT.is_file(), f"missing authoritative contract: {CONTRACT}")
        try:
            import yaml

            self.assertEqual(PY_YAML_VERSION, yaml.__version__)
            document = yaml.safe_load(CONTRACT.read_text(encoding="utf-8"))
        except ImportError as exc:  # pragma: no cover - environment setup failure
            self.fail(f"PyYAML is required for the semantic test: {exc}")
        self.assertIsInstance(document, dict)
        return document

    def test_auth_and_public_foundation_is_independently_complete(self) -> None:
        document = self.load_contract()
        self.assertEqual("3.0.4", document.get("openapi"))
        self.assertEqual("/api/v1", document["servers"][0]["url"])
        self.assertIn("Auth", {tag["name"] for tag in document["tags"]})
        self.assertEqual(5, len([path for path in document["paths"] if path.startswith("/auth/")]))
        operations = {
            operation["operationId"]
            for item in document["paths"].values()
            for method, operation in item.items()
            if method in {"get", "post", "patch"}
        }
        self.assertTrue(
            {"login", "changeInitialPassword", "getCurrentUser", "getMyPermissions", "logout"}
            .issubset(operations)
        )
        schemes = document["components"]["securitySchemes"]
        self.assertEqual("cookie", schemes["sessionCookie"]["in"])
        self.assertEqual("FAMS_SESSION", schemes["sessionCookie"]["name"])
        self.assertEqual("X-CSRF-Token", schemes["csrfHeader"]["name"])
        self.assertEqual("apiKey", schemes["csrfHeader"]["type"])
        self.assertIn("sessionCookie", document["security"][0])

    def test_public_common_schemas_are_explicit(self) -> None:
        document = self.load_contract()
        schemas = document["components"]["schemas"]
        self.assertEqual({"type": "string", "pattern": "^[0-9]+$"}, {
            key: schemas["Id"][key] for key in ("type", "pattern")
        })
        self.assertEqual("date-time", schemas["Instant"]["format"])
        self.assertEqual("date", schemas["BusinessDate"]["format"])
        self.assertEqual("^[0-9]+\\.[0-9]{2}$", schemas["Money"]["pattern"])
        self.assertEqual({"page", "pageSize", "total"}, set(schemas["PageMeta"]["required"]))
        self.assertEqual(1, schemas["PageMeta"]["properties"]["page"]["minimum"])
        self.assertEqual(1, document["components"]["parameters"]["Page"]["schema"]["default"])
        self.assertEqual(1, document["components"]["parameters"]["Page"]["schema"]["minimum"])
        self.assertEqual(20, document["components"]["parameters"]["PageSize"]["schema"]["default"])
        self.assertEqual(200, document["components"]["parameters"]["PageSize"]["schema"]["maximum"])
        self.assertEqual(
            {
                "EMPLOYEE",
                "DEPARTMENT_ASSET_MANAGER",
                "ASSET_ADMIN",
                "WAREHOUSE_OPERATOR",
                "TECH_ASSISTANT",
                "AUDIT_READ_ONLY",
                "SYSTEM_ADMIN",
            },
            set(schemas["Role"]["enum"]),
        )
        self.assertEqual(
            {"code", "message", "requestId", "fieldErrors"},
            set(schemas["ErrorResponse"]["required"]),
        )

    def test_auth_session_and_cookie_boundaries_are_explicit(self) -> None:
        document = self.load_contract()
        session = document["components"]["schemas"]["SessionResponse"]
        self.assertNotIn("sessionId", session["properties"])
        self.assertEqual(
            {
                "csrfToken",
                "idleExpiresAt",
                "absoluteExpiresAt",
                "requiresInitialPasswordChange",
                "user",
            },
            set(session["required"]),
        )
        self.assertEqual("CurrentUser", session["properties"]["user"]["$ref"].rsplit("/", 1)[-1])

        login = document["paths"]["/auth/login"]["post"]
        login_text = json.dumps(login, sort_keys=True).lower()
        self.assertIn("x-transaction", login)
        self.assertIn("x-audit-event", login)
        self.assertIn("same", login_text)
        self.assertIn("429", login["responses"])
        self.assertNotIn("409", login["responses"])
        set_cookie = login["responses"]["200"]["headers"]["Set-Cookie"]
        cookie_text = json.dumps(set_cookie).lower()
        for token in ("secure", "httponly", "samesite=lax", "path=/api"):
            self.assertIn(token, cookie_text)

        password = document["paths"]["/auth/password"]["post"]
        password_text = json.dumps(password, sort_keys=True).lower()
        self.assertNotIn("system_account", password_text)
        self.assertIn("enabled session", password_text)
        self.assertIn("initial password", password_text)
        self.assertEqual(
            "SessionResponse",
            password["responses"]["200"]["content"]["application/json"]["schema"]["$ref"].rsplit("/", 1)[-1],
        )
        self.assertIn("old session", password_text)

        logout = document["paths"]["/auth/logout"]["post"]
        self.assertIn("x-transaction", logout)
        self.assertIn("x-audit-event", logout)
        logout_cookie = logout["responses"]["204"]["headers"]["Set-Cookie"]
        self.assertIn("max-age=0", json.dumps(logout_cookie).lower())

    def test_organization_operations_expose_tree_and_disable_invariants(self) -> None:
        document = self.load_contract()
        paths = document["paths"]
        self.assertIn("/organizations", paths)
        self.assertIn("/organizations/{organizationId}", paths)
        self.assertIn("/organizations/{organizationId}/disable", paths)
        operations = {
            operation["operationId"]: operation
            for item in paths.values()
            for operation in item.values()
            if isinstance(operation, dict) and "operationId" in operation
        }
        self.assertIn("listOrganizations", operations)
        self.assertIn("createOrganization", operations)
        self.assertIn("updateOrganization", operations)
        self.assertIn("disableOrganization", operations)
        self.assertEqual(
            {"Page", "PageSize"},
            {
                parameter["$ref"].rsplit("/", 1)[-1]
                for parameter in paths["/organizations"]["get"]["parameters"]
            },
        )
        self.assertIn("ORGANIZATION_CODE_CONFLICT", operations["createOrganization"]["x-error-codes"])
        invariants = json.dumps(operations["updateOrganization"], sort_keys=True).lower()
        self.assertIn("cycle", invariants)
        self.assertIn("responsibility", invariants)
        self.assertIn("asset", invariants)
        disable_invariants = json.dumps(operations["disableOrganization"], sort_keys=True).lower()
        self.assertIn("asset", disable_invariants)
        self.assertIn("responsibility", disable_invariants)

    def test_employee_operations_preserve_history_and_revoke_access_on_disable(self) -> None:
        document = self.load_contract()
        paths = document["paths"]
        self.assertIn("/employees", paths)
        self.assertIn("/employees/{employeeId}", paths)
        self.assertIn("/employees/{employeeId}/disable", paths)
        operations = {
            operation["operationId"]: operation
            for item in paths.values()
            for operation in item.values()
            if isinstance(operation, dict) and "operationId" in operation
        }
        for operation_id in (
            "listEmployees",
            "createEmployee",
            "updateEmployee",
            "disableEmployee",
        ):
            self.assertIn(operation_id, operations)
        self.assertIn("EMPLOYEE_NO_CONFLICT", operations["createEmployee"]["x-error-codes"])
        update_text = json.dumps(operations["updateEmployee"], sort_keys=True).lower()
        self.assertIn("transfer", update_text)
        self.assertIn("asset", update_text)
        disable_text = json.dumps(operations["disableEmployee"], sort_keys=True).lower()
        for term in ("session", "upload", "history"):
            self.assertIn(term, disable_text)
        employee_schemas = document["components"]["schemas"]
        self.assertIn(
            "phone",
            json.dumps([employee_schemas["Employee"], employee_schemas["EmployeeInput"]]).lower(),
        )

    def test_system_account_operations_cover_lifecycle_and_initial_password_reset(self) -> None:
        document = self.load_contract()
        paths = document["paths"]
        self.assertIn("/system-accounts", paths)
        self.assertIn("/system-accounts/{systemAccountId}/status", paths)
        self.assertIn("/system-accounts/{systemAccountId}/initial-password-reset", paths)
        operations = {
            operation["operationId"]: operation
            for item in paths.values()
            for operation in item.values()
            if isinstance(operation, dict) and "operationId" in operation
        }
        for operation_id in ("createSystemAccount", "changeSystemAccountStatus", "resetInitialPassword"):
            self.assertIn(operation_id, operations)
        status_schema = document["components"]["schemas"]["SystemAccountStatusChangeRequest"]
        self.assertEqual({"ENABLE", "DISABLE", "UNLOCK"}, set(status_schema["properties"]["action"]["enum"]))
        self.assertIn("session", json.dumps(operations["changeSystemAccountStatus"]).lower())
        self.assertIn("initial", json.dumps(operations["resetInitialPassword"]).lower())
        self.assertIn("first", json.dumps(operations["resetInitialPassword"]).lower())

    def test_role_configuration_covers_grant_revoke_and_safety_invariants(self) -> None:
        document = self.load_contract()
        paths = document["paths"]
        operations = {
            operation["operationId"]: operation
            for item in paths.values()
            for operation in item.values()
            if isinstance(operation, dict) and "operationId" in operation
        }
        for operation_id in (
            "listRoleConfigurations",
            "grantRolePermission",
            "revokeRolePermission",
            "grantSystemAccountRole",
            "revokeSystemAccountRole",
        ):
            self.assertIn(operation_id, operations)
        role_text = json.dumps(document["components"]["schemas"]["RoleConfiguration"]).lower()
        self.assertIn("system_admin", role_text)
        self.assertIn("asset_admin", role_text)
        self.assertIn("warehouse_operator", role_text)
        grant_text = json.dumps(operations["grantRolePermission"], sort_keys=True).lower()
        self.assertIn("business", grant_text)
        self.assertIn("enterprise", grant_text)
        self.assertIn("minimum", grant_text)
        revoke_text = json.dumps(operations["revokeRolePermission"], sort_keys=True).lower()
        self.assertIn("immediate", revoke_text)
        self.assertIn("session", revoke_text)
        self.assertNotIn("delete", " ".join(paths))

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
                    header_parameters = [
                        parameter
                        for parameter in operation.get("parameters", [])
                        if parameter.get("in") == "header" or "$ref" in parameter
                    ]
                    parameter_names = {parameter.get("name") for parameter in header_parameters}
                    parameter_refs = {parameter.get("$ref", "").rsplit("/", 1)[-1] for parameter in header_parameters}
                    self.assertTrue(
                        "Idempotency-Key" in parameter_names or "IdempotencyKey" in parameter_refs
                    )
                    self.assertTrue("X-CSRF-Token" in parameter_names or "CsrfToken" in parameter_refs)

    def test_identifier_time_money_and_state_shapes_are_explicit(self) -> None:
        document = self.load_contract()
        schemas = document["components"]["schemas"]
        future_schemas = ("Organization", "Employee", "Asset", "InboundReceipt")
        missing = [name for name in future_schemas if name not in schemas]
        self.assertFalse(missing, f"later domain schemas remain RED: {missing}")
        if missing:
            return
        for name in future_schemas:
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

    def test_auth_examples_are_machine_readable_and_named(self) -> None:
        self.assertTrue(EXAMPLES.is_dir(), f"missing examples directory: {EXAMPLES}")
        files = sorted(EXAMPLES.glob("auth-*.json"))
        self.assertGreaterEqual(len(files), 4)
        kinds: set[str] = set()
        operation_ids: set[str] = set()
        for path in files:
            with self.subTest(path=path.name):
                value = json.loads(path.read_text(encoding="utf-8"))
                self.assertIn(value.get("kind"), {"positive", "rejection"})
                self.assertTrue(value.get("operationId"))
                self.assertIsInstance(value.get("status"), int)
                self.assertIn("payload", value)
                kinds.add(value["kind"])
                operation_ids.add(value["operationId"])
        self.assertEqual({"positive", "rejection"}, kinds)
        self.assertTrue({"login", "changeInitialPassword", "logout"}.issubset(operation_ids))

    def test_identity_master_data_examples_are_machine_readable_and_named(self) -> None:
        self.assertTrue(EXAMPLES.is_dir(), f"missing examples directory: {EXAMPLES}")
        files = sorted(EXAMPLES.glob("masterdata-identity-*.json"))
        self.assertGreaterEqual(len(files), 8)
        operation_ids: set[str] = set()
        kinds: set[str] = set()
        for path in files:
            with self.subTest(path=path.name):
                value = json.loads(path.read_text(encoding="utf-8"))
                self.assertIn(value.get("kind"), {"positive", "rejection"})
                self.assertTrue(value.get("operationId"))
                self.assertIsInstance(value.get("status"), int)
                self.assertIn("payload", value)
                operation_ids.add(value["operationId"])
                kinds.add(value["kind"])
        self.assertEqual({"positive", "rejection"}, kinds)
        self.assertTrue(
            {
                "createOrganization",
                "updateOrganization",
                "disableOrganization",
                "createEmployee",
                "updateEmployee",
                "disableEmployee",
            }.issubset(operation_ids)
        )

    def test_account_role_examples_are_machine_readable_and_named(self) -> None:
        self.assertTrue(EXAMPLES.is_dir(), f"missing examples directory: {EXAMPLES}")
        files = sorted(EXAMPLES.glob("masterdata-account-*.json"))
        self.assertGreaterEqual(len(files), 8)
        operation_ids: set[str] = set()
        kinds: set[str] = set()
        for path in files:
            with self.subTest(path=path.name):
                value = json.loads(path.read_text(encoding="utf-8"))
                self.assertIn(value.get("kind"), {"positive", "rejection"})
                self.assertTrue(value.get("operationId"))
                self.assertIsInstance(value.get("status"), int)
                self.assertIn("payload", value)
                operation_ids.add(value["operationId"])
                kinds.add(value["kind"])
        self.assertEqual({"positive", "rejection"}, kinds)
        self.assertTrue(
            {
                "createSystemAccount",
                "changeSystemAccountStatus",
                "resetInitialPassword",
                "grantRolePermission",
                "revokeRolePermission",
                "grantSystemAccountRole",
                "revokeSystemAccountRole",
            }.issubset(operation_ids)
        )

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
