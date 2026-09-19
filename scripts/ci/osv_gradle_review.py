#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

OSV_BASE = "https://api.osv.dev"
BATCH_SIZE = 400
BLOCKING_SEVERITIES = {"HIGH", "CRITICAL"}


def parse_gradle_report(path: Path) -> list[tuple[str, str, str]]:
    coordinates: set[tuple[str, str, str]] = set()

    for raw_line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if "--- " not in raw_line:
            continue

        body = raw_line.split("--- ", 1)[1].strip()
        body = re.sub(r"\s+\((?:\*|c|n)\)\s*$", "", body).strip()

        if body.startswith("project "):
            continue
        if body.endswith(" FAILED"):
            raise SystemExit("Unresolved Gradle dependency in report: " + body)

        if " -> " in body:
            left, right = body.split(" -> ", 1)
            right_token = right.strip().split()[0]
            if right_token.count(":") >= 2:
                candidate = right_token
            else:
                left_parts = left.strip().split(":")
                if len(left_parts) < 2:
                    continue
                candidate = f"{left_parts[0]}:{left_parts[1]}:{right_token}"
        else:
            candidate = body.split()[0]

        parts = candidate.split(":")
        if len(parts) < 3:
            continue

        group, artifact, version = parts[0], parts[1], parts[2]
        if not group or not artifact or not version:
            continue
        if version in {"unspecified", "FAILED"}:
            continue
        if version.startswith("{") or version.startswith("["):
            continue

        coordinates.add((group, artifact, version))

    if not coordinates:
        raise SystemExit("No resolved Maven coordinates were parsed from Gradle report.")

    return sorted(coordinates)


def post_json(url: str, payload: dict) -> dict:
    request = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "content-type": "application/json",
            "user-agent": "hiltech-bootstrap-osv-review/1",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
        raise SystemExit(f"OSV query failed closed: {exc}") from exc


def get_json(url: str) -> dict:
    request = urllib.request.Request(
        url,
        headers={"user-agent": "hiltech-bootstrap-osv-review/1"},
    )
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            return json.load(response)
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
        raise SystemExit(f"OSV vulnerability detail lookup failed closed: {exc}") from exc


def query_vulnerabilities(
    coordinates: list[tuple[str, str, str]],
) -> dict[str, set[tuple[str, str, str]]]:
    found: dict[str, set[tuple[str, str, str]]] = {}

    for offset in range(0, len(coordinates), BATCH_SIZE):
        chunk = coordinates[offset : offset + BATCH_SIZE]
        payload = {
            "queries": [
                {
                    "package": {
                        "ecosystem": "Maven",
                        "name": f"{group}:{artifact}",
                    },
                    "version": version,
                }
                for group, artifact, version in chunk
            ]
        }
        response = post_json(f"{OSV_BASE}/v1/querybatch", payload)
        results = response.get("results")

        if not isinstance(results, list) or len(results) != len(chunk):
            raise SystemExit(
                "OSV querybatch response did not preserve request/result cardinality."
            )

        for coordinate, result in zip(chunk, results):
            if result.get("next_page_token"):
                raise SystemExit(
                    "OSV pagination encountered; security review fails closed "
                    f"for {coordinate[0]}:{coordinate[1]}:{coordinate[2]}."
                )
            for vuln in result.get("vulns", []):
                vuln_id = vuln.get("id")
                if vuln_id:
                    found.setdefault(vuln_id, set()).add(coordinate)

    return found


def normalized_severities(record: dict) -> set[str]:
    values: set[str] = set()

    database_specific = record.get("database_specific")
    if isinstance(database_specific, dict):
        severity = database_specific.get("severity")
        if isinstance(severity, str):
            values.add(severity.upper())

    for affected in record.get("affected", []):
        specific = affected.get("database_specific")
        if isinstance(specific, dict):
            severity = specific.get("severity")
            if isinstance(severity, str):
                values.add(severity.upper())

    for item in record.get("severity", []):
        score = item.get("score")
        if isinstance(score, (int, float)):
            if score >= 9:
                values.add("CRITICAL")
            elif score >= 7:
                values.add("HIGH")
            elif score >= 4:
                values.add("MODERATE")
            else:
                values.add("LOW")
        elif isinstance(score, str):
            try:
                numeric = float(score)
            except ValueError:
                continue
            if numeric >= 9:
                values.add("CRITICAL")
            elif numeric >= 7:
                values.add("HIGH")
            elif numeric >= 4:
                values.add("MODERATE")
            else:
                values.add("LOW")

    return values or {"UNKNOWN"}


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit(
            "usage: osv_gradle_review.py <gradle-dependency-report.txt>"
        )

    report = Path(sys.argv[1])
    if not report.is_file():
        raise SystemExit(f"Gradle dependency report not found: {report}")

    coordinates = parse_gradle_report(report)
    vulnerabilities = query_vulnerabilities(coordinates)

    blocking: list[str] = []
    warnings: list[str] = []

    for vuln_id in sorted(vulnerabilities):
        detail = get_json(
            f"{OSV_BASE}/v1/vulns/{urllib.parse.quote(vuln_id, safe='')}"
        )
        severities = normalized_severities(detail)
        packages = ", ".join(
            f"{g}:{a}:{v}" for g, a, v in sorted(vulnerabilities[vuln_id])
        )
        summary = str(detail.get("summary") or "").replace("\n", " ")[:180]
        line = (
            f"{vuln_id} severity={','.join(sorted(severities))} "
            f"packages=[{packages}] summary={summary}"
        )

        if severities & BLOCKING_SEVERITIES:
            blocking.append(line)
        else:
            warnings.append(line)

    print(
        "HILTECH_OSV_REVIEW "
        f"coordinates={len(coordinates)} "
        f"vulnerabilities={len(vulnerabilities)} "
        f"blocking={len(blocking)}"
    )

    for line in warnings:
        print("OSV_NON_BLOCKING " + line)

    if blocking:
        for line in blocking:
            print("OSV_BLOCKING " + line, file=sys.stderr)
        raise SystemExit(
            "HIGH/CRITICAL known vulnerabilities found in resolved Gradle dependencies."
        )

    print("HILTECH_OSV_REVIEW=PASS")


if __name__ == "__main__":
    main()
