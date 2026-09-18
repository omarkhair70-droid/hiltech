#!/usr/bin/env python3
import importlib.util
import json
import pathlib
import secrets
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from queue import Empty, Queue
from urllib.parse import parse_qs, urlparse

from playwright.sync_api import sync_playwright


CORE_PATH = pathlib.Path(__file__).with_name("test_native_oidc.py")
SPEC = importlib.util.spec_from_file_location("hiltech_oidc_core", CORE_PATH)
core = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(core)


class LoopbackCallback:
    def __init__(self):
        self.events = Queue()
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                parsed = urlparse(self.path)

                if parsed.path != "/callback":
                    self.send_response(404)
                    self.end_headers()
                    return

                outer.events.put(parse_qs(parsed.query))

                body = (
                    "<!doctype html><html><body>"
                    "<h1>HILTECH authentication received</h1>"
                    "<p>You can return to the app.</p>"
                    "</body></html>"
                ).encode("utf-8")

                self.send_response(200)
                self.send_header("content-type", "text/html; charset=utf-8")
                self.send_header("content-length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, format, *args):
                return

        self.server = ThreadingHTTPServer(
            ("127.0.0.1", 53682),
            Handler,
        )
        self.thread = threading.Thread(
            target=self.server.serve_forever,
            name="hiltech-loopback-callback",
            daemon=True,
        )

    def start(self):
        self.thread.start()

    def next_query(self, timeout=20):
        try:
            return self.events.get(timeout=timeout)
        except Empty as exc:
            raise AssertionError(
                "Timed out waiting for Windows native loopback callback"
            ) from exc

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)


def expect_callback(callback, expected_state):
    query = callback.next_query()

    core.require(
        query.get("state", [None])[0] == expected_state,
        "Loopback callback state mismatch",
    )
    core.require(
        "error" not in query,
        "OIDC callback returned error: " + repr(query),
    )
    core.require(
        "code" in query,
        "Loopback callback did not contain authorization code",
    )

    return query["code"][0]


def windows_browser_flow():
    callback = LoopbackCallback()
    callback.start()

    try:
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(headless=True)
            context = browser.new_context(ignore_https_errors=True)
            page = context.new_page()

            # First Windows native login with Authorization Code + PKCE.
            verifier, challenge = core.pkce_pair()
            state = secrets.token_urlsafe(24)
            first_url = core.authorization_url(
                core.WINDOWS_REDIRECT,
                challenge,
                state,
            )

            page.goto(first_url, wait_until="domcontentloaded")
            page.locator("#username").fill(core.USERNAME)
            page.locator("#password").fill(core.PASSWORD)
            page.locator("#kc-login").click()

            first_code = expect_callback(callback, state)
            windows_tokens = core.exchange_code(
                first_code,
                verifier,
                core.WINDOWS_REDIRECT,
            )

            payload = core.jwt_payload(windows_tokens["access_token"])
            core.require(
                payload.get("preferred_username") == core.USERNAME,
                "Wrong Windows token user",
            )
            core.require(
                payload.get("azp") == core.CLIENT_ID,
                "Wrong Windows token client",
            )
            core.require(
                "refresh_token" in windows_tokens,
                "Windows flow did not return refresh token",
            )

            cookie_snapshot = [
                {
                    "name": cookie["name"],
                    "domain": cookie["domain"],
                    "path": cookie["path"],
                    "secure": cookie["secure"],
                    "sameSite": cookie["sameSite"],
                }
                for cookie in context.cookies()
            ]
            print(
                "SPIKE-08 BROWSER COOKIES AFTER LOGIN " +
                json.dumps(cookie_snapshot, sort_keys=True)
            )

            # Second authorization in the SAME browser context must reuse SSO.
            verifier2, challenge2 = core.pkce_pair()
            state2 = secrets.token_urlsafe(24)
            second_url = core.authorization_url(
                core.WINDOWS_REDIRECT,
                challenge2,
                state2,
            )

            page.goto(second_url, wait_until="domcontentloaded")

            if not page.url.startswith(core.WINDOWS_REDIRECT):
                print(
                    "SPIKE-08 SECOND AUTH DEBUG " +
                    "url=" + page.url +
                    " title=" + page.title() +
                    " usernameVisible=" +
                    str(page.locator("#username").is_visible())
                )
                print(
                    "SPIKE-08 SECOND AUTH COOKIES " +
                    json.dumps(context.cookies(), sort_keys=True)
                )

            second_code = expect_callback(callback, state2)
            sso_tokens = core.exchange_code(
                second_code,
                verifier2,
                core.WINDOWS_REDIRECT,
            )

            sso_payload = core.jwt_payload(sso_tokens["access_token"])
            core.require(
                sso_payload.get("preferred_username") == core.USERNAME,
                "SSO authorization returned wrong user",
            )

            # Explicit re-auth must show an interactive login even with SSO.
            _, challenge3 = core.pkce_pair()
            state3 = secrets.token_urlsafe(24)
            reauth_url = core.authorization_url(
                core.WINDOWS_REDIRECT,
                challenge3,
                state3,
                prompt="login",
            )

            page.goto(reauth_url, wait_until="domcontentloaded")
            core.require(
                page.locator("#username").is_visible(),
                "prompt=login did not render username field",
            )
            core.require(
                page.locator("#password").is_visible(),
                "prompt=login did not render password field",
            )

            print(
                "SPIKE-08 BROWSER PASS "
                "windows_pkce=PASS sso=PASS prompt_login_reauth=PASS"
            )

            context.close()
            browser.close()
            return windows_tokens
    finally:
        callback.close()


def main():
    core.wait_for_keycloak()
    user_id = core.setup_realm()

    metadata = core.discovery()
    core.require(
        "authorization_code" in metadata.get("grant_types_supported", []),
        "authorization_code missing from discovery",
    )
    core.require(
        "S256" in metadata.get("code_challenge_methods_supported", []),
        "S256 PKCE missing from discovery",
    )

    # Android-style private-use URI + PKCE.
    _, android_tokens = core.native_login(core.ANDROID_REDIRECT)
    android_payload = core.jwt_payload(android_tokens["access_token"])
    core.require(
        android_payload.get("preferred_username") == core.USERNAME,
        "Wrong Android token user",
    )
    core.require(
        android_payload.get("azp") == core.CLIENT_ID,
        "Wrong Android token client",
    )
    core.require(
        "refresh_token" in android_tokens,
        "Android flow did not return refresh token",
    )

    # Refresh + explicit logout.
    refreshed = core.refresh(android_tokens["refresh_token"])
    core.require(
        refreshed.status_code == 200,
        "Android refresh failed: " + refreshed.text,
    )
    refreshed_tokens = refreshed.json()

    logout = core.logout(refreshed_tokens["refresh_token"])
    core.require(
        logout.status_code in (200, 204),
        "Logout failed: " + str(logout.status_code),
    )
    core.require(
        core.refresh(refreshed_tokens["refresh_token"]).status_code == 400,
        "Refresh token still worked after logout",
    )

    # Real Chromium proves Windows loopback, SSO and re-auth.
    windows_tokens = windows_browser_flow()

    # Remote admin revoke must invalidate the native refresh session.
    core.test_remote_session_revoke(
        user_id,
        windows_tokens["refresh_token"],
    )

    # WebAuthn/passkey registration paths must exist.
    core.test_webauthn_actions()

    clients = core.admin_request(
        "GET",
        f"/admin/realms/{core.REALM}/clients?clientId={core.CLIENT_ID}",
    )
    core.require(len(clients) == 1, "Native client not found")
    client = clients[0]

    core.require(client.get("publicClient") is True, "Native client is not public")
    core.require(
        client.get("standardFlowEnabled") is True,
        "Authorization code flow disabled",
    )
    core.require(
        client.get("directAccessGrantsEnabled") is False,
        "Password grant enabled for native client",
    )

    print(
        json.dumps(
            {
                "status": "PASS",
                "keycloak": "26.7.4",
                "client": core.CLIENT_ID,
                "authorization_code_pkce": "S256",
                "android_private_use_redirect": "PASS",
                "windows_loopback_redirect": "PASS",
                "windows_browser_sso": "PASS",
                "prompt_login_reauth": "PASS",
                "refresh": "PASS",
                "logout_refresh_invalidation": "PASS",
                "admin_session_revoke": "PASS",
                "webauthn_register_action": "PASS",
                "webauthn_passwordless_action": "PASS",
                "direct_password_grant_for_native_client": "DISABLED",
            },
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
