# HILTECH OS — Agent Execution Contract

Status: **ACTIVE / MANDATORY ENTRYPOINT**

This repository is the single source of truth for HILTECH OS.

Any coding agent, Codex session, new chat, automation, or human contributor must treat repository state and canonical documents as authoritative. Conversation memory is never the project source of truth.

## Current program position

- Phase 0 — Repository / Engineering Foundation: **VERIFIED / MERGED**
- Phase 1 — Identity, Organization, Permissions Foundation: **VERIFIED / COMPLETE**
- Current execution phase: **Phase 2 — Shared Product Infrastructure**
- Phase 2 Slice 01: **Shared HTTP / Command Runtime — VERIFIED**
- Phase 2 Slice 02: **Evidence Metadata + Upload/Finalize — VERIFIED**
- Slice 02 canonical verified code head: `563875c0421df713b52e6661bf3d46b8c5ec5878`
- Slice 02 verification runs: Phase 2 `35433246103` / Bootstrap `35433246124` / Phase 1 OIDC `35433246188` — **PASS**
- Phase 2 Slice 02 merge commit: `21a04718414ab20ecf8deeffccfd63961e45099a`; post-merge Bootstrap run `35434157140` — **PASS**
- Phase 2 Slice 03: **Activity Events Foundation — VERIFIED**
- Slice 03 canonical implementation code head: `e1244da23a0889bab33b249bf4ef96488aef1699`
- Slice 03 verification-closure head: `1ddf37da97eb626101777f0b1ae2833bfa8baa89`
- Slice 03 verification runs: Phase 2 `35437488895` / Bootstrap `35437488932` / Phase 1 OIDC `35437489102` — **PASS**
- Slice 03 contract: `docs/13-delivery/phase2/03_ACTIVITY_EVENTS_FOUNDATION_SLICE_2026-09-19.md`
- Next vertical after Slice 03 merge: **Phase 2 / Slice 04 — Approval Engine Authority Reality Closure**
- Slice 04 implementation is **NOT AUTHORIZED** until real HILTECH approval lines, threshold semantics, delegation/absence rules, self-approval policy and execution-vs-approval authority are validated.
- First production slice contracts: **FROZEN / PASS**
- Figma: **OPTIONAL**, not an implementation blocker
- First-slice rendered design proof: **PASS**
- Infrastructure baseline: **OCI**, provider architecture already selected

Canonical current status:
`docs/00-program/CURRENT_PROGRAM_STATUS.md`

Canonical implementation order:
`docs/13-delivery/IMPLEMENTATION_ORDER.md`

Canonical first-slice stack:
`docs/12-stack/FINAL_STACK.md`

## Mandatory read order before changing code

1. `AGENTS.md`
2. `docs/00-program/CURRENT_PROGRAM_STATUS.md`
3. `docs/13-delivery/IMPLEMENTATION_ORDER.md`
4. the relevant phase/domain/workflow/object contract
5. `docs/12-stack/FINAL_STACK.md` when technology/runtime is involved
6. first-slice Freeze pack when touching Project / Site / Work / Warehouse / Evidence / Offline / Authorization contracts
7. `docs/01-reality/REALITY_EVIDENCE_REGISTER.md` when a phase depends on real HILTECH operating facts

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

## Infrastructure rule

Do not reselect hosting during feature work.

Frozen direction:
- OCI production baseline,
- Container Instances preferred,
- Compute fallback,
- OCI Database with PostgreSQL,
- OCI Object Storage/KMS/Secrets,
- OpenTelemetry remains vendor-neutral in application code.

Existing infrastructure may be reused only when it satisfies the frozen isolation/security/operability contract.

Do not create a new VM/server or buy on-prem hardware just because a feature phase starts.

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
