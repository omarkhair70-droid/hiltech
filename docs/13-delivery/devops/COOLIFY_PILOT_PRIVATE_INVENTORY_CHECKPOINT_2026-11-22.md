# HILTECH — Coolify pilot private inventory checkpoint

Date: 2026-11-22  
State: **READ-ONLY INVENTORY TOOL ADDED / LIVE CLASSIFICATION PENDING / NO DEPLOY, NO STOP, NO PRUNE**

## Why

The owner wants a limited *real* internal HILTECH pilot for leadership, accounts, a few engineers/technicians and a warehouse operator on the existing Oracle + Coolify environment, if safe. Other projects must survive. Possible leftover previews (e.g. branches named `pr-240`, `pr-241`, `pr-253`) and the owner's possible old thermography app **may not be deleted based on a name or user uncertainty**.

We already measured the actual existing OCI VM through an authorized owner connection (see `COOLIFY_HOST_AUDIT_AND_STAGING_GATE_2026-11-22.md`): 1 OCPU/6 GB A1, ~20 GB root disk free, 30 running Docker containers, 2 unhealthy, ~1.3 GB swap used. The latter and one container's abnormal PID reading make an automatic HILTECH deployment on this VM a **NO-GO pending measured safety tests**.

## Step 1 delivered: privacy-reduced inventory tooling

`scripts/devops/coolify_pilot_inventory.py` only invokes read-only Docker `ps -aq` and `inspect --type container`. It never reads container environment values or Docker secrets, never prints arbitrary labels, mount source paths, domains or logs, and cannot stop/restart/delete anything.

It classifies:
- `PREVIEW_REVIEW_ONLY`: container name *resembles* a PR preview; this is **NOT** a finding of inactivity or cleanup approval.
- `OWNER_UNKNOWN_REVIEW`: has no recognized Compose/Coolify ownership label.
- `KEEP_UNTIL_OWNER_APPROVES`: everything else.

It also counts running/unhealthy services, explicit memory limits, and presence of persistent volumes/bind mounts. Default output is *aggregate-only*.

Usage, **only on authorized host**:

```bash
python3 scripts/devops/coolify_pilot_inventory.py --sudo
# local admin-only details, never paste directly to public GitHub issues
python3 scripts/devops/coolify_pilot_inventory.py --sudo --details
```

No credentials are required from the user in ChatGPT; SSH/Coolify access and approval remain separate. The command must be run manually under authorization; no automatic schedule or host mutation.

The unit tests use synthetic Docker inspect fixtures and assert that names, secret-labeled fields and mount source paths do not appear in aggregate output. `.github/workflows/devops-coolify-pilot-inventory.yml` runs the checks on GitHub, **without connecting to the owner VM**.

## Next staged actions, NOT yet taken

1. Obtain a safe actual `--details` inventory privately, mapping **Coolify project + branch + persistent volume owner** without copying secrets or publicly publishing private project names. A matching `pr-` string is not enough to declare a container unused.
2. Determine whether the existing unhealthy worker/web app issues are function-critical; inspect CPU/PID behavior by project and record owner consent. No automatic restarts.
3. Collect a representative load baseline and define a **reserve** for other workloads. Do not infer the host can fit Keycloak, OpenFGA and PostgreSQL merely from one instantaneous RAM snapshot.
4. For each proposed preview stop, record exact Coolify resource, proof no traffic/dependency, backup state, **named owner approval**, reversible stop-only procedure and rollback. Never `docker system prune`, remove any volume, or delete an unknown project.
5. If host still constrained, keep current Coolify as control plane and use a separately funded / properly sized execution VM for HILTECH. No cost is authorized yet.
6. Before real users: integrated source/main, actual health/readiness [#137](https://github.com/omarkhair70-droid/hiltech/issues/137), persistent own PostgreSQL/Keycloak/OpenFGA, private Evidence storage, backups **and restore**, signed Android/Windows release, cross-role/denial/offline E2E, telemetry and rollback.

**Implementation note:** Phase6 integration [#140](https://github.com/omarkhair70-droid/hiltech/pull/140) is merged and latest exact-head CI was green. This clears a Docker boot integration blocker, not the above actual pilot acceptance gates.

## Safety/result at this checkpoint

- `SERVER_MODIFIED=NO`
- `CONTAINERS_STOPPED_OR_DELETED=NO`
- `DATA_VOLUMES_MODIFIED=NO`
- `COOLIFY_PROJECT_CREATED=NO`
- `HILTECH_PILOT_DEPLOYED=NO`
- `OWNER_APPROVAL_FOR_PREVIEW_STOP=NO`

If authenticated host inventory commands are blocked by the current connection's security policy, **do not bypass**; keep tool and docs available for a properly authorized audit session.
