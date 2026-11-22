#!/usr/bin/env python3
"""Read-only, privacy-reduced Docker inventory for deciding a HILTECH Coolify pilot.

This program cannot stop/remove/create containers, alter volumes or call Coolify.
By default it emits aggregate counts only. --details emits local-only names,
allowlisted Compose/Coolify identifiers and risk hints: do not post to public issues.
No environment values, full arbitrary labels, mount source paths, logs, domains,
Docker command strings or credential material are emitted.
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from collections import Counter
from typing import Any

PREVIEW_RE = re.compile(r"(?:^|[-_])pr[-_]?[0-9]+(?:$|[-_])", re.IGNORECASE)
ALLOWED_LABELS = (
    "com.docker.compose.project",
    "com.docker.compose.service",
    "coolify.applicationId",
    "coolify.serviceId",
)
PROJECT_LABELS = (
    "com.docker.compose.project",
    "coolify.applicationId",
    "coolify.serviceId",
)


def docker_snapshot(sudo: bool = False) -> list[dict[str, Any]]:
    """Use only Docker read commands, with no shell and no changes."""
    cmd = ["sudo", "-n", "docker"] if sudo else ["docker"]
    ids_result = subprocess.run(
        cmd + ["ps", "-aq"], capture_output=True, text=True, check=True, timeout=20
    )
    ids = [line.strip() for line in ids_result.stdout.splitlines() if line.strip()]
    if not ids:
        return []
    inspect = subprocess.run(
        cmd + ["inspect", "--type", "container", *ids],
        capture_output=True, text=True, check=True, timeout=30
    )
    parsed = json.loads(inspect.stdout)
    if not isinstance(parsed, list) or not all(isinstance(x, dict) for x in parsed):
        raise ValueError("Docker inspect returned an unexpected JSON shape")
    return parsed


def analyze(raw: list[dict[str, Any]]) -> dict[str, Any]:
    """Only retain allowlisted fields and counts. Never equate preview with unused."""
    rows: list[dict[str, Any]] = []
    for obj in raw:
        labels = obj.get("Config", {}).get("Labels") or {}
        if not isinstance(labels, dict):
            labels = {}
        safe_labels = {
            key: str(labels[key])
            for key in ALLOWED_LABELS
            if isinstance(labels.get(key), (str, int))
        }
        state = obj.get("State") or {}
        health = (state.get("Health") or {}).get("Status", "not-configured")
        name = str(obj.get("Name", "")).lstrip("/")
        mounts = obj.get("Mounts") or []
        mounts = mounts if isinstance(mounts, list) else []
        host_cfg = obj.get("HostConfig") or {}
        memory = host_cfg.get("Memory") or 0
        pids = host_cfg.get("PidsLimit") or 0
        cpu = host_cfg.get("NanoCpus") or 0
        has_project = any(safe_labels.get(k) for k in PROJECT_LABELS)
        is_preview = bool(PREVIEW_RE.search(name))
        status = (
            "PREVIEW_REVIEW_ONLY" if is_preview
            else "OWNER_UNKNOWN_REVIEW" if not has_project
            else "KEEP_UNTIL_OWNER_APPROVES"
        )
        rows.append({
            "name": name,
            "image": str(obj.get("Config", {}).get("Image", "")),
            "running": bool(state.get("Running")),
            "health": str(health),
            "restart_count": int(obj.get("RestartCount", 0)),
            "project_labels": safe_labels,
            "mount_count": len(mounts),
            "named_volume_count": sum(m.get("Type") == "volume" for m in mounts if isinstance(m, dict)),
            "bind_mount_count": sum(m.get("Type") == "bind" for m in mounts if isinstance(m, dict)),
            "memory_limit_mib": int(memory) // (1024 * 1024),
            "cpu_limit_nanocpus": int(cpu),
            "pids_limit": int(pids),
            "classification": status,
        })
    summary = Counter()
    for row in rows:
        summary["total"] += 1
        summary["running"] += int(row["running"])
        summary["unhealthy"] += int(row["health"] == "unhealthy")
        summary["preview_review"] += int(row["classification"] == "PREVIEW_REVIEW_ONLY")
        summary["owner_unknown"] += int(row["classification"] == "OWNER_UNKNOWN_REVIEW")
        summary["without_memory_limit"] += int(row["memory_limit_mib"] == 0)
        summary["with_volumes_or_binds"] += int(row["mount_count"] > 0)
    return {
        "schema_version": 1,
        "safety": "READ_ONLY; NOT AN UNUSED-RESOURCE CLAIM; NOT A CLEANUP AUTHORIZATION",
        "summary": dict(sorted(summary.items())),
        "containers": sorted(rows, key=lambda r: r["name"]),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sudo", action="store_true", help="Use passwordless sudo -n Docker access")
    parser.add_argument("--details", action="store_true", help="Print private container names/allowlisted labels")
    parser.add_argument("--fixture", help="Read a Docker-inspect JSON fixture instead of Docker (test-only)")
    args = parser.parse_args()
    try:
        if args.fixture:
            with open(args.fixture, encoding="utf-8") as handle:
                raw = json.load(handle)
            if not isinstance(raw, list):
                raise ValueError("Fixture must be a JSON array of container objects")
        else:
            raw = docker_snapshot(args.sudo)
        report = analyze(raw)
    except (OSError, ValueError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as exc:
        print(f"READ_ONLY_INVENTORY_FAILED: {type(exc).__name__}", file=sys.stderr)
        return 2

    # The default CLI output is aggregate-only; named/private details require --details.
    if not args.details:
        report.pop("containers")
    print(json.dumps(report, ensure_ascii=False, sort_keys=True, indent=2))
    if args.details:
        print("PRIVATE_OUTPUT: do not paste names or project identifiers to public issues.", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
