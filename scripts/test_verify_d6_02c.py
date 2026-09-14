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
                allowed_fields = {"id", "code", "name", "version"}
                if schema_name == "BrandActiveReference":
                    allowed_fields.add("defaultManufacturerId")
                self.assertTrue(
                    allowed_fields.issuperset(schemas[schema_name]["properties"])
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

    def test_catalog_disabled_relation_guards_and_extension_labels_are_explicit(self) -> None:
        document = self.load_contract()
        schemas = document["components"]["schemas"]
        operations = {
            operation["operationId"]: operation
            for item in document["paths"].values()
            for operation in item.values()
            if isinstance(operation, dict) and "operationId" in operation
        }
        extension_template = schemas["ExtensionFieldTemplate"]
        self.assertIn("label", extension_template["required"])
        for operation_id in ("createBrand", "updateBrand"):
            with self.subTest(operation=operation_id):
                operation = operations[operation_id]
                self.assertIn(
                    "BRAND_DEFAULT_MANUFACTURER_DISABLED",
                    operation["x-error-codes"],
                )
                self.assertIn("disabled", json.dumps(operation).lower())
        for operation_id in ("createAssetCategory", "updateAssetCategory"):
            with self.subTest(operation=operation_id):
                operation = operations[operation_id]
                self.assertIn("ASSET_CATEGORY_PARENT_DISABLED", operation["x-error-codes"])
                self.assertIn("disabled", json.dumps(operation).lower())
        enable_category = operations["enableAssetCategory"]
        self.assertIn("ASSET_CATEGORY_PARENT_DISABLED", enable_category["x-error-codes"])
        enable_text = json.dumps(enable_category, sort_keys=True).lower()
        for term in ("ancestor", "active", "enabled"):
            self.assertIn(term, enable_text)

    def test_disabled_manufacturer_keeps_history_but_is_omitted_from_active_brand_selector(self) -> None:
        document = self.load_contract()
        operations = {
            operation["operationId"]: operation
            for item in document["paths"].values()
            for operation in item.values()
            if isinstance(operation, dict) and "operationId" in operation
        }
        disable_text = json.dumps(operations["disableManufacturer"], sort_keys=True).lower()
        for term in ("enabled brand", "allowed", "history", "not rewrit"):
            self.assertIn(term, disable_text)
        selector_text = json.dumps(operations["listActiveBrandReferences"], sort_keys=True).lower()
        for term in ("disabled", "omit", "default"):
            self.assertIn(term, selector_text)
        selector_examples = []
        for path in sorted(EXAMPLES.glob("masterdata-catalog-*.json")):
            value = json.loads(path.read_text(encoding="utf-8"))
            if value.get("operationId") != "listActiveBrandReferences":
                continue
            items = value.get("response", {}).get("items", [])
            if any("defaultManufacturerId" not in item for item in items):
                selector_examples.append(value)
        self.assertTrue(selector_examples, "missing selector example without disabled manufacturer default")
        self.assertIn("history", json.dumps(selector_examples).lower())

    def test_warehouse_management_and_active_reference_contract_is_explicit(self) -> None:
        document = self.load_contract()
        paths = document["paths"]
        operations = {
            operation["operationId"]: operation
            for item in paths.values()
            for operation in item.values()
            if isinstance(operation, dict) and "operationId" in operation
        }
        required_operations = {
            "listWarehouses",
            "getWarehouse",
            "createWarehouse",
            "updateWarehouse",
            "disableWarehouse",
            "enableWarehouse",
            "listActiveWarehouseReferences",
        }
        self.assertFalse(
            sorted(required_operations - set(operations)),
            f"warehouse contract remains RED: {sorted(required_operations - set(operations))}",
        )
        if required_operations - set(operations):
            return
        self.assertIn("/warehouses", paths)
        self.assertIn("/warehouses/{warehouseId}", paths)
        self.assertIn("/warehouses/{warehouseId}/disable", paths)
        self.assertIn("/warehouses/{warehouseId}/enable", paths)
        self.assertEqual(
            {"Page", "PageSize"},
            {
                parameter["$ref"].rsplit("/", 1)[-1]
                for parameter in paths["/warehouses"]["get"]["parameters"]
            },
        )
        for path, item in paths.items():
            if not path.startswith("/warehouses") or path.endswith("/active-references"):
                continue
            self.assertNotIn("delete", item)
            for method, operation in item.items():
                if method not in {"get", "post", "patch"}:
                    continue
                with self.subTest(method=method, path=path):
                    self.assertEqual(["SYSTEM_ADMIN"], operation["x-actor"]["roles"])
                    self.assertIn("401", operation["responses"])
                    self.assertIn("403", operation["responses"])
        active = operations["listActiveWarehouseReferences"]
        self.assertEqual(
            {"WAREHOUSE_OPERATOR", "ASSET_ADMIN"},
            set(active["x-actor"]["roles"]),
        )
        self.assertEqual(
            "WarehouseActiveReferencePage",
            active["responses"]["200"]["content"]["application/json"]["schema"]["$ref"].rsplit("/", 1)[-1],
        )
        self.assertIn("401", active["responses"])
        self.assertIn("403", active["responses"])

        schemas = document["components"]["schemas"]
        for schema_name in ("WarehouseCreateRequest", "WarehouseUpdateRequest"):
            with self.subTest(schema=schema_name):
                self.assertNotIn("status", schemas[schema_name]["properties"])
                self.assertFalse(schemas[schema_name].get("additionalProperties", True))
        self.assertEqual(1, schemas["WarehouseUpdateRequest"].get("minProperties"))
        self.assertNotIn("required", schemas["WarehouseUpdateRequest"])
        self.assertEqual(
            "ReferenceStatus",
            schemas["Warehouse"]["properties"]["status"]["$ref"].rsplit("/", 1)[-1],
        )
        disable_text = json.dumps(operations["disableWarehouse"], sort_keys=True).lower()
        for term in ("active asset", "open", "history", "never reused"):
            self.assertIn(term, disable_text)

    def test_location_tree_and_inbound_target_rules_are_explicit(self) -> None:
        document = self.load_contract()
        paths = document["paths"]
        schemas = document["components"]["schemas"]
        operations = {
            operation["operationId"]: operation
            for item in paths.values()
            for operation in item.values()
            if isinstance(operation, dict) and "operationId" in operation
        }
        required_operations = {
            "listLocations",
            "getLocation",
            "createLocation",
            "updateLocation",
            "disableLocation",
            "enableLocation",
            "listActiveLocationReferences",
        }
        missing = sorted(required_operations - set(operations))
        self.assertFalse(missing, f"location contract remains RED: {missing}")
        if missing:
            return
        self.assertEqual(
            {"BUILDING", "FLOOR", "ROOM", "AREA", "BIN"},
            set(schemas["LocationType"]["enum"]),
        )
        for schema_name in ("LocationCreateRequest", "LocationUpdateRequest"):
            with self.subTest(schema=schema_name):
                self.assertNotIn("status", schemas[schema_name]["properties"])
                self.assertFalse(schemas[schema_name].get("additionalProperties", True))
        self.assertEqual(1, schemas["LocationUpdateRequest"].get("minProperties"))
        self.assertNotIn("required", schemas["LocationUpdateRequest"])
        for schema_name, property_name in (
            ("LocationCreateRequest", "parentLocation"),
            ("LocationUpdateRequest", "parentLocation"),
            ("LocationCreateRequest", "warehouse"),
            ("LocationUpdateRequest", "warehouse"),
        ):
            with self.subTest(schema=schema_name, property=property_name):
                self.assertEqual(
                    "CatalogReference",
                    schemas[schema_name]["properties"][property_name]["$ref"].rsplit("/", 1)[-1],
                )
        location_disable = json.dumps(operations["disableLocation"], sort_keys=True).lower()
        for term in ("current", "asset", "open", "child", "cascade", "history"):
            self.assertIn(term, location_disable)
        location_create = json.dumps(operations["createLocation"], sort_keys=True).lower()
        location_update = json.dumps(operations["updateLocation"], sort_keys=True).lower()
        for operation_text in (location_create, location_update):
            for term in ("version", "cycle", "disabled", "409", "never reused"):
                self.assertIn(term, operation_text)
        location_enable = json.dumps(operations["enableLocation"], sort_keys=True).lower()
        for term in ("parent", "warehouse", "active", "enabled"):
            self.assertIn(term, location_enable)
        active_schema = schemas["LocationActiveReference"]
        self.assertTrue(
            {"id", "code", "name", "version"}.issubset(active_schema["properties"])
        )
        self.assertTrue(
            set(active_schema["properties"]).issubset(
                {"id", "code", "name", "version", "type", "warehouse", "parentLocation"}
            )
        )
        self.assertNotIn("phone", json.dumps(active_schema).lower())
        self.assertNotIn("email", json.dumps(active_schema).lower())
        active = operations["listActiveLocationReferences"]
        self.assertEqual(
            {"WAREHOUSE_OPERATOR", "ASSET_ADMIN"},
            set(active["x-actor"]["roles"]),
        )

    def _assert_writes_and_examples_cover_frozen_boundaries(
        self,
        management_prefix: str,
        example_prefix: str,
        operation_ids: set[str],
    ) -> None:
        document = self.load_contract()
        for path, item in document["paths"].items():
            if not path.startswith(management_prefix):
                continue
            for method, operation in item.items():
                if method not in {"post", "patch"} or not operation.get("x-business-write"):
                    continue
                with self.subTest(method=method, path=path):
                    self.assertEqual(["SYSTEM_ADMIN"], operation["x-actor"]["roles"])
                    self.assertTrue(operation.get("x-version"))
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
        files = sorted(EXAMPLES.glob(f"{example_prefix}*.json"))
        self.assertGreaterEqual(len(files), 8)
        values = [json.loads(path.read_text(encoding="utf-8")) for path in files]
        self.assertEqual({"positive", "rejection"}, {value["kind"] for value in values})
        self.assertTrue(operation_ids.issubset({value["operationId"] for value in values}))
        self.assertTrue(all("payload" in value for value in values))

    def test_warehouse_writes_and_examples_cover_frozen_boundaries(self) -> None:
        self._assert_writes_and_examples_cover_frozen_boundaries(
            "/warehouses",
            "masterdata-warehouse-",
            {"createWarehouse", "updateWarehouse", "disableWarehouse", "enableWarehouse"},
        )

    def test_location_writes_and_examples_cover_frozen_boundaries(self) -> None:
        self._assert_writes_and_examples_cover_frozen_boundaries(
            "/locations",
            "masterdata-location-",
            {"createLocation", "updateLocation", "disableLocation", "enableLocation"},
        )

    def test_numbering_rule_management_and_allocation_policy_are_explicit(self) -> None:
        document = self.load_contract()
        paths = document["paths"]
        schemas = document["components"]["schemas"]
        operations = {
            operation["operationId"]: operation
            for item in paths.values()
            for operation in item.values()
            if isinstance(operation, dict) and "operationId" in operation
        }
        required_operations = {
            "listNumberingRules",
            "getNumberingRule",
            "createNumberingRule",
            "updateNumberingRule",
            "disableNumberingRule",
            "enableNumberingRule",
        }
        missing = sorted(required_operations - set(operations))
        self.assertFalse(missing, f"numbering contract remains RED: {missing}")
        if missing:
            return
        self.assertIn("/numbering-rules", paths)
        self.assertIn("/numbering-rules/{numberingRuleId}", paths)
        self.assertIn("/numbering-rules/{numberingRuleId}/disable", paths)
        self.assertIn("/numbering-rules/{numberingRuleId}/enable", paths)
        self.assertEqual(
            {"Page", "PageSize"},
            {
                parameter["$ref"].rsplit("/", 1)[-1]
                for parameter in paths["/numbering-rules"]["get"]["parameters"]
            },
        )
        for path, item in paths.items():
            if not path.startswith("/numbering-rules"):
                continue
            self.assertNotIn("delete", item)
            for method, operation in item.items():
                if method not in {"get", "post", "patch"}:
                    continue
                with self.subTest(method=method, path=path):
                    self.assertEqual(["SYSTEM_ADMIN"], operation["x-actor"]["roles"])
                    self.assertIn("401", operation["responses"])
                    self.assertIn("403", operation["responses"])
        for schema_name in (
            "NumberingRule",
            "NumberingRuleCreateRequest",
            "NumberingRuleUpdateRequest",
            "NumberingTarget",
            "NumberingMode",
            "NumberingDatePart",
        ):
            self.assertIn(schema_name, schemas)
        self.assertEqual({"ASSET", "INBOUND_RECEIPT"}, set(schemas["NumberingTarget"]["enum"]))
        self.assertEqual({"ENTERPRISE", "CATEGORY_PREFIX"}, set(schemas["NumberingMode"]["enum"]))
        self.assertEqual(
            {"NONE", "YYYY", "YYYYMM", "YYYYMMDD"},
            set(schemas["NumberingDatePart"]["enum"]),
        )
        for schema_name in ("NumberingRuleCreateRequest", "NumberingRuleUpdateRequest"):
            with self.subTest(schema=schema_name):
                self.assertNotIn("status", schemas[schema_name]["properties"])
                self.assertFalse(schemas[schema_name].get("additionalProperties", True))
        self.assertEqual(1, schemas["NumberingRuleUpdateRequest"].get("minProperties"))
        self.assertNotIn("required", schemas["NumberingRuleUpdateRequest"])
        rule_fields = {
            "code",
            "target",
            "mode",
            "fixedPrefix",
            "datePart",
            "sequenceLength",
            "status",
            "version",
        }
        self.assertTrue(rule_fields.issubset(schemas["NumberingRule"]["properties"]))
        self.assertEqual(
            "ReferenceStatus",
            schemas["NumberingRule"]["properties"]["status"]["$ref"].rsplit("/", 1)[-1],
        )
        for operation_id in ("createNumberingRule", "updateNumberingRule"):
            operation_text = json.dumps(operations[operation_id], sort_keys=True).lower()
            for term in ("namespace", "category", "snapshot", "permanent", "reuse"):
                self.assertIn(term, operation_text)
        for operation_id in ("updateNumberingRule", "enableNumberingRule", "disableNumberingRule"):
            refs = {
                parameter.get("$ref", "").rsplit("/", 1)[-1]
                for parameter in operations[operation_id].get("parameters", [])
            }
            self.assertIn("IfMatchVersion", refs)
        for operation_id in ("enableNumberingRule", "updateNumberingRule"):
            operation_text = json.dumps(operations[operation_id], sort_keys=True).lower()
            for term in ("overlap", "current", "409"):
                self.assertIn(term, operation_text)
        category_prefix_text = json.dumps(operations["enableNumberingRule"], sort_keys=True).lower()
        for term in ("prefix", "missing", "conflict", "ambiguous"):
            self.assertIn(term, category_prefix_text)
        allocation_text = json.dumps(document, sort_keys=True).lower()
        for term in ("short transaction", "gap", "failed", "cancelled", "preview", "client"):
            self.assertIn(term, allocation_text)

    def test_numbering_examples_cover_rule_conflicts_and_gap_policy_without_fake_execution(self) -> None:
        files = sorted(EXAMPLES.glob("masterdata-numbering-*.json"))
        self.assertGreaterEqual(len(files), 8)
        values = [json.loads(path.read_text(encoding="utf-8")) for path in files]
        self.assertEqual({"positive", "rejection"}, {value["kind"] for value in values})
        operation_ids = {value["operationId"] for value in values}
        self.assertTrue(
            {
                "createNumberingRule",
                "updateNumberingRule",
                "disableNumberingRule",
                "enableNumberingRule",
            }.issubset(operation_ids)
        )
        evidence = [value for value in values if value.get("evidenceType") == "allocation-policy"]
        self.assertTrue(evidence)
        evidence_text = json.dumps(evidence, sort_keys=True).lower()
        for term in ("gap", "failed", "cancelled", "not reused", "not implemented"):
            self.assertIn(term, evidence_text)
        all_text = json.dumps(values, sort_keys=True).lower()
        for term in ("duplicate", "overlap", "missing", "conflict"):
            self.assertIn(term, all_text)

    def test_numbering_target_mode_and_prefix_combinations_are_explicit(self) -> None:
        document = self.load_contract()
        schemas = document["components"]["schemas"]
        operations = {
            operation["operationId"]: operation
            for item in document["paths"].values()
            for operation in item.values()
            if isinstance(operation, dict) and "operationId" in operation
        }
        allowed = ["ASSET:ENTERPRISE", "ASSET:CATEGORY_PREFIX", "INBOUND_RECEIPT:ENTERPRISE"]
        for operation_id in ("createNumberingRule", "updateNumberingRule", "enableNumberingRule"):
            with self.subTest(operation=operation_id):
                operation = operations[operation_id]
                self.assertEqual(allowed, operation.get("x-allowed-target-modes"))
                self.assertIn(
                    "INBOUND_RECEIPT:CATEGORY_PREFIX",
                    operation.get("x-rejected-target-modes", []),
                )
                self.assertIn(
                    "CATEGORY_PREFIX:fixedPrefix",
                    operation.get("x-rejected-prefix-combinations", []),
                )
                self.assertIn("NUMBERING_MODE_NOT_ALLOWED_FOR_TARGET", operation["x-error-codes"])
                self.assertIn("NUMBERING_RULE_FIXED_PREFIX_NOT_ALLOWED", operation["x-error-codes"])
                validation = json.dumps(operation, sort_keys=True).lower()
                for term in (
                    "category_prefix",
                    "inbound_receipt",
                    "enterprise",
                    "fixedprefix",
                    "numberingprefix",
                    "400",
                ):
                    self.assertIn(term, validation)

        for schema_name in ("NumberingRuleCreateRequest", "NumberingRuleUpdateRequest", "NumberingRule"):
            with self.subTest(schema=schema_name):
                fixed_prefix = json.dumps(schemas[schema_name]["properties"]["fixedPrefix"]).lower()
                self.assertIn("enterprise", fixed_prefix)
                self.assertIn("category_prefix", fixed_prefix)
                self.assertIn("omit", fixed_prefix)
                self.assertIn("numberingprefix", fixed_prefix)

        values = [
            json.loads(path.read_text(encoding="utf-8"))
            for path in sorted(EXAMPLES.glob("masterdata-numbering-*.json"))
        ]
        inbound_positive = [
            value
            for value in values
            if value.get("kind") == "positive"
            and value.get("operationId") == "createNumberingRule"
            and value.get("payload", {}).get("target") == "INBOUND_RECEIPT"
        ]
        self.assertTrue(inbound_positive)
        self.assertTrue(all(value["payload"]["mode"] == "ENTERPRISE" for value in inbound_positive))
        invalid_inbound = [
            value
            for value in values
            if value.get("status") == 400
            and value.get("payload", {}).get("target") == "INBOUND_RECEIPT"
            and value.get("payload", {}).get("mode") == "CATEGORY_PREFIX"
        ]
        self.assertTrue(invalid_inbound)
        self.assertTrue(
            all(value["response"]["code"] == "NUMBERING_MODE_NOT_ALLOWED_FOR_TARGET" for value in invalid_inbound)
        )
        category_positive = [
            value
            for value in values
            if value.get("kind") == "positive"
            and value.get("operationId") == "createNumberingRule"
            and value.get("payload", {}).get("target") == "ASSET"
            and value.get("payload", {}).get("mode") == "CATEGORY_PREFIX"
        ]
        self.assertTrue(category_positive)
        self.assertTrue(all("fixedPrefix" not in value["payload"] for value in category_positive))
        invalid_fixed_prefix = [
            value
            for value in values
            if value.get("status") == 400
            and value.get("payload", {}).get("mode") == "CATEGORY_PREFIX"
            and "fixedPrefix" in value.get("payload", {})
        ]
        self.assertTrue(invalid_fixed_prefix)
        self.assertTrue(
            all(value["response"]["code"] == "NUMBERING_RULE_FIXED_PREFIX_NOT_ALLOWED" for value in invalid_fixed_prefix)
        )

    def test_business_parameters_and_configuration_anomalies_are_explicit(self) -> None:
        document = self.load_contract()
        paths = document["paths"]
        schemas = document["components"]["schemas"]
        operations = {
            operation["operationId"]: operation
            for item in paths.values()
            for operation in item.values()
            if isinstance(operation, dict) and "operationId" in operation
        }
        required_operations = {
            "listBusinessParameterVersions",
            "getCurrentBusinessParameterVersion",
            "getBusinessParameterVersion",
            "createBusinessParameterVersion",
            "listConfigurationAnomalies",
            "getConfigurationAnomaly",
        }
        missing = sorted(required_operations - set(operations))
        self.assertFalse(missing, f"business parameter contract remains RED: {missing}")
        if missing:
            return

        self.assertIn("/business-parameters", paths)
        self.assertIn("/business-parameters/current", paths)
        self.assertIn("/business-parameters/{parameterVersionId}", paths)
        self.assertNotIn("patch", paths["/business-parameters/{parameterVersionId}"])
        self.assertNotIn("delete", paths["/business-parameters/{parameterVersionId}"])
        for operation_id in (
            "listBusinessParameterVersions",
            "getCurrentBusinessParameterVersion",
            "getBusinessParameterVersion",
            "listConfigurationAnomalies",
            "getConfigurationAnomaly",
        ):
            with self.subTest(operation=operation_id):
                operation = operations[operation_id]
                self.assertEqual(["SYSTEM_ADMIN"], operation["x-actor"]["roles"])
                self.assertIn("401", operation["responses"])
                self.assertIn("403", operation["responses"])

        create = operations["createBusinessParameterVersion"]
        self.assertEqual(["SYSTEM_ADMIN"], create["x-actor"]["roles"])
        self.assertTrue(create.get("x-business-write"))
        self.assertTrue(create.get("x-version"))
        create_text = json.dumps(create, sort_keys=True).lower()
        for term in ("if-match-version", "current version", "short transaction", "before", "after", "audit"):
            self.assertIn(term, create_text)
        refs = {
            parameter.get("$ref", "").rsplit("/", 1)[-1]
            for parameter in create.get("parameters", [])
        }
        self.assertTrue({"IfMatchVersion", "IdempotencyKey", "CsrfToken"}.issubset(refs))
        self.assertEqual({400, 401, 403, 409}, set(create["x-required-error-statuses"]))
        self.assertEqual(
            "BusinessParameterVersion",
            create["responses"]["201"]["content"]["application/json"]["schema"]["$ref"].rsplit("/", 1)[-1],
        )

        required_schemas = {
            "BusinessParameterVersion",
            "BusinessParameterVersionCreateRequest",
            "BusinessParameterSnapshot",
            "BusinessParameterVersionStatus",
            "ConfigurationAnomaly",
            "ConfigurationAnomalyType",
            "ConfigurationAnomalyStatus",
            "PageBusinessParameterVersion",
            "PageConfigurationAnomaly",
            "TodoReference",
        }
        missing_schemas = sorted(required_schemas - set(schemas))
        self.assertFalse(missing_schemas, f"business parameter schemas remain RED: {missing_schemas}")
        if missing_schemas:
            return

        snapshot = schemas["BusinessParameterSnapshot"]
        snapshot_fields = {
            "timezone",
            "workWeek",
            "holidays",
            "adjustedWorkdays",
            "adminDecisionDeadlineWorkdays",
            "warehouseClaimDeadlineWorkdays",
            "warehouseFirstExecutionDeadlineWorkdays",
            "handoverAcceptanceDeadlineNaturalDays",
            "notificationEscalationThresholds",
        }
        self.assertTrue(snapshot_fields.issubset(snapshot["required"]))
        timezone = snapshot["properties"]["timezone"]
        self.assertEqual("Asia/Shanghai", timezone["default"])
        self.assertIn("iana", json.dumps(timezone).lower())
        self.assertIn("timezone", json.dumps(snapshot).lower())
        self.assertEqual(
            {"MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"},
            set(snapshot["properties"]["workWeek"]["items"]["enum"]),
        )
        for field in ("holidays", "adjustedWorkdays"):
            with self.subTest(calendar_field=field):
                items = snapshot["properties"][field]["items"]
                self.assertEqual("BusinessDate", items["$ref"].rsplit("/", 1)[-1])
                self.assertTrue(snapshot["properties"][field]["uniqueItems"])
        for field in (
            "adminDecisionDeadlineWorkdays",
            "warehouseClaimDeadlineWorkdays",
            "warehouseFirstExecutionDeadlineWorkdays",
            "handoverAcceptanceDeadlineNaturalDays",
        ):
            with self.subTest(duration_field=field):
                property_schema = snapshot["properties"][field]
                self.assertEqual("integer", property_schema["type"])
                self.assertEqual(1, property_schema["minimum"])
                self.assertIn(property_schema["x-unit"], {"workdays", "natural-days"})

        version = schemas["BusinessParameterVersion"]
        self.assertTrue(
            {
                "id",
                "version",
                "status",
                "effectiveAt",
                "actor",
                "snapshot",
            }.issubset(version["required"])
        )
        self.assertEqual({"ACTIVE", "SUPERSEDED"}, set(schemas["BusinessParameterVersionStatus"]["enum"]))
        self.assertEqual(
            "Instant",
            version["properties"]["effectiveAt"]["$ref"].rsplit("/", 1)[-1],
        )
        self.assertEqual(
            "BusinessParameterSnapshot",
            version["properties"]["snapshot"]["$ref"].rsplit("/", 1)[-1],
        )
        semantic_text = json.dumps(
            [create, version, snapshot], sort_keys=True
        ).lower()
        for term in (
            "newly created",
            "parameterversionid",
            "computed",
            "deadline",
            "not recalculate",
            "historical",
            "businessdate",
            "instant",
            "00:00",
        ):
            self.assertIn(term, semantic_text)
        create_schema = schemas["BusinessParameterVersionCreateRequest"]
        self.assertEqual("BusinessParameterSnapshot", create_schema["properties"]["snapshot"]["$ref"].rsplit("/", 1)[-1])
        self.assertIn("current", json.dumps(create_schema).lower())
        self.assertIn("conflict", json.dumps(create_schema).lower())

        anomaly = schemas["ConfigurationAnomaly"]
        self.assertTrue(
            {
                "id",
                "type",
                "status",
                "detectedAt",
                "dedupKey",
                "relatedTodos",
            }.issubset(anomaly["required"])
        )
        self.assertEqual(
            {"NO_ASSET_ADMIN", "NO_WAREHOUSE_OPERATOR", "SMS_CHANNEL_MISSING", "WECHAT_TEMPLATE_MISSING"},
            set(schemas["ConfigurationAnomalyType"]["enum"]),
        )
        self.assertEqual({"OPEN", "RESOLVED"}, set(schemas["ConfigurationAnomalyStatus"]["enum"]))
        anomaly_text = json.dumps(anomaly, sort_keys=True).lower()
        for term in ("dedup", "todo", "retained", "automatically", "ignore", "delete"):
            self.assertIn(term, anomaly_text)
        self.assertIn("Instant", json.dumps(anomaly["properties"]["detectedAt"]))
        self.assertEqual("date-time", anomaly["properties"]["resolvedAt"]["format"])
        self.assertEqual("TodoReference", anomaly["properties"]["relatedTodos"]["items"]["$ref"].rsplit("/", 1)[-1])

        for path, item in paths.items():
            if not path.startswith("/configuration-anomalies"):
                continue
            self.assertNotIn("post", item)
            self.assertNotIn("patch", item)
            self.assertNotIn("delete", item)
        self.assertEqual(
            {"Page", "PageSize"},
            {
                parameter["$ref"].rsplit("/", 1)[-1]
                for parameter in paths["/configuration-anomalies"]["get"]["parameters"]
            },
        )
        forbidden_operations = {"ignoreConfigurationAnomaly", "resolveConfigurationAnomaly", "deleteConfigurationAnomaly"}
        self.assertTrue(forbidden_operations.isdisjoint(operations))

        parameter_examples = [
            json.loads(path.read_text(encoding="utf-8"))
            for path in sorted(EXAMPLES.glob("masterdata-business-parameter-*.json"))
        ]
        self.assertGreaterEqual(len(parameter_examples), 5)
        parameter_operation_ids = {value["operationId"] for value in parameter_examples}
        self.assertTrue(
            {
                "listBusinessParameterVersions",
                "getCurrentBusinessParameterVersion",
                "createBusinessParameterVersion",
            }.issubset(parameter_operation_ids)
        )
        self.assertTrue(any(value.get("status") == 409 for value in parameter_examples))
        self.assertTrue(any(value.get("status") == 400 for value in parameter_examples))
        parameter_text = json.dumps(parameter_examples, sort_keys=True).lower()
        for term in ("if-match", "holiday", "duplicate", "threshold", "not implemented"):
            self.assertIn(term, parameter_text)

        anomaly_examples = [
            json.loads(path.read_text(encoding="utf-8"))
            for path in sorted(EXAMPLES.glob("masterdata-configuration-anomaly-*.json"))
        ]
        self.assertGreaterEqual(len(anomaly_examples), 4)
        anomaly_types = {
            value.get("response", {}).get("type")
            for value in anomaly_examples
            if value.get("response", {}).get("type")
        }
        self.assertEqual(
            {"NO_ASSET_ADMIN", "NO_WAREHOUSE_OPERATOR", "SMS_CHANNEL_MISSING", "WECHAT_TEMPLATE_MISSING"},
            anomaly_types,
        )
        recovery_text = json.dumps(anomaly_examples, sort_keys=True).lower()
        for term in ("resolved", "retained", "original", "todo", "automatic"):
            self.assertIn(term, recovery_text)

    def test_business_parameter_deadlines_use_prd_units_and_open_anomaly_resolution_is_optional(self) -> None:
        document = self.load_contract()
        schemas = document["components"]["schemas"]
        snapshot = schemas["BusinessParameterSnapshot"]
        required_deadlines = {
            "adminDecisionDeadlineWorkdays": 2,
            "warehouseClaimDeadlineWorkdays": 1,
            "warehouseFirstExecutionDeadlineWorkdays": 2,
            "handoverAcceptanceDeadlineNaturalDays": 3,
        }
        self.assertTrue(set(required_deadlines).issubset(snapshot["required"]))
        self.assertNotIn("adminProcessingDeadline", snapshot["properties"])
        self.assertNotIn("warehouseOperatorClaimDeadline", snapshot["properties"])
        self.assertNotIn("handoverDeadline", snapshot["properties"])
        self.assertNotIn("messageEscalationThreshold", snapshot["properties"])
        expected_units = {
            "adminDecisionDeadlineWorkdays": "workdays",
            "warehouseClaimDeadlineWorkdays": "workdays",
            "warehouseFirstExecutionDeadlineWorkdays": "workdays",
            "handoverAcceptanceDeadlineNaturalDays": "natural-days",
        }
        for field, default in required_deadlines.items():
            with self.subTest(deadline=field):
                property_schema = snapshot["properties"][field]
                self.assertEqual("integer", property_schema["type"])
                self.assertEqual(1, property_schema["minimum"])
                self.assertEqual(default, property_schema["default"])
                self.assertEqual(expected_units[field], property_schema["x-unit"])

        thresholds = schemas["NotificationEscalationThresholds"]
        threshold_fields = {
            "administratorUnreadHours": (4, "hours"),
            "onDutyWarehouseOperatorUnreadHours": (4, "hours"),
            "firstExecutionOverdueNaturalDays": (1, "natural-days"),
            "receiptDueUnreadHours": (24, "hours"),
        }
        self.assertTrue(set(threshold_fields).issubset(thresholds["required"]))
        self.assertEqual(
            "NotificationEscalationThresholds",
            snapshot["properties"]["notificationEscalationThresholds"]["$ref"].rsplit("/", 1)[-1],
        )
        self.assertIn("notificationEscalationThresholds", snapshot["required"])
        for field, (default, unit) in threshold_fields.items():
            with self.subTest(threshold=field):
                property_schema = thresholds["properties"][field]
                self.assertEqual("integer", property_schema["type"])
                self.assertEqual(1, property_schema["minimum"])
                self.assertEqual(default, property_schema["default"])
                self.assertEqual(unit, property_schema["x-unit"])
        calendar_text = json.dumps(snapshot, sort_keys=True).lower()
        for term in ("workday", "work week", "natural day", "timezone", "not recalculat"):
            self.assertIn(term, calendar_text)

        anomaly = schemas["ConfigurationAnomaly"]
        self.assertNotIn("resolvedAt", anomaly["required"])
        resolved_at = anomaly["properties"]["resolvedAt"]
        self.assertEqual("string", resolved_at["type"])
        self.assertEqual("date-time", resolved_at["format"])
        self.assertNotIn("nullable", resolved_at)
        self.assertNotIn("allOf", resolved_at)
        resolution_text = json.dumps(anomaly, sort_keys=True).lower()
        for term in ("open", "omitted", "resolved", "required"):
            self.assertIn(term, resolution_text)

        parameter_examples = [
            json.loads(path.read_text(encoding="utf-8"))
            for path in sorted(EXAMPLES.glob("masterdata-business-parameter-*.json"))
        ]
        self.assertTrue(parameter_examples)
        parameter_text = json.dumps(parameter_examples, sort_keys=True).lower()
        for field in required_deadlines:
            self.assertIn(field.lower(), parameter_text)
        self.assertIn("notificationescalationthresholds", parameter_text)
        non_positive = [
            value
            for value in parameter_examples
            if value.get("status") == 400
            and "threshold" in value.get("scenario", "").lower()
        ]
        self.assertTrue(non_positive)
        self.assertTrue(
            any(
                threshold_value <= 0
                for value in non_positive
                for threshold_value in value["payload"]["snapshot"]["notificationEscalationThresholds"].values()
            )
        )

        anomaly_examples = [
            json.loads(path.read_text(encoding="utf-8"))
            for path in sorted(EXAMPLES.glob("masterdata-configuration-anomaly-*.json"))
        ]
        open_examples = [
            value for value in anomaly_examples if value.get("response", {}).get("status") == "OPEN"
        ]
        resolved_examples = [
            value for value in anomaly_examples if value.get("response", {}).get("status") == "RESOLVED"
        ]
        self.assertTrue(open_examples)
        self.assertTrue(resolved_examples)
        self.assertTrue(all("resolvedAt" not in value["response"] for value in open_examples))
        self.assertTrue(all(value["response"].get("resolvedAt") for value in resolved_examples))

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

    def test_inbound_draft_receipt_contract_declares_scoped_reads_and_mutations(self) -> None:
        document = self.load_contract()
        paths = document["paths"]
        operations = {
            operation["operationId"]: operation
            for item in paths.values()
            for operation in item.values()
            if isinstance(operation, dict) and "operationId" in operation
        }
        required = {
            "listInboundReceipts",
            "getInboundReceipt",
            "createInboundReceipt",
            "updateInboundReceipt",
        }
        missing = sorted(required - set(operations))
        self.assertFalse(missing, f"inbound draft contract remains RED: {missing}")
        if missing:
            return

        self.assertIn("Inbound", {tag["name"] for tag in document["tags"]})
        self.assertIn("/inbound-receipts", paths)
        self.assertIn("/inbound-receipts/{receiptId}", paths)
        self.assertNotIn("delete", paths["/inbound-receipts"])
        self.assertNotIn("delete", paths["/inbound-receipts/{receiptId}"])

        read_roles = {"ASSET_ADMIN", "WAREHOUSE_OPERATOR", "AUDIT_READ_ONLY"}
        for operation_id in ("listInboundReceipts", "getInboundReceipt"):
            with self.subTest(operation=operation_id):
                operation = operations[operation_id]
                self.assertEqual(read_roles, set(operation["x-actor"]["roles"]))
                self.assertNotIn("SYSTEM_ADMIN", operation["x-actor"]["roles"])
                self.assertIn("200", operation["responses"])
                self.assertIn("401", operation["responses"])
                self.assertIn("403", operation["responses"])
                scope = json.dumps(operation, sort_keys=True).lower()
                for phrase in (
                    "own",
                    "draft",
                    "submitted",
                    "history",
                    "approved",
                    "execution",
                    "explicit",
                    "no automatic",
                ):
                    self.assertIn(phrase, scope)

        for operation_id in ("createInboundReceipt", "updateInboundReceipt"):
            with self.subTest(operation=operation_id):
                operation = operations[operation_id]
                self.assertTrue(operation.get("x-business-write"))
                self.assertEqual(["WAREHOUSE_OPERATOR"], operation["x-actor"]["roles"])
                self.assertTrue(operation.get("x-version"))
                self.assertTrue(operation.get("x-transaction"))
                self.assertTrue(operation.get("x-audit-event"))
                self.assertTrue(
                    {400, 401, 403, 404, 409}.issubset(
                        set(operation["x-required-error-statuses"])
                    )
                )
                refs = {
                    parameter.get("$ref", "").rsplit("/", 1)[-1]
                    for parameter in operation.get("parameters", [])
                }
                self.assertTrue({"IdempotencyKey", "CsrfToken"}.issubset(refs))
                if operation_id == "updateInboundReceipt":
                    self.assertIn("IfMatchVersion", refs)
                text = json.dumps(operation, sort_keys=True).lower()
                for phrase in (
                    "creator",
                    "draft",
                    "server",
                    "business number",
                    "asset",
                    "lock",
                    "warehouse",
                    "location",
                    "snapshot",
                ):
                    self.assertIn(phrase, text)

        request_refs = {
            "createInboundReceipt": "InboundReceiptCreateRequest",
            "updateInboundReceipt": "InboundReceiptUpdateRequest",
        }
        for operation_id, schema_name in request_refs.items():
            request_ref = operations[operation_id]["requestBody"]["content"]["application/json"]["schema"]
            self.assertEqual(schema_name, request_ref["$ref"].rsplit("/", 1)[-1])
        list_refs = {
            parameter.get("$ref", "").rsplit("/", 1)[-1]
            for parameter in operations["listInboundReceipts"].get("parameters", [])
        }
        self.assertTrue({"Page", "PageSize"}.issubset(list_refs))

    def test_inbound_receipt_schemas_freeze_snapshots_and_draft_input_rules(self) -> None:
        document = self.load_contract()
        schemas = document["components"]["schemas"]
        required_schemas = {
            "InboundReceipt",
            "PageInboundReceipt",
            "InboundReceiptStatus",
            "InboundReceiptItem",
            "InboundReceiptCreateRequest",
            "InboundReceiptUpdateRequest",
            "InboundReceiptSnapshot",
            "InboundAssetReference",
            "InboundTargetReference",
        }
        missing = sorted(required_schemas - set(schemas))
        self.assertFalse(missing, f"inbound receipt schemas remain RED: {missing}")
        if missing:
            return

        self.assertEqual(
            {
                "DRAFT",
                "PENDING_ADMIN_DECISION",
                "REJECTED",
                "WAITING_WAREHOUSE_CLAIM",
                "WAREHOUSE_PROCESSING",
                "COMPLETED",
                "CANCELLED",
            },
            set(schemas["InboundReceiptStatus"]["enum"]),
        )
        receipt = schemas["InboundReceipt"]
        receipt_fields = {
            "id",
            "receiptNumber",
            "status",
            "businessDate",
            "createdBy",
            "createdAt",
            "version",
            "parameterVersionId",
            "snapshot",
            "items",
        }
        self.assertTrue(receipt_fields.issubset(set(receipt["required"])))
        self.assertEqual("Id", receipt["properties"]["id"]["$ref"].rsplit("/", 1)[-1])
        self.assertEqual(
            "BusinessDate",
            receipt["properties"]["businessDate"]["$ref"].rsplit("/", 1)[-1],
        )
        self.assertEqual("Instant", receipt["properties"]["createdAt"]["$ref"].rsplit("/", 1)[-1])
        self.assertEqual("Id", receipt["properties"]["parameterVersionId"]["$ref"].rsplit("/", 1)[-1])
        receipt_text = json.dumps(receipt, sort_keys=True).lower()
        for phrase in ("permanent", "server", "snapshot", "target", "asset"):
            self.assertIn(phrase, receipt_text)

        item = schemas["InboundReceiptItem"]
        self.assertTrue(
            {
                "asset",
                "assetSnapshot",
                "targetWarehouse",
                "targetLocation",
                "warehouseSnapshot",
                "locationSnapshot",
            }.issubset(set(item["required"]))
        )
        for schema_name in ("InboundAssetReference", "InboundTargetReference"):
            self.assertEqual({"id", "version"}, set(schemas[schema_name]["required"]))
            self.assertEqual(
                "Id",
                schemas[schema_name]["properties"]["id"]["$ref"].rsplit("/", 1)[-1],
            )
            self.assertIn("version", schemas[schema_name]["properties"])

        create = schemas["InboundReceiptCreateRequest"]
        update = schemas["InboundReceiptUpdateRequest"]
        self.assertIn("items", create["required"])
        self.assertFalse(create.get("additionalProperties", True))
        self.assertEqual(1, update.get("minProperties"))
        self.assertNotIn("required", update)
        self.assertFalse(update.get("additionalProperties", True))
        for schema in (create, update):
            self.assertNotIn("receiptNumber", schema["properties"])
            self.assertNotIn("status", schema["properties"])
            self.assertNotIn("createdBy", schema["properties"])
            self.assertNotIn("parameterVersionId", schema["properties"])

    def test_inbound_draft_examples_cover_server_number_and_state_rejections(self) -> None:
        files = sorted(EXAMPLES.glob("masterdata-inbound-*.json"))
        self.assertGreaterEqual(len(files), 6)
        values = [json.loads(path.read_text(encoding="utf-8")) for path in files]
        self.assertTrue(all(value.get("evidence") for value in values))
        self.assertEqual({"positive", "rejection"}, {value["kind"] for value in values})
        operation_ids = {value["operationId"] for value in values}
        self.assertTrue({"createInboundReceipt", "updateInboundReceipt"}.issubset(operation_ids))
        create = next(
            value
            for value in values
            if value["operationId"] == "createInboundReceipt" and value["kind"] == "positive"
        )
        self.assertEqual(201, create["status"])
        self.assertEqual("DRAFT", create["response"]["status"])
        self.assertTrue(create["response"]["receiptNumber"])
        self.assertNotIn("receiptNumber", create["payload"])
        self.assertTrue(
            any(value["status"] == 403 and "creator" in json.dumps(value).lower() for value in values)
        )
        rejection_text = json.dumps(
            [value for value in values if value["kind"] == "rejection"], sort_keys=True
        ).lower()
        for phrase in ("duplicate", "stale", "disabled", "mismatch"):
            self.assertIn(phrase, rejection_text)

    def test_inbound_submit_and_cancel_freeze_atomic_lock_and_race_semantics(self) -> None:
        document = self.load_contract()
        operations = {
            operation["operationId"]: operation
            for item in document["paths"].values()
            for operation in item.values()
            if isinstance(operation, dict) and "operationId" in operation
        }
        required = {"submitInboundReceipt", "cancelInboundReceipt"}
        missing = sorted(required - set(operations))
        self.assertFalse(missing, f"inbound submit/cancel remains RED: {missing}")
        if missing:
            return

        for operation_id in sorted(required):
            with self.subTest(operation=operation_id):
                operation = operations[operation_id]
                self.assertEqual(["WAREHOUSE_OPERATOR"], operation["x-actor"]["roles"])
                self.assertTrue(operation.get("x-business-write"))
                self.assertTrue(operation.get("x-version"))
                self.assertTrue(operation.get("x-transaction"))
                self.assertTrue(operation.get("x-audit-event"))
                self.assertTrue(
                    {400, 401, 403, 404, 409}.issubset(
                        set(operation["x-required-error-statuses"])
                    )
                )
                refs = {
                    parameter.get("$ref", "").rsplit("/", 1)[-1]
                    for parameter in operation.get("parameters", [])
                }
                self.assertTrue(
                    {"IdempotencyKey", "CsrfToken", "IfMatchVersion"}.issubset(refs)
                )

        submit = operations["submitInboundReceipt"]
        submit_text = json.dumps(submit, sort_keys=True).lower()
        for phrase in (
            "complete",
            "all assets",
            "business lock",
            "rollback",
            "waiting_inbound",
            "pending_inbound",
            "custody",
            "shared",
            "deadline",
            "parameter",
            "current processor",
            "idempotent",
            "restart",
            "timeout",
            "processing ownership",
        ):
            self.assertIn(phrase, submit_text)

        cancel = operations["cancelInboundReceipt"]
        cancel_text = json.dumps(cancel, sort_keys=True).lower()
        for phrase in (
            "creator",
            "draft",
            "pending_admin_decision",
            "race",
            "decision",
            "version",
            "release",
            "history",
            "active todo",
            "approved",
            "terminal",
            "idempotent",
        ):
            self.assertIn(phrase, cancel_text)
        request_ref = cancel["requestBody"]["content"]["application/json"]["schema"]["$ref"]
        self.assertEqual("InboundReceiptCancelRequest", request_ref.rsplit("/", 1)[-1])

    def test_inbound_lock_deadline_and_submit_cancel_examples_are_explicit(self) -> None:
        document = self.load_contract()
        schemas = document["components"]["schemas"]
        required = {
            "InboundAssetBusinessLock",
            "InboundReceiptCancelRequest",
            "InboundReceiptSnapshot",
        }
        missing = sorted(required - set(schemas))
        self.assertFalse(missing, f"inbound submit/cancel schemas remain RED: {missing}")
        if missing:
            return

        lock = schemas["InboundAssetBusinessLock"]
        self.assertEqual(
            {"assetId", "businessType", "businessId", "businessItemId", "lockVersion", "acquiredAt"},
            set(lock["required"]),
        )
        self.assertEqual(["INBOUND"], lock["properties"]["businessType"]["enum"])
        self.assertEqual("Instant", lock["properties"]["acquiredAt"]["$ref"].rsplit("/", 1)[-1])
        item = schemas["InboundReceiptItem"]
        self.assertIn("id", item["required"])
        self.assertIn("businessLock", item["properties"])
        snapshot = schemas["InboundReceiptSnapshot"]
        self.assertTrue(
            {"parameterVersionId", "businessDate", "timezone", "adminDecisionDeadlineWorkdays"}
            .issubset(set(snapshot["required"]))
        )
        cancel = schemas["InboundReceiptCancelRequest"]
        self.assertEqual({"reason"}, set(cancel["required"]))
        self.assertFalse(cancel.get("additionalProperties", True))

        receipt = schemas["InboundReceipt"]
        for field in ("submittedAt", "adminDecisionDueAt"):
            self.assertIn(field, receipt["properties"])
            self.assertEqual(
                "Instant", receipt["properties"][field]["$ref"].rsplit("/", 1)[-1]
            )

        files = sorted(EXAMPLES.glob("masterdata-inbound-*.json"))
        values = [json.loads(path.read_text(encoding="utf-8")) for path in files]
        submit_values = [value for value in values if value["operationId"] == "submitInboundReceipt"]
        cancel_values = [value for value in values if value["operationId"] == "cancelInboundReceipt"]
        self.assertGreaterEqual(len(submit_values), 5)
        self.assertGreaterEqual(len(cancel_values), 2)
        positive_submit = next(value for value in submit_values if value["kind"] == "positive")
        self.assertEqual("PENDING_ADMIN_DECISION", positive_submit["response"]["status"])
        self.assertTrue(positive_submit["idempotencyReplayReturnsSameResult"])
        self.assertEqual("PENDING_INBOUND", positive_submit["assetAfter"]["cardStage"])
        self.assertEqual("WAITING_INBOUND", positive_submit["assetAfter"]["lifecycle"])
        self.assertIsNone(positive_submit["assetAfter"]["currentCustody"])
        self.assertTrue(positive_submit["response"]["items"][0]["businessLock"])
        rejection_text = json.dumps(
            [value for value in submit_values if value["kind"] == "rejection"],
            sort_keys=True,
        ).lower()
        for phrase in ("incomplete", "lock", "rollback", "mismatch"):
            self.assertIn(phrase, rejection_text)
        positive_cancel = next(value for value in cancel_values if value["kind"] == "positive")
        self.assertEqual("CANCELLED", positive_cancel["response"]["status"])
        self.assertEqual("DRAFT", positive_cancel["assetAfter"]["cardStage"])
        self.assertFalse(positive_cancel["activeLockAfter"])
        conflict = next(value for value in cancel_values if value["kind"] == "rejection")
        self.assertIn("decision", json.dumps(conflict, sort_keys=True).lower())

    def test_inbound_single_admin_decision_contract_is_atomic_and_scope_preserving(self) -> None:
        document = self.load_contract()
        operations = {
            operation["operationId"]: operation
            for item in document["paths"].values()
            for operation in item.values()
            if isinstance(operation, dict) and "operationId" in operation
        }
        self.assertIn("decideInboundReceipt", operations)
        if "decideInboundReceipt" not in operations:
            return
        operation = operations["decideInboundReceipt"]
        self.assertEqual(["ASSET_ADMIN"], operation["x-actor"]["roles"])
        self.assertTrue(operation.get("x-business-write"))
        self.assertTrue(operation.get("x-version"))
        self.assertTrue(operation.get("x-transaction"))
        self.assertTrue(operation.get("x-audit-event"))
        self.assertTrue(
            {400, 401, 403, 404, 409}.issubset(set(operation["x-required-error-statuses"]))
        )
        refs = {
            parameter.get("$ref", "").rsplit("/", 1)[-1]
            for parameter in operation.get("parameters", [])
        }
        self.assertTrue({"IdempotencyKey", "CsrfToken", "IfMatchVersion"}.issubset(refs))
        request_ref = operation["requestBody"]["content"]["application/json"]["schema"]["$ref"]
        self.assertEqual("InboundDecisionRequest", request_ref.rsplit("/", 1)[-1])
        text = json.dumps(operation, sort_keys=True).lower()
        for phrase in (
            "all asset administrators",
            "view",
            "does not reserve",
            "first legal decision",
            "one effective",
            "receipt version",
            "actual effective decision",
            "approved scope",
            "waiting_warehouse_claim",
            "rejected",
            "release",
            "pending_inbound",
            "draft",
            "current processor",
            "dual-role",
            "does not claim",
            "idempotent",
        ):
            self.assertIn(phrase, text)

    def test_inbound_decision_schemas_and_examples_capture_unique_outcomes(self) -> None:
        document = self.load_contract()
        schemas = document["components"]["schemas"]
        required = {
            "InboundDecisionRequest",
            "InboundApproveRequest",
            "InboundRejectRequest",
            "InboundDecision",
            "InboundApprovalScope",
        }
        missing = sorted(required - set(schemas))
        self.assertFalse(missing, f"inbound decision schemas remain RED: {missing}")
        if missing:
            return
        request = schemas["InboundDecisionRequest"]
        refs = {
            item["$ref"].rsplit("/", 1)[-1]
            for item in request["oneOf"]
        }
        self.assertEqual({"InboundApproveRequest", "InboundRejectRequest"}, refs)
        self.assertEqual(["APPROVED"], schemas["InboundApproveRequest"]["properties"]["decision"]["enum"])
        self.assertEqual(["REJECTED"], schemas["InboundRejectRequest"]["properties"]["decision"]["enum"])
        self.assertIn("reason", schemas["InboundRejectRequest"]["required"])
        decision = schemas["InboundDecision"]
        self.assertTrue(
            {"id", "result", "decidedReceiptVersion", "decidedBy", "decidedAt", "scope"}
            .issubset(set(decision["required"]))
        )
        receipt = schemas["InboundReceipt"]
        self.assertIn("decision", receipt["properties"])
        self.assertIn("warehouseClaimDueAt", receipt["properties"])
        snapshot = schemas["InboundReceiptSnapshot"]
        self.assertTrue(
            {"warehouseClaimDeadlineWorkdays", "warehouseFirstExecutionDeadlineWorkdays"}
            .issubset(set(snapshot["required"]))
        )
        values = [
            json.loads(path.read_text(encoding="utf-8"))
            for path in sorted(EXAMPLES.glob("masterdata-inbound-decision-*.json"))
        ]
        self.assertGreaterEqual(len(values), 3)
        self.assertTrue(all(value.get("evidence") for value in values))
        self.assertEqual({"positive", "rejection"}, {value["kind"] for value in values})
        approved = next(value for value in values if value.get("response", {}).get("status") == "WAITING_WAREHOUSE_CLAIM")
        rejected = next(value for value in values if value.get("response", {}).get("status") == "REJECTED")
        conflict = next(value for value in values if value["kind"] == "rejection")
        self.assertTrue(approved["assetAfter"]["businessLockRetained"])
        self.assertEqual("PENDING_INBOUND", approved["assetAfter"]["cardStage"])
        self.assertIsNone(approved["response"].get("currentProcessor"))
        self.assertFalse(rejected["assetAfter"]["businessLockRetained"])
        self.assertEqual("DRAFT", rejected["assetAfter"]["cardStage"])
        self.assertIn("actualEffectiveDecision", conflict)

    def test_inbound_claim_contract_freezes_explicit_whole_receipt_ownership(self) -> None:
        document = self.load_contract()
        operations = {
            operation["operationId"]: operation
            for item in document["paths"].values()
            for operation in item.values()
            if isinstance(operation, dict) and "operationId" in operation
        }
        self.assertIn("claimInboundReceipt", operations)
        if "claimInboundReceipt" not in operations:
            return
        operation = operations["claimInboundReceipt"]
        self.assertEqual(["WAREHOUSE_OPERATOR"], operation["x-actor"]["roles"])
        self.assertTrue(operation.get("x-business-write"))
        self.assertTrue(operation.get("x-version"))
        self.assertTrue(operation.get("x-transaction"))
        self.assertTrue(operation.get("x-audit-event"))
        refs = {
            parameter.get("$ref", "").rsplit("/", 1)[-1]
            for parameter in operation.get("parameters", [])
        }
        self.assertTrue({"IdempotencyKey", "CsrfToken", "IfMatchVersion"}.issubset(refs))
        text = json.dumps(operation, sort_keys=True).lower()
        for phrase in (
            "explicit",
            "view",
            "does not claim",
            "creator",
            "must claim",
            "first legal",
            "whole receipt",
            "one current processor",
            "other warehouse operators",
            "read-only",
            "actual processor",
            "business locks unchanged",
            "approved scope unchanged",
            "restart",
            "logout",
            "timeout",
            "no automatic release",
            "idempotent",
        ):
            self.assertIn(phrase, text)

    def test_inbound_claim_schema_and_examples_preserve_processing_ownership(self) -> None:
        document = self.load_contract()
        schemas = document["components"]["schemas"]
        self.assertIn("InboundProcessingOwnership", schemas)
        if "InboundProcessingOwnership" not in schemas:
            return
        ownership = schemas["InboundProcessingOwnership"]
        self.assertEqual(
            {"processor", "claimedAt", "ownershipVersion"}, set(ownership["required"])
        )
        receipt = schemas["InboundReceipt"]
        self.assertEqual(
            "InboundProcessingOwnership",
            receipt["properties"]["currentProcessor"]["$ref"].rsplit("/", 1)[-1],
        )
        self.assertIn("warehouseFirstExecutionDueAt", receipt["properties"])
        values = [
            json.loads(path.read_text(encoding="utf-8"))
            for path in sorted(EXAMPLES.glob("masterdata-inbound-claim-*.json"))
        ]
        self.assertGreaterEqual(len(values), 2)
        positive = next(value for value in values if value["kind"] == "positive")
        conflict = next(value for value in values if value["kind"] == "rejection")
        self.assertEqual("WAREHOUSE_PROCESSING", positive["response"]["status"])
        self.assertEqual(
            positive["actor"], positive["response"]["currentProcessor"]["processor"]["username"]
        )
        self.assertTrue(positive["creatorStillHadToClaim"])
        self.assertTrue(positive["businessLocksUnchanged"])
        self.assertEqual(
            positive["response"]["currentProcessor"]["processor"],
            conflict["actualProcessor"]["processor"],
        )

    def test_inbound_item_acceptance_is_current_processor_only_and_transactional(self) -> None:
        document = self.load_contract()
        paths = document["paths"]
        operations = {
            operation["operationId"]: operation
            for item in paths.values()
            for operation in item.values()
            if isinstance(operation, dict) and "operationId" in operation
        }
        self.assertIn("acceptInboundReceiptItem", operations)
        self.assertNotIn("acceptInboundReceiptItems", operations)
        if "acceptInboundReceiptItem" not in operations:
            return
        self.assertIn("/inbound-receipts/{receiptId}/items/{itemId}/accept", paths)
        operation = operations["acceptInboundReceiptItem"]
        self.assertEqual(["WAREHOUSE_OPERATOR"], operation["x-actor"]["roles"])
        self.assertNotIn("SYSTEM_ADMIN", operation["x-actor"]["roles"])
        self.assertTrue(operation.get("x-business-write"))
        self.assertTrue(operation.get("x-version"))
        self.assertTrue(operation.get("x-transaction"))
        self.assertTrue(operation.get("x-audit-event"))
        self.assertTrue(
            {400, 401, 403, 404, 409}.issubset(set(operation["x-required-error-statuses"]))
        )
        refs = {
            parameter.get("$ref", "").rsplit("/", 1)[-1]
            for parameter in operation.get("parameters", [])
        }
        self.assertTrue({"IdempotencyKey", "CsrfToken", "IfMatchVersion"}.issubset(refs))
        request_ref = operation["requestBody"]["content"]["application/json"]["schema"]["$ref"]
        self.assertEqual("InboundAcceptanceRequest", request_ref.rsplit("/", 1)[-1])
        text = json.dumps(operation, sort_keys=True).lower()
        for phrase in (
            "current processor",
            "one item",
            "short transaction",
            "receipt version",
            "asset version",
            "lock version",
            "observed asset code",
            "approved scope",
            "active warehouse",
            "active location",
            "warehouse membership",
            "waiting_inbound",
            "in_stock",
            "pending_inbound",
            "active",
            "storage custody",
            "release",
            "remaining items",
            "warehouse_processing",
            "last item",
            "completed",
            "archived",
            "read-only",
            "idempotent",
            "no partial",
            "rollback",
            "response loss",
        ):
            self.assertIn(phrase, text)

    def test_inbound_acceptance_schemas_and_examples_close_each_asset_exactly_once(self) -> None:
        document = self.load_contract()
        schemas = document["components"]["schemas"]
        required = {
            "InboundReceiptItemExecutionStatus",
            "InboundAcceptanceRequest",
            "InboundAcceptanceRecord",
            "InboundAcceptedAsset",
            "InboundAcceptanceResult",
        }
        missing = sorted(required - set(schemas))
        self.assertFalse(missing, f"inbound acceptance schemas remain RED: {missing}")
        if missing:
            return
        self.assertEqual(
            {"PENDING", "ACCEPTED"},
            set(schemas["InboundReceiptItemExecutionStatus"]["enum"]),
        )
        request = schemas["InboundAcceptanceRequest"]
        self.assertEqual(
            {"assetVersion", "lockVersion", "observedAssetCode"}, set(request["required"])
        )
        self.assertFalse(request.get("additionalProperties", True))
        item = schemas["InboundReceiptItem"]
        self.assertIn("executionStatus", item["required"])
        self.assertIn("acceptance", item["properties"])
        acceptance = schemas["InboundAcceptanceRecord"]
        self.assertTrue(
            {
                "id",
                "receiptItemId",
                "assetId",
                "acceptedBy",
                "acceptedAt",
                "businessDate",
                "receiptVersionBefore",
                "assetVersionBefore",
                "assetVersionAfter",
                "lockVersion",
                "storageCustody",
            }.issubset(set(acceptance["required"]))
        )
        result = schemas["InboundAcceptanceResult"]
        self.assertEqual({"receipt", "item", "asset"}, set(result["required"]))
        asset = schemas["InboundAcceptedAsset"]
        self.assertEqual(
            {"id", "assetCode", "lifecycle", "exception", "cardStage", "version", "currentCustody", "inboundBusinessDate"},
            set(asset["required"]),
        )

        values = [
            json.loads(path.read_text(encoding="utf-8"))
            for path in sorted(EXAMPLES.glob("masterdata-inbound-accept-*.json"))
        ]
        self.assertGreaterEqual(len(values), 6)
        positives = [value for value in values if value["kind"] == "positive"]
        rejections = [value for value in values if value["kind"] == "rejection"]
        self.assertGreaterEqual(len(positives), 2)
        self.assertGreaterEqual(len(rejections), 4)
        partial = next(value for value in positives if value["scenario"] == "remaining items")
        final = next(value for value in positives if value["scenario"] == "last item")
        self.assertEqual("WAREHOUSE_PROCESSING", partial["response"]["receipt"]["status"])
        self.assertEqual("COMPLETED", final["response"]["receipt"]["status"])
        for value in positives:
            self.assertEqual("ACCEPTED", value["response"]["item"]["executionStatus"])
            self.assertEqual("IN_STOCK", value["response"]["asset"]["lifecycle"])
            self.assertEqual("ACTIVE", value["response"]["asset"]["cardStage"])
            self.assertEqual("STORAGE", value["response"]["asset"]["currentCustody"]["custodyType"])
            self.assertFalse(value["activeBusinessLockAfter"])
            self.assertTrue(value["idempotencyReplayReturnsSameResult"])
        self.assertTrue(final["processingOwnershipArchived"])
        rejection_text = json.dumps(rejections, sort_keys=True).lower()
        for phrase in ("not current processor", "stale", "inactive", "mismatch", "rollback"):
            self.assertIn(phrase, rejection_text)

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
        id_schema = schemas["Id"]
        self.assertEqual("string", id_schema["type"])
        self.assertEqual("^[0-9]+$", id_schema["pattern"])
        for name in future_schemas:
            if name not in schemas:
                continue
            with self.subTest(schema=name):
                self.assertEqual(
                    "#/components/schemas/Id",
                    schemas[name]["properties"]["id"]["$ref"],
                )
        if "Asset" in schemas:
            self.assertEqual(
                "#/components/schemas/BusinessDate",
                schemas["Asset"]["properties"]["createdBusinessDate"]["$ref"],
            )
            self.assertNotIn("businessDate", schemas["Asset"]["properties"])
        self.assertEqual(
            "#/components/schemas/Instant",
            schemas["AuditEvent"]["properties"]["occurredAt"]["$ref"],
        )
        self.assertEqual("date-time", schemas["Instant"]["format"])
        self.assertEqual("string", schemas["Money"]["type"])
        self.assertEqual("^[0-9]+\\.[0-9]{2}$", schemas["Money"]["pattern"])
        self.assertIn("PERSONAL", schemas["UsageMode"]["enum"])
        self.assertIn("SHARED", schemas["UsageMode"]["enum"])

    def test_asset_read_contract_exposes_scoped_ledger_and_id_time_shapes(self) -> None:
        document = self.load_contract()
        paths = document["paths"]
        schemas = document["components"]["schemas"]
        operations = {
            operation["operationId"]: operation
            for item in paths.values()
            for operation in item.values()
            if isinstance(operation, dict) and "operationId" in operation
        }
        required_operations = {"listAssets", "getAsset"}
        missing = sorted(required_operations - set(operations))
        self.assertFalse(missing, f"asset read contract remains RED: {missing}")
        if missing:
            return
        self.assertIn("Assets", {tag["name"] for tag in document["tags"]})
        self.assertIn("/assets", paths)
        self.assertIn("/assets/{assetId}", paths)
        self.assertNotIn("delete", paths["/assets/{assetId}"])
        expected_roles = {"ASSET_ADMIN", "WAREHOUSE_OPERATOR", "AUDIT_READ_ONLY"}
        for operation_id in required_operations:
            with self.subTest(operation=operation_id):
                operation = operations[operation_id]
                self.assertEqual(expected_roles, set(operation["x-actor"]["roles"]))
                self.assertNotIn("SYSTEM_ADMIN", operation["x-actor"]["roles"])
                self.assertIn("200", operation["responses"])
                self.assertIn("401", operation["responses"])
                self.assertIn("403", operation["responses"])
        list_assets = operations["listAssets"]
        list_refs = {
            parameter.get("$ref", "").rsplit("/", 1)[-1]
            for parameter in list_assets.get("parameters", [])
        }
        self.assertTrue({"Page", "PageSize"}.issubset(list_refs))
        filter_refs = {
            "AssetCodeFilter",
            "AssetNameFilter",
            "SerialNumberFilter",
            "AssetCategoryFilter",
            "AssetLifecycleFilter",
            "AssetExceptionFilter",
            "AssetWarehouseFilter",
            "AssetLocationFilter",
            "AssetOrganizationFilter",
        }
        self.assertTrue(filter_refs.issubset(list_refs))
        self.assertEqual(
            "PageAsset",
            list_assets["responses"]["200"]["content"]["application/json"]["schema"]["$ref"].rsplit("/", 1)[-1],
        )
        self.assertEqual(
            "Asset",
            operations["getAsset"]["responses"]["200"]["content"]["application/json"]["schema"]["$ref"].rsplit("/", 1)[-1],
        )

        required_schemas = {
            "Asset",
            "PageAsset",
            "AssetLifecycle",
            "AssetException",
            "AssetCardStage",
            "AssetCustodySummary",
            "AssetBusinessSummary",
        }
        missing_schemas = sorted(required_schemas - set(schemas))
        self.assertFalse(missing_schemas, f"asset read schemas remain RED: {missing_schemas}")
        if missing_schemas:
            return
        asset = schemas["Asset"]
        asset_fields = {
            "id",
            "assetCode",
            "name",
            "lifecycle",
            "exception",
            "cardStage",
            "version",
            "createdBusinessDate",
            "createdAt",
            "parameterVersionId",
            "plannedInboundWarehouse",
            "plannedInboundLocation",
            "currentCustody",
            "openBusinessSummary",
        }
        self.assertTrue(asset_fields.issubset(asset["properties"]))
        self.assertEqual("#/components/schemas/Id", asset["properties"]["id"]["$ref"])
        self.assertEqual(
            "#/components/schemas/BusinessDate",
            asset["properties"]["createdBusinessDate"]["$ref"],
        )
        self.assertNotIn("businessDate", asset["properties"])
        self.assertEqual("#/components/schemas/Instant", asset["properties"]["createdAt"]["$ref"])
        self.assertTrue({"WAITING_INBOUND", "IN_STOCK"}.issubset(schemas["AssetLifecycle"]["enum"]))
        self.assertEqual(
            {"DRAFT", "PENDING_INBOUND", "ACTIVE", "CANCELLED"},
            set(schemas["AssetCardStage"]["enum"]),
        )
        custody = schemas["AssetCustodySummary"]
        self.assertIn("oneOf", custody)
        custody_text = json.dumps(custody, sort_keys=True).lower()
        for term in ("personal", "shared", "responsib", "mutually", "exclusive"):
            self.assertIn(term, custody_text)
        self.assertIn("formal", json.dumps(asset, sort_keys=True).lower())
        self.assertIn("unsettled", json.dumps(schemas["AssetBusinessSummary"], sort_keys=True).lower())

    def test_asset_state_vocabularies_match_prd_without_overloading_exception_states(self) -> None:
        document = self.load_contract()
        schemas = document["components"]["schemas"]
        self.assertEqual(
            {
                "WAITING_INBOUND",
                "IN_STOCK",
                "IN_USE",
                "BORROWED",
                "UNDER_REPAIR",
                "FACTORY_REPAIR",
                "PENDING_SCRAP",
                "SCRAPPED",
            },
            set(schemas["AssetLifecycle"]["enum"]),
        )
        self.assertEqual({"NORMAL", "SEARCHING", "LOST"}, set(schemas["AssetException"]["enum"]))
        self.assertNotIn("LOST", schemas["AssetLifecycle"]["enum"])
        self.assertNotIn("OVERDUE", schemas["AssetException"]["enum"])

    def test_asset_custody_distinguishes_storage_personal_shared_and_orgs(self) -> None:
        document = self.load_contract()
        schemas = document["components"]["schemas"]
        custody = schemas["AssetCustodySummary"]
        self.assertEqual(3, len(custody["oneOf"]))
        branches = {
            branch["properties"]["custodyType"]["enum"][0]: branch
            for branch in custody["oneOf"]
        }
        self.assertEqual({"STORAGE", "PERSONAL", "SHARED"}, set(branches))

        storage = branches["STORAGE"]
        self.assertTrue({"custodyType", "warehouse", "location"}.issubset(storage["required"]))
        self.assertNotIn("usageMode", storage["properties"])
        self.assertNotIn("responsibleEmployee", storage["properties"])
        self.assertNotIn("departmentResponsibleEmployee", storage["properties"])
        self.assertFalse(storage.get("additionalProperties", True))

        personal = branches["PERSONAL"]
        self.assertTrue(
            {"custodyType", "usingOrganization", "responsibleEmployee", "location"}.issubset(
                personal["required"]
            )
        )
        self.assertNotIn("sharedResponsibleEmployees", personal["properties"])
        self.assertNotIn("departmentResponsibleEmployee", personal["properties"])
        self.assertEqual(
            "OrganizationReference",
            personal["properties"]["usingOrganization"]["$ref"].rsplit("/", 1)[-1],
        )
        self.assertFalse(personal.get("additionalProperties", True))

        shared = branches["SHARED"]
        self.assertTrue(
            {"custodyType", "usingOrganization", "departmentResponsibleEmployee", "location"}.issubset(
                shared["required"]
            )
        )
        self.assertNotIn("responsibleEmployee", shared["properties"])
        self.assertNotIn("sharedResponsibleEmployees", shared["properties"])
        self.assertEqual(
            "OrganizationReference",
            shared["properties"]["usingOrganization"]["$ref"].rsplit("/", 1)[-1],
        )
        self.assertFalse(shared.get("additionalProperties", True))

        self.assertEqual(
            "OrganizationReference",
            schemas["Asset"]["properties"]["organization"]["$ref"].rsplit("/", 1)[-1],
        )
        for schema_name in ("AssetCreateRequest", "AssetUpdateRequest"):
            self.assertEqual(
                "OrganizationReference",
                schemas[schema_name]["properties"]["organization"]["$ref"].rsplit("/", 1)[-1],
            )

    def test_asset_read_scope_and_copy_accept_any_visible_non_cancelled_source(self) -> None:
        document = self.load_contract()
        operations = {
            operation["operationId"]: operation
            for item in document["paths"].values()
            for operation in item.values()
            if isinstance(operation, dict) and "operationId" in operation
        }
        for operation_id in ("listAssets", "getAsset"):
            scope_text = json.dumps(operations[operation_id], sort_keys=True).lower()
            for phrase in (
                "enterprise-wide",
                "not narrowed",
                "explicitly authorized audit scope",
                "system_admin",
                "no automatic",
            ):
                self.assertIn(phrase, scope_text)

        copy = operations["copyAsset"]
        copy_text = json.dumps(copy, sort_keys=True).lower()
        for phrase in ("non-cancelled", "active", "business lock", "does not block", "new card", "source version"):
            self.assertIn(phrase, copy_text)
        self.assertNotIn("source draft version", copy_text)
        self.assertNotIn("ASSET_NOT_CREATOR", copy["x-error-codes"])
        self.assertNotIn("ASSET_DRAFT_LOCKED", copy["x-error-codes"])
        self.assertIn("ASSET_SOURCE_CANCELLED", copy["x-error-codes"])

        positive_examples = [
            json.loads(path.read_text(encoding="utf-8"))
            for path in sorted(EXAMPLES.glob("masterdata-asset-copy-*.json"))
            if json.loads(path.read_text(encoding="utf-8")).get("kind") == "positive"
        ]
        self.assertTrue(positive_examples)
        source = positive_examples[0]["source"]
        self.assertEqual("ACTIVE", source["cardStage"])
        self.assertTrue(source["businessLock"])
        self.assertNotEqual(source["createdBy"], positive_examples[0]["actor"])
        self.assertTrue(
            any(
                value.get("kind") == "rejection"
                and value.get("operationId") == "copyAsset"
                and value.get("error", {}).get("code") == "ASSET_SOURCE_CANCELLED"
                for path in sorted(EXAMPLES.glob("masterdata-asset-copy-*.json"))
                for value in [json.loads(path.read_text(encoding="utf-8"))]
            )
        )

    def test_asset_draft_mutations_and_examples_preserve_card_boundaries(self) -> None:
        document = self.load_contract()
        paths = document["paths"]
        schemas = document["components"]["schemas"]
        operations = {
            operation["operationId"]: operation
            for item in paths.values()
            for operation in item.values()
            if isinstance(operation, dict) and "operationId" in operation
        }
        required_operations = {"createAsset", "updateAsset", "copyAsset", "cancelAssetCard"}
        missing = sorted(required_operations - set(operations))
        self.assertFalse(missing, f"asset draft mutations remain RED: {missing}")
        if missing:
            return
        self.assertIn("/assets/{assetId}/copy", paths)
        self.assertIn("/assets/{assetId}/cancel", paths)
        for operation_id in required_operations:
            with self.subTest(operation=operation_id):
                operation = operations[operation_id]
                self.assertTrue(operation.get("x-business-write"))
                self.assertEqual(["WAREHOUSE_OPERATOR"], operation["x-actor"]["roles"])
                self.assertTrue(operation.get("x-version"))
                self.assertTrue(operation.get("x-transaction"))
                self.assertTrue(operation.get("x-audit-event"))
                self.assertTrue(
                    {400, 401, 403, 404, 409}.issubset(set(operation["x-required-error-statuses"]))
                )
                refs = {
                    parameter.get("$ref", "").rsplit("/", 1)[-1]
                    for parameter in operation.get("parameters", [])
                }
                self.assertTrue({"IdempotencyKey", "CsrfToken"}.issubset(refs))
        request_schema_by_operation = {
            "createAsset": "AssetCreateRequest",
            "updateAsset": "AssetUpdateRequest",
            "copyAsset": "AssetCopyRequest",
            "cancelAssetCard": "AssetCancelRequest",
        }
        for operation_id, schema_name in request_schema_by_operation.items():
            with self.subTest(operation=operation_id):
                request_ref = operations[operation_id]["requestBody"]["content"]["application/json"]["schema"]["$ref"]
                self.assertEqual(schema_name, request_ref.rsplit("/", 1)[-1])
                self.assertIn(schema_name, schemas)

        create_schema = schemas["AssetCreateRequest"]
        self.assertEqual({"name"}, set(create_schema["required"]))
        self.assertFalse(create_schema.get("additionalProperties", True))
        allowed_fields = {
            "name",
            "category",
            "model",
            "unit",
            "organization",
            "plannedInboundWarehouse",
            "plannedInboundLocation",
            "brand",
            "manufacturer",
            "serialNumber",
            "acquisitionDate",
            "warrantyExpiryDate",
            "serviceProvider",
            "serviceContact",
            "servicePhone",
            "source",
            "notes",
            "attachments",
            "extensionValues",
        }
        self.assertTrue(allowed_fields.issubset(create_schema["properties"]))
        forbidden_fields = {
            "assetCode",
            "lifecycle",
            "exception",
            "cardStage",
            "currentCustody",
            "usageMode",
            "responsibleEmployee",
            "createdBusinessDate",
            "parameterVersionId",
        }
        for schema_name in ("AssetCreateRequest", "AssetUpdateRequest", "AssetCopyRequest"):
            self.assertTrue(forbidden_fields.isdisjoint(schemas[schema_name]["properties"]))
        for field in (
            "category",
            "organization",
            "plannedInboundWarehouse",
            "plannedInboundLocation",
            "brand",
            "manufacturer",
        ):
            with self.subTest(reference=field):
                self.assertEqual(
                    "OrganizationReference" if field == "organization" else "CatalogReference",
                    create_schema["properties"][field]["$ref"].rsplit("/", 1)[-1],
                )
        update_schema = schemas["AssetUpdateRequest"]
        self.assertEqual(1, update_schema.get("minProperties"))
        self.assertNotIn("required", update_schema)
        self.assertFalse(update_schema.get("additionalProperties", True))
        copy_schema = schemas["AssetCopyRequest"]
        self.assertEqual({"quantity"}, set(copy_schema["required"]))
        self.assertEqual(1, copy_schema["properties"]["quantity"]["minimum"])
        self.assertEqual(100, copy_schema["properties"]["quantity"]["maximum"])
        self.assertEqual({"reason"}, set(schemas["AssetCancelRequest"]["required"]))

        update_text = json.dumps(operations["updateAsset"], sort_keys=True).lower()
        for term in (
            "draft",
            "pending_inbound",
            "active",
            "descriptive",
            "lock",
            "assetcode",
            "category",
            "organization",
            "lifecycle",
            "responsibility",
        ):
            self.assertIn(term, update_text)
        create_text = json.dumps(operations["createAsset"], sort_keys=True).lower()
        for term in (
            "assetcode",
            "permanent",
            "waiting_inbound",
            "normal",
            "draft",
            "planned",
            "custody",
            "parameterversionid",
            "createdbusinessdate",
            "createdat",
            "creator",
            "serial",
        ):
            self.assertIn(term, create_text)
        copy_text = json.dumps(operations["copyAsset"], sort_keys=True).lower()
        for term in ("100", "independent", "serial", "attachment", "history", "gap", "transaction", "partial"):
            self.assertIn(term, copy_text)
        cancel_text = json.dumps(operations["cancelAssetCard"], sort_keys=True).lower()
        for term in ("draft", "creator", "lock", "tombstone", "never reused", "physical", "read-only"):
            self.assertIn(term, cancel_text)

        examples = [
            json.loads(path.read_text(encoding="utf-8"))
            for path in sorted(EXAMPLES.glob("masterdata-asset-*.json"))
        ]
        self.assertGreaterEqual(len(examples), 10)
        self.assertTrue(all("evidence" in value for value in examples))
        operation_ids = {value["operationId"] for value in examples}
        self.assertTrue(required_operations.issubset(operation_ids))
        minimal = [
            value
            for value in examples
            if value.get("operationId") == "createAsset"
            and value.get("kind") == "positive"
            and set(value.get("payload", {})) == {"name"}
        ]
        self.assertTrue(minimal)
        minimal_response = minimal[0]["response"]
        self.assertEqual("WAITING_INBOUND", minimal_response["lifecycle"])
        self.assertEqual("NORMAL", minimal_response["exception"])
        self.assertEqual("DRAFT", minimal_response["cardStage"])
        self.assertTrue(any(value.get("operationId") == "copyAsset" for value in examples))
        cancelled = [value for value in examples if value.get("operationId") == "cancelAssetCard"]
        self.assertTrue(cancelled)
        self.assertTrue(any(value.get("response", {}).get("cardStage") == "CANCELLED" for value in cancelled))
        rejection_text = json.dumps(
            [value for value in examples if value.get("kind") == "rejection"], sort_keys=True
        ).lower()
        for term in ("serial", "disabled", "expired", "non-creator", "lock", "lifecycle", "responsibility"):
            self.assertIn(term, rejection_text)
        self.assertTrue(any(value.get("status") == 409 and "serial" in json.dumps(value).lower() for value in examples))
        self.assertTrue(any(value.get("status") == 409 and "code" in json.dumps(value).lower() for value in examples))

    def test_asset_tag_read_reprint_and_batch_print_contract_is_explicit(self) -> None:
        document = self.load_contract()
        paths = document["paths"]
        schemas = document["components"]["schemas"]
        operations = {
            operation["operationId"]: operation
            for item in paths.values()
            for operation in item.values()
            if isinstance(operation, dict) and "operationId" in operation
        }
        required_operations = {
            "getAssetTag",
            "reprintAssetTag",
            "batchPrintAssetTags",
        }
        missing = sorted(required_operations - set(operations))
        self.assertFalse(missing, f"asset tag printing remains RED: {missing}")
        if missing:
            return

        self.assertIn("Assets", {tag["name"] for tag in document["tags"]})
        self.assertIn("/assets/{assetId}/tag", paths)
        self.assertIn("/assets/{assetId}/tag/reprint", paths)
        self.assertIn("/asset-tag-print-jobs", paths)
        expected_read_roles = {"ASSET_ADMIN", "WAREHOUSE_OPERATOR", "AUDIT_READ_ONLY"}
        self.assertEqual(expected_read_roles, set(operations["getAssetTag"]["x-actor"]["roles"]))
        self.assertNotIn("SYSTEM_ADMIN", operations["getAssetTag"]["x-actor"]["roles"])
        for operation_id in ("reprintAssetTag", "batchPrintAssetTags"):
            with self.subTest(operation=operation_id):
                operation = operations[operation_id]
                self.assertEqual(["WAREHOUSE_OPERATOR"], operation["x-actor"]["roles"])
                self.assertTrue(operation.get("x-business-write"))
                self.assertTrue(operation.get("x-version"))
                self.assertTrue(operation.get("x-transaction"))
                self.assertTrue(operation.get("x-audit-event"))
                self.assertTrue(
                    {400, 401, 403, 404, 409}.issubset(
                        set(operation["x-required-error-statuses"])
                    )
                )
                refs = {
                    parameter.get("$ref", "").rsplit("/", 1)[-1]
                    for parameter in operation.get("parameters", [])
                }
                self.assertTrue({"IdempotencyKey", "CsrfToken"}.issubset(refs))

        tag = schemas["AssetTag"]
        self.assertTrue(
            {"enterpriseName", "assetName", "assetCode", "qrCode", "qrToken"}.issubset(
                tag["required"]
            )
        )
        self.assertNotIn("assetId", schemas["AssetQrToken"].get("properties", {}))
        token = schemas["AssetQrToken"]
        self.assertEqual("string", token["type"])
        self.assertEqual("^[A-Za-z0-9_-]{43}$", token["pattern"])
        self.assertEqual(43, token["minLength"])
        self.assertEqual(43, token["maxLength"])
        token_text = json.dumps(token, sort_keys=True).lower()
        for term in ("opaque", "256-bit", "base64url", "asset id", "asset code", "pii"):
            self.assertIn(term, token_text)

        batch = operations["batchPrintAssetTags"]
        batch_text = json.dumps(batch, sort_keys=True).lower()
        for term in ("all-or-nothing", "no partial", "selected", "per-item", "active", "draft"):
            self.assertIn(term, batch_text)
        batch_request = schemas["AssetTagBatchPrintRequest"]
        self.assertEqual({"assets"}, set(batch_request["required"]))
        self.assertEqual(1, batch_request["properties"]["assets"]["minItems"])
        self.assertFalse(batch_request.get("additionalProperties", True))

        examples = [
            json.loads(path.read_text(encoding="utf-8"))
            for path in sorted(EXAMPLES.glob("masterdata-asset-tag-*.json"))
        ]
        self.assertGreaterEqual(len(examples), 7)
        self.assertTrue(all("evidence" in value for value in examples))
        example_operations = {value["operationId"] for value in examples}
        self.assertTrue(required_operations.issubset(example_operations))
        reprint = [
            value
            for value in examples
            if value.get("operationId") == "reprintAssetTag" and value.get("kind") == "positive"
        ]
        self.assertTrue(reprint)
        self.assertTrue(reprint[0].get("idempotencyReplayReturnsSameResult"))
        self.assertEqual(reprint[0]["before"]["qrToken"], reprint[0]["after"]["qrToken"])
        self.assertEqual(reprint[0]["before"]["assetCode"], reprint[0]["after"]["assetCode"])
        for stage in ("DRAFT", "PENDING_INBOUND", "CANCELLED"):
            with self.subTest(stage=stage):
                self.assertTrue(
                    any(
                        value.get("kind") == "rejection"
                        and value.get("asset", {}).get("cardStage") == stage
                        and value.get("operationId") in {"reprintAssetTag", "batchPrintAssetTags"}
                        for value in examples
                    )
                )

    def test_asset_tag_rotation_and_access_token_policy_is_explicit(self) -> None:
        document = self.load_contract()
        paths = document["paths"]
        schemas = document["components"]["schemas"]
        operations = {
            operation["operationId"]: operation
            for item in paths.values()
            for operation in item.values()
            if isinstance(operation, dict) and "operationId" in operation
        }
        required_operations = {"replaceAssetTag", "resolveAssetAccessToken"}
        missing = sorted(required_operations - set(operations))
        self.assertFalse(missing, f"asset tag rotation/access remains RED: {missing}")
        if missing:
            return

        self.assertIn("/assets/{assetId}/tag/replace", paths)
        self.assertIn("/asset-access/{token}", paths)
        replace = operations["replaceAssetTag"]
        self.assertEqual(["WAREHOUSE_OPERATOR"], replace["x-actor"]["roles"])
        self.assertTrue(replace.get("x-business-write"))
        self.assertTrue(replace.get("x-version"))
        self.assertTrue(replace.get("x-transaction"))
        self.assertTrue(replace.get("x-audit-event"))
        self.assertTrue(
            {400, 401, 403, 404, 409}.issubset(set(replace["x-required-error-statuses"]))
        )
        replace_refs = {
            parameter.get("$ref", "").rsplit("/", 1)[-1]
            for parameter in replace.get("parameters", [])
        }
        self.assertTrue({"IdempotencyKey", "CsrfToken", "IfMatchVersion"}.issubset(replace_refs))
        replace_request = schemas["AssetTagReplaceRequest"]
        self.assertEqual({"reason"}, set(replace_request["required"]))
        replace_text = json.dumps(replace, sort_keys=True).lower()
        for term in (
            "atomic",
            "rotate",
            "old token",
            "immediately invalid",
            "audit",
            "idempotent",
            "same",
            "new token",
        ):
            self.assertIn(term, replace_text)

        access = operations["resolveAssetAccessToken"]
        access_roles = set(access["x-actor"]["roles"])
        self.assertTrue(
            {
                "ASSET_ADMIN",
                "WAREHOUSE_OPERATOR",
                "AUDIT_READ_ONLY",
                "EMPLOYEE",
                "DEPARTMENT_ASSET_MANAGER",
            }.issubset(access_roles)
        )
        self.assertNotIn("SYSTEM_ADMIN", access_roles)
        self.assertNotEqual([], access.get("security", [{"sessionCookie": []}]))
        self.assertIn("401", access["responses"])
        self.assertIn("403", access["responses"])
        self.assertIn("404", access["responses"])
        self.assertNotIn("410", access["responses"])
        access_text = json.dumps(access, sort_keys=True).lower()
        for term in (
            "current authentication",
            "permission",
            "invalid",
            "old",
            "guess",
            "uniform",
            "does not grant",
            "scrapped",
            "sensitive",
        ):
            self.assertIn(term, access_text)
        token_parameter = paths["/asset-access/{token}"]["parameters"][0]
        self.assertEqual("QrToken", token_parameter["$ref"].rsplit("/", 1)[-1])
        result_text = json.dumps(schemas["AssetQrAccessResult"], sort_keys=True).lower()
        self.assertNotIn("servicephone", result_text)
        self.assertNotIn("attachments", result_text)
        self.assertIn("necessary", result_text)

        examples = [
            json.loads(path.read_text(encoding="utf-8"))
            for path in sorted(EXAMPLES.glob("masterdata-asset-tag-*.json"))
        ]
        replacement = [
            value
            for value in examples
            if value.get("operationId") == "replaceAssetTag" and value.get("kind") == "positive"
        ]
        self.assertTrue(replacement)
        replacement_response = replacement[0]["response"]
        self.assertIn("tag", replacement_response)
        if "tag" not in replacement_response:
            return
        self.assertNotEqual(
            replacement[0]["previousQrToken"], replacement_response["tag"]["qrToken"]
        )
        self.assertIsInstance(replacement_response["assetVersion"], int)
        old_token = next(
            value
            for value in examples
            if value.get("operationId") == "resolveAssetAccessToken"
            and value.get("scenario") == "old token after replacement"
        )
        guessed_token = next(
            value
            for value in examples
            if value.get("operationId") == "resolveAssetAccessToken"
            and value.get("scenario") == "guessed token"
        )
        self.assertEqual(404, old_token["status"])
        self.assertEqual(404, guessed_token["status"])
        for key in ("code", "message", "fieldErrors"):
            self.assertEqual(old_token["response"][key], guessed_token["response"][key])

    def test_asset_print_jobs_are_recoverable_and_replacement_returns_versioned_envelope(self) -> None:
        document = self.load_contract()
        paths = document["paths"]
        schemas = document["components"]["schemas"]
        operations = {
            operation["operationId"]: operation
            for item in paths.values()
            for operation in item.values()
            if isinstance(operation, dict) and "operationId" in operation
        }
        self.assertIn("/asset-tag-print-jobs/{printJobId}", paths)
        self.assertIn("PrintJobId", document["components"]["parameters"])
        self.assertIn("getAssetTagPrintJob", operations)
        if "getAssetTagPrintJob" not in operations:
            return

        get_job = operations["getAssetTagPrintJob"]
        self.assertEqual(["WAREHOUSE_OPERATOR"], get_job["x-actor"]["roles"])
        self.assertNotIn("SYSTEM_ADMIN", get_job["x-actor"]["roles"])
        self.assertIn("200", get_job["responses"])
        self.assertIn("401", get_job["responses"])
        self.assertIn("403", get_job["responses"])
        self.assertIn("404", get_job["responses"])
        self.assertNotIn("410", get_job["responses"])
        job_text = json.dumps(get_job, sort_keys=True).lower()
        for term in ("creator", "recovery relation", "queued", "completed", "failed", "no automatic"):
            self.assertIn(term, job_text)
        self.assertEqual(
            "PrintJobId",
            paths["/asset-tag-print-jobs/{printJobId}"]["parameters"][0]["$ref"].rsplit("/", 1)[-1],
        )
        job_result = schemas["AssetTagPrintJobResult"]
        result_refs = {
            branch["$ref"].rsplit("/", 1)[-1]
            for branch in job_result["oneOf"]
        }
        self.assertEqual({"AssetTagPrintJob", "AssetTagBatchPrintJob"}, result_refs)
        self.assertEqual(
            {"QUEUED", "COMPLETED", "FAILED"},
            set(schemas["AssetTagPrintJobStatus"]["enum"]),
        )

        for operation_id in ("reprintAssetTag", "batchPrintAssetTags"):
            response = operations[operation_id]["responses"]["202"]
            self.assertIn("Location", response["headers"])
            location_text = json.dumps(response["headers"]["Location"], sort_keys=True).lower()
            self.assertIn("asset-tag-print-jobs", location_text)
            self.assertIn("print job", response["description"].lower())

        replacement = operations["replaceAssetTag"]
        replacement_response = replacement["responses"]["200"]["content"]["application/json"]["schema"]
        self.assertEqual("AssetTagReplacementResult", replacement_response["$ref"].rsplit("/", 1)[-1])
        envelope = schemas["AssetTagReplacementResult"]
        self.assertTrue({"tag", "assetVersion"}.issubset(envelope["required"]))
        self.assertEqual(
            "AssetTag",
            envelope["properties"]["tag"]["$ref"].rsplit("/", 1)[-1],
        )
        self.assertEqual("integer", envelope["properties"]["assetVersion"]["type"])
        replacement_text = json.dumps(replacement, sort_keys=True).lower()
        self.assertIn("same version", replacement_text)


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
