#!/usr/bin/env python3
from __future__ import annotations

import argparse
import html
import os
import pathlib
import time
from urllib.parse import parse_qs, urlparse

import requests
from bs4 import BeautifulSoup
from playwright.sync_api import sync_playwright


BASE = os.environ.get(
    "KEYCLOAK_URL",
    "https://127.0.0.1:8443",
).rstrip("/")
CA_BUNDLE = os.environ.get("REQUESTS_CA_BUNDLE")
ADMIN_USER = os.environ.get("KEYCLOAK_ADMIN", "admin")
ADMIN_PASSWORD = os.environ.get(
    "KEYCLOAK_ADMIN_PASSWORD",
    "admin",
)
REALM = "hiltech"
CLIENT_ID = "hiltech-native"
USERNAME = "phase1-smoke"
PASSWORD = "Phase1-Smoke-Only!42"
ANDROID_REDIRECT = "com.hiltech.app:/oauth2redirect"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def request_verify():
    return CA_BUNDLE if CA_BUNDLE else True


def wait_for_keycloak() -> None:
    url = BASE + "/realms/master/.well-known/openid-configuration"
    deadline = time.time() + 90
    last_error = None

    while time.time() < deadline:
        try:
            response = requests.get(
                url,
                timeout=3,
                verify=request_verify(),
            )
            if response.status_code == 200:
                return
            last_error = (
                f"HTTP {response.status_code}: "
                f"{response.text[:300]}"
            )
        except Exception as exc:
            last_error = repr(exc)
        time.sleep(1)

    raise RuntimeError(
        f"Keycloak did not become ready: {last_error}",
    )


def admin_token() -> str:
    response = requests.post(
        BASE + "/realms/master/protocol/openid-connect/token",
        data={
            "grant_type": "password",
            "client_id": "admin-cli",
            "username": ADMIN_USER,
            "password": ADMIN_PASSWORD,
        },
        timeout=15,
        verify=request_verify(),
    )
    response.raise_for_status()
    return response.json()["access_token"]


def admin_request(
    method: str,
    path: str,
    payload=None,
    expected=(200, 201, 204),
):
    response = requests.request(
        method,
        BASE + path,
        headers={
            "authorization": "Bearer " + admin_token(),
            "content-type": "application/json",
        },
        json=payload,
        timeout=20,
        verify=request_verify(),
    )

    if response.status_code not in expected:
        raise RuntimeError(
            f"{method} {path} -> {response.status_code}: "
            f"{response.text[:1000]}",
        )

    if response.content:
        return response.json()
    return None


def setup_realm() -> None:
    response = requests.get(
        BASE + "/admin/realms/" + REALM,
        headers={
            "authorization": "Bearer " + admin_token(),
        },
        timeout=10,
        verify=request_verify(),
    )

    if response.status_code == 404:
        admin_request(
            "POST",
            "/admin/realms",
            {
                "realm": REALM,
                "enabled": True,
                "registrationAllowed": False,
                "loginWithEmailAllowed": True,
                "rememberMe": False,
            },
        )

    clients = admin_request(
        "GET",
        f"/admin/realms/{REALM}/clients?clientId={CLIENT_ID}",
    )
    if not clients:
        admin_request(
            "POST",
            f"/admin/realms/{REALM}/clients",
            {
                "clientId": CLIENT_ID,
                "name": "HILTECH Phase 1 Native Smoke",
                "enabled": True,
                "protocol": "openid-connect",
                "publicClient": True,
                "standardFlowEnabled": True,
                "directAccessGrantsEnabled": False,
                "serviceAccountsEnabled": False,
                "consentRequired": False,
                "redirectUris": [
                    ANDROID_REDIRECT,
                    "http://127.0.0.1:*",
                ],
                "webOrigins": [],
                "attributes": {
                    "pkce.code.challenge.method": "S256",
                    "oauth2.device.authorization.grant.enabled":
                        "false",
                },
            },
        )

    users = admin_request(
        "GET",
        f"/admin/realms/{REALM}/users"
        f"?username={USERNAME}&exact=true",
    )
    if users:
        user_id = users[0]["id"]
    else:
        admin_request(
            "POST",
            f"/admin/realms/{REALM}/users",
            {
                "username": USERNAME,
                "enabled": True,
                "email": "phase1-smoke@hiltech.example",
                "emailVerified": True,
                "firstName": "Phase1",
                "lastName": "Smoke",
            },
        )
        users = admin_request(
            "GET",
            f"/admin/realms/{REALM}/users"
            f"?username={USERNAME}&exact=true",
        )
        require(
            len(users) == 1,
            "Expected exactly one Phase 1 smoke user.",
        )
        user_id = users[0]["id"]

    admin_request(
        "PUT",
        f"/admin/realms/{REALM}/users/{user_id}/reset-password",
        {
            "type": "password",
            "value": PASSWORD,
            "temporary": False,
        },
    )

    clients = admin_request(
        "GET",
        f"/admin/realms/{REALM}/clients?clientId={CLIENT_ID}",
    )
    require(len(clients) == 1, "Native client not found.")
    client = clients[0]
    require(
        client.get("publicClient") is True,
        "Native client is not public.",
    )
    require(
        client.get("standardFlowEnabled") is True,
        "Authorization Code flow is disabled.",
    )
    require(
        client.get("directAccessGrantsEnabled") is False,
        "Direct password grant is enabled for native client.",
    )

    print(
        "HILTECH_PHASE1_KEYCLOAK_SETUP_PASS "
        "client=hiltech-native public=PASS "
        "authorization_code=PASS direct_grant=DISABLED",
    )


def wait_text(path: pathlib.Path) -> str:
    deadline = time.time() + 90
    while time.time() < deadline:
        if path.is_file():
            value = path.read_text(encoding="utf-8").strip()
            if value:
                return value
        time.sleep(0.1)
    raise TimeoutError(f"Timed out waiting for {path}")


def parse_login_form(response):
    soup = BeautifulSoup(response.text, "html.parser")
    form = (
        soup.find("form", id="kc-form-login")
        or soup.find("form")
    )
    require(form is not None, "Keycloak login form not found.")

    action = html.unescape(form.get("action", ""))
    require(action, "Keycloak login form has no action.")

    payload = {}
    for field in form.find_all("input"):
        name = field.get("name")
        if name:
            payload[name] = field.get("value", "")

    payload["username"] = USERNAME
    payload["password"] = PASSWORD
    payload.setdefault("credentialId", "")
    return action, payload


def drive_android(smoke_dir: pathlib.Path) -> None:
    auth_url = wait_text(
        smoke_dir / "android-auth-url.txt",
    )
    session = requests.Session()
    session.verify = request_verify()

    login = session.get(
        auth_url,
        allow_redirects=False,
        timeout=20,
    )
    require(
        login.status_code == 200,
        f"Expected login page, got {login.status_code}.",
    )

    action, payload = parse_login_form(login)
    result = session.post(
        action,
        data=payload,
        allow_redirects=False,
        timeout=20,
    )
    location = result.headers.get("location", "")
    require(
        result.status_code in (302, 303),
        f"Expected authorization redirect, got "
        f"{result.status_code}: {result.text[:500]}",
    )
    require(
        location.startswith(ANDROID_REDIRECT),
        f"Unexpected Android redirect: {location}",
    )

    query = parse_qs(urlparse(location).query)
    require("code" in query, "Android callback has no code.")
    require("state" in query, "Android callback has no state.")

    (smoke_dir / "android-callback.txt").write_text(
        location,
        encoding="utf-8",
    )
    print(
        "HILTECH_PHASE1_ANDROID_BROWSER_CALLBACK_PASS "
        "private_redirect=PASS",
    )


def drive_windows(smoke_dir: pathlib.Path) -> None:
    auth_url = wait_text(
        smoke_dir / "windows-auth-url.txt",
    )

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
        context = browser.new_context(
            ignore_https_errors=True,
        )
        page = context.new_page()

        page.goto(
            auth_url,
            wait_until="domcontentloaded",
        )
        page.locator("#username").fill(USERNAME)
        page.locator("#password").fill(PASSWORD)
        page.locator("#kc-login").click()

        page.wait_for_selector(
            "text=HILTECH authentication received",
            timeout=20_000,
        )
        require(
            page.url.startswith("http://127.0.0.1:"),
            f"Unexpected Windows callback URL: {page.url}",
        )

        context.close()
        browser.close()

    print(
        "HILTECH_PHASE1_WINDOWS_BROWSER_CALLBACK_PASS "
        "loopback=PASS chromium=PASS",
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "mode",
        choices=("setup", "android", "windows"),
    )
    parser.add_argument(
        "--smoke-dir",
        default=os.environ.get(
            "HILTECH_OIDC_SMOKE_DIR",
            "",
        ),
    )
    args = parser.parse_args()

    wait_for_keycloak()

    if args.mode == "setup":
        setup_realm()
        return

    require(
        args.smoke_dir,
        "--smoke-dir or HILTECH_OIDC_SMOKE_DIR is required.",
    )
    smoke_dir = pathlib.Path(args.smoke_dir)
    smoke_dir.mkdir(parents=True, exist_ok=True)

    if args.mode == "android":
        drive_android(smoke_dir)
    else:
        drive_windows(smoke_dir)


if __name__ == "__main__":
    main()
