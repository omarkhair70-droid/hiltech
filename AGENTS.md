# HILTECH OS — Agent Execution Contract

Status: **ACTIVE / MANDATORY ENTRYPOINT**

This repository is the single source of truth for HILTECH OS.

Any coding agent, Codex session, new chat, automation, or human contributor must treat repository state and canonical documents as authoritative. Conversation memory is never the project source of truth.

## Current engineering baseline — 2026-11-25

The active work is end-to-end product verification and repair on the unified
candidate, not resuming a historical phase cursor.

- Unified candidate: PR #242, `integration/hiltech-unified-staging-20261124`,
  reviewed at `4a3709c5707d39135a6e8034d5ff1eb8ef949145`.
- Follow-up changes: PR #243, `product/hiltech-reconstruction-20261125`.
  Fetch the current heads and inspect their diffs before continuing. Neither
  branch name nor a historical green check proves deployment or acceptance.
- Read `docs/13-delivery/NATIVE_PRODUCT_VERIFICATION.md` for the current
  engineering evidence contract and publication boundary.
- Company responsibility and delegation commands are described in
  `docs/13-delivery/RESPONSIBILITY_ADMINISTRATION_CONTRACT.md`; payroll bank
  evidence and result semantics are in
  `docs/13-delivery/FINANCE_PAYROLL_DISBURSEMENT_CONTRACT.md`.
  These are implementation contracts. Verify the current native flow, provider
  prerequisites and exact source/test results before treating a journey as ready.
- Reconcile other checkouts and unique local work before merging. Preserve
  dirty files and evidence; never reset or clean to make a comparison easier.
- Complete interactions across UI, command response, permissions and source
  truth. Screen access is separate from authority to execute an operation.
- Record new defects in the existing private closeout register; do not create
  another phase or publish a duplicate internal audit in this public repository.
- Publish code, synthetic tests and sanitized engineering documentation only.
  Internal reports, role assignments, detailed audit matrices and operational
  evidence stay private. Do not embed private artifact links or identifiers.

## Historical records

Past phase checkpoints remain in Git history and the domain contracts.
They are not active execution instructions or acceptance results for the
unified product. Use the current integrated source and actual verification.

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
- verify that the requested work belongs to the current authorized product scope.

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
- open/update the isolated PR against the verified unified candidate; do not retarget to `main` merely to trigger checks,
- merge only the exact tested head,
- never force-push over branch movement.

## Historical decomposition rule

The historical phases organize contracts; they do not replace current end-to-end
product requirements or require creating a new phase for every repair.

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

Record engineering decisions in the appropriate sanitized repository document.
Keep sensitive operational decisions and their evidence in the private canonical
record; publishing them is not a prerequisite for continuing authorized work.

The sanitized engineering record must answer:
- where the program is,
- what is next,
- what is frozen,
- what is still unknown,
- what evidence justified a non-obvious decision,
- what exact gate closes the current slice.
