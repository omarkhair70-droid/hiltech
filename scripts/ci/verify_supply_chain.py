#!/usr/bin/env python3
from __future__ import annotations

import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BOOTSTRAP_WORKFLOW = ROOT / ".github/workflows/bootstrap-phase0.yml"

FULL_SHA = re.compile(r"^[0-9a-f]{40}$")
USES_LINE = re.compile(r"^\s*uses:\s*([^\s#]+)", re.MULTILINE)

STRONG_SECRET_PATTERNS = {
    "PEM private key": re.compile(
        rb"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"
    ),
    "GitHub token": re.compile(rb"gh[pousr]_[A-Za-z0-9_]{30,}"),
    "AWS access key": re.compile(rb"AKIA[0-9A-Z]{16}"),
    "Slack token": re.compile(rb"xox[baprs]-[A-Za-z0-9-]{10,}"),
}


def tracked_files() -> list[Path]:
    output = subprocess.check_output(
        ["git", "ls-files", "-z"],
        cwd=ROOT,
    )
    return [
        ROOT / item.decode("utf-8")
        for item in output.split(b"\0")
        if item
    ]


def verify_action_pins(workflow_text: str) -> None:
    failures: list[str] = []

    for match in USES_LINE.finditer(workflow_text):
        reference = match.group(1)

        if reference.startswith("./"):
            continue

        if "@" not in reference:
            failures.append(f"missing ref: {reference}")
            continue

        action, ref = reference.rsplit("@", 1)
        if not FULL_SHA.fullmatch(ref):
            failures.append(
                f"external action is not pinned to a full commit SHA: "
                f"{action}@{ref}"
            )

    if failures:
        raise SystemExit("\n".join(failures))


def verify_default_permissions(workflow_text: str) -> None:
    if not re.search(
        r"(?m)^permissions:\s*\n\s{2}contents:\s*read\s*$",
        workflow_text,
    ):
        raise SystemExit(
            "Bootstrap workflow must keep default token permission at contents: read."
        )


def verify_no_strong_secret_material(files: list[Path]) -> None:
    failures: list[str] = []

    for path in files:
        try:
            content = path.read_bytes()
        except OSError as exc:
            failures.append(f"cannot read tracked file {path}: {exc}")
            continue

        for label, pattern in STRONG_SECRET_PATTERNS.items():
            if pattern.search(content):
                failures.append(
                    f"{label} pattern found in tracked file "
                    f"{path.relative_to(ROOT)}"
                )

    if failures:
        raise SystemExit("\n".join(failures))


def verify_no_mutable_runtime_markers(files: list[Path]) -> None:
    checked = [
        ROOT / ".github/workflows/bootstrap-phase0.yml",
        ROOT / "infrastructure/local/docker-compose.yml",
    ]

    for path in checked:
        if not path.exists():
            continue
        text = path.read_text(encoding="utf-8")
        if ":latest" in text:
            raise SystemExit(
                f"mutable :latest image marker found in {path.relative_to(ROOT)}"
            )


def verify_gradle_versions_are_not_dynamic() -> None:
    catalog = (
        ROOT / "gradle/libs.versions.toml"
    ).read_text(encoding="utf-8")

    section = catalog.split("[versions]", 1)[1].split("[libraries]", 1)[0]
    dynamic = re.compile(
        r'(?im)^\s*[A-Za-z0-9_.-]+\s*=\s*"[^"]*(?:\+|SNAPSHOT|latest)[^"]*"'
    )

    match = dynamic.search(section)
    if match:
        raise SystemExit(
            "Dynamic Gradle version is not allowed: " + match.group(0).strip()
        )


def verify_no_real_tfvars(files: list[Path]) -> None:
    bad = [
        path.relative_to(ROOT)
        for path in files
        if path.suffix == ".tfvars"
        and not path.name.endswith(".tfvars.example")
    ]
    if bad:
        raise SystemExit(
            "Tracked non-example Terraform variable files are forbidden: "
            + ", ".join(map(str, bad))
        )


def main() -> None:
    workflow_text = BOOTSTRAP_WORKFLOW.read_text(encoding="utf-8")
    files = tracked_files()

    verify_action_pins(workflow_text)
    verify_default_permissions(workflow_text)
    verify_no_strong_secret_material(files)
    verify_no_mutable_runtime_markers(files)
    verify_gradle_versions_are_not_dynamic()
    verify_no_real_tfvars(files)

    print(
        "HILTECH_SUPPLY_CHAIN_CONTRACT=PASS "
        "actions=full-sha secrets=strong-patterns dynamic_versions=denied"
    )


if __name__ == "__main__":
    main()
