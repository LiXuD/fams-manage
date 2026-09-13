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
        self.assertNotIn("403", login["responses"])
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
        password_cookie = password["responses"]["200"]["headers"]["Set-Cookie"]
        password_cookie_text = json.dumps(password_cookie).lower()
        for token in ("secure", "httponly", "samesite=lax", "path=/api"):
            self.assertIn(token, password_cookie_text)
        self.assertIn("old session", password_text)

        logout = document["paths"]["/auth/logout"]["post"]
        self.assertIn("x-transaction", logout)
        self.assertIn("x-audit-event", logout)
        logout_cookie = logout["responses"]["204"]["headers"]["Set-Cookie"]
        self.assertIn("max-age=0", json.dumps(logout_cookie).lower())

        login_rejections = []
        for path in sorted(EXAMPLES.glob("auth-login*-rejection*.json")):
            value = json.loads(path.read_text(encoding="utf-8"))
            if value.get("operationId") == "login" and value.get("kind") == "rejection":
                login_rejections.append(value)
        self.assertGreaterEqual(len(login_rejections), 4)
        self.assertTrue(all(value["status"] == 401 for value in login_rejections))
        response_keys = {tuple(sorted(value["response"])) for value in login_rejections}
        self.assertEqual({("code", "fieldErrors", "message", "requestId")}, response_keys)
        response_values = {
            (
                value["status"],
                value["response"]["code"],
                value["response"]["message"],
                tuple(sorted(value["response"]["fieldErrors"].items())),
            )
            for value in login_rejections
        }
        self.assertEqual(
            {(401, "AUTHENTICATION_FAILED", "Authentication failed.", ())},
            response_values,
        )

    def test_identity_master_data_reads_and_writes_are_system_admin_only(self) -> None:
        document = self.load_contract()
        prefixes = ("/organizations", "/employees", "/system-accounts", "/role-configurations")
        for path, item in document["paths"].items():
            if not path.startswith(prefixes):
                continue
            for method, operation in item.items():
                if method not in {"get", "post", "patch", "put", "delete"}:
                    continue
                with self.subTest(method=method, path=path):
                    self.assertEqual(["SYSTEM_ADMIN"], operation.get("x-actor", {}).get("roles"))

    def test_identity_inputs_split_status_mutations_and_reference_versions(self) -> None:
        document = self.load_contract()
        schemas = document["components"]["schemas"]
        operations = {
            operation["operationId"]: operation
            for path, item in document["paths"].items()
            if path.startswith(("/organizations", "/employees"))
            for operation in item.values()
            if isinstance(operation, dict) and "operationId" in operation
        }
        request_schema_by_operation = {
            "createOrganization": "OrganizationCreateRequest",
            "updateOrganization": "OrganizationUpdateRequest",
            "createEmployee": "EmployeeCreateRequest",
            "updateEmployee": "EmployeeUpdateRequest",
        }
        for operation_id, schema_name in request_schema_by_operation.items():
            with self.subTest(operation=operation_id):
                ref = operations[operation_id]["requestBody"]["content"]["application/json"]["schema"]["$ref"]
                self.assertEqual(schema_name, ref.rsplit("/", 1)[-1])
                self.assertNotIn("status", schemas[schema_name]["properties"])
        required_schemas = set(request_schema_by_operation.values()) | {"OrganizationReference"}
        missing_schemas = sorted(required_schemas - set(schemas))
        self.assertFalse(missing_schemas, f"split identity schemas remain RED: {missing_schemas}")
        if missing_schemas:
            return
        reference = schemas["OrganizationReference"]
        self.assertEqual({"id", "version"}, set(reference["required"]))
        self.assertEqual({"id", "version"}, set(reference["properties"]))
        for schema_name, reference_name in (
            ("OrganizationCreateRequest", "parentOrganization"),
            ("OrganizationUpdateRequest", "parentOrganization"),
            ("EmployeeCreateRequest", "organization"),
            ("EmployeeUpdateRequest", "organization"),
        ):
            with self.subTest(schema=schema_name):
                self.assertEqual(
                    "OrganizationReference",
                    schemas[schema_name]["properties"][reference_name]["$ref"].rsplit("/", 1)[-1],
                )
        for schema_name in ("OrganizationUpdateRequest", "EmployeeUpdateRequest"):
            with self.subTest(schema=schema_name, patch_semantics=True):
                self.assertEqual(1, schemas[schema_name].get("minProperties"))
                self.assertNotIn("required", schemas[schema_name])

    def test_employee_disable_revokes_all_access_without_blocking_on_pending_work(self) -> None:
        document = self.load_contract()
        operation = next(
            operation
            for item in document["paths"].values()
            for operation in item.values()
            if isinstance(operation, dict) and operation.get("operationId") == "disableEmployee"
        )
        self.assertFalse(any("PENDING" in code for code in operation["x-error-codes"]))
        side_effects = json.dumps(operation, sort_keys=True).lower()
        for term in ("session", "upload", "account access", "responsibility", "history", "todo"):
            self.assertIn(term, side_effects)
        self.assertIn("never blocked", side_effects)

    def test_employee_enable_is_explicit_and_does_not_restore_account_access(self) -> None:
        document = self.load_contract()
        operations = {
            operation["operationId"]: operation
            for path, item in document["paths"].items()
            if path.startswith("/employees")
            for operation in item.values()
            if isinstance(operation, dict) and "operationId" in operation
        }
        self.assertIn("enableEmployee", operations)
        if "enableEmployee" not in operations:
            return
        operation = operations["enableEmployee"]
        self.assertEqual(["SYSTEM_ADMIN"], operation["x-actor"]["roles"])
        self.assertTrue(operation["x-version"])
        self.assertTrue(operation["x-transaction"])
        self.assertTrue(operation["x-audit-event"])
        enable_text = json.dumps(operation, sort_keys=True).lower()
        for phrase in (
            "only restores the employee enabled state",
            "does not automatically restore revoked roles",
            "does not automatically grant business permissions",
            "system account status remains controlled by its separate operation",
        ):
            self.assertIn(phrase, enable_text)

    def test_system_account_credentials_are_write_only_and_default_enabled(self) -> None:
        document = self.load_contract()
        schemas = document["components"]["schemas"]
        self.assertNotIn("AccountStatus", schemas)
        self.assertEqual(
            "SystemAccountStatus",
            schemas["CurrentUser"]["properties"]["status"]["$ref"].rsplit("/", 1)[-1],
        )
        self.assertIn("/system-accounts", document["paths"])
        self.assertIn("SystemAccountCreateRequest", schemas)
        self.assertIn("InitialPasswordResetRequest", schemas)
        self.assertIn("SystemAccount", schemas)
        self.assertIn("SystemAccountStatus", schemas)
        if "/system-accounts" not in document["paths"]:
            return
        create = document["paths"]["/system-accounts"]["post"]
        create_ref = create["requestBody"]["content"]["application/json"]["schema"]["$ref"]
        self.assertEqual("SystemAccountCreateRequest", create_ref.rsplit("/", 1)[-1])
        create_schema = schemas["SystemAccountCreateRequest"]
        self.assertNotIn("status", create_schema["properties"])
        self.assertIn("initialPassword", create_schema["properties"])
        self.assertTrue(create_schema["properties"]["initialPassword"]["writeOnly"])
        self.assertIn("EmployeeReference", schemas)
        employee_reference = schemas["EmployeeReference"]
        self.assertEqual({"id", "version"}, set(employee_reference["required"]))
        self.assertEqual({"id", "version"}, set(employee_reference["properties"]))
        self.assertIn("employee", create_schema["properties"])
        self.assertEqual(
            "EmployeeReference",
            create_schema["properties"]["employee"]["$ref"].rsplit("/", 1)[-1],
        )
        self.assertNotIn("employeeId", create_schema["properties"])
        account = schemas["SystemAccount"]
        self.assertEqual("SystemAccountStatus", account["properties"]["status"]["$ref"].rsplit("/", 1)[-1])
        status_values = set(schemas["SystemAccountStatus"]["enum"])
        self.assertEqual({"ENABLED", "DISABLED", "LOCKED"}, status_values)
        self.assertEqual("ENABLED", schemas["SystemAccountStatus"]["default"])
        self.assertIn("enabled", json.dumps(create, sort_keys=True).lower())
        reset = schemas["InitialPasswordResetRequest"]
        self.assertIn("newInitialPassword", reset["required"])
        self.assertTrue(reset["properties"]["newInitialPassword"]["writeOnly"])
        account_property_names = set(account["properties"])
        self.assertTrue(
            account_property_names.isdisjoint(
                {"password", "initialPassword", "newInitialPassword", "passwordHash"}
            )
        )

    def test_role_configuration_is_read_only_and_member_revocation_records_warning(self) -> None:
        document = self.load_contract()
        operations = {
            operation["operationId"]: operation
            for item in document["paths"].values()
            for operation in item.values()
            if isinstance(operation, dict) and "operationId" in operation
        }
        self.assertNotIn("grantRolePermission", operations)
        self.assertNotIn("revokeRolePermission", operations)
        self.assertFalse(any("/grants" in path for path in document["paths"]))
        role_schema = document["components"]["schemas"]["RoleConfiguration"]
        self.assertNotIn("minimumActiveAdministrators", role_schema["properties"])
        self.assertNotIn("minimumActiveWarehouseOperators", role_schema["properties"])
        role_text = json.dumps(role_schema).lower()
        self.assertIn("businessscope", role_text)
        self.assertIn("systemadminbusinesspermissions", role_text)
        self.assertEqual(
            [False],
            role_schema["properties"]["systemAdminBusinessPermissions"].get("enum"),
        )
        self.assertIn("enum", role_schema["properties"]["businessScope"])
        revoke = operations["revokeSystemAccountRole"]
        revoke_text = json.dumps(revoke, sort_keys=True).lower()
        self.assertNotIn("last_admin", revoke_text)
        self.assertNotIn("last_warehouse", revoke_text)
        self.assertIn("warning", revoke_text)
        self.assertIn("immediate", revoke_text)

    def test_contract_has_unique_yaml_keys_and_operation_ids(self) -> None:
        import yaml

        class UniqueKeyLoader(yaml.SafeLoader):
            pass

        def construct_mapping(loader, node, deep=False):
            mapping = {}
            for key_node, value_node in node.value:
                key = loader.construct_object(key_node, deep=deep)
                self.assertNotIn(key, mapping, f"duplicate YAML key: {key}")
                mapping[key] = loader.construct_object(value_node, deep=deep)
            return mapping

        UniqueKeyLoader.add_constructor(
            yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG,
            construct_mapping,
        )
        with CONTRACT.open(encoding="utf-8") as stream:
            document = yaml.load(stream, Loader=UniqueKeyLoader)
        operation_ids = [
            operation["operationId"]
            for item in document["paths"].values()
            for operation in item.values()
            if isinstance(operation, dict) and "operationId" in operation
        ]
        self.assertEqual(len(operation_ids), len(set(operation_ids)))

    def test_catalog_group_a_management_and_active_reference_surfaces_are_explicit(self) -> None:
        document = self.load_contract()
        paths = document["paths"]
        management = {
            "asset-categories": "AssetCategory",
            "brands": "Brand",
            "manufacturers": "Manufacturer",
        }
        required_operations = {
            "AssetCategory": {
                "listAssetCategories",
                "getAssetCategory",
                "createAssetCategory",
                "updateAssetCategory",
                "disableAssetCategory",
                "enableAssetCategory",
            },
            "Brand": {
                "listBrands",
                "getBrand",
                "createBrand",
                "updateBrand",
                "disableBrand",
                "enableBrand",
            },
            "Manufacturer": {
                "listManufacturers",
                "getManufacturer",
                "createManufacturer",
                "updateManufacturer",
                "disableManufacturer",
                "enableManufacturer",
            },
        }
        operations = {
            operation["operationId"]: operation
            for item in paths.values()
            for operation in item.values()
            if isinstance(operation, dict) and "operationId" in operation
        }
        missing_operations = sorted(
            operation_id
            for operation_ids in required_operations.values()
            for operation_id in operation_ids
            if operation_id not in operations
        )
        self.assertFalse(missing_operations, f"catalog group A remains RED: {missing_operations}")
        if missing_operations:
            return
        for collection, resource in management.items():
            with self.subTest(collection=collection):
                self.assertIn(f"/{collection}", paths)
                self.assertIn("get", paths[f"/{collection}"])
                self.assertIn("Page", paths[f"/{collection}"]["get"]["parameters"][0]["$ref"])
                self.assertIn("PageSize", paths[f"/{collection}"]["get"]["parameters"][1]["$ref"])
                for path, item in paths.items():
                    if not path.startswith(f"/{collection}") or path.endswith("/active-references"):
                        continue
                    self.assertNotIn("delete", item)
                    for method, operation in item.items():
                        if method not in {"get", "post", "patch"}:
                            continue
                        self.assertEqual(["SYSTEM_ADMIN"], operation.get("x-actor", {}).get("roles"))
                        self.assertIn("401", operation["responses"])
                        self.assertIn("403", operation["responses"])
        active_reference_operations = {
            "listActiveAssetCategoryReferences": "AssetCategoryActiveReferencePage",
            "listActiveBrandReferences": "BrandActiveReferencePage",
            "listActiveManufacturerReferences": "ManufacturerActiveReferencePage",
        }
        for operation_id, response_schema in active_reference_operations.items():
            with self.subTest(operation=operation_id):
                self.assertIn(operation_id, operations)
                operation = operations[operation_id]
                self.assertEqual(
                    {"WAREHOUSE_OPERATOR", "ASSET_ADMIN"},
                    set(operation["x-actor"]["roles"]),
                )
                self.assertEqual(
                    response_schema,
                    operation["responses"]["200"]["content"]["application/json"]["schema"]["$ref"].rsplit("/", 1)[-1],
                )
                self.assertIn("401", operation["responses"])
                self.assertIn("403", operation["responses"])
        forbidden_prefixes = ("/warehouses", "/locations", "/assets", "/inbound")
        self.assertFalse(any(path.startswith(forbidden_prefixes) for path in paths))

    def test_asset_category_tree_and_patch_schema_rules_are_explicit(self) -> None:
        document = self.load_contract()
        schemas = document["components"]["schemas"]
        required_schemas = {
            "AssetCategory",
            "AssetCategoryCreateRequest",
            "AssetCategoryUpdateRequest",
            "CatalogReference",
            "ExtensionFieldTemplate",
        }
        missing_schemas = sorted(required_schemas - set(schemas))
        self.assertFalse(missing_schemas, f"catalog schemas remain RED: {missing_schemas}")
        if missing_schemas:
            return
        category_fields = {
            "code",
            "name",
            "parentCategory",
            "status",
            "description",
            "suggestedUnit",
            "numberingPrefix",
            "extensionFieldTemplate",
        }
        self.assertTrue(category_fields.issubset(schemas["AssetCategory"]["properties"]))
        for schema_name in ("AssetCategoryCreateRequest", "AssetCategoryUpdateRequest"):
            with self.subTest(schema=schema_name):
                self.assertNotIn("status", schemas[schema_name]["properties"])
                self.assertFalse(schemas[schema_name].get("additionalProperties", True))
        category_create = schemas["AssetCategoryCreateRequest"]
        category_update = schemas["AssetCategoryUpdateRequest"]
        self.assertEqual(
            "CatalogReference",
            category_create["properties"]["parentCategory"]["$ref"].rsplit("/", 1)[-1],
        )
        self.assertEqual(
            "CatalogReference",
            category_update["properties"]["parentCategory"]["$ref"].rsplit("/", 1)[-1],
        )
        self.assertEqual(1, category_update.get("minProperties"))
        self.assertNotIn("required", category_update)
        reference = schemas["CatalogReference"]
        self.assertEqual({"id", "version"}, set(reference["required"]))
        self.assertEqual("Id", reference["properties"]["id"]["$ref"].rsplit("/", 1)[-1])
        category = schemas["AssetCategory"]
        self.assertEqual(
            "ReferenceStatus",
            category["properties"]["status"]["$ref"].rsplit("/", 1)[-1],
        )
        operations = {
            operation["operationId"]: operation
            for item in document["paths"].values()
            for operation in item.values()
            if isinstance(operation, dict) and "operationId" in operation
        }
        category_update_text = json.dumps(operations["updateAssetCategory"], sort_keys=True).lower()
        for term in ("cycle", "version", "409", "unique", "reuse"):
            self.assertIn(term, category_update_text)

    def test_brand_manufacturer_references_and_active_selector_minimality_are_explicit(self) -> None:
        document = self.load_contract()
        schemas = document["components"]["schemas"]
        required_schemas = {
            "Brand",
            "BrandCreateRequest",
            "BrandUpdateRequest",
            "Manufacturer",
            "ManufacturerCreateRequest",
            "ManufacturerUpdateRequest",
            "BrandActiveReference",
            "ManufacturerActiveReference",
        }
        missing_schemas = sorted(required_schemas - set(schemas))
        self.assertFalse(missing_schemas, f"brand/manufacturer schemas remain RED: {missing_schemas}")
        if missing_schemas:
            return
        self.assertEqual(
            "CatalogReference",
            schemas["BrandCreateRequest"]["properties"]["defaultManufacturer"]["$ref"].rsplit("/", 1)[-1],
        )
        self.assertEqual(
            "CatalogReference",
            schemas["BrandUpdateRequest"]["properties"]["defaultManufacturer"]["$ref"].rsplit("/", 1)[-1],
        )
        for schema_name in ("BrandUpdateRequest", "ManufacturerUpdateRequest"):
            with self.subTest(schema=schema_name):
                self.assertNotIn("status", schemas[schema_name]["properties"])
                self.assertEqual(1, schemas[schema_name].get("minProperties"))
                self.assertNotIn("required", schemas[schema_name])
        for schema_name in ("Brand", "Manufacturer"):
            with self.subTest(schema=schema_name):
                self.assertEqual(
                    "ReferenceStatus",
                    schemas[schema_name]["properties"]["status"]["$ref"].rsplit("/", 1)[-1],
                )
        for schema_name in ("BrandActiveReference", "ManufacturerActiveReference"):
            with self.subTest(schema=schema_name):
                self.assertEqual(
                    {"id", "code", "name", "version"},
                    set(schemas[schema_name]["required"]),
                )
                self.assertTrue(
                    {"id", "code", "name", "version"}.issuperset(schemas[schema_name]["properties"])
                )
                self.assertNotIn("phone", json.dumps(schemas[schema_name]).lower())
                self.assertNotIn("email", json.dumps(schemas[schema_name]).lower())
        self.assertIn("defaultManufacturerId", schemas["BrandActiveReference"]["properties"])
        operations = {
            operation["operationId"]: operation
            for item in document["paths"].values()
            for operation in item.values()
            if isinstance(operation, dict) and "operationId" in operation
        }
        for operation_id in (
            "disableBrand",
            "disableManufacturer",
            "disableAssetCategory",
        ):
            with self.subTest(operation=operation_id):
                operation_text = json.dumps(operations[operation_id], sort_keys=True).lower()
                for term in ("referenced", "history", "new", "disabled"):
                    self.assertIn(term, operation_text)

    def test_catalog_business_writes_have_short_transaction_audit_and_error_boundaries(self) -> None:
        document = self.load_contract()
        management_prefixes = ("/asset-categories", "/brands", "/manufacturers")
        for path, item in document["paths"].items():
            if not path.startswith(management_prefixes):
                continue
            for method, operation in item.items():
                if method not in {"post", "patch"}:
                    continue
                with self.subTest(method=method, path=path):
                    if not operation.get("x-business-write"):
                        continue
                    self.assertTrue(operation.get("x-actor"))
                    self.assertTrue(operation.get("x-version"))
                    self.assertTrue(operation.get("x-transaction"))
                    self.assertIn("short", json.dumps(operation["x-transaction"]).lower())
                    self.assertTrue(operation.get("x-audit-event"))
                    self.assertIsInstance(operation.get("x-required-error-statuses"), list)
                    self.assertIn("401", operation["responses"])
                    self.assertIn("403", operation["responses"])
                    self.assertIn("409", operation["responses"])
                    refs = {
                        parameter.get("$ref", "").rsplit("/", 1)[-1]
                        for parameter in operation.get("parameters", [])
                    }
                    self.assertIn("IdempotencyKey", refs)
                    self.assertIn("CsrfToken", refs)

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
        required_employee_schemas = {
            "Employee",
            "EmployeeCreateRequest",
            "EmployeeUpdateRequest",
        }
        missing_employee_schemas = required_employee_schemas - set(employee_schemas)
        self.assertFalse(
            missing_employee_schemas,
            f"split employee schemas remain RED: {sorted(missing_employee_schemas)}",
        )
        if missing_employee_schemas:
            return
        self.assertIn(
            "phone",
            json.dumps(
                [
                    employee_schemas["Employee"],
                    employee_schemas["EmployeeCreateRequest"],
                    employee_schemas["EmployeeUpdateRequest"],
                ]
            ).lower(),
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

    def test_role_configuration_covers_fixed_baseline_and_member_safety(self) -> None:
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
            "getRoleConfiguration",
            "grantSystemAccountRole",
            "revokeSystemAccountRole",
        ):
            self.assertIn(operation_id, operations)
        role_text = json.dumps(document["components"]["schemas"]["RoleConfiguration"]).lower()
        self.assertIn("system_admin", role_text)
        self.assertIn("asset_admin", role_text)
        self.assertIn("warehouse_operator", role_text)
        grant_text = json.dumps(operations["grantSystemAccountRole"], sort_keys=True).lower()
        self.assertIn("business", grant_text)
        self.assertIn("enterprise", grant_text)
        revoke_text = json.dumps(operations["revokeSystemAccountRole"], sort_keys=True).lower()
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
                "enableEmployee",
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
                "getRoleConfiguration",
                "grantSystemAccountRole",
                "revokeSystemAccountRole",
            }.issubset(operation_ids)
        )
        self.assertFalse({"grantRolePermission", "revokeRolePermission"} & operation_ids)

    def test_catalog_group_a_examples_are_machine_readable_and_cover_lifecycle_boundaries(self) -> None:
        self.assertTrue(EXAMPLES.is_dir(), f"missing examples directory: {EXAMPLES}")
        files = sorted(EXAMPLES.glob("masterdata-catalog-*.json"))
        self.assertGreaterEqual(len(files), 12)
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
                "createAssetCategory",
                "updateAssetCategory",
                "disableAssetCategory",
                "enableAssetCategory",
                "createBrand",
                "updateBrand",
                "disableBrand",
                "enableBrand",
                "createManufacturer",
                "updateManufacturer",
                "disableManufacturer",
                "enableManufacturer",
            }.issubset(operation_ids)
        )
        self.assertIn("listActiveAssetCategoryReferences", operation_ids)
        self.assertIn("listActiveBrandReferences", operation_ids)
        self.assertIn("listActiveManufacturerReferences", operation_ids)

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
