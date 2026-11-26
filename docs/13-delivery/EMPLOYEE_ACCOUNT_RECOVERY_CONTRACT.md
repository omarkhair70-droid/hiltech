# Employee account access and credential recovery

Status: implementation contract; native device and deployed identity-provider acceptance must be recorded separately.

This extends account creation for existing and preboarding employees with two
bounded operations: link the administrator’s current unowned identity to their
employee record, and explicitly replace a current employee account’s password.
Both operations are mounted in the employee account workspace on the shared
native UI. They use the employee and identity sources already owned by People
and Identity.

## Current source and authority

Current People administration is required to inspect or change account access.
The command routes also require an active native session and recent
reauthentication. Neither operation assigns employment, company roles,
delegation ceilings or approval authority.

The account source requires an active identity, an active HILTECH organization,
and an active membership whose validity window includes the current instant.
Credential replacement additionally requires a PREBOARDING or ACTIVE employee
whose person owns that identity. The command pins employee, identity and
membership versions and the exact membership ID. History remains valid after
these source versions advance, while reading a command receipt still requires
current authorized access.

The account availability response describes current local source and configured
provider capability. A successful read is not evidence that sign-in with the
provider has been tested.

| Route | Purpose | Additional command inputs |
| --- | --- | --- |
| `GET /v1/employees/{employeeId}/account-access` | Current account, replacement availability, latest replacement metadata, named fixed-self link candidate | None |
| `POST /v1/employees/{employeeId}/own-account-link` | Attach the signed-in administrator’s unowned account to their employee record | Operation ID and employee/identity/membership snapshots |
| `POST /v1/employees/{employeeId}/credential-reissues` | Replace a current account’s password through the configured identity adapter | Operation ID, exact source snapshots, reason, latest replacement operation/version |

The `Idempotency-Key` header must match the command’s operation ID. Account
responses are `no-store`; credential responses additionally carry `Pragma:
no-cache`.

## Fixed-self account link

The native selector names the account already authenticated in the current
session. Its display label comes from the verified principal. The wire command
has no editable identity, subject, issuer or display-name target.

The administrator explicitly confirms that the selected employee record is
their own. The service derives the identity from the authenticated actor. It
requires an unowned identity, current company membership, an eligible employee,
no other account for the employee’s person, and no existing invitation. The
existing invitation serialization lock prevents creation and self-link from
claiming the same employee concurrently.

Linking updates the identity’s person association and employee version in the
existing idempotent command transaction. Employment state, onboarding state and
membership state are preserved. The normal identity-linked event refreshes
downstream source projections. A repeated successful operation returns its
receipt without a second link or event.

An identity already belonging to another person cannot be moved by this
command. When an existing authenticated identity already owns a person record,
employee creation must reuse that established person through an explicitly
supported creation path; the link operation does not merge people.

## Password replacement and uncertain outcomes

The user supplies a reason and confirms that the new password replaces the
previous password. Temporary password handoff is the bounded replacement
method. The existing configured Keycloak service client reads the exact user,
requires the returned user to be enabled and to match the subject, then invokes
the password-reset endpoint with a temporary password.

The adapter follows Keycloak’s documented
[user administration methods](https://www.keycloak.org/docs-api/latest/javadocs/org/keycloak/services/resources/admin/UserResource.html)
and [Admin REST API](https://www.keycloak.org/docs-api/latest/rest-api/).
The password is marked temporary so the employee chooses a replacement during
sign-in. Existing provider sessions and other authentication factors have their
own lifecycle; the password receipt does not claim those sessions were revoked.

V0120 stores a credential-reissue intent and outcome without credential material.
An identity-scoped advisory lock serializes replacement attempts. The local
dispatch intent is committed before the external password request. The same
operation is dispatched at most once, including after a process failure or lost
response.

| Recorded state | Meaning | Native recovery |
| --- | --- | --- |
| `DISPATCHING` | Intent exists; a terminal outcome was not recorded | Present as unconfirmed; same operation returns metadata without another provider call |
| `ISSUED` | The provider accepted the reset and the local outcome was recorded | First successful response may contain the temporary secret; replay contains metadata only |
| `REJECTED` | The adapter or source check rejected before password dispatch | Explain that no new password was issued; refresh current account state |
| `UNCONFIRMED` | Password dispatch was attempted but its outcome could not be confirmed | Preserve the result; allow a new explicitly confirmed replacement using the latest history version |

A post-dispatch change to People authority, employee eligibility or the exact
account source prevents secret delivery. The error remains an uncertain
command outcome rather than claiming the password was unchanged.

To recover a lost one-time secret, the user refreshes the source and confirms a
new operation. It must reference the latest replacement operation and version;
an old open form cannot silently replace a newer password. The new operation
replaces any password that may have been issued by the previous attempt. The
original intent and outcome remain immutable.

Credentials are absent from database records, idempotent receipts, audit diffs,
saved native state and object string representations. The native UI holds a
successful secret in transient memory, reveals it only through an explicit
action and clears it on handoff or leaving the employee workspace. A session
change disposes the workspace. The host receives credential visibility to
apply its native privacy behavior.

The replacement path never enables, creates or restores a restricted provider
account or company membership. Current source restriction requires its own
authorized lifecycle operation.

## Verification boundaries

The accompanying tests cover the source-authority and version checks, exact
operation replay, lost outcomes, explicit subsequent replacement, self-link
ownership and source preservation, required recent authentication, receipt
matching and transient-secret rules. PostgreSQL contracts exercise V0120 and
the real source tables in rollback-only synthetic fixtures. The Keycloak
protocol tests use a loopback HTTP fixture with synthetic identities and do not
contact a deployed provider.

### Real isolated provider contract

`EmployeeAccountKeycloakContractTest` and
`scripts/ci/phase1_employee_account_keycloak.py` extend the existing native OIDC
workflow with the production JVM provisioning/replacement adapter and an actual
Chromium authorization-code flow with PKCE. The test creates a random, marked
realm on the workflow's disposable HTTPS loopback Keycloak, with a dedicated
service client holding only its user-management roles. It uses no company
database, deployed account, SMTP delivery or existing realm.

The gate checks this sequence:

1. Creating an invitation's provider user leaves it disabled and without a
   password. Repeating the invitation recovers the same unique provider subject.
2. Temporary-password handoff enables that subject and requires a password
   change. Chromium completes the actual first sign-in, including the provider's
   profile form if required, and the production server verifier validates the
   returned ID token for the exact subject, issuer, client and nonce.
3. A replacement through the production adapter makes both the prior chosen
   password and the original temporary password fail fresh browser login. The
   new temporary password requires another successful password change.
4. Disabling the owned provider user causes credential replacement to reject.
   Its credential metadata and disabled state remain unchanged, and a fresh
   browser login fails.

The script requires the explicit test switch, an exact HTTPS `127.0.0.1` base,
an owned random realm and a private runner-temp directory. Cleanup checks the
realm's ownership marker before deletion. The workflow verifies one executed,
non-skipped JUnit test plus the JVM receipt; a skipped opt-in contract cannot be
reported as a pass. Passwords, tokens and browser authorization URLs are neither
printed nor uploaded. Temporary channel files and subprocess logs are removed
after the run. Browser certificate handling is limited to this disposable
fixture; provider API calls and the JVM verifier use its generated CA.

The provisioning adapter stores `hiltechProvisioningId` as a stable recovery
marker. A configured realm must retain and search this attribute and prevent
employees from editing it. The isolated fixture declares it as a managed
user-profile attribute with administrator-only view/edit permissions, while
preserving the provider's existing profile requirements. Silently discarding
this marker makes recovery unsafe; permitting arbitrary unmanaged employee
attributes is unnecessary. This requirement follows the provider's
[user-profile permission model](https://www.keycloak.org/docs/26.7.4/server_admin/)
and [profile and password administration API](https://www.keycloak.org/docs-api/26.7.4/rest-api/index.html).

This contract establishes provider interoperability for the pinned isolated
runtime. It does not establish native Windows/Android handoff, deployed
provider configuration, email delivery, current company responsibilities or
local employee transaction behavior; those retain their separate source and
device gates.

Production Compose renders and semantics can check Arabic/English interaction
and narrow/wide layouts with explicit mock responses. They cannot establish
Windows or Android acceptance against the deployed server. Final journey
evidence must include the actual account, device session, provider result,
first sign-in/password change, current employee responsibilities and restricted
account behavior in an approved test environment.
