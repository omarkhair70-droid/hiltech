#!/usr/bin/env python3
"""Real account-provider contract on an owned disposable loopback Keycloak realm.

No mail, company database, existing realm or external provider is used. Secrets
stay in memory or a mode-0600 runner-temp channel, never stdout or artifacts.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import pathlib
import re
import secrets
import shutil
import stat
import subprocess
import threading
import time
import uuid
import xml.etree.ElementTree as ET
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import parse_qs, urlencode, urlparse

OWNER_ATTRIBUTE = "hiltechAccountContractOwner"
REALM_PREFIX = "hiltech-account-contract-"
TEST_CLASS = "com.hiltech.server.identity.EmployeeAccountKeycloakContractTest"
NATIVE_CLIENT = "hiltech-account-native"
ADMIN_CLIENT = "hiltech-account-provisioner"
STAGE = "safety"


class ContractError(RuntimeError):
    pass


def require(condition: bool, code: str) -> None:
    if not condition:
        raise ContractError(code)


def loopback_base(value: str) -> str:
    value = value.rstrip("/")
    parsed = urlparse(value)
    require(parsed.scheme == "https" and parsed.hostname == "127.0.0.1"
            and parsed.port is not None and 0 < parsed.port < 65536
            and not parsed.path and not parsed.query and not parsed.fragment
            and not parsed.username and not parsed.password, "NON_DISPOSABLE_PROVIDER")
    return value


def work_directory() -> pathlib.Path:
    require(os.environ.get("HILTECH_ACCOUNT_KEYCLOAK_CONTRACT_TEST") == "1", "OPT_IN_REQUIRED")
    value = os.environ.get("HILTECH_ACCOUNT_KEYCLOAK_CONTRACT_DIR", "")
    require(bool(value), "PRIVATE_DIRECTORY_REQUIRED")
    path = pathlib.Path(value).absolute()
    require(path.name == "hiltech-account-provider-contract" and not path.is_symlink(), "UNOWNED_DIRECTORY")
    return path


def private_json(path: pathlib.Path, value: dict) -> None:
    temp = path.parent / ("private-" + uuid.uuid4().hex + ".tmp")
    descriptor = os.open(temp, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as output:
            json.dump(value, output)
        os.replace(temp, path)
    finally:
        temp.unlink(missing_ok=True)


def read_private(path: pathlib.Path) -> dict:
    require(path.is_file() and not path.is_symlink()
            and stat.S_IMODE(path.stat().st_mode) == 0o600, "PRIVATE_FILE_REQUIRED")
    return json.loads(path.read_text(encoding="utf-8"))


def fixture_in(directory: pathlib.Path) -> dict:
    require(directory.is_dir() and not directory.is_symlink()
            and stat.S_IMODE(directory.stat().st_mode) == 0o700, "PRIVATE_DIRECTORY_REQUIRED")
    fixture = read_private(directory / "fixture.json")
    owner = fixture.get("owner", "")
    require(re.fullmatch(r"[a-f0-9]{32}", owner) is not None, "INVALID_OWNERSHIP")
    require(fixture.get("realm") == REALM_PREFIX + owner, "UNOWNED_REALM")
    base = loopback_base(fixture.get("base", ""))
    require(base == loopback_base(os.environ["KEYCLOAK_URL"]), "PROVIDER_CHANGED")
    require(fixture.get("issuer") == base + "/realms/" + fixture["realm"], "ISSUER_CHANGED")
    return fixture


class OwnedRealm:
    def __init__(self, fixture: dict):
        import requests

        self.fixture = fixture
        self.base = loopback_base(fixture["base"])
        self.realm = fixture["realm"]
        require(self.realm == REALM_PREFIX + fixture["owner"], "UNOWNED_REALM")
        self.path = "/admin/realms/" + self.realm
        self.http = requests.Session()
        self.http.trust_env = False
        ca = pathlib.Path(os.environ["REQUESTS_CA_BUNDLE"])
        require(ca.is_file(), "DISPOSABLE_CA_REQUIRED")
        self.http.verify = str(ca)

    def request(self, method: str, path: str, *, payload=None, data=None, token=None, expected=(200, 201, 204)):
        headers = {"Authorization": "Bearer " + token} if token else {}
        response = self.http.request(method, self.base + path, json=payload, data=data,
                                     headers=headers, timeout=15, allow_redirects=False)
        require(response.status_code in expected, "PROVIDER_HTTP_" + str(response.status_code))
        return response

    def token(self) -> str:
        response = self.request("POST", "/realms/master/protocol/openid-connect/token", data={
            "grant_type": "password", "client_id": "admin-cli",
            "username": os.environ["KEYCLOAK_ADMIN"], "password": os.environ["KEYCLOAK_ADMIN_PASSWORD"],
        })
        return response.json()["access_token"]

    def admin(self, method: str, suffix: str = "", *, payload=None, expected=(200, 201, 204)):
        require(not suffix or suffix.startswith("/") or suffix.startswith("?"), "INVALID_OWNED_PATH")
        return self.request(method, self.path + suffix, payload=payload, token=self.token(), expected=expected)

    def check_owner(self) -> bool:
        response = self.admin("GET", expected=(200, 404))
        if response.status_code == 404:
            return False
        require(response.json().get("attributes", {}).get(OWNER_ATTRIBUTE) == self.fixture["owner"], "REALM_OWNER_MISMATCH")
        return True

    def user(self, subject: str) -> dict:
        require(re.fullmatch(r"[a-f0-9-]{36}", subject) is not None, "INVALID_OWNED_SUBJECT")
        user = self.admin("GET", "/users/" + subject).json()
        require(user.get("attributes", {}).get("hiltechProvisioningId") == [self.fixture["invitationId"]], "USER_OWNER_MISMATCH")
        require(user.get("username") == self.fixture["login"], "USER_LOGIN_MISMATCH")
        return user


def setup(directory: pathlib.Path) -> dict:
    global STAGE
    require(not directory.exists(), "FIXTURE_ALREADY_EXISTS")
    directory.mkdir(mode=0o700, parents=False)
    owner = uuid.uuid4().hex
    base = loopback_base(os.environ["KEYCLOAK_URL"])
    fixture = {
        "owner": owner, "base": base, "realm": REALM_PREFIX + owner,
        "issuer": base + "/realms/" + REALM_PREFIX + owner,
        "nativeClientId": NATIVE_CLIENT, "adminClientId": ADMIN_CLIENT,
        "adminClientSecret": secrets.token_urlsafe(36), "invitationId": str(uuid.uuid4()),
        "login": "employee-" + owner[:16],
    }
    private_json(directory / "fixture.json", fixture)
    realm = OwnedRealm(fixture)
    require(realm.admin("GET", expected=(200, 404)).status_code == 404, "REALM_ALREADY_EXISTS")
    realm.request("POST", "/admin/realms", token=realm.token(), payload={
        "realm": fixture["realm"], "enabled": True, "registrationAllowed": False,
        "resetPasswordAllowed": False, "rememberMe": False, "verifyEmail": False,
        "bruteForceProtected": False, "eventsEnabled": False, "adminEventsEnabled": False,
        "attributes": {OWNER_ATTRIBUTE: owner},
    })
    require(realm.check_owner(), "OWNED_REALM_MISSING")
    STAGE = "owned-user-profile"
    profile = realm.admin("GET", "/users/profile").json()
    profile.setdefault("attributes", []).append({
        "name": "hiltechProvisioningId", "displayName": "Provisioning reference",
        "permissions": {"view": ["admin"], "edit": ["admin"]},
        "validations": {"length": {"min": 36, "max": 36}}, "multivalued": False,
    })
    realm.admin("PUT", "/users/profile", payload=profile)
    STAGE = "owned-native-client"
    realm.admin("POST", "/clients", payload={
        "clientId": NATIVE_CLIENT, "protocol": "openid-connect", "enabled": True,
        "publicClient": True, "standardFlowEnabled": True, "directAccessGrantsEnabled": False,
        "serviceAccountsEnabled": False, "consentRequired": False,
        "redirectUris": ["http://127.0.0.1:*"], "webOrigins": [],
        "attributes": {"pkce.code.challenge.method": "S256"},
    })
    STAGE = "owned-service-client"
    realm.admin("POST", "/clients", payload={
        "clientId": ADMIN_CLIENT, "protocol": "openid-connect", "enabled": True,
        "publicClient": False, "standardFlowEnabled": False, "directAccessGrantsEnabled": False,
        "serviceAccountsEnabled": True, "secret": fixture["adminClientSecret"],
        "fullScopeAllowed": True,
    })
    clients = realm.admin("GET", "/clients?clientId=" + ADMIN_CLIENT).json()
    require(len(clients) == 1, "SERVICE_CLIENT_MISSING")
    service = realm.admin("GET", "/clients/" + clients[0]["id"] + "/service-account-user").json()
    STAGE = "owned-service-authority"
    management = realm.admin("GET", "/clients?clientId=realm-management").json()
    require(len(management) == 1, "REALM_MANAGEMENT_MISSING")
    management_id = management[0]["id"]
    roles = [realm.admin("GET", "/clients/" + management_id + "/roles/" + name).json()
             for name in ("manage-users", "view-users", "query-users")]
    realm.admin("POST", "/users/" + service["id"] + "/role-mappings/clients/" + management_id, payload=roles)
    return fixture


def cleanup(directory: pathlib.Path) -> None:
    if not directory.exists():
        return
    fixture = fixture_in(directory)
    realm = OwnedRealm(fixture)
    try:
        if realm.check_owner():
            realm.admin("DELETE")
            require(not realm.check_owner(), "OWNED_REALM_CLEANUP_FAILED")
    finally:
        # Only the exact private directory whose fixture ownership was validated.
        shutil.rmtree(directory)


class Callback:
    def __init__(self, state: str, issuer: str):
        self.received: dict[str, list[str]] | None = None
        parent = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, _format, *_args):
                pass  # The callback URL contains a short-lived authorization code.

            def do_GET(self):
                parsed = urlparse(self.path)
                query = parse_qs(parsed.query)
                valid = (parsed.path == "/oidc/callback" and query.get("state") == [state]
                         and query.get("iss") == [issuer] and len(query.get("code", [])) == 1
                         and "error" not in query and parent.received is None)
                if valid:
                    parent.received = query
                body = ("Account contract authentication received" if valid else "Invalid account contract callback").encode()
                self.send_response(200 if valid else 400)
                self.send_header("Content-Type", "text/plain; charset=utf-8")
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                self.wfile.write(body)

        self.server = HTTPServer(("127.0.0.1", 0), Handler)
        self.redirect = "http://127.0.0.1:" + str(self.server.server_port) + "/oidc/callback"
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *_args):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)


def browser_login(playwright, realm: OwnedRealm, password: str, *, new_password: str | None = None,
                  reject: str | None = None) -> dict:
    state, nonce, verifier = (secrets.token_urlsafe(32) for _ in range(3))
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip("=")
    with Callback(state, realm.fixture["issuer"]) as callback:
        browser = playwright.chromium.launch(headless=True)
        context = browser.new_context(ignore_https_errors=True, locale="en-US")
        page = context.new_page()
        page.set_default_timeout(20_000)
        try:
            page.goto(realm.fixture["issuer"] + "/protocol/openid-connect/auth?" + urlencode({
                "response_type": "code", "client_id": NATIVE_CLIENT, "redirect_uri": callback.redirect,
                "scope": "openid profile email", "state": state, "nonce": nonce,
                "code_challenge": challenge, "code_challenge_method": "S256", "prompt": "login",
            }), wait_until="domcontentloaded")
            page.locator("#username").fill(realm.fixture["login"])
            page.locator("#password").fill(password)
            page.locator("#kc-login").click()
            if reject:
                message = "Invalid username or password" if reject == "old-password" else "Account is disabled"
                page.get_by_text(message, exact=False).first.wait_for(state="visible")
                require(callback.received is None, "REJECTED_PASSWORD_AUTHENTICATED")
                require(not page.locator("#password-new").is_visible(), "REJECTED_PASSWORD_REACHED_CHANGE")
                return {"rejected": True}

            updated_password = False
            updated_profile = False
            deadline = time.monotonic() + 35
            while time.monotonic() < deadline and callback.received is None:
                require(page.locator('input[name="hiltechProvisioningId"]').count() == 0, "MARKER_EXPOSED_TO_EMPLOYEE")
                if page.locator("#password-new").is_visible():
                    require(new_password is not None and not updated_password, "UNEXPECTED_PASSWORD_CHALLENGE")
                    page.locator("#password-new").fill(new_password)
                    page.locator("#password-confirm").fill(new_password)
                    page.locator('input[type="submit"], button[type="submit"]').first.click()
                    updated_password = True
                elif page.locator("#kc-update-profile-form").is_visible():
                    require(not updated_profile, "REPEATED_PROFILE_CHALLENGE")
                    for name, value in (("firstName", "Synthetic"), ("lastName", "Contract"),
                                        ("email", realm.fixture["login"] + "@example.test")):
                        field = page.locator('input[name="' + name + '"]')
                        if field.is_visible() and not field.input_value():
                            field.fill(value)
                    page.locator('#kc-update-profile-form input[type="submit"], #kc-update-profile-form button[type="submit"]').first.click()
                    updated_profile = True
                else:
                    page.wait_for_timeout(100)
            require(callback.received is not None, "BROWSER_CALLBACK_MISSING")
            require(updated_password == (new_password is not None), "TEMPORARY_PASSWORD_CHANGE_NOT_ENFORCED")
            response = realm.request("POST", "/realms/" + realm.realm + "/protocol/openid-connect/token", data={
                "grant_type": "authorization_code", "client_id": NATIVE_CLIENT,
                "redirect_uri": callback.redirect, "code": callback.received["code"][0], "code_verifier": verifier,
            })
            token = response.json().get("id_token")
            require(isinstance(token, str) and bool(token), "ID_TOKEN_MISSING")
            return {"idToken": token, "nonce": nonce, "profileCompleted": updated_profile}
        finally:
            context.close()
            browser.close()


def wait_stage(directory: pathlib.Path, fixture: dict, process, stage: str) -> dict:
    deadline = time.monotonic() + 100
    while time.monotonic() < deadline:
        if (directory / "jvm-failure.json").is_file():
            failure = read_private(directory / "jvm-failure.json")
            code = failure.get("code", "PROVIDER_CONTRACT_ASSERTION")
            require(isinstance(code, str) and re.fullmatch(r"[A-Z0-9_]{1,80}", code) is not None,
                    "JVM_PROVIDER_STAGE_FAILED")
            raise ContractError(code)
        path = directory / ("jvm-" + stage + ".json")
        if path.is_file():
            message = read_private(path)
            require(message.get("owner") == fixture["owner"], "JVM_CHANNEL_OWNER_MISMATCH")
            return message
        require(process.poll() is None, "JVM_PROVIDER_EXITED_EARLY")
        time.sleep(0.1)
    raise ContractError("JVM_PROVIDER_STAGE_TIMED_OUT")


def acknowledge(directory: pathlib.Path, fixture: dict, stage: str, **values) -> None:
    private_json(directory / ("browser-" + stage + ".json"), {"owner": fixture["owner"], "stage": stage, **values})


def drive(directory: pathlib.Path, fixture: dict, process) -> None:
    from playwright.sync_api import sync_playwright

    global STAGE
    realm = OwnedRealm(fixture)
    with sync_playwright() as playwright:
        STAGE = "created-disabled-and-recovered"
        created = wait_stage(directory, fixture, process, "created")
        subject = created["subject"]
        user = realm.user(subject)
        require(user.get("enabled") is False, "CREATED_USER_NOT_DISABLED")
        require(realm.admin("GET", "/users/" + subject + "/credentials").json() == [], "DISABLED_USER_HAS_CREDENTIAL")
        matches = realm.admin("GET", "/users?" + urlencode({"q": "hiltechProvisioningId:" + fixture["invitationId"]})).json()
        require(len(matches) == 1 and matches[0]["id"] == subject, "PROVISIONING_RECOVERY_NOT_UNIQUE")
        acknowledge(directory, fixture, "created")

        STAGE = "first-temporary-password-login"
        delivered = wait_stage(directory, fixture, process, "delivered")
        require(delivered.get("subject") == subject, "DELIVERY_SUBJECT_CHANGED")
        user = realm.user(subject)
        require(user.get("enabled") is True and "UPDATE_PASSWORD" in user.get("requiredActions", []), "DELIVERY_NOT_TEMPORARY")
        first_permanent = "Account-Contract!" + secrets.token_urlsafe(24)
        result = browser_login(playwright, realm, delivered["temporaryCredential"], new_password=first_permanent)
        require("UPDATE_PASSWORD" not in realm.user(subject).get("requiredActions", []), "FIRST_PASSWORD_CHANGE_INCOMPLETE")
        acknowledge(directory, fixture, "delivered", **result)

        STAGE = "replacement-invalidates-old-password"
        replaced = wait_stage(directory, fixture, process, "replaced")
        require(replaced.get("subject") == subject, "REPLACEMENT_SUBJECT_CHANGED")
        browser_login(playwright, realm, first_permanent, reject="old-password")
        browser_login(playwright, realm, delivered["temporaryCredential"], reject="old-password")
        STAGE = "replacement-temporary-password-login"
        second_permanent = "Account-Contract!" + secrets.token_urlsafe(24)
        result = browser_login(playwright, realm, replaced["temporaryCredential"], new_password=second_permanent)
        require("UPDATE_PASSWORD" not in realm.user(subject).get("requiredActions", []), "REPLACEMENT_PASSWORD_CHANGE_INCOMPLETE")
        acknowledge(directory, fixture, "replaced", **result)

        STAGE = "disable-owned-provider-user"
        require(wait_stage(directory, fixture, process, "disable").get("subject") == subject, "DISABLE_SUBJECT_CHANGED")
        realm.user(subject)
        credentials = realm.admin("GET", "/users/" + subject + "/credentials").json()
        realm.admin("PUT", "/users/" + subject, payload={"enabled": False})
        acknowledge(directory, fixture, "disable")

        STAGE = "disabled-account-preserved"
        require(wait_stage(directory, fixture, process, "restricted").get("subject") == subject, "RESTRICTED_SUBJECT_CHANGED")
        require(realm.user(subject).get("enabled") is False, "DISABLED_USER_WAS_REENABLED")
        require(realm.admin("GET", "/users/" + subject + "/credentials").json() == credentials, "DISABLED_PASSWORD_WAS_CHANGED")
        browser_login(playwright, realm, second_permanent, reject="disabled")
        acknowledge(directory, fixture, "restricted")


def run(directory: pathlib.Path) -> None:
    global STAGE
    STAGE = "owned-realm-setup"
    fixture = setup(directory)
    project = pathlib.Path(__file__).resolve().parents[2]
    report = project / "server/application/build/test-results/test" / ("TEST-" + TEST_CLASS + ".xml")
    started = time.time()
    descriptor = os.open(directory / "private-jvm.log", os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as log:
        process = subprocess.Popen([str(project / "gradlew"), ":server:application:test", "--rerun",
                                    "--tests", TEST_CLASS, "--console=plain"],
                                   cwd=project, stdout=log, stderr=subprocess.STDOUT)
        try:
            drive(directory, fixture, process)
            STAGE = "junit-completion"
            require(process.wait(timeout=60) == 0, "JVM_PROVIDER_TEST_FAILED")
            require(report.is_file() and report.stat().st_mtime >= started, "JUNIT_REPORT_MISSING")
            suite = ET.parse(report).getroot()
            require(suite.get("tests") == "1" and all(suite.get(key, "0") == "0"
                    for key in ("failures", "errors", "skipped")), "JUNIT_PROVIDER_CONTRACT_NOT_EXECUTED")
            require(read_private(directory / "jvm-pass.json").get("passed") is True, "JVM_PASS_RECEIPT_MISSING")
        finally:
            if process.poll() is None:
                private_json(directory / "browser-failure.json", {"owner": fixture["owner"], "stage": STAGE})
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)


def main() -> int:
    global STAGE
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("run", "cleanup"))
    args = parser.parse_args()
    directory = None
    passed = False
    try:
        directory = work_directory()
        if args.mode == "run":
            run(directory)
        passed = True
    except Exception as failure:
        # Never print raw browser/HTTP exceptions, response bodies, fixture JSON,
        # subprocess output or authorization URLs. Codes here are fixed labels.
        code = str(failure) if isinstance(failure, ContractError) else type(failure).__name__
        print("HILTECH_ACCOUNT_PROVIDER_CONTRACT_FAIL stage=" + STAGE + " code=" + code)
    finally:
        if directory is not None:
            try:
                cleanup(directory)
            except Exception as failure:
                passed = False
                code = str(failure) if isinstance(failure, ContractError) else type(failure).__name__
                print("HILTECH_ACCOUNT_PROVIDER_CLEANUP_FAIL code=" + code)
    if passed:
        print("HILTECH_ACCOUNT_PROVIDER_" + ("CONTRACT_PASS" if args.mode == "run" else "CLEANUP_PASS"))
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
