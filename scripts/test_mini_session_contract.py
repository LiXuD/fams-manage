"""Validate this slice's transport and payload boundaries independently of historical audits."""
import copy
import runpy
import unittest
from pathlib import Path

VERIFIER = runpy.run_path(str(Path(__file__).with_name("verify-d6-02c.py")))


class MiniSessionContractTest(unittest.TestCase):
    def test_mini_headers_are_explicit_and_pc_cookie_contract_is_retained(self):
        document = VERIFIER["load_contract"]()
        VERIFIER["assert_all_local_refs"](document)
        VERIFIER["assert_unique_operation_ids"](document)
        self.assertEqual("cookie", document["components"]["securitySchemes"]["sessionCookie"]["in"])
        self.assertEqual("X-FAMS-Mini-Session", document["components"]["securitySchemes"]["miniSession"]["name"])
        for path in ("/mini/auth/session", "/mini/auth/logout"):
            operation = next(iter(document["paths"][path].values()))
            self.assertEqual([{"miniSession": []}], operation["security"])
        mini_document = copy.deepcopy(document)
        mini_document["paths"] = {key: value for key, value in document["paths"].items() if key.startswith("/mini/")}
        VERIFIER["assert_write_contracts"](mini_document)

    def test_numeric_ids_unknown_fields_and_oversized_codes_are_rejected(self):
        document = VERIFIER["load_contract"]()
        validate = VERIFIER["validate_instance"]
        grant = {"bindingStatus": "BOUND", "sessionToken": "a" * 43, "accountId": "9007199254740993",
                 "bindingId": "1", "idleExpiresAt": "2026-10-05T02:30:00Z", "absoluteExpiresAt": "2026-10-05T10:00:00Z"}
        schema = {"$ref": "#/components/schemas/MiniSessionGrant"}
        validate(grant, schema, document, "grant")
        for bad in ({**grant, "accountId": 9007199254740993}, {**grant, "sessionKey": "secret"}):
            with self.assertRaises(VERIFIER["VerificationFailure"]):
                validate(bad, schema, document, "bad grant")
        for bad in ({"code": "x" * 257}, {"code": "valid", "openId": "client-claim"}):
            with self.assertRaises(VERIFIER["VerificationFailure"]):
                validate(bad, {"$ref": "#/components/schemas/MiniLoginRequest"}, document, "bad login")


if __name__ == "__main__":
    unittest.main()
