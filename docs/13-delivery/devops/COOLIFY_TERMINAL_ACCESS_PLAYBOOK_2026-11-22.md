# Coolify Terminal Access — Official CLI + SSH split

Date: 2026-11-22. Owner explicitly requested a direct, repeatable terminal workflow rather than opening Coolify dashboards one project at a time.

## Verified, non-secret connection facts

- Windows authorized workstation online through Desktop Commander; Go v1.26.5 installed.
- Coolify URL: `https://coolify.158.101.254.30.sslip.io` (owner's self-hosted root team; v4.7.6 observed in its dashboard).
- From the Windows workstation, unauthenticated `GET /api/health` and `GET /api/v1/health` returned **HTTP 200 OK**.
- `GET /api/v1/projects` returned **HTTP 401** without credentials, as it should. **This is not authenticated API access**.
- Coolify CLI **v1.11.0 installed and verified** on the authorized Windows workstation at `%LOCALAPPDATA%\\Programs\\CoolifyCLI\\coolify.exe`; downloaded official GitHub release ZIP and checksums; independent SHA-256 match verified with .NET before extraction. Its folder was appended to Windows **user** PATH for new PowerShell sessions. The optional Go-source build failed on that workstation; the verified release binary is working.
- An already authorized owner Windows SSH route to Oracle VM had previously worked for read-only host/Docker observations, but a subsequent attempted Docker SSH inventory was **blocked by the remote-execution safety gate**. Do not bypass that block.
- GUI owner session identified `localhost` plus `teswa-core01` (Tailscale-attached) and real project inventory. Terminal tokens are **not** browser cookies or SSH keys.

## Correct architecture: two independently authorized interfaces

**Coolify CLI** `coolify` -> Coolify self-hosted HTTPS REST API `/api/v1` -> project/app/service/database/server configuration, permitted status, backup and deployment actions.

**SSH** -> authorized Oracle OS host -> `df`, `free`, process and Docker health/usage, subject to the existing host access and tool security policy. OCI account-level quotas/billing require **separate OCI API/Console access**, not Coolify.

This avoids privileged DB inspection, stealing browser cookies, reusing another app's database credentials, and confusing the Coolify control plane with the underlying OCI tenancy.

## Coolify official CLI bootstrap — completed on owner's Windows PC

Prefer the documented first-party `github.com/coollabsio/coolify-cli/coolify` CLI. Install on the workstation, **not** the already-constrained Oracle VM:

```powershell
go install github.com/coollabsio/coolify-cli/coolify@latest
& "$HOME\go\bin\coolify.exe" version
& "$HOME\go\bin\coolify.exe" --help
```

Official references:
- https://coolify.io/docs/cli/installation
- https://coolify.io/docs/cli/authentication
- https://coolify.io/docs/cli/contexts
- https://coolify.io/docs/api/authorization
- https://coolify.io/docs/core/security/credentials/api-tokens

**Critical one-time authentication gate:** Owner must authorize a Coolify API token in **Keys & Tokens > API Tokens**, for the correct active team, with **read-only permissions**, **All team resources** only if cross-project and server inventory is needed, short expiry, no `write`, `delete`, `read:sensitive`, or `root`. On self-hosted Coolify the authorized admin must confirm API Access and optional allowed source IP in Settings; do not silently alter that setting. Token is a password. **Never enter it into a chat, public issue, shell command transcript, or GitHub file.**

Set one context locally only after secure token creation, following official context documentation. Note that Coolify CLI contexts **store tokens in a local config file**; restrict it to the user and never read/log/show it in assistant tool output. Prefer a local interactive terminal for this action. CLI itself supports `coolify context add <name> <url> <token>`.

Then verify the **read-only** inventory (without sensitive flags):

```powershell
coolify context verify
coolify server list
coolify project list
coolify resource list
coolify app list
coolify database list
coolify service list
```

Only call CLI commands appropriate to the **actual token scope**. A token limited to selected projects cannot call team-level `server list`; a read-only team-scoped token is necessary for the desired whole-account inventory.

## Deployment and editing are a **separate step**

The owner wants eventual installation/deployment/changes from the terminal. Use a **separate deploy/workflow token**, scoped **only to the new HILTECH project**, with the minimum write/deploy rights and independent expiry. Never use a root/all-resources token to automate changes. All actual spending, restart, stop, deletion, env/secret updates, firewall edits, VM provisioning, backup location or production activation require a clearly named reviewed plan and exact-resource owner approval. No assistant-created token grants itself the business authority to stop another project.

Read-only command success does not establish database durability, backups/restore, native Android/Windows E2E, health #137 or production readiness.

## Current checkpoint

`COOLIFY_GUI_AUTHENTICATED=YES` (owner Chrome session)  
`COOLIFY_HTTP_HEALTH=PASS`  
`COOLIFY_CLI_INSTALLED=YES (v1.11.0, upstream release SHA256 verified)`  
`COOLIFY_CLI_CONTEXT=hiltech-oci (default, correct self-hosted HTTPS URL)`  
`COOLIFY_API_READ_TOKEN=NOT_CONFIGURED`  
`COOLIFY_API_AUTH_VERIFIED=NO (coolify context verify returned 401 Unauthenticated, expected with no token)`  
`COOLIFY_SERVICES_MODIFIED=NO`  
`HILTECH_PILOT_DEPLOYED=NO`
