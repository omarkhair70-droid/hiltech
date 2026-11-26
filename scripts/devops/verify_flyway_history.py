#!/usr/bin/env python3
"""Read-only HILTECH Flyway merge gate.

Rejects missing migration versions, duplicate versions, edits/removals of
migrations already on main, and lower-version backfills on a merge candidate.
Does not connect to PostgreSQL or modify runtime state.
"""
from __future__ import annotations

import argparse
import re
import subprocess
from pathlib import Path

PREFIX = "database/migrations/"
VERSION = re.compile(r"^V([0-9]{4})(?:_([1-9][0-9]*))?__[A-Za-z0-9_]+\.sql$")


class MigrationGateError(ValueError):
    pass


def _label(number: tuple[int, int]) -> str:
    return f"V{number[0]:04d}" + (f"_{number[1]}" if number[1] else "")


def _versions(files: dict[str, bytes], context: str) -> dict[tuple[int, int], str]:
    versions: dict[tuple[int, int], str] = {}
    for path in sorted(files):
        if not path.startswith(PREFIX):
            continue
        filename = path[len(PREFIX):]
        if "/" in filename:
            raise MigrationGateError(f"{context}: unexpected nested migration: {path}")
        if not filename.startswith("V") or not filename.endswith(".sql"):
            # Non-versioned metadata is not part of Flyway's schema history.
            continue
        match = VERSION.fullmatch(filename)
        if match is None:
            raise MigrationGateError(f"{context}: malformed Flyway filename: {path}")
        number = (int(match.group(1)), int(match.group(2) or "0"))
        if number in versions:
            raise MigrationGateError(
                f"{context}: duplicate {_label(number)}: {versions[number]}, {path}"
            )
        versions[number] = path
    if not versions:
        raise MigrationGateError(f"{context}: no committed V migrations found")
    return versions


def verify_history(
    baseline: dict[str, bytes], candidate: dict[str, bytes]
) -> tuple[int, int, int]:
    old = _versions(baseline, "baseline")
    now = _versions(candidate, "candidate")
    for number, path in sorted(old.items()):
        if candidate.get(path) != baseline[path]:
            raise MigrationGateError(f"already-merged {_label(number)} removed or changed: {path}")
    if any(number not in now or now[number] != path for number, path in old.items()):
        raise MigrationGateError("historical main migration filename altered")
    base_max = max(old)
    additions = {number: path for number, path in now.items() if number not in old}
    for number in additions:
        if number <= base_max:
            raise MigrationGateError(
                f"candidate backfills {_label(number)} below main's {_label(base_max)}"
            )
    observed = sorted(number[0] for number in now if number[1] == 0)
    expected = list(range(1, max(now)[0] + 1))
    if observed != expected:
        missing = sorted(set(expected) - set(observed))
        raise MigrationGateError(
            f"missing lower Flyway versions {missing}; never merge higher versions first"
        )
    for major in observed:
        minors = sorted(number[1] for number in now if number[0] == major and number[1] > 0)
        if minors and minors != list(range(1, minors[-1] + 1)):
            raise MigrationGateError(f"missing lower Flyway minor versions for V{major:04d}")
    return len(old), len(additions), max(now)[0]


def _git(repo: Path, *args: str) -> bytes:
    return subprocess.check_output(["git", "-C", str(repo), *args])


def load_baseline(repo: Path, ref: str) -> dict[str, bytes]:
    if not re.fullmatch(r"[A-Za-z0-9_./-]+", ref) or ref.startswith("-"):
        raise MigrationGateError("invalid git baseline ref")
    paths = _git(
        repo, "ls-tree", "-r", "--name-only", ref, "--", PREFIX
    ).decode("utf-8").splitlines()
    return {
        path: _git(repo, "show", f"{ref}:{path}")
        for path in paths
        if path.startswith(PREFIX) and path.endswith(".sql")
    }


def load_worktree(repo: Path) -> dict[str, bytes]:
    folder = repo / PREFIX
    return {
        item.relative_to(repo).as_posix(): item.read_bytes()
        for item in folder.rglob("*.sql")
        if item.is_file()
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", type=Path, default=Path("."))
    parser.add_argument("--base-ref", default="origin/main")
    args = parser.parse_args()
    try:
        repo = args.repo.resolve(strict=True)
        base = load_baseline(repo, args.base_ref)
        current = load_worktree(repo)
        historical, added, version = verify_history(base, current)
    except (MigrationGateError, OSError, subprocess.CalledProcessError) as exc:
        print(f"BLOCKED: HILTECH Flyway migration history: {exc}")
        return 1
    print(
        f"PASS: unchanged main migrations={historical}; new additive={added}; "
        f"highest={_label(max(_versions(current, 'candidate')))}; "
        f"contiguous majors 1..V{version:04d} and ordered minor repairs; no duplicates"
    )
    print("Read-only schema check; no database migration or rollback was executed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
