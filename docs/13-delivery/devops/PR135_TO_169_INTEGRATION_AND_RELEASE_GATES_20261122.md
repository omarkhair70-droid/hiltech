# HILTECH — integration, migration and release blockers

Date: 2026-11-22
Owner: DevOps / integration lane
Baseline: `main@fae1d7f36eb9adeaecfea6ccbd07baba6b2af646` (verified), **V0038** final committed Flyway migration.

This is a **source/CI/control-plane audit**, **not** a release, domain sign-off, user pilot or permission to merge procurement. Live statuses are point-in-time. Keep Phase 12 hardware adapters deferred, Phases 14–16 outside the internal scope.

## 1. Direct answer: what really works

- `main` already has the established People/Project/Work/Warehouse/Field foundations through V0038. It **does not** yet contain the seven stacked Phase 7 purchase branches or the independent Phase 8/9/10/11/13 domain implementations.
- Phase 7 `#163` is a **main-plus-procurement integration test candidate**. Its dedicated Postgres/Windows/Android test job passed on source `dbc612f001696ebe85e5e9b0c58883d2af51f4a5`. Several server/native CI checks also passed, **but Bootstrap Phase 0 `foundation` failed** in `ArchitectureTest.moduleBoundariesAreValid()`.
- New DevOps diagnostic `#165` proved via real CI on a disposable clone that all historical migrations are **byte-for-byte identical to main**, and there are **exactly 22 additive V0039–V0060** migration versions (no duplicates). [Diagnostic CI #45075406711](https://github.com/omarkhair70-droid/hiltech/actions/runs/45075406711) also reproduced the blocking Spring Modulith cycle: **`procurement -> warehouse -> procurement`**. Specifically Warehouse's `ReceivingService` directly injects Procurement's `ProcurementPoReceivingGuard`, while Procurement depends on Warehouse's stock/asset/service types. The architecture job correctly fails; do not disable `ArchitectureTest`.
- Opened separate integrator-only Draft [#169](https://github.com/omarkhair70-droid/hiltech/pull/169) against **#163's branch**, not main, with a Warehouse-owned PO receiving policy port; Procurement implements it while preserving all seven existing business-guard methods and default-OFF flags. Full server + disposable-PG tests are being run on the exact integration branch; **do not merge until they pass**. Domain owner must review and port fix into the correct Phase 7 branch before ordered merge.

## 2. Pull-request inventory and safe order

| PR | Focus | Latest verified source condition | Merge/release disposition |
| --- | --- | --- | --- |
| [#135](https://github.com/omarkhair70-droid/hiltech/pull/135) | Early Oracle/Coolify host audit, staging gate, image packaging | Draft; GitHub reports **CONFLICTING** with main. At least ten paths have both sides modified since old base, including package workflow, `Dockerfile.server`, IdentitySessionRuntime and FieldMaterialUsage service | **BLOCK**. Manually reconcile on independent branch; don't wholesale overwrite current security, field work or deployment scripts |
| [#145](https://github.com/omarkhair70-droid/hiltech/pull/145) | Read-only Coolify resource inventory + backup audit | Draft; **9 passing checks**, mergeable | Can review as isolated tool/documentation slice; no authority for service deletion or pilot |
| [#155](https://github.com/omarkhair70-droid/hiltech/pull/155) | Reference Compose, actual staging checkpoints, FGA model/bootstrap helper | Draft; **12 passing checks**, mergeable | Review and merge source/docs only; **do not apply reference Compose to the already-deployed separate Postgres resources** |
| [#159](https://github.com/omarkhair70-droid/hiltech/pull/159) | Spring OpenFGA preshared bearer auth | Draft; **27 passing checks**, mergeable. Exact-source multiarch staging OCI image built and published | Security review then merge into current main. Existing capped API resource points to new immutable image but **is not started** |
| [#160](https://github.com/omarkhair70-droid/hiltech/pull/160) | Fail-closed production DNS/TLS/OIDC/readiness checker | Draft; **9 passing checks**, mergeable | Static tests passed; no live production DNS, issuer, native release or backup approval available. This is **not production GO** |
| [#143](https://github.com/omarkhair70-droid/hiltech/pull/143) | P7 S01 WorkOrder demand, stock-before-buy, V0039–V0041 | Draft; **44 passing checks**, mergeable | First of strictly ordered purchasing stack, after full source/business acceptance |
| [#146](https://github.com/omarkhair70-droid/hiltech/pull/146) | P7 supplier RFQ/quote, V0042 | Draft; 3 passing checks, base is #143 branch | Never merge before #143 |
| [#148](https://github.com/omarkhair70-droid/hiltech/pull/148) | P7 technical review/supplier register/evidence, V0043–V0045 | Draft; 3 passing checks, base is #146 branch | Never merge before #146 |
| [#153](https://github.com/omarkhair70-droid/hiltech/pull/153) | P7 governed PO lifecycle, V0046–V0049 | Draft; 3 passing checks, base is #148 branch | Never merge before #148; policy/issuer authority remains separate gate |
| [#157](https://github.com/omarkhair70-droid/hiltech/pull/157) | P7 canonical PO-linked receiving, V0050 | Draft; 3 passing checks, base is #153 branch | Never merge before #153; no fake StockMovement |
| [#158](https://github.com/omarkhair70-droid/hiltech/pull/158) | P7 supplier attestation, three-way match and evidence, V0051–V0059 | Draft; 3 passing checks, base is #157 branch | Never merge before #157. **No automated vendor send or money movement** |
| [#162](https://github.com/omarkhair70-droid/hiltech/pull/162) | P7 team/office stock only, V0060 | Draft; 3 passing checks, base is #158 branch | Never merge before #158. Non-stock external service workflow remains a different required contract |
| [#163](https://github.com/omarkhair70-droid/hiltech/pull/163) | Main + S01–S06 combined verification, *not* approval | Draft; 45 passing / 1 **failing `foundation`** checks | **BLOCK**; never merge direct to main to bypass parents |
| [#165](https://github.com/omarkhair70-droid/hiltech/pull/165) | Integrator migration/architecture diagnostics | Draft; migration proof PASS, architecture test reproduced failure | Diagnostic only, based on #163, no domain release |
| [#169](https://github.com/omarkhair70-droid/hiltech/pull/169) | Warehouse-owned PO guard port boundary repair | Draft child of #163, CI pending at authoring | Review exact-head full suite and hand over to Phase 7 engineering source owner; **not** a merge authorization |

**Actual proposed merge order:** resolve #135 safely or leave conflicting old work unmerged; review #145 + #155 + #159 + #160 as independent DevOps/security infrastructure changes with overlapping workflow/build files reconciled against current main; incorporate approved #169 boundary contract into the correct Phase 7 source branch; then source-reviewed P7 stack **#143 → #146 → #148 → #153 → #157 → #158 → #162**, with a fresh main-based candidate and exact-head CI after each. Never skip the parent stack by merging #163 directly.

## 3. Flyway / shared source coordination

- V0001–V0038: canonical in `main`; do **not change, replay in live DB, squash or renumber** previously applied migrations.
- V0039–V0060: contiguous, source-stable Phase 7 candidate; verified on disposable CI PostgreSQL and zero historical edits by diagnostic job. **Not applied to company databases**.
- Phase 8–11,13 teams need **provisional** migration plans on independent branches while P7 remains unmerged. Do not claim V0061+ permanently allocated until one integration owner has checked actually merged main and all concurrent PR heads. Avoid two domain lanes claiming the same `V0061`.
- Shared wiring ownership: Android `MainActivity`, Desktop `Main`, common Gradle, `application.yml`, shared ApprovalEngine/OpenFGA config, and Flyway numbering belong to one integration owner; phase chats implement their domain-owned screens/services and post adapter contracts. No undocumented direct package coupling.
- New Warehouse receiving port retains every old PO guard: same-company issued PO, supplier dispatch attestation, effective destination, stock item/unit and quantity bounds, READY restricted delivery-note Evidence and serialized canonical stock receipt. Do not silence tests by relaxing any of them.

## 4. Live Coolify, Oracle, identity, storage and backup

**Verified in prior authorized DevOps execution, not a new production launch:**
- Dedicated HILTECH **staging** contains three independent `running:healthy` PostgreSQL resources and private Keycloak, preshared authenticated OpenFGA and RustFS applications. Keycloak's `hiltech` Realm was imported and OIDC discovery returned HTTP 200 internally. OpenFGA staging store `01M82HW5JVHE75XSKPSK3CZ7YE` and model `01M82HW5K678TMQ73MPW7MNC82` passed positive/negative contextual policy checks. RustFS staging bucket signed object PUT/GET/DELETE, anonymous denial and post-delete 404 passed.
- Exact FGA-authenticated staging API image `ghcr.io/omarkhair70-droid/hiltech-server@sha256:d5254f53580322d9b2a07fc5adc1de3f813cd1e8b239535ba81d51d9c239b2b9` was published from security PR #159 and configured on a **stopped** capped API candidate. This image predates Phase 7 and new Phase 8–13 domain changes: **do not treat it as the final integrated release**.
- Temporary FGA bootstrap helper stopped, task reset to dry-run, credential overwritten with invalid sentinel. Coolify `delete` API returned 403: owner/administrator needs to remove the remaining temporary environment-variable **name**; original FGA key unaffected.
- `HILTECH Internal OS` production environment exists in Coolify but is **empty**. No approved company DNS+TLS Keycloak issuer, no native-reachable HTTPS presigned S3 endpoint, no production API/DB/bucket/users, no witnessed cross-device role/offline E2E, and no signed native distribution acceptance.
- [#145 backup audit](https://github.com/omarkhair70-droid/hiltech/pull/145) found **zero Coolify S3/off-host destinations**, only one prior local scheduled database backup among eight older DBs, and **no witnessed restore**. It cannot establish independent backups outside Coolify. The new HILTECH DBs, Keycloak DB, OpenFGA DB and RustFS evidence require a dedicated encrypted offsite backup plan and disposable restore before real business data.
- [#135 Oracle audit](https://github.com/omarkhair70-droid/hiltech/pull/135) found the older shared VM constrained (1 vCPU, ~5.8 GiB RAM, swap use, ~20GiB free disk, unhealthy existing workloads). The host-capacity baseline and actual Teswa reserve must be remeasured; no deletion, cross-project restart, invented hosting or new paid machine is authorized. Latest previous Teswa status was `running:healthy`.

**Go/no-go:** Production **NO-GO** until (1) full exact-head merged branch and security/architecture checks, (2) native OIDC and signed evidence HTTPS, (3) isolated DB/role/backup+restore, (4) real-device cross-role/negative/offline integrated journey, (5) explicit company PO/payroll/finance/supplier policy signoff, (6) pinned ARM64 OCI + Android/Windows releases and rollback. Company approval for implementation is **not** equivalent to authorizing money transfers or vendor sends.

This report deliberately distinguishes build/test evidence, deployed *staging infrastructure*, and company production acceptance.
