import unittest

from audit_parallel_migrations import Migration, audit, parse_tree, format_versions


def item(version: int, lane: str = "same", path: str | None = None) -> Migration:
    return Migration(
        path=path or f"database/migrations/V{version:04d}__{lane}.sql",
        blob=(f"{version:040x}" if lane == "same" else f"{version+1000:040x}"),
        version=version,
    )


class ParallelAuditTests(unittest.TestCase):
    def setUp(self):
        self.main = {i: item(i) for i in range(1, 4)}

    def test_identical_ancestry_from_parallel_branches_does_not_collide(self):
        shared = {**self.main, 4: item(4)}
        result = audit(self.main, {"p7": (shared, [], []), "finance": (shared, [], [])})
        self.assertEqual([], result["conflicts"])
        self.assertTrue(result["release_ready"])

    def test_conflicting_new_version_from_separate_lanes_is_blocked(self):
        p7 = {**self.main, 4: item(4, "service")}
        p8 = {**self.main, 4: item(4, "finance")}
        result = audit(self.main, {"p7": (p7, [], []), "finance": (p8, [], [])})
        self.assertFalse(result["release_ready"])
        self.assertEqual(["V0004"], [r["version"] for r in result["conflicts"]])
        self.assertIn("CONFLICT", " ".join(result["blockers"]))

    def test_missing_intervening_versions_on_payroll_branch_is_blocked(self):
        payroll = {**self.main, 9: item(9, "payroll")}
        result = audit(self.main, {"payroll": (payroll, [], [])})
        self.assertFalse(result["release_ready"])
        self.assertIn("missing lower versions", result["blockers"][0])

    def test_divergent_baseline_blob_is_blocked(self):
        branch = {**self.main, 1: item(1, "rewritten")}
        result = audit(self.main, {"p7": (branch, [], [])})
        self.assertFalse(result["release_ready"])
        self.assertIn("V0001", " ".join(result["blockers"]))

    def test_pending_ddl_is_clearly_not_release_ready(self):
        result = audit(self.main, {"sales": (dict(self.main), ["database/phase10/pending_commercial_schema.sql"], [])})
        self.assertFalse(result["release_ready"])
        self.assertEqual(1, len(result["unversioned_warnings"]))

    def test_git_tree_parsing_preserves_blobs_and_detects_duplicate_filename(self):
        p = (
            "100644 blob " + ("a" * 40) + "\tdatabase/migrations/V0004__finance.sql\n"
            "100644 blob " + ("b" * 40) + "\tdatabase/migrations/V0004__payroll.sql\n"
            "100644 blob " + ("c" * 40) + "\tdatabase/phase10/pending_commercial_schema.sql"
        )
        migrations, pending, errors = parse_tree(p)
        self.assertEqual(1, len(migrations))
        self.assertEqual(1, len(pending))
        self.assertTrue(any("duplicate version" in x for x in errors))

    def test_bad_migration_filename_is_not_silently_ignored(self):
        _, _, errors = parse_tree(
            "100644 blob " + ("d" * 40) + "\tdatabase/migrations/V61__service.sql"
        )
        self.assertTrue(any("malformed migration" in x for x in errors))

    def test_short_and_long_gap_formatting(self):
        self.assertEqual("V0004,V0005", format_versions([4, 5]))
        self.assertIn("missing", format_versions(list(range(4, 40))))


if __name__ == "__main__":
    unittest.main()
