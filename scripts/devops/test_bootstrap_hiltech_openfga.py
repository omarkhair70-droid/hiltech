import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import bootstrap_hiltech_openfga as mod

STORE = "01" + "A" * 24
MODEL = "01" + "B" * 24
TYPES = ("user", "organization", "team", "crew", "project", "site", "work_order",
         "warehouse", "storage_location", "asset", "stock_item", "evidence",
         "configuration_revision", "approval_request")
APPROVED = {"schema_version": "1.1", "type_definitions": [{"type": key} for key in TYPES]}


class FakeOpenFga:
    def __init__(self):
        self.stores = []
        self.models = []
        self.writes = []

    def request(self, method, path, body=None):
        self.writes.append((method, path, body))
        if method == "GET" and path.startswith("/stores?"):
            return {"stores": list(self.stores)}
        if method == "POST" and path == "/stores":
            store = {"id": STORE, "name": body["name"]}
            self.stores.append(store)
            return store
        if method == "GET" and path.startswith("/stores/" + STORE + "/authorization-models?"):
            return {"authorization_models": list(self.models)}
        if method == "POST" and path == "/stores/" + STORE + "/authorization-models":
            self.models.append({"id": MODEL, **body})
            return {"authorization_model_id": MODEL}
        if method == "POST" and path == "/stores/" + STORE + "/check":
            return {"allowed": bool(body.get("contextual_tuples"))}
        raise AssertionError(f"Unexpected test request: {method} {path}")


class BootstrapTest(unittest.TestCase):
    def test_private_auth_credentials_required(self):
        with self.assertRaises(ValueError):
            mod.PrivateOpenFga("")
        with self.assertRaises(ValueError):
            mod.PrivateOpenFga("short")

    def test_reject_unexpected_file_and_model_shape(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "model.json"
            path.write_text(json.dumps(APPROVED), encoding="utf-8")
            self.assertEqual(len(mod.validated_model(path)["type_definitions"]), 14)
            path.write_text('{"schema_version":"1.1","type_definitions":[]}', encoding="utf-8")
            with self.assertRaises(ValueError):
                mod.validated_model(path)

    def test_dry_run_requires_no_credentials_or_network(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "model.json"
            path.write_text(json.dumps(APPROVED), encoding="utf-8")
            with patch.object(sys, "argv", ["bootstrap_hiltech_openfga.py", "--model", str(path)]):
                with patch.dict("os.environ", {"HILTECH_FGA_BOOTSTRAP_KEY": ""}):
                    self.assertEqual(mod.main(), 0)

    def test_store_and_model_are_idempotent(self):
        api = FakeOpenFga()
        self.assertEqual(mod.ensure_store(api), STORE)
        self.assertEqual(mod.ensure_model(api, STORE, APPROVED), MODEL)
        self.assertTrue(mod.contextual_check(api, STORE, MODEL, True))
        self.assertFalse(mod.contextual_check(api, STORE, MODEL, False))
        before = len([x for x in api.writes if x[0] == "POST" and
                      (x[1] == "/stores" or x[1].endswith("/authorization-models"))])
        self.assertEqual(mod.ensure_store(api), STORE)
        self.assertEqual(mod.ensure_model(api, STORE, APPROVED), MODEL)
        after = len([x for x in api.writes if x[0] == "POST" and
                     (x[1] == "/stores" or x[1].endswith("/authorization-models"))])
        self.assertEqual(before, after)

    def test_refuses_duplicate_stores(self):
        api = FakeOpenFga()
        api.stores = [
            {"id": STORE, "name": mod.STAGING_STORE},
            {"id": "01" + "C" * 24, "name": mod.STAGING_STORE},
        ]
        with self.assertRaises(RuntimeError):
            mod.ensure_store(api)

    def test_bad_identifier_refused(self):
        with self.assertRaises(ValueError):
            mod.checked_id("../../master", "store")
        with self.assertRaises(ValueError):
            mod.checked_id("01" + "I" * 24, "model")


if __name__ == "__main__":
    unittest.main()
