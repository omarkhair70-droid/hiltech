# 21 — First-Slice Final Pre-Freeze Consistency Review

Date: 2026-09-18
Status: **PASS WITH ONE REMAINING PRE-CODE GATE — RENDERED DESIGN PROOF**
Review scope: first production vertical only.

## Executive result

The first-slice contract set is internally coherent enough that broad product/domain/architecture discovery is no longer justified.

No known contradiction currently requires reopening:
- product thesis,
- configurable operating model,
- Project/Site/Work structure,
- Asset/Warehouse/Stock structure,
- offline architecture,
- HTTP transport,
- PostgreSQL persistence approach,
- authorization architecture,
- evidence protocol,
- provider architecture,
- Windows packaging/signing/distribution,
- backup/DR topology.

One pre-code gate remains before FIRST_SLICE_FREEZE can be called:

**actual rendered design proof**
- mobile/desktop first-slice surfaces,
- conflict states,
- Arabic RTL,
- adaptive tablet,
- navigation comparison.

The final stack/version/CI-action review is now PASS and `docs/12-stack/FINAL_STACK.md` exists.

Everything else below is either:
- structurally closed,
- seed/configuration data,
- post-Freeze bootstrap verification,
- or production activation/cutover work.

---

# 1. Product / Company model

Decision:
**PASS**

Canonical laws:
- one HILTECH product,
- one authoritative truth,
- role/context-aware experiences,
- enter once, flow everywhere,
- management by exception,
- physical=digital,
- audit/history first-class,
- offline as architecture,
- scale without redesign.

Current company size/headcount/crew shape is seed data, not a product-size constraint.

HILTECH can grow through:
- new users,
- teams,
- roles,
- projects,
- Sites,
- ProjectSites,
- warehouses/storage,
- WorkTypes,
- policies,
- clients,
- suppliers,
- subcontractors,
- future branches

without domain rewrite.

No contradiction found.

---

# 2. Configuration vs hard invariants

Decision:
**PASS**

Operating variability is represented by typed/versioned configuration:

- WorkTypeDefinition,
- AssignmentPolicy,
- ReadinessPolicy,
- EvidencePolicy,
- ReviewPolicy,
- FieldTrackingPolicy,
- ApprovalPolicy,
- CodePolicy,
- ProjectHealthPolicy,
- templates/checklists,
- Teams/Roles/Delegation,
- StorageLocation/master data,
- AssetType/Stock categories,
- notifications/escalation.

Hard invariants remain code/domain laws:
- server authorization,
- audit,
- idempotency,
- baseVersion conflict protection,
- one active Asset custody,
- no silent negative stock,
- evidence checksum/finalize,
- exact-version review,
- replay re-authorization.

Configuration cannot disable those invariants.

No contradiction found.

---

# 3. Site / ProjectSite identity

Decision:
**PASS**

Canonical:
- Site = durable physical/client location.
- ProjectSite = Project-specific relationship/context to Site.
- Area hierarchy belongs to Site.
- WorkOrder references Project + Site and ProjectSite where exact project delivery context is needed.
- Support/Maintenance can reuse the same Site after Project closure.

This resolves the earlier project-bound Site ambiguity.

No remaining structural question.

---

# 4. Project / Work state model

Decision:
**PASS**

Separated dimensions:

WorkOrder lifecycle:
- DRAFT
- PLANNED
- ASSIGNED
- IN_PROGRESS
- BLOCKED
- SUBMITTED_FOR_REVIEW
- REWORK_REQUIRED
- ACCEPTED
- CLOSED
- CANCELLED

Readiness:
- NOT_EVALUATED
- READY
- BLOCKED

Readiness is policy-derived and does not masquerade as lifecycle.

Assignments are typed history:
- USER
- CREW
- TEAM
- SUBCONTRACTOR_ORGANIZATION

No authoritative assigned-user array model remains.

No contradiction found.

---

# 5. Work policy / instruction historical reproducibility

Decision:
**PASS**

WorkOrder binds versioned policy revisions through normalized WorkPolicyBinding history.

Rules:
- one current unsuperseded binding,
- prior binding revisions retained,
- active/in-progress/submitted historical meaning not rewritten by later configuration,
- controlled rebind only through explicit command/path.

Instructions:
- WorkInstructionRevision append-only.
- WorkOrder points to current instruction revision.
- material revision after ASSIGNED makes stale offline bundle/version conflict.

Checklist:
- runtime checklist items are materialized WorkChecklistItemInstance rows.

No contradiction found.

---

# 6. Project progress / health

Decision:
**PASS**

Progress:
- accepted WorkOrders only,
- snapshotted progressWeight,
- acceptedWeight / totalBaselineWeight,
- no authoritative manual percent,
- scope denominator changes through controlled baseline/variation.

Health:
- explainable signal-derived projection,
- UNKNOWN / HEALTHY / ATTENTION / CRITICAL / ON_HOLD,
- no opaque/manual authoritative score,
- contributing signals visible.

No contradiction found.

---

# 7. Asset / custody / availability

Decision:
**PASS**

Separated dimensions:
- lifecycle,
- condition,
- custody,
- calibration,
- maintenance,
- incident,
- derived availability.

Asset identity:
- persists through tag replacement, damage, movement, projects.
- assetCode is HILTECH hard human identity.
- serial is searchable/duplicate-warning, not blindly globally unique.

Tags:
- AssetTag points to Asset.
- one active primary tag.
- history retained.
- no circular Asset.tagId source-of-truth.

Custody:
- append movement,
- one current projection,
- one authoritative active custody,
- collision one winner.

No contradiction found.

---

# 8. Warehouse / Storage / Stock

Decision:
**PASS**

Warehouse:
optional operational/facility grouping.

StorageLocation:
authoritative physical/logical inventory location dimension.

Kinds:
- MAIN_WAREHOUSE,
- WAREHOUSE,
- PROJECT_STORAGE,
- SITE_STORAGE,
- OTHER_TYPED.

Temporary remote material storage does not require fake full Warehouse.

Stock:
- exact decimals,
- append movement,
- derived balance,
- no silent negative quantity,
- stock financial valuation remains Finance boundary.

Reservation:
- typed AssetReservation / StockReservation.

No contradiction found.

---

# 9. Evidence

Decision:
**PASS AT APPLICATION CONTRACT LEVEL**

Protocol:
local durable capture
→ reserve
→ private signed upload
→ server checksum/size verification
→ optional quarantine scan
→ authoritative READY.

First slice:
- max 16 MiB/object,
- no multipart,
- private object storage,
- opaque object keys,
- no permanent public restricted URL,
- HIGHLY_RESTRICTED download through authenticated API proxy,
- no automatic authoritative evidence deletion without active RetentionPolicy.

Provider:
OCI Object Storage + OCI KMS.

ARBITRARY_FILE:
requires scanner before READY.
If pilot does not enable arbitrary files, exact scanner product is not a Freeze blocker.

No contradiction found.

---

# 10. HTTP / Client contract

Decision:
**PASS AT PRE-CODE CONTRACT LEVEL**

Accepted:
- /v1,
- typed action routes,
- Ktor 3.5.2,
- Android OkHttp,
- JVM Desktop CIO,
- kotlinx.serialization,
- manual thin shared typed client,
- UUID,
- Idempotency-Key,
- baseVersion,
- X-Correlation-Id,
- traceparent,
- native client metadata,
- typed error/conflict/reauth envelopes,
- external OBJECT_NOT_VISIBLE,
- internal PERMISSION_DENIED where safe,
- opaque stateless cursor,
- additive v1 compatibility,
- OpenAPI 3.1 generated post-Freeze for drift/docs.

Implementation tests are Bootstrap Verification, not circular pre-code blockers.

No contradiction found.

---

# 11. PostgreSQL / Flyway / jOOQ

Decision:
**PASS AT GENERATION-CONTRACT LEVEL**

Accepted:
- PostgreSQL authoritative store.
- jOOQ adapter boundary.
- concrete table shapes.
- exact first-slice key/FK/check/unique/index contract.
- one global Flyway stream.
- VNNNN__module__description.sql.
- V0001..V0009 initial ordering.
- no normal production baselineOnMigrate.
- expand/migrate/contract.
- jOOQ KotlinGenerator.
- generated source not committed.
- UUID / OffsetDateTime / BigDecimal / JSONB persistence mappings.
- varchar + CHECK state persistence.

Production SQL/jOOQ generated files are post-Freeze bootstrap artifacts.

No contradiction found.

---

# 12. Android Room / offline

Decision:
**PASS AT PRE-CODE CONTRACT LEVEL**

Accepted:
- local observable cache.
- durable typed PendingCommand.
- explicit dependencies.
- LocalEvidence.
- ConflictRecord.
- cursor scopes.
- same operationId retry.
- WorkManager.
- process-death recovery.
- explicit conflict.
- no LWW.
- UUID TEXT.
- JSON payloadVersion.
- app-private OS-encrypted storage baseline.
- HIGHLY_RESTRICTED not cached by default.
- safe eviction/pinning.
- release-declared migration support window.
- bounded retry schedule.

Production Room classes/migrations/tests are post-Freeze Bootstrap Verification.

No contradiction found.

---

# 13. Authorization

Decision:
**PASS AT PRE-CODE CONTRACT LEVEL**

OpenFGA:
- executable first-slice model exists.
- official contract tests GREEN.
- run 35331537375.
- teams/usersets/project/work/warehouse/asset/evidence/configuration tested.

Boundary:
PostgreSQL business relationship truth
→ transactional projection intent/outbox
→ fail-closed guard
→ OpenFGA projection
→ selective higher consistency.

Security:
- pending grant denied until applied.
- pending revoke denied immediately.
- stale projector revision cannot regrant.
- model ID pinned.
- delegation expiry checked authoritatively.
- no first-slice positive allow cache.

No contradiction found.

---

# 14. Infrastructure

Decision:
**PASS AT PROVIDER-CONTRACT LEVEL**

Provider:
OCI / ADR-014.

Baseline:
- Jeddah primary candidate,
- Container Instances preferred,
- Compute container fallback,
- OCI Database with PostgreSQL,
- OCI Object Storage,
- OCI KMS,
- OCI Secret Management,
- OCI Registry,
- OCI Load Balancer,
- OTel Collector → OCI observability baseline,
- Terraform + OCI Provider,
- OCI Resource Manager,
- GitHub Actions control plane,
- private application/data tiers,
- no Kubernetes baseline.

Remaining tenancy/quota/latency/sizing/staging-deploy work is activation/cutover validation unless it exposes a contract incompatibility.

No application-domain OCI lock-in.

No contradiction found.

---

# 15. Backup / DR

Decision:
**PASS AT ENGINEERING-CONTRACT LEVEL**

PILOT:
- PITR,
- >=10-day window baseline,
- cross-region copied backups,
- recovery drill target.

STABLE:
- Jeddah primary candidate,
- Riyadh Warm Standby candidate,
- 5-minute RPO enforcement,
- <=60-minute service recovery drill target.

These are engineering objectives, not external SLA promises.

No contradiction found.

---

# 16. Windows packaging / signing / distribution

Decision:
**PASS AT CONTRACT LEVEL**

Packaging:
ADR-012 / SPIKE-07.

Signing:
- DigiCert OV Code Signing,
- KeyLocker cloud HSM.

Distribution:
- HILTECH Update Service,
- authenticated release manifest,
- private OCI artifact,
- SHA-256,
- Authenticode verification,
- expected publisher,
- controlled MSI swap,
- previous compatible installer rollback,
- INTERNAL / PILOT / STABLE channels.

Intune/MDM is an optional adapter to same signed MSI.

Certificate issuance and signed staging proof are activation gates.

No contradiction found.

---

# 17. Reality coverage

Decision:
**PASS FOR STRUCTURAL MODEL COVERAGE / SEED STILL OPEN**

Internal representative fixtures cover:
- Bank HQ/data-center rack work,
- Alamein repeated building cable pulling,
- temporary Project/Site material storage,
- technician pair,
- engineer + technicians,
- factory/site infrastructure.

These prove the first-slice model spans materially different work shapes.

Still useful before pilot:
- actual current staff/team seed,
- asset/stock/storage seed,
- project/client/site code terminology,
- representative devices/site restrictions,
- selected WorkType policy seed.

Those are configuration/pilot setup unless they reveal missing structure.

---

# 18. Design

Decision:
**FAIL / BLOCKING FIRST-SLICE FREEZE**

Interaction/state specs are complete enough to build.

Actual rendered proof still missing for:
- Technician Job,
- Warehouse Checkout,
- Configuration Center,
- Supervisor Review,
- Project Command updates,
- conflict states,
- Arabic RTL,
- tablet adaptation,
- navigation comparison.

External blocker:
Figma Starter/View MCP quota.

Latest check:
- root metadata call succeeded,
- next metadata expansion hit Starter MCP rate limit,
- whoami confirmed Starter / View / admin.

Canvas-ready queue:
`docs/10-design/FIRST_SLICE_DESIGN_FREEZE_EXECUTION_PACK_2026-09-18.md`

This is the primary remaining pre-code blocker.

---

# 19. Final stack/version review

Decision:
**PASS**

Canonical:
- `docs/12-stack/FINAL_STACK_VERSION_REVIEW_2026-09-18.md`
- `docs/12-stack/FINAL_STACK.md`

Final focused version evidence:
- AGP 9.3.2 run 35388858253 PASS.
- AGP 9.3.3 run 35389326629 PASS.
- AGP 9.3.3 frozen for first slice.
- immutable production GitHub Actions SHAs selected.
- deliberate non-upgrades documented.

Flyway exact resolved transitive version is captured through dependency lock at Bootstrap under the accepted Spring Boot-managed line.

The stack is no longer a pre-code blocker.

---

# 20. Freeze / Bootstrap boundary

Decision:
**PASS**

Canonical:
`17_FREEZE_TO_BOOTSTRAP_BOUNDARY.md`

Before Freeze:
- contracts,
- generation rules,
- test specifications,
- design proof,
- final version review.

After Freeze:
- production Flyway SQL,
- jOOQ generated code,
- Spring/Ktor/Room implementations,
- production OpenFGA projector,
- IaC,
- production UI code,
- executable implementation tests.

No circular "need production code to allow production code" blocker remains.

---

# Final blocker list

## Blocking FIRST_SLICE_FREEZE

1. **Rendered Design Proof**
   - currently Figma MCP quota-blocked.

No other pre-code blocker is currently open.

## Not blocking contract Freeze, but blocking production activation/cutover

- OCI tenancy/subscription.
- Jeddah quotas/capacity.
- representative Egypt latency.
- final OCI sizing/cost.
- staging IaC deploy.
- PITR/DR drills.
- telemetry operational settings.
- domain/TLS ownership.
- DigiCert legal validation/certificate issuance/KeyLocker credentials.
- signed MSI staging proof.
- pilot seed/master data.
- malware scanner only if arbitrary-file evidence is enabled.

---

# Review conclusion

**FIRST_SLICE_CONTRACT_CONSISTENCY = PASS**

**FIRST_SLICE_FREEZE = NOT YET PASS**

Reason:
- rendered design evidence is missing.

No other broad architecture/domain/reality discovery gate is justified at this point.

Next:
1. complete Figma design proof when quota permits,
2. run Freeze Review,
3. then bootstrap production repository.
