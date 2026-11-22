#!/usr/bin/env python3
"""Secretless static checks for the isolated Coolify staging manifest."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
COMPOSE = ROOT / "infrastructure/coolify/staging/compose.yaml"
INIT = ROOT / "infrastructure/coolify/staging/postgres-init.sh"
DOC = ROOT / "infrastructure/coolify/staging/README.md"


def main() -> None:
    yaml = COMPOSE.read_text(encoding="utf-8")
    init = INIT.read_text(encoding="utf-8")
    doc = DOC.read_text(encoding="utf-8")
    assert yaml.strip() and init.strip() and doc.strip()
    assert "start-dev" not in yaml
    assert not re.search(r"(?m)^\s*ports\s*:", yaml), "public port mapping prohibited"
    assert 'OPENFGA_PLAYGROUND_ENABLED: "false"' in yaml
    for setting in (
        'HILTECH_OIDC_ENABLED: "true"',
        'HILTECH_FGA_ENABLED: "true"',
        'HILTECH_EVIDENCE_STORAGE_ENABLED: "true"',
        'profiles: ["api"]',
        "ghcr.io/omarkhair70-droid/hiltech-server@sha256:",
        "/var/lib/postgresql",
        "HILTECH_APP_DB_PASSWORD:?",
        "HILTECH_KC_BOOTSTRAP_ADMIN_PASSWORD:?",
    ):
        assert setting in yaml, f"missing fail-closed requirement: {setting}"
    for val in ('hiltech_app', 'hiltech_keycloak', 'hiltech_openfga'):
        assert val in init
    assert "CREATE DATABASE" in init
    assert ":'app_password'" in init, "password must use SQL-literal psql quoting"
    print("PASS: HILTECH Coolify staging template static guardrails")


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, OSError) as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        raise SystemExit(1)
