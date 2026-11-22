#!/usr/bin/env python3
"""Fail-closed evidence ledger gate for HILTECH preprod/prod release reviews.

No network, secrets, Docker, Coolify mutation, credential inspection or deployment.
Checks whether a separately reviewed release ledger has enough *declared* evidence
to enter a human release review. It does NOT independently prove the evidence.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from urllib.parse import urlparse

REQUIRED_GATES = (
    "host_capacity_isolation",
    "immutable_image_provenance",
    "private_tls_ingress",
    "separate_postgres_migration",
    "real_keycloak_oidc_revocation",
    "persistent_canonical_openfga",
    "private_durable_evidence_storage",
    "operational_health_observability",
    "backups_disposable_restore",
    "real_native_android_windows_e2e",
    "cross_role_full_journey_and_denials",
    "authorized_operator_acceptance",
)
SOURCE_RE = re.compile(r"^[a-f0-9]{40}$")
DIGEST_RE = re.compile(r"^sha256:[a-f0-9]{64}$")


def acceptable_proof(reference: object) -> bool:
    if not isinstance(reference, str) or not reference.strip():
        return False
    if reference.startswith("https://"):
        parsed = urlparse(reference)
        return bool(parsed.hostname) and not parsed.username and not parsed.password
    if reference.startswith(("docs/", "evidence/", "reports/")):
        return not any(segment in ("", "..") for segment in reference.split("/"))
    return False


def check(manifest: object, expected_environment: str) -> list[str]:
    reasons: list[str] = []
    if not isinstance(manifest, dict):
        return ["Top-level manifest must be a JSON object."]

    if manifest.get("environment") != expected_environment:
        reasons.append("environment must match explicit --environment argument")

    sha = manifest.get("source_commit")
    if not isinstance(sha, str) or not SOURCE_RE.fullmatch(sha):
        reasons.append("source_commit must be an exact 40-digit lowercase commit SHA")

    digest = manifest.get("immutable_image_digest")
    if not isinstance(digest, str) or not DIGEST_RE.fullmatch(digest):
        reasons.append("immutable_image_digest must be an exact sha256:<64 hex> digest")

    address = manifest.get("deployment_url")
    if not isinstance(address, str) or not address.startswith("https://") or not urlparse(address).hostname:
        reasons.append("deployment_url must be an HTTPS ingress URL")

    gates = manifest.get("evidence_gates")
    if not isinstance(gates, dict):
        return reasons + ["evidence_gates must be a JSON object"]

    missing = sorted(set(REQUIRED_GATES) - set(gates))
    for name in missing:
        reasons.append(f"missing evidence gate: {name}")
    for name in REQUIRED_GATES:
        entry = gates.get(name)
        if not isinstance(entry, dict):
            if name not in missing:
                reasons.append(f"{name}: gate must be a JSON object")
            continue
        if entry.get("status") != "PASS":
            reasons.append(f"{name}: not PASS")
        refs = entry.get("proof_refs")
        if not isinstance(refs, list) or not refs or not all(acceptable_proof(ref) for ref in refs):
            reasons.append(f"{name}: required non-empty proof_refs (HTTPS URLs or safe repo-relative evidence paths)")

    approval = manifest.get("owner_release_approval")
    if not isinstance(approval, dict) or approval.get("status") != "PASS":
        reasons.append("owner_release_approval: not PASS")
    elif not isinstance(approval.get("proof_refs"), list) or not approval["proof_refs"] or not all(acceptable_proof(x) for x in approval["proof_refs"]):
        reasons.append("owner_release_approval: missing valid proof_refs")

    if expected_environment == "production" and not acceptable_proof(manifest.get("preprod_acceptance_reference")):
        reasons.append("production requires a distinct preprod_acceptance_reference")

    return reasons


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path, help="JSON release ledger (not secrets file)")
    parser.add_argument("--environment", required=True, choices=("preprod", "production"))
    args = parser.parse_args()
    try:
        manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, ValueError) as error:
        print(f"NO-GO: could not read JSON release ledger: {type(error).__name__}", file=sys.stderr)
        return 2

    errors = check(manifest, args.environment)
    if errors:
        print(f"NO-GO — {len(errors)} preprod/production evidence gate(s) unresolved:")
        for reason in errors:
            print(" - " + reason)
        return 1
    print("EVIDENCE-LEDGER COMPLETE: human independent verification still required.")
    print("This tool does not authorize deployment, feature activation, or production cutover.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
