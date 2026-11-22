"""No-mutation, no-secret-leak and conservative classification checks."""
import importlib.util
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest

SOURCE = pathlib.Path(__file__).resolve().parents[1] / "coolify_pilot_inventory.py"
spec = importlib.util.spec_from_file_location("coolify_pilot_inventory", SOURCE)
inventory = importlib.util.module_from_spec(spec)
spec.loader.exec_module(inventory)

SECRET = "SECRET_VALUE_SHOULD_NOT_APPEAR"


def container(name, *, labels=None, running=True, health=None, mounts=None, memory=0):
    return {
        "Name": "/" + name,
        "State": {"Running": running, "Health": {"Status": health}} if health else {"Running": running},
        "Config": {
            "Image": "company/app:test",
            "Labels": {**(labels or {}), "private.secret.label": SECRET},
            "Env": ["PASSWORD=" + SECRET],
        },
        "Mounts": mounts or [{"Type": "volume", "Source": "/sensitive/" + SECRET}],
        "HostConfig": {"Memory": memory, "NanoCpus": 0, "PidsLimit": 0},
        "RestartCount": 1,
    }


class PilotInventoryTests(unittest.TestCase):
    def test_preview_name_is_only_candidate_and_mount_paths_secret_never_exposed(self):
        source = [
            container("service-pr-253", labels={"com.docker.compose.project": "demo-pr-253"}),
            container("core-db", labels={"com.docker.compose.project": "core"}, health="unhealthy"),
            container("unmapped", labels={}, memory=512 * 1024 * 1024),
        ]
        report = inventory.analyze(source)
        self.assertEqual(report["summary"]["total"], 3)
        self.assertEqual(report["summary"]["preview_review"], 1)
        self.assertEqual(report["summary"]["unhealthy"], 1)
        self.assertEqual(report["summary"]["owner_unknown"], 1)
        self.assertEqual(report["summary"]["without_memory_limit"], 2)
        statuses = {r["name"]: r["classification"] for r in report["containers"]}
        self.assertEqual(statuses["service-pr-253"], "PREVIEW_REVIEW_ONLY")
        self.assertEqual(statuses["core-db"], "KEEP_UNTIL_OWNER_APPROVES")
        self.assertEqual(statuses["unmapped"], "OWNER_UNKNOWN_REVIEW")
        self.assertNotIn(SECRET, json.dumps(report))
        self.assertNotIn("/sensitive/", json.dumps(report))

    def test_cli_aggregate_output_omits_names_and_secrets(self):
        fixture = [container("test-pr-241")]
        with tempfile.TemporaryDirectory() as folder:
            path = pathlib.Path(folder) / "docker-inspect.json"
            path.write_text(json.dumps(fixture), encoding="utf-8")
            result = subprocess.run(
                [sys.executable, str(SOURCE), "--fixture", str(path)],
                check=False, capture_output=True, text=True, timeout=8
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertNotIn("test-pr-241", result.stdout)
            self.assertNotIn(SECRET, result.stdout)
            self.assertNotIn("containers", json.dumps(json.loads(result.stdout)))
            self.assertEqual(json.loads(result.stdout)["summary"]["preview_review"], 1)

    def test_bad_fixture_and_unknown_arguments_fail_closed(self):
        with tempfile.TemporaryDirectory() as folder:
            bad = pathlib.Path(folder) / "bad.json"
            bad.write_text('{"not":"array"}', encoding="utf-8")
            p = subprocess.run(
                [sys.executable, str(SOURCE), "--fixture", str(bad)],
                capture_output=True, text=True, timeout=8
            )
            self.assertEqual(p.returncode, 2)
            self.assertIn("READ_ONLY_INVENTORY_FAILED", p.stderr)
            p = subprocess.run(
                [sys.executable, str(SOURCE), "--stop", "something"],
                capture_output=True, text=True, timeout=8
            )
            self.assertNotEqual(p.returncode, 0)

    def test_zero_containers(self):
        report = inventory.analyze([])
        self.assertEqual(report["containers"], [])
        self.assertEqual(report["summary"], {})


if __name__ == "__main__":
    unittest.main()
