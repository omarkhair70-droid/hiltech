#!/usr/bin/env python3
"""One-shot HILTECH staging-only OpenFGA store/model bootstrap.

Run this ONLY from the authorized private Docker network, after the security
review grants a temporary secret in HILTECH_FGA_BOOTSTRAP_KEY. Dry-run is the
default. Never records real business tuples or emits the bearer credential.

Example:
  python3 scripts/devops/bootstrap_hiltech_openfga.py --model first-slice-model.generated.json
  HILTECH_FGA_BOOTSTRAP_KEY=... python3 ... --model ... --apply
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

INTERNAL_ENDPOINT = "http://hiltech-fga-private:8080"
STAGING_STORE = "hiltech-internal-staging"
ULID = re.compile(r"[0-9A-HJKMNP-TV-Z]{26}\Z")


def validated_model(path: Path) -> dict:
    model = json.loads(path.read_text(encoding="utf-8"))
    definitions = model.get("type_definitions")
    types = {item.get("type") for item in definitions} if isinstance(definitions, list) else set()
    if model.get("schema_version") != "1.1" or len(types) != 14:
        raise ValueError("Expected the reviewed HILTECH 14-type OpenFGA schema 1.1")
    if not {"user", "organization", "project", "warehouse", "work_order", "evidence"} <= types:
        raise ValueError("Missing required HILTECH policy entities")
    return model


def checked_id(value: object, label: str) -> str:
    if not isinstance(value, str) or not ULID.fullmatch(value):
        raise ValueError(f"Invalid OpenFGA {label}")
    return value


class PrivateOpenFga:
    def __init__(self, secret: str):
        if len(secret) < 40 or "\n" in secret or "\r" in secret:
            raise ValueError("The approved OpenFGA staging credential is missing or invalid")
        self._secret = secret

    def request(self, method: str, path: str, body: dict | None = None) -> dict:
        if not path.startswith("/") or path.startswith("//") or "://" in path:
            raise ValueError("Refusing non-local OpenFGA target")
        payload = None if body is None else json.dumps(body, separators=(",", ":")).encode("utf-8")
        request = Request(
            INTERNAL_ENDPOINT + path,
            data=payload,
            method=method,
            headers={
                "Authorization": "Bearer " + self._secret,
                "Content-Type": "application/json",
                "Accept": "application/json",
            },
        )
        try:
            with urlopen(request, timeout=15) as response:
                return json.loads(response.read().decode("utf-8"))
        except HTTPError as exc:
            raise RuntimeError(f"OpenFGA {method} request failed with HTTP {exc.code}; response hidden") from None
        except URLError:
            raise RuntimeError("Private OpenFGA is unavailable; no alternate host attempted") from None


def pages(api: PrivateOpenFga, path: str, item_key: str):
    token = ""
    seen: set[str] = set()
    while True:
        query = {"page_size": 100}
        if token:
            query["continuation_token"] = token
        data = api.request("GET", path + "?" + urlencode(query))
        for item in data.get(item_key, []):
            yield item
        token = data.get("continuation_token") or ""
        if not token:
            return
        if token in seen:
            raise RuntimeError("Repeated OpenFGA pagination token; refusing bootstrap")
        seen.add(token)


def ensure_store(api: PrivateOpenFga) -> str:
    matches = [s for s in pages(api, "/stores", "stores") if s.get("name") == STAGING_STORE]
    if len(matches) > 1:
        raise RuntimeError("Duplicate HILTECH staging stores detected; manual reconciliation required")
    if matches:
        return checked_id(matches[0].get("id"), "existing store id")
    created = api.request("POST", "/stores", {"name": STAGING_STORE})
    return checked_id(created.get("id"), "new store id")


def ensure_model(api: PrivateOpenFga, store_id: str, model: dict) -> str:
    path = f"/stores/{checked_id(store_id, 'store id')}/authorization-models"
    matching = []
    for item in pages(api, path, "authorization_models"):
        if (item.get("schema_version") == model["schema_version"]
                and item.get("type_definitions") == model["type_definitions"]
                and item.get("conditions", {}) == model.get("conditions", {})):
            matching.append(checked_id(item.get("id"), "existing model id"))
    if matching:
        return matching[0]
    response = api.request("POST", path, model)
    return checked_id(response.get("authorization_model_id"), "new model id")


def contextual_check(api: PrivateOpenFga, store_id: str, model_id: str, allow: bool) -> bool:
    user = "user:hiltech-qa-allowed" if allow else "user:hiltech-qa-outsider"
    body = {
        "tuple_key": {
            "user": user,
            "relation": "can_view",
            "object": "project:hiltech-qa-disposable",
        },
        "authorization_model_id": checked_id(model_id, "model id"),
    }
    if allow:
        body["contextual_tuples"] = {"tuple_keys": [{
            "user": user,
            "relation": "project_manager",
            "object": "project:hiltech-qa-disposable",
        }]}
    result = api.request("POST", f"/stores/{checked_id(store_id, 'store id')}/check", body)
    if not isinstance(result.get("allowed"), bool):
        raise RuntimeError("Malformed OpenFGA check response")
    return result["allowed"]


def main() -> int:
    parser = argparse.ArgumentParser(description="Idempotent internal OpenFGA staging bootstrap")
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--apply", action="store_true", help="explicitly mutate staging store/model")
    args = parser.parse_args()

    model = validated_model(args.model)
    if not args.apply:
        print(f"DRY RUN: reviewed model valid, types={len(model['type_definitions'])}; no network or writes")
        return 0

    key = os.environ.get("HILTECH_FGA_BOOTSTRAP_KEY", "")
    api = PrivateOpenFga(key)
    store_id = ensure_store(api)
    model_id = ensure_model(api, store_id, model)
    if contextual_check(api, store_id, model_id, True) is not True:
        raise RuntimeError("Fail-closed: positive contextual project access test was denied")
    if contextual_check(api, store_id, model_id, False) is not False:
        raise RuntimeError("Fail-closed: unassigned outsider unexpectedly allowed")

    print("PASS: internal staging OpenFGA model with contextual allow/deny; no permanent QA tuples")
    print(f"HILTECH_FGA_STORE_ID={store_id}")
    print(f"HILTECH_FGA_MODEL_ID={model_id}")
    print("ACTION: store these two nonsecret identifiers in the private staging API settings")
    print("ACTION: delete temporary HILTECH_FGA_BOOTSTRAP_KEY from the bootstrap runner")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, RuntimeError, json.JSONDecodeError) as exc:
        print(f"BOOTSTRAP BLOCKED: {exc.__class__.__name__}: {exc}", file=sys.stderr)
        sys.exit(1)
