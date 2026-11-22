#!/usr/bin/env python3
"""HILTECH parallel-lane Flyway source audit: read-only, no database access.

Inspect exact Git refs for the live five domain lanes and procurement source.
Inherited migrations with the same filename + Git blob are shared ancestry,
not a conflict; divergent blob/path with the same version is a hard conflict.
The report deliberately cannot approve a merge or live database migration.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path

LANES = {
    "procurement_service_s07": "phase7/s07c-service-order-approval-20261123",
    "finance_8d": "phase8/finance-8d-result-safety-contract-20261123",
    "payroll_9": "phase9/payroll-snapshot-20261122",
    "sales_10": "phase10/sales-tenders-commercial-20261122",
    "service_11": "phase11/handover-service-20261122",
    "executive_13": "phase13/executive-control-source-bound-20261122",
}
SQL_RE = re.compile(r"^database/migrations/V([0-9]{4})__[^/]+\.sql$")
BLOB_LINE_RE = re.compile(r"^[0-9]{6} blob ([0-9a-f]{40})\t(.+)$")


@dataclass(frozen=True)
class Migration:
    path: str
    blob: str
    version: int


def parse_tree(text: str) -> tuple[dict[int, Migration], list[str], list[str]]:
    versions: dict[int, Migration] = {}
    pending: list[str] = []
    errors: list[str] = []
    for line in text.splitlines():
        match = BLOB_LINE_RE.fullmatch(line)
        if not match:
            if line.strip():
                errors.append("unexpected git tree record")
            continue
        blob, path = match.groups()
        if not path.startswith("database/") or not path.endswith(".sql"):
            continue
        if path.startswith("database/migrations/"):
            m = SQL_RE.fullmatch(path)
            if m is None:
                errors.append(f"malformed migration filename: {path}")
                continue
            version = int(m.group(1))
            if version in versions:
                errors.append(
                    f"duplicate version V{version:04d} within branch: "
                    f"{versions[version].path} vs {path}"
                )
            else:
                versions[version] = Migration(path, blob, version)
        elif "pending" in path.lower():
            pending.append(path)
    return versions, sorted(pending), errors


def audit(
    main: dict[int, Migration],
    lanes: dict[str, tuple[dict[int, Migration], list[str], list[str]]],
) -> dict:
    problems: list[str] = []
    warnings: list[str] = []
    max_main = max(main) if main else 0
    by_version: dict[int, dict[tuple[str, str], list[str]]] = defaultdict(lambda: defaultdict(list))
    for lane, (versions, pending, parse_errors) in sorted(lanes.items()):
        problems.extend(f"{lane}: {error}" for error in parse_errors)
        for v, main_file in sorted(main.items()):
            found = versions.get(v)
            if found != main_file:
                problems.append(f"{lane}: merged main V{v:04d} removed or mutated")
        new_versions = sorted(v for v in versions if v > max_main)
        for v in new_versions:
            m = versions[v]
            by_version[v][(m.path, m.blob)].append(lane)
        if new_versions:
            expected = set(range(max_main + 1, max(new_versions) + 1))
            missing = sorted(expected - set(new_versions))
            if missing:
                problems.append(f"{lane}: missing lower versions {format_versions(missing)}")
        if pending:
            warnings.append(f"{lane}: {len(pending)} unnumbered pending SQL file(s)")
    collisions = []
    for version, unique in sorted(by_version.items()):
        if len(unique) > 1:
            variants = [
                {"path": path, "blob": blob, "lanes": sorted(lanes_)}
                for (path, blob), lanes_ in sorted(unique.items())
            ]
            collisions.append({"version": f"V{version:04d}", "variants": variants})
            problems.append(
                f"V{version:04d} CONFLICT: " +
                " | ".join(x["path"] + " in " + ",".join(x["lanes"]) for x in variants)
            )
    return {
        "release_ready": not problems and not warnings,
        "baseline_highest": f"V{max_main:04d}",
        "conflicts": collisions,
        "blockers": problems,
        "unversioned_warnings": warnings,
        "lanes": {
            name: {
                "highest": f"V{max(versions):04d}" if versions else None,
                "new_migrations": [
                    f"V{n:04d}: {versions[n].path}" for n in sorted(versions) if n > max_main
                ],
                "pending_sql": pending,
            }
            for name, (versions, pending, _) in sorted(lanes.items())
        },
    }


def format_versions(values: list[int]) -> str:
    if len(values) > 7:
        return ",".join(f"V{v:04d}" for v in values[:3]) + f",...,V{values[-1]:04d} ({len(values)} missing)"
    return ",".join(f"V{v:04d}" for v in values)


def git(repo: Path, *args: str) -> str:
    return subprocess.check_output(
        ["git", "-C", str(repo), *args],
        text=True,
        stderr=subprocess.DEVNULL,
    ).strip()


def snapshot(repo: Path, ref: str) -> tuple[dict[int, Migration], list[str], list[str]]:
    # Git refs are local, obtained by the CI checkout/fetch step. Never shell out.
    return parse_tree(git(repo, "ls-tree", "-r", ref, "--", "database/"))


def render_markdown(result: dict, shas: dict[str, str]) -> str:
    lines = [
        "# HILTECH parallel migrations: source-state diagnostic",
        "",
        "**NO-GO**: this checks current branch source only, not a company database, external DNS or approvals."
        if not result["release_ready"]
        else "**Source migration numbering appears consistent; operational acceptance is still mandatory.**",
        "",
        f"Main migration baseline: {result['baseline_highest']}.",
        "",
        "## Exact branch refs",
    ]
    lines += [f"- \\u0060{name}\\u0060: \\u0060{sha}\\u0060".replace("\\u0060", "`")
              for name, sha in sorted(shas.items())]
    lines += ["", "## Hard blockers"]
    lines += [f"- {item}" for item in result["blockers"]] or ["- None detected by this source-only check"]
    lines += ["", "## Still-unversioned SQL"]
    lines += [f"- {item}" for item in result["unversioned_warnings"]] or ["- None"]
    lines += ["", "## Per-lane source inventory"]
    for name, lane in result["lanes"].items():
        lines.append(f"### {name}")
        lines += [f"- {m}" for m in lane["new_migrations"]] or ["- No Flyway additions"]
        lines += [f"- pending: {p}" for p in lane["pending_sql"]]
        lines.append("")
    lines += [
        "No branches, runtime feature flags, migrations or company databases were changed.",
        "The final Flyway version allocation belongs to the integrator after approved parent merges.",
    ]
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", type=Path, default=Path("."))
    parser.add_argument("--out", type=Path, default=Path("build/reports/devops-parallel-audit"))
    parser.add_argument("--strict", action="store_true", help="nonzero for any source conflict or unversioned pending DDL")
    args = parser.parse_args()
    repo = args.repo.resolve()
    try:
        main_ref = "refs/remotes/origin/main"
        main_files, _, parse_issues = snapshot(repo, main_ref)
        if parse_issues:
            raise ValueError("main migration structure invalid: " + "; ".join(parse_issues))
        heads: dict[str, str] = {"main": git(repo, "rev-parse", main_ref)}
        lanes = {}
        for name, branch in LANES.items():
            ref = f"refs/remotes/origin/{branch}"
            lanes[name] = snapshot(repo, ref)
            heads[name] = git(repo, "rev-parse", ref)
        report = audit(main_files, lanes)
        report["exact_refs"] = heads
        out = args.out.resolve()
        out.mkdir(parents=True, exist_ok=True)
        (out / "audit.json").write_text(
            json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
        md = render_markdown(report, heads)
        (out / "audit.md").write_text(md, encoding="utf-8")
        print("BLOCKED: parallel migrations need coordinated version allocation" if not report["release_ready"]
              else "PASS: parallel migration numbering source audit")
        print(md)
        summary = os.environ.get("GITHUB_STEP_SUMMARY")
        if summary:
            with open(summary, "a", encoding="utf-8") as handle:
                handle.write(md)
        return 1 if args.strict and not report["release_ready"] else 0
    except (OSError, subprocess.CalledProcessError, ValueError) as error:
        print(f"AUDIT ERROR: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
