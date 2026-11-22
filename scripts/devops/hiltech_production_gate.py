#!/usr/bin/env python3
"""Fail-closed, read-only cutover preflight for HILTECH's internal production OS.

Never starts/deploys services or prints environment secrets. The API, OIDC
and externally signed evidence URLs are the only intentional public surfaces.
"""
from __future__ import annotations

import argparse
import ipaddress
import json
import os
import re
import socket
import ssl
import sys
from urllib.parse import urlsplit
from urllib.request import Request, urlopen

PUBLIC_URLS = (
    "HILTECH_PROD_API_URL",
    "HILTECH_PROD_OIDC_ISSUER",
    "HILTECH_PROD_EVIDENCE_URL",
)
REQUIRED = PUBLIC_URLS + (
    "HILTECH_PROD_API_IMAGE",
    "HILTECH_PROD_FGA_STORE_ID",
    "HILTECH_PROD_FGA_MODEL_ID",
    "HILTECH_PROD_ANDROID_RELEASE_REF",
    "HILTECH_PROD_WINDOWS_RELEASE_REF",
    "HILTECH_PROD_BACKUP_RESTORE_REF",
    "HILTECH_PROD_APPROVAL_REF",
)

def public_https_host(url: str) -> str:
    parsed = urlsplit(url)
    if parsed.scheme.lower() != "https" or not parsed.hostname:
        raise ValueError("requires a fully qualified HTTPS URL")
    if parsed.username or parsed.password or parsed.fragment:
        raise ValueError("must not contain credentials or a fragment")
    if parsed.port not in (None, 443):
        raise ValueError("public production ingress must use HTTPS port 443")
    host = parsed.hostname.rstrip(".").lower()
    if "." not in host or host.endswith((".local", ".localhost", ".internal", ".test")):
        raise ValueError("requires a public DNS hostname, not an internal address")
    if any(host.endswith(suffix) for suffix in (".sslip.io", ".nip.io")):
        raise ValueError("temporary wildcard-IP domains are not production identity domains")
    try:
        ipaddress.ip_address(host)
        raise ValueError("use an approved production DNS name, not a raw IP")
    except ValueError as exc:
        if "approved production DNS" in str(exc):
            raise
    return host

def validate_config(environ: dict[str, str]) -> list[str]:
    errors: list[str] = []
    for name in REQUIRED:
        if not environ.get(name, "").strip():
            errors.append(f"{name}: missing")
    for name in PUBLIC_URLS:
        value = environ.get(name, "").strip()
        if value:
            try:
                public_https_host(value)
            except (ValueError, OverflowError) as exc:
                errors.append(f"{name}: {exc}")
    image = environ.get("HILTECH_PROD_API_IMAGE", "")
    if image and not re.fullmatch(r"[^\s@]+@sha256:[0-9a-f]{64}", image):
        errors.append("HILTECH_PROD_API_IMAGE: must be an immutable sha256 digest")
    for name in ("HILTECH_PROD_FGA_STORE_ID", "HILTECH_PROD_FGA_MODEL_ID"):
        value = environ.get(name, "").strip()
        if value and not re.fullmatch(r"[A-Za-z0-9_-]{16,80}", value):
            errors.append(f"{name}: invalid identifier")
    if environ.get("HILTECH_PROD_OPENFGA_PUBLIC", "").lower() in ("true", "1", "yes"):
        errors.append("HILTECH_PROD_OPENFGA_PUBLIC: must not expose OpenFGA")
    if environ.get("HILTECH_PROD_DATABASE_PUBLIC", "").lower() in ("true", "1", "yes"):
        errors.append("HILTECH_PROD_DATABASE_PUBLIC: must not expose database ports")
    if environ.get("HILTECH_PROD_FGA_AUTHN_METHOD", "preshared") != "preshared":
        errors.append("HILTECH_PROD_FGA_AUTHN_METHOD: preshared authentication required")
    return errors

def public_dns(host: str) -> None:
    answers = socket.getaddrinfo(host, 443, proto=socket.IPPROTO_TCP)
    if not answers:
        raise ValueError("DNS did not resolve")
    for item in answers:
        ip = ipaddress.ip_address(item[4][0])
        if not ip.is_global:
            raise ValueError("DNS points to private, loopback, or reserved address")

def tls_probe(host: str) -> None:
    with socket.create_connection((host, 443), timeout=12) as conn:
        with ssl.create_default_context().wrap_socket(conn, server_hostname=host):
            pass

def json_get(url: str) -> dict:
    with urlopen(Request(url, headers={"Accept": "application/json"}), timeout=12) as response:
        if response.status != 200:
            raise ValueError(f"unexpected HTTP {response.status}")
        return json.load(response)

def live_probes(environ: dict[str, str]) -> list[str]:
    errors: list[str] = []
    for name in PUBLIC_URLS:
        try:
            host = public_https_host(environ[name])
            public_dns(host)
            tls_probe(host)
        except Exception as exc:
            errors.append(f"{name}: public DNS/TLS verification failed ({type(exc).__name__})")
    issuer = environ["HILTECH_PROD_OIDC_ISSUER"].rstrip("/")
    try:
        metadata = json_get(issuer + "/.well-known/openid-configuration")
        if metadata.get("issuer") != issuer:
            errors.append("HILTECH_PROD_OIDC_ISSUER: discovery issuer does not match configured issuer")
        if not metadata.get("jwks_uri", "").startswith("https://"):
            errors.append("HILTECH_PROD_OIDC_ISSUER: JWKS URL is not HTTPS")
        if not metadata.get("authorization_endpoint", "").startswith("https://"):
            errors.append("HILTECH_PROD_OIDC_ISSUER: authorization endpoint is not HTTPS")
    except Exception as exc:
        errors.append(f"HILTECH_PROD_OIDC_ISSUER: discovery failed ({type(exc).__name__})")
    try:
        health = json_get(environ["HILTECH_PROD_API_URL"].rstrip("/") + "/actuator/health/readiness")
        if health.get("status") != "UP":
            errors.append("HILTECH_PROD_API_URL: readiness is not UP")
    except Exception as exc:
        errors.append(f"HILTECH_PROD_API_URL: readiness probe failed ({type(exc).__name__})")
    return errors

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--live", action="store_true", help="check public TLS, OIDC discovery and API readiness")
    args = parser.parse_args()
    errors = validate_config(dict(os.environ))
    if not errors and args.live:
        errors.extend(live_probes(dict(os.environ)))
    for item in errors:
        print("BLOCKED: " + item)
    if errors:
        print(f"Production cutover blocked by {len(errors)} unverified requirements. No changes made.")
        return 1
    print("PASS: production configuration preflight" + (" + DNS/TLS/OIDC/API" if args.live else " (static)"))
    print("NOTE: this does not by itself prove native E2E, role safety, object backup, or company acceptance.")
    return 0

if __name__ == "__main__":
    sys.exit(main())
