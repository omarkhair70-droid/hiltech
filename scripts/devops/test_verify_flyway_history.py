import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from verify_flyway_history import MigrationGateError, verify_history

PREFIX = "database/migrations/"


def migration(n: int, suffix: str = "safe", sql: bytes | None = None) -> tuple[str, bytes]:
    return (f"{PREFIX}V{n:04d}__{suffix}.sql",
            sql if sql is not None else f"SELECT {n};".encode())


class FlywayHistoryGateTests(unittest.TestCase):
    def setUp(self):
        self.base = dict([migration(1, "baseline"), migration(2, "warehouse")])

    def test_appended_contiguous_migration_is_allowed(self):
        candidate = {**self.base, **dict([migration(3, "finance")])}
        self.assertEqual(verify_history(self.base, candidate), (2, 1, 3))

    def test_same_main_is_allowed(self):
        self.assertEqual(verify_history(self.base, self.base), (2, 0, 2))

    def test_gap_blocks_higher_parallel_lane(self):
        candidate = {**self.base, **dict([migration(7, "payroll")])}
        with self.assertRaisesRegex(MigrationGateError, "missing lower Flyway"):
            verify_history(self.base, candidate)

    def test_main_migration_mutation_is_blocked(self):
        candidate = dict(self.base)
        key = migration(1, "baseline")[0]
        candidate[key] = b"DROP TABLE evidence;"
        with self.assertRaisesRegex(MigrationGateError, "removed or changed"):
            verify_history(self.base, candidate)

    def test_main_migration_removal_is_blocked(self):
        candidate = dict(self.base)
        del candidate[migration(2, "warehouse")[0]]
        with self.assertRaisesRegex(MigrationGateError, "removed or changed"):
            verify_history(self.base, candidate)

    def test_duplicate_version_is_blocked(self):
        candidate = {**self.base, **dict([migration(3, "finance"), migration(3, "payroll")])}
        with self.assertRaisesRegex(MigrationGateError, "duplicate V0003"):
            verify_history(self.base, candidate)

    def test_malformed_script_name_blocks(self):
        candidate = {**self.base, f"{PREFIX}V3__finance.sql": b"SELECT 3;"}
        with self.assertRaisesRegex(MigrationGateError, "malformed"):
            verify_history(self.base, candidate)

    def test_additive_minor_repair_orders_before_next_major(self):
        repair = f"{PREFIX}V0002_1__guard_repair.sql"
        candidate = {**self.base, repair: b"SELECT 1;", **dict([migration(3)])}
        self.assertEqual(verify_history(self.base, candidate), (2, 2, 3))

    def test_minor_repair_cannot_rewrite_applied_migration(self):
        repair = f"{PREFIX}V0002_1__guard_repair.sql"
        baseline = {**self.base, repair: b"SELECT 1;"}
        with self.assertRaisesRegex(MigrationGateError, "removed or changed"):
            verify_history(baseline, {**baseline, repair: b"SELECT 2;"})

    def test_lower_minor_cannot_be_backfilled_after_next_major(self):
        baseline = {**self.base, **dict([migration(3)])}
        with self.assertRaisesRegex(MigrationGateError, "backfills"):
            verify_history(baseline, {**baseline, f"{PREFIX}V0002_1__late.sql": b"SELECT 1;"})

    def test_minor_sequence_and_major_predecessor_are_required(self):
        with self.assertRaisesRegex(MigrationGateError, "missing lower Flyway minor"):
            verify_history(self.base, {**self.base, f"{PREFIX}V0002_2__skipped.sql": b"SELECT 1;"})
        with self.assertRaisesRegex(MigrationGateError, "missing lower Flyway versions"):
            verify_history(self.base, {**self.base, f"{PREFIX}V0003_1__without_major.sql": b"SELECT 1;"})

    def test_noncanonical_minor_and_duplicate_versions_are_rejected(self):
        for suffix in ("_0", "_01", "_1_1", ".1"):
            with self.assertRaisesRegex(MigrationGateError, "malformed"):
                verify_history(self.base, {**self.base, f"{PREFIX}V0002{suffix}__bad.sql": b"SELECT 1;"})
        with self.assertRaisesRegex(MigrationGateError, "duplicate V0002_1"):
            verify_history(self.base, {**self.base, f"{PREFIX}V0002_1__first.sql": b"SELECT 1;", f"{PREFIX}V0002_1__second.sql": b"SELECT 1;"})


if __name__ == "__main__":
    unittest.main()
