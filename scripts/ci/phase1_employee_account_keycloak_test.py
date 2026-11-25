"""Safety checks for the isolated fixture; no Keycloak or HTTP calls are made."""
import os
import pathlib
import tempfile
import unittest
import uuid
from unittest.mock import MagicMock, patch

import phase1_employee_account_keycloak as contract


class AccountProviderFixtureSafetyTest(unittest.TestCase):
    def test_requires_explicit_opt_in(self):
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaises(contract.ContractError):
                contract.work_directory()

    def test_accepts_only_exact_https_loopback_base(self):
        self.assertEqual("https://127.0.0.1:8443", contract.loopback_base("https://127.0.0.1:8443/"))
        for value in ("http://127.0.0.1:8443", "https://identity.example.test:8443", "https://localhost:8443",
                      "https://127.0.0.1", "https://127.0.0.1:8443/path", "https://127.0.0.1:8443?secret=x",
                      "https://user:password@127.0.0.1:8443", "https://127.0.0.1:8443#fragment"):
            with self.subTest(base=value):
                with self.assertRaises(contract.ContractError):
                    contract.loopback_base(value)

    def test_cleanup_deletes_only_realm_whose_owner_was_verified(self):
        with self.fixture() as directory:
            realm = MagicMock()
            realm.check_owner.side_effect = [True, False]
            with patch.object(contract, "OwnedRealm", return_value=realm):
                contract.cleanup(directory)
            realm.admin.assert_called_once_with("DELETE")
            self.assertFalse(directory.exists())

    def test_cleanup_never_deletes_realm_with_another_owner(self):
        with self.fixture() as directory:
            realm = MagicMock()
            realm.check_owner.side_effect = contract.ContractError("REALM_OWNER_MISMATCH")
            with patch.object(contract, "OwnedRealm", return_value=realm):
                with self.assertRaises(contract.ContractError):
                    contract.cleanup(directory)
            realm.admin.assert_not_called()

    def test_cleanup_of_missing_owned_realm_never_issues_delete(self):
        with self.fixture() as directory:
            realm = MagicMock()
            realm.check_owner.return_value = False
            with patch.object(contract, "OwnedRealm", return_value=realm):
                contract.cleanup(directory)
            realm.admin.assert_not_called()

    def test_cleanup_rejects_a_different_local_provider(self):
        with self.fixture() as directory, patch.dict(os.environ, {"KEYCLOAK_URL": "https://127.0.0.1:9443"}):
            with patch.object(contract, "OwnedRealm") as realm:
                with self.assertRaises(contract.ContractError):
                    contract.cleanup(directory)
            realm.assert_not_called()
            self.assertTrue(directory.exists())

    def test_fixture_refuses_shared_readable_manifest(self):
        with self.fixture() as directory:
            (directory / "fixture.json").chmod(0o644)
            with self.assertRaises(contract.ContractError):
                contract.fixture_in(directory)

    def test_cleanup_refuses_existing_business_realm(self):
        with self.fixture() as directory:
            manifest = contract.read_private(directory / "fixture.json")
            manifest["realm"] = "hiltech"
            contract.private_json(directory / "fixture.json", manifest)
            with patch.object(contract, "OwnedRealm") as realm:
                with self.assertRaises(contract.ContractError):
                    contract.cleanup(directory)
            realm.assert_not_called()

    def fixture(self):
        class Fixture:
            def __enter__(self):
                self.temp = tempfile.TemporaryDirectory()
                self.directory = pathlib.Path(self.temp.name) / "hiltech-account-provider-contract"
                self.directory.mkdir(mode=0o700)
                owner = uuid.uuid4().hex
                base = "https://127.0.0.1:8443"
                contract.private_json(self.directory / "fixture.json", {
                    "owner": owner, "realm": contract.REALM_PREFIX + owner, "base": base,
                    "issuer": base + "/realms/" + contract.REALM_PREFIX + owner,
                })
                self.environment = patch.dict(os.environ, {"KEYCLOAK_URL": base})
                self.environment.start()
                return self.directory

            def __exit__(self, *_args):
                self.environment.stop()
                self.temp.cleanup()

        return Fixture()


if __name__ == "__main__":
    unittest.main()
