# HILTECH OS — Agent Execution Contract

Status: **ACTIVE / MANDATORY ENTRYPOINT**

This repository is the single source of truth for HILTECH OS.

Any coding agent, Codex session, new chat, automation, or human contributor must treat repository state and canonical documents as authoritative. Conversation memory is never the project source of truth.

## Current program position

- Phase 0 — Repository / Engineering Foundation: **VERIFIED / MERGED**
- Phase 1 — Identity, Organization, Permissions Foundation: **VERIFIED / COMPLETE**
- Phase 2 — Shared Product Infrastructure: **VERIFIED / COMPLETE**
- Current execution phase: **Phase 3 — People / Internal Workforce Core / Slice 06 — Offboarding Skeleton — IMPLEMENTATION AUTHORIZED**
- Phase 2 Slice 01: **Shared HTTP / Command Runtime — VERIFIED**
- Phase 2 Slice 02: **Evidence Metadata + Upload/Finalize — VERIFIED**
- Slice 02 canonical verified code head: `563875c0421df713b52e6661bf3d46b8c5ec5878`
- Slice 02 verification runs: Phase 2 `35433246103` / Bootstrap `35433246124` / Phase 1 OIDC `35433246188` — **PASS**
- Phase 2 Slice 02 merge commit: `21a04718414ab20ecf8deeffccfd63961e45099a`; post-merge Bootstrap run `35434157140` — **PASS**
- Phase 2 Slice 03: **Activity Events Foundation — VERIFIED / MERGED**
- Slice 03 merge commit: `9eaf7a2a18ac7138a08c6fcab43ab282b2449ce9`
- Slice 03 post-merge Bootstrap run: `35438086181` — **PASS**
- Slice 03 contract: `docs/13-delivery/phase2/03_ACTIVITY_EVENTS_FOUNDATION_SLICE_2026-09-19.md`
- Phase 2 Slice 04: **Minimal Approval Engine Foundation — VERIFIED / MERGED**
- Slice 04 merge commit: `b6aad197bb4a9e8200717ad9801092dc2cabbb5b`
- Slice 04 post-merge Bootstrap run `35464733404` — **PASS**
- Slice 04 post-merge OpenFGA run `35464733333` — **PASS**
- Slice 04 implementation contract: `docs/13-delivery/phase2/04_APPROVAL_ENGINE_FOUNDATION_SLICE_2026-09-19.md` — **VERIFIED**
- Phase 2 Slice 05: **Inbox / Work Queue Foundation — VERIFIED / MERGED**
- Slice 05 branch / PR: `phase2/inbox-work-queue-foundation-20260919` / **#34**
- Slice 05 canonical tested code head: `fa8d0660f49c6de0dc4bc1285976f6a06a4610cb`
- Slice 05 merge commit: `6b6194a9107dcfc1ba74e392776a1dee9e38964a`
- Slice 05 exact-head verification: Bootstrap `35467408783` / Phase 2 `35467408781` / Phase 1 OIDC `35467408788` — **PASS**
- Slice 05 post-merge Bootstrap run: `35468123129` — **PASS**
- Slice 05 reality gate: `docs/13-delivery/phase2/05_INBOX_WORK_QUEUE_REALITY_CLOSURE_2026-09-19.md` — **PASS**
- Slice 05 implementation contract: `docs/13-delivery/phase2/05_INBOX_WORK_QUEUE_FOUNDATION_SLICE_2026-09-19.md` — **VERIFIED / MERGED**
- Slice 05 rule preserved in production: source domains stay authoritative; Work Queue is current actionable attention, Inbox is durable attention/read state, and Notifications remain a separate delivery concern.
- Phase 2 Slice 06: **Notification Abstraction — VERIFIED / MERGED**
- Slice 06 reality gate: `docs/13-delivery/phase2/06_NOTIFICATION_ABSTRACTION_REALITY_CLOSURE_2026-09-20.md` — **PASS**
- Slice 06 implementation contract: `docs/13-delivery/phase2/06_NOTIFICATION_ABSTRACTION_FOUNDATION_SLICE_2026-09-20.md` — **VERIFIED / MERGED**
- Slice 06 canonical tested code head: `df72b296181890b3f4a680800f924c8bde3ec5b8`
- Slice 06 verification runs: Bootstrap `35472074530` / Phase 2 `35472074603` / Phase 1 OIDC `35472074705` — **PASS**
- Slice 06 rule preserved: provider-neutral Notification intent/policy/attempt foundation only; no production provider, preferences, quiet hours, digest, escalation or TEAM fan-out were invented.
- Slice 06 merge commit: `a24715e9d792672a0a33fbebaf2e16a1acafadc9`; post-merge Bootstrap `35474093059` — **PASS**
- Phase 2 final gap review: `docs/13-delivery/phase2/07_PHASE2_FINAL_GAP_REVIEW_2026-09-20.md` — **PASS / NO ADDITIONAL PHASE 2 SLICE REQUIRED**
- Phase 2 final closure PR #37 merge commit: `624bd6c50f8d17535316ae450a8a780bc756a108`; post-merge Bootstrap `35474921624` — **PASS**
- Phase 3 scope closure: `docs/13-delivery/phase3/00_PHASE3_PEOPLE_CORE_SCOPE_CLOSURE_2026-09-20.md` — **PASS / SLICE PLAN FROZEN**
- Phase 3 Slice 01 contract: `docs/13-delivery/phase3/01_EMPLOYEE_EMPLOYMENT_CORE_SLICE_2026-09-20.md` — **VERIFIED / MERGED**
- Phase 3 Slice 01 canonical tested code head: `59c5cb9fb4d859260ba48a9f8edd5ecce5a97e96`
- Slice 01 verification runs: OpenFGA `35477856791` / Bootstrap `35477856788` / Phase 1 OIDC `35477856782` / Phase 2 runtime `35477856796` — **PASS**
- Slice 01 merge commit: `0183a273f1a01d3b9d0277df2ac2e8bbe2f98390`; post-merge Bootstrap `35478418690` and OpenFGA `35478418692` — **PASS**
- Slice 02 reality closure: `docs/13-delivery/phase3/02_WORKFORCE_ASSIGNMENT_REALITY_CLOSURE_2026-09-20.md` — **PASS**
- Slice 02 implementation contract: `docs/13-delivery/phase3/02_WORKFORCE_ASSIGNMENT_FOUNDATION_SLICE_2026-09-20.md` — **VERIFIED / MERGED**
- Slice 02 canonical tested code head: `b838e92f1126ebf1081c81568d5abb90d0c17edd`
- Slice 02 verification: Bootstrap `35479675064` / Phase 2 runtime `35479675067` / Phase 1 OIDC `35479675062` attempt 2 — **PASS**
- Slice 02 merge commit: `9b42ba8981364884b1e9217ad1b1d52c55f3b2ce`; post-merge Bootstrap `35480853486` — **PASS**
- Slice 03 reality closure: `docs/13-delivery/phase3/03_HR_DOCUMENTS_CERTIFICATIONS_REALITY_CLOSURE_2026-09-20.md` — **PASS**
- Slice 03 implementation contract: `docs/13-delivery/phase3/03_HR_DOCUMENTS_CERTIFICATIONS_SLICE_2026-09-20.md` — **VERIFIED / MERGED**
- Slice 03 canonical tested code head: `9fa0b95de549bd92cc7d3a554cd7cdcf941ef35f`
- Slice 03 exact-head verification: Bootstrap `35482995671` / Phase 2 runtime `35482995728` / Phase 1 OIDC `35482995573` — **PASS**
- Slice 03 merge commit: `d5d0cb256e68cf7b30978434aa6ba241c7500df1`; post-merge Bootstrap `35485263189` — **PASS**
- Slice 04 reality closure: `docs/13-delivery/phase3/04_ONBOARDING_SELF_SERVICE_REALITY_CLOSURE_2026-09-20.md` — **PASS**
- Slice 04 implementation contract: `docs/13-delivery/phase3/04_ONBOARDING_SELF_SERVICE_SLICE_2026-09-20.md` — **VERIFIED / MERGED**
- Slice 04 canonical tested code head: `d511f1e262e954a2646bd040692d5a6f0e74371e`
- Slice 04 exact-head verification: Bootstrap `35488578970` / Phase 2 runtime `35488578980` / Phase 1 OIDC `35488578992` / Onboarding Human Proof `35488579082` — **PASS**
- Slice 04 merge commit: `f7d7460718f43e634a180734623dabe5260b0224`; post-merge Bootstrap `35489327649` — **PASS**
- Slice 05 reality closure: `docs/13-delivery/phase3/05_WORKFORCE_ASSIGNMENT_CHANGE_REALITY_CLOSURE_2026-09-20.md` — **PASS**
- Slice 05 implementation contract: `docs/13-delivery/phase3/05_WORKFORCE_ASSIGNMENT_CHANGE_SLICE_2026-09-20.md` — **VERIFIED / MERGED**
- Slice 05 canonical tested code head: `1c9986ef369cdd40b16cea53726d19f7fb10e799`
- Slice 05 merge commit: `51648418a69df285334f76f5b1b4cfca9ec3cc43`; post-merge Bootstrap `35492558129` — **PASS**
- Slice 06 reality closure: `docs/13-delivery/phase3/06_OFFBOARDING_SKELETON_REALITY_CLOSURE_2026-09-20.md` — **PASS**
- Slice 06 implementation contract: `docs/13-delivery/phase3/06_OFFBOARDING_SKELETON_SLICE_2026-09-20.md` — **IMPLEMENTATION AUTHORIZED**
- Current rule: build the People-owned offboarding coordinator + real HILTECH access/session revocation + typed external clearance slots only; no Project/Asset/Finance/Payroll engine and no Phase 4 scope.
- First production slice contracts: **FROZEN / PASS**
- Figma: **OPTIONAL**, not an implementation blocker
- First-slice rendered design proof: **PASS**
- Infrastructure provider baseline: **OCI research/contract selected; activation/purchase remains separately gated**

Canonical current status:
`docs/00-program/CURRENT_PROGRAM_STATUS.md`

Canonical implementation order:
`docs/13-delivery/IMPLEMENTATION_ORDER.md`

Canonical first-slice stack:
`docs/12-stack/FINAL_STACK.md`

## Mandatory read order before changing code

1. `AGENTS.md`
2. `docs/00-program/HILTECH_PRODUCT_OWNERSHIP_PRINCIPLES.md`
3. `docs/00-program/CURRENT_PROGRAM_STATUS.md`
4. `docs/13-delivery/IMPLEMENTATION_ORDER.md`
5. the relevant phase/domain/workflow/object contract
6. `docs/12-stack/FINAL_STACK.md` when technology/runtime is involved
7. first-slice Freeze pack when touching Project / Site / Work / Warehouse / Evidence / Offline / Authorization contracts
8. `docs/01-reality/REALITY_EVIDENCE_REGISTER.md` when a phase depends on real HILTECH operating facts

Do not start by rediscovering architecture already frozen in these documents.

## Execution protocol

Before work:

- fetch the actual remote branch state,
- inspect `main` and the intended working branch,
- do not assume any SHA from a prior chat,
- inspect relevant open PRs and current GitHub Actions,
- verify that the requested work belongs to the current phase/slice.

During work:

- implement complete vertical slices, not isolated database/backend/UI layers,
- preserve module ownership and frozen contracts,
- prefer typed commands/transitions over generic CRUD for critical business state,
- keep authorization, audit, tests and observability with the slice,
- preserve offline/idempotency/conflict semantics where applicable,
- never silently change a frozen contract because implementation is inconvenient.

If implementation contradicts a frozen contract:

1. stop the conflicting part,
2. record the contradiction,
3. use explicit change control,
4. update the canonical contract first,
5. then continue implementation.

After work:

- run the relevant regression gates,
- inspect the actual workflow result,
- update execution/status evidence where the slice requires it,
- open a PR against the current `main`,
- merge only the exact tested head,
- never force-push over branch movement.

## Phase rule

Each later phase receives its own reality/contract/design closure when implementation reaches it.

Later-domain facts do **not** reopen Phase 0 or Phase 1 unless they expose a genuine cross-cutting contradiction.

Examples:
- payroll reality belongs to Payroll closure,
- warehouse catalog details belong to Assets/Warehouse closure,
- client claim/certification detail belongs to Finance/Commercial closure,
- CCTV/vendor details belong to Security/Facilities closure.

Record useful future facts now, consume them when their phase arrives.

## Design rule

Figma is not required for implementation.

Accepted first-slice design proof:
- interactive browser prototype under `prototypes/first-slice-design/`,
- real responsive renders,
- Arabic RTL,
- phone/tablet/desktop,
- conflict/offline/authoritative states,
- automated Playwright proof.

Canonical design decision:
`docs/10-design/FIRST_SLICE_DESIGN_FREEZE_EXECUTION_PACK_2026-09-18.md`

For future slices:
- contract the interaction,
- render/review the design in the available tool,
- implement accepted production UI in Compose,
- do not block delivery merely because Figma is unavailable.

## Company evidence / raw files

Raw HILTECH operational files are **not repository source artifacts by default**.

Do not commit:
- payroll workbooks,
- employee salary sheets,
- raw invoices,
- bank files,
- private client commercial workbooks,
- unredacted inventory exports,
- credentials or secrets.

Instead:
- extract the durable business fact,
- record its evidence class and affected phase,
- create sanitized/synthetic fixtures where a test needs representative data.

Canonical policy:
`docs/01-reality/RAW_EVIDENCE_POLICY.md`

Canonical durable reality notes:
`docs/01-reality/REALITY_EVIDENCE_REGISTER.md`

## Product ownership / external dependency rule

Canonical owner intent:
`docs/00-program/HILTECH_PRODUCT_OWNERSHIP_PRINCIPLES.md`

Apply it as a governance layer, not as permission to reopen verified phases.

Required interpretation:
- distinguish **required capability** from a specific provider implementation;
- architecture-ready / adapter-ready does **not** authorize purchase, subscription, certificate issuance, provider activation, new server procurement, or hardware procurement;
- paid/managed services are allowed when their reliability/security/operational value justifies their cost — HILTECH is cost-aware, not "cheap at any risk";
- keep optional external integrations behind explicit adapters/contracts and preserve honest disabled/`NO_PROVIDER` behavior where applicable;
- do not let SMS/email/bank/camera/access-control/MDM/other optional integrations become accidental blockers for the internal core when a safe internal/manual path exists;
- backup/restore, authorization, audit, migration safety, secret hygiene and other real reliability controls are not optional merely because they are invisible;
- research real HILTECH reality, mature patterns and official provider/device documentation before inventing important workflow/integration behavior;
- human-facing slices require representative flow review, not CI alone.

If this governance intent exposes a true contradiction with a frozen ADR/contract, use explicit change control and preserve the prior research/history.

## Infrastructure rule

Do not reselect hosting during feature work.

Provider architecture direction:
- OCI remains the accepted researched first production baseline;
- Container Instances preferred / Compute fallback remain deployment candidates;
- OCI Database with PostgreSQL and OCI Object Storage/KMS/Secrets remain researched managed implementations;
- OpenTelemetry and application/domain contracts remain provider-neutral.

Interpretation:
- this is **not** automatic purchase/activation authorization;
- production cutover may activate managed services when cost/risk/reliability review justifies them;
- existing infrastructure may be reused when it satisfies the frozen isolation/security/operability contract;
- do not create a new VM/server, buy on-prem hardware, or activate a paid provider merely because a feature phase starts;
- do not weaken backup/restore/security merely to avoid cost.

Do not reselect hosting casually during feature work. If provider economics or operational evidence materially contradict the baseline, use explicit change control.

## Security / secrets

Never commit:
- passwords,
- access tokens,
- private keys,
- production `.tfvars`,
- real customer/employee sensitive exports.

Keep external Actions pinned and preserve the existing supply-chain/OSV gates.

## Continuity rule

A new agent must be able to continue without prior chat memory.

If a decision exists only in a conversation, it is not durable until it is represented in the appropriate repository document.

The repository must always answer:
- where the program is,
- what is next,
- what is frozen,
- what is still unknown,
- what evidence justified a non-obvious decision,
- what exact gate closes the current slice.
