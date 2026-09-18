#!/usr/bin/env python3
import base64
import hashlib
import html
import json
import os
import secrets
import sys
import time
from urllib.parse import parse_qs, urlencode, urlparse

import requests
from bs4 import BeautifulSoup

BASE = os.environ.get("KEYCLOAK_URL", "http://127.0.0.1:8080").rstrip("/")
ADMIN_USER = os.environ.get("KEYCLOAK_ADMIN", "admin")
ADMIN_PASSWORD = os.environ.get("KEYCLOAK_ADMIN_PASSWORD", "admin")
REALM = "hiltech"
CLIENT_ID = "hiltech-native"
USERNAME = "tech1"
PASSWORD = "Passw0rd!ForSpike"

ANDROID_REDIRECT = "com.hiltech.app:/oauth2redirect"
WINDOWS_REDIRECT = "http://127.0.0.1:53682/callback"


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def wait_for_keycloak():
    url = BASE + "/realms/master/.well-known/openid-configuration"
    deadline = time.time() + 90
    last_error = None

    while time.time() < deadline:
        try:
            response = requests.get(url, timeout=3)
            if response.status_code == 200:
                return
            last_error = f"HTTP {response.status_code}: {response.text[:300]}"
        except Exception as exc:
            last_error = repr(exc)
        time.sleep(1)

    raise RuntimeError(f"Keycloak did not become ready: {last_error}")


def admin_token():
    response = requests.post(
        BASE + "/realms/master/protocol/openid-connect/token",
        data={
            "grant_type": "password",
            "client_id": "admin-cli",
            "username": ADMIN_USER,
            "password": ADMIN_PASSWORD,
        },
        timeout=15,
    )
    response.raise_for_status()
    return response.json()["access_token"]


def admin_request(method, path, payload=None, expected=(200, 201, 204)):
    token = admin_token()
    response = requests.request(
        method,
        BASE + path,
        headers={
            "authorization": "Bearer " + token,
            "content-type": "application/json",
        },
        json=payload,
        timeout=20,
    )

    if response.status_code not in expected:
        raise RuntimeError(
            f"{method} {path} -> {response.status_code}: {response.text[:1000]}"
        )

    if response.content:
        return response.json()
    return None


def setup_realm():
    response = requests.get(
        BASE + "/admin/realms/" + REALM,
        headers={"authorization": "Bearer " + admin_token()},
        timeout=10,
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
                "name": "HILTECH Native Spike",
                "enabled": True,
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
                    "oauth2.device.authorization.grant.enabled": "false",
                },
            },
        )

    users = admin_request(
        "GET",
        f"/admin/realms/{REALM}/users?username={USERNAME}&exact=true",
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
                "emailVerified": True,
                "firstName": "HILTECH",
                "lastName": "Technician",
            },
        )
        users = admin_request(
            "GET",
            f"/admin/realms/{REALM}/users?username={USERNAME}&exact=true",
        )
        require(len(users) == 1, "Expected exactly one spike user")
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

    return user_id


def discovery():
    response = requests.get(
        BASE + f"/realms/{REALM}/.well-known/openid-configuration",
        timeout=10,
    )
    response.raise_for_status()
    return response.json()


def pkce_pair():
    verifier = secrets.token_urlsafe(64)
    challenge = base64.urlsafe_b64encode(
        hashlib.sha256(verifier.encode("ascii")).digest()
    ).rstrip(b"=").decode("ascii")
    return verifier, challenge


def authorization_url(redirect_uri, challenge, state, prompt=None):
    params = {
        "client_id": CLIENT_ID,
        "response_type": "code",
        "scope": "openid offline_access",
        "redirect_uri": redirect_uri,
        "code_challenge": challenge,
        "code_challenge_method": "S256",
        "state": state,
        "nonce": secrets.token_urlsafe(24),
    }
    if prompt:
        params["prompt"] = prompt

    return (
        BASE
        + f"/realms/{REALM}/protocol/openid-connect/auth?"
        + urlencode(params)
    )


def parse_login_form(response):
    soup = BeautifulSoup(response.text, "html.parser")
    form = soup.find("form", id="kc-form-login") or soup.find("form")

    if not form:
        return None, None

    action = html.unescape(form.get("action", ""))
    payload = {}

    for field in form.find_all("input"):
        name = field.get("name")
        if name:
            payload[name] = field.get("value", "")

    return action, payload


def submit_browser_login(session, auth_url, redirect_uri):
    response = session.get(auth_url, allow_redirects=False, timeout=15)
    require(response.status_code == 200, f"Expected login page, got {response.status_code}")

    # Keycloak 26.x marks auth cookies Secure even in local dev HTTP.
    # Real native apps use an HTTPS system browser. Python requests correctly
    # refuses to send Secure cookies over HTTP, so for this localhost-only
    # protocol spike we downgrade the cookie flag after receipt.
    if BASE.startswith("http://127.0.0.1") or BASE.startswith("http://localhost"):
        for cookie in session.cookies:
            cookie.secure = False

    action, payload = parse_login_form(response)
    require(action, "Keycloak login form not found")

    payload["username"] = USERNAME
    payload["password"] = PASSWORD
    payload.setdefault("credentialId", "")

    response = session.post(
        action,
        data=payload,
        allow_redirects=False,
        timeout=15,
    )

    location = response.headers.get("location")
    require(
        response.status_code in (302, 303) and location,
        f"Expected authorization redirect, got {response.status_code}: {response.text[:500]}",
    )
    require(
        location.startswith(redirect_uri),
        f"Unexpected redirect: {location}",
    )

    query = parse_qs(urlparse(location).query)
    require("code" in query, f"No authorization code in redirect: {location}")

    return query["code"][0]


def exchange_code(code, verifier, redirect_uri):
    response = requests.post(
        BASE + f"/realms/{REALM}/protocol/openid-connect/token",
        data={
            "grant_type": "authorization_code",
            "client_id": CLIENT_ID,
            "redirect_uri": redirect_uri,
            "code": code,
            "code_verifier": verifier,
        },
        timeout=15,
    )

    if response.status_code != 200:
        raise RuntimeError(
            f"Authorization-code exchange failed: {response.status_code} {response.text}"
        )

    return response.json()


def native_login(redirect_uri):
    verifier, challenge = pkce_pair()
    state = secrets.token_urlsafe(24)
    session = requests.Session()
    auth_url = authorization_url(
        redirect_uri=redirect_uri,
        challenge=challenge,
        state=state,
    )
    code = submit_browser_login(session, auth_url, redirect_uri)
    tokens = exchange_code(code, verifier, redirect_uri)
    return session, tokens


def refresh(refresh_token):
    return requests.post(
        BASE + f"/realms/{REALM}/protocol/openid-connect/token",
        data={
            "grant_type": "refresh_token",
            "client_id": CLIENT_ID,
            "refresh_token": refresh_token,
        },
        timeout=15,
    )


def logout(refresh_token):
    return requests.post(
        BASE + f"/realms/{REALM}/protocol/openid-connect/logout",
        data={
            "client_id": CLIENT_ID,
            "refresh_token": refresh_token,
        },
        timeout=15,
    )


def jwt_payload(token):
    body = token.split(".")[1]
    body += "=" * (-len(body) % 4)
    return json.loads(base64.urlsafe_b64decode(body.encode("ascii")))


def test_reauth(browser_session, redirect_uri):
    verifier, challenge = pkce_pair()
    state = secrets.token_urlsafe(24)

    # Existing Keycloak SSO session should skip the login form.
    normal = browser_session.get(
        authorization_url(redirect_uri, challenge, state),
        allow_redirects=False,
        timeout=15,
    )
    require(
        normal.status_code in (302, 303),
        f"Expected existing SSO session to redirect without login; got {normal.status_code}",
    )

    # Explicit re-auth must bring the browser back to a login interaction.
    verifier2, challenge2 = pkce_pair()
    forced = browser_session.get(
        authorization_url(
            redirect_uri,
            challenge2,
            secrets.token_urlsafe(24),
            prompt="login",
        ),
        allow_redirects=False,
        timeout=15,
    )

    require(
        forced.status_code == 200,
        f"prompt=login did not require re-auth; got {forced.status_code}",
    )
    action, _ = parse_login_form(forced)
    require(action, "prompt=login did not render a login form")


def test_remote_session_revoke(user_id, refresh_token):
    sessions = admin_request(
        "GET",
        f"/admin/realms/{REALM}/users/{user_id}/sessions",
    )
    require(sessions, "Expected at least one Keycloak user session")

    for session in sessions:
        admin_request(
            "DELETE",
            f"/admin/realms/{REALM}/sessions/{session['id']}",
        )

    response = refresh(refresh_token)
    require(
        response.status_code == 400,
        f"Refresh token still usable after admin session revoke: {response.status_code}",
    )


def test_webauthn_actions():
    actions = admin_request(
        "GET",
        f"/admin/realms/{REALM}/authentication/required-actions",
    )

    aliases = {item.get("alias") for item in actions}

    require(
        "webauthn-register" in aliases,
        f"WebAuthn registration action missing. aliases={sorted(a for a in aliases if a)}",
    )
    require(
        "webauthn-register-passwordless" in aliases,
        f"Passwordless WebAuthn action missing. aliases={sorted(a for a in aliases if a)}",
    )


def main():
    wait_for_keycloak()
    user_id = setup_realm()

    metadata = discovery()
    require(
        "authorization_code" in metadata.get("grant_types_supported", []),
        "authorization_code missing from discovery",
    )
    require(
        "S256" in metadata.get("code_challenge_methods_supported", []),
        "S256 PKCE missing from discovery",
    )

    # Android-style private-use URI scheme.
    android_browser, android_tokens = native_login(ANDROID_REDIRECT)
    android_payload = jwt_payload(android_tokens["access_token"])
    require(android_payload.get("preferred_username") == USERNAME, "Wrong Android token user")
    require(android_payload.get("azp") == CLIENT_ID, "Wrong Android token client")
    require("refresh_token" in android_tokens, "Android flow did not return refresh token")

    # Existing SSO vs explicit prompt=login re-auth.
    test_reauth(android_browser, ANDROID_REDIRECT)

    # Refresh works.
    refreshed = refresh(android_tokens["refresh_token"])
    require(refreshed.status_code == 200, f"Refresh failed: {refreshed.text}")
    refreshed_tokens = refreshed.json()

    # RP-initiated refresh-token logout kills the refresh session.
    out = logout(refreshed_tokens["refresh_token"])
    require(out.status_code in (204, 200), f"Logout failed: {out.status_code} {out.text}")

    post_logout_refresh = refresh(refreshed_tokens["refresh_token"])
    require(
        post_logout_refresh.status_code == 400,
        "Refresh token still worked after logout",
    )

    # Windows loopback native redirect.
    _, windows_tokens = native_login(WINDOWS_REDIRECT)
    windows_payload = jwt_payload(windows_tokens["access_token"])
    require(windows_payload.get("preferred_username") == USERNAME, "Wrong Windows token user")
    require(windows_payload.get("azp") == CLIENT_ID, "Wrong Windows token client")

    # Remote admin session revoke invalidates refresh capability.
    test_remote_session_revoke(user_id, windows_tokens["refresh_token"])

    # WebAuthn / passwordless registration path exists in this server line.
    test_webauthn_actions()

    clients = admin_request(
        "GET",
        f"/admin/realms/{REALM}/clients?clientId={CLIENT_ID}",
    )
    require(len(clients) == 1, "Native client not found")
    client = clients[0]
    require(client.get("publicClient") is True, "Native client is not public")
    require(client.get("standardFlowEnabled") is True, "Authorization code flow disabled")
    require(client.get("directAccessGrantsEnabled") is False, "Password grant enabled for native client")

    print(
        json.dumps(
            {
                "status": "PASS",
                "keycloak": "26.7.4",
                "client": CLIENT_ID,
                "public_client": True,
                "authorization_code_pkce": "S256",
                "android_redirect": ANDROID_REDIRECT,
                "windows_loopback_redirect": WINDOWS_REDIRECT,
                "refresh": "PASS",
                "logout_refresh_invalidation": "PASS",
                "admin_session_revoke": "PASS",
                "prompt_login_reauth": "PASS",
                "webauthn_register_action": "PASS",
                "webauthn_passwordless_action": "PASS",
            },
            indent=2,
        )
    )


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print("SPIKE-08 FAILED:", repr(exc), file=sys.stderr)
        raise
