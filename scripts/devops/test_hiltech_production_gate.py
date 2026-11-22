import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
from hiltech_production_gate import public_https_host, validate_config, live_probes


def production_config():
    return {
        "HILTECH_PROD_API_URL": "https://api.hiltech.example.com",
        "HILTECH_PROD_OIDC_ISSUER": "https://auth.hiltech.example.com/realms/hiltech",
        "HILTECH_PROD_EVIDENCE_URL": "https://objects.hiltech.example.com",
        "HILTECH_PROD_API_IMAGE": "ghcr.io/owner/hiltech-server@sha256:" + "a" * 64,
        "HILTECH_PROD_FGA_STORE_ID": "01ABCDEFGHIJKLMNOPQRSTUVWX",
        "HILTECH_PROD_FGA_MODEL_ID": "01ABCDEFGHIJKLMNOPQRSTUVWX",
        "HILTECH_PROD_ANDROID_RELEASE_REF": "https://github.com/owner/repo/releases/tag/android-1",
        "HILTECH_PROD_WINDOWS_RELEASE_REF": "https://github.com/owner/repo/releases/tag/windows-1",
        "HILTECH_PROD_BACKUP_RESTORE_REF": "https://github.com/owner/repo/issues/123",
        "HILTECH_PROD_APPROVAL_REF": "https://github.com/owner/repo/issues/456",
        "HILTECH_PROD_FGA_AUTHN_METHOD": "preshared",
        "HILTECH_PROD_OPENFGA_PUBLIC": "false",
        "HILTECH_PROD_DATABASE_PUBLIC": "false",
    }


class ProductionGateTest(unittest.TestCase):
    def test_missing_parameters_block_release(self):
        errors = validate_config({})
        self.assertGreaterEqual(len(errors), 10)
        self.assertTrue(any("HILTECH_PROD_API_URL" in x for x in errors))

    def test_private_and_temporary_hosts_are_rejected(self):
        for host in (
            "http://api.example.com",
            "https://100.78.2.83",
            "https://localhost",
            "https://foo.158.101.254.30.sslip.io",
            "https://auth.internal",
        ):
            with self.subTest(host=host):
                with self.assertRaises(ValueError):
                    public_https_host(host)

    def test_immutable_image_and_private_services_required(self):
        config = production_config()
        self.assertEqual(validate_config(config), [])
        config["HILTECH_PROD_API_IMAGE"] = "ghcr.io/owner/hiltech-server:latest"
        config["HILTECH_PROD_OPENFGA_PUBLIC"] = "true"
        config["HILTECH_PROD_FGA_AUTHN_METHOD"] = "none"
        problems = validate_config(config)
        self.assertTrue(any("immutable" in x for x in problems))
        self.assertTrue(any("OpenFGA" in x for x in problems))
        self.assertTrue(any("preshared" in x for x in problems))

    @patch("hiltech_production_gate.tls_probe")
    @patch("hiltech_production_gate.public_dns")
    @patch("hiltech_production_gate.json_get")
    def test_live_checks_oidc_issuer_and_api_readiness(self, get_json, _dns, _tls):
        config = production_config()
        issuer = config["HILTECH_PROD_OIDC_ISSUER"]
        get_json.side_effect = [
            {"issuer": issuer, "jwks_uri": issuer + "/protocol/openid-connect/certs",
             "authorization_endpoint": issuer + "/protocol/openid-connect/auth"},
            {"status": "UP"},
        ]
        self.assertEqual(live_probes(config), [])
        get_json.side_effect = [
            {"issuer": "http://wrong-issuer", "jwks_uri": "http://insecure",
             "authorization_endpoint": "http://insecure"},
            {"status": "DOWN"},
        ]
        self.assertGreaterEqual(len(live_probes(config)), 4)


if __name__ == "__main__":
    unittest.main()
