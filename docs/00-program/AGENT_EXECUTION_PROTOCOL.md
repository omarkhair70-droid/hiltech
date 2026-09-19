# HILTECH Agent / Session Continuity Protocol

Date: 2026-09-19
Status: **ACTIVE**

## Purpose

Prevent project quality from depending on one long chat, one Codex session, or one agent's memory.

HILTECH must remain executable by a fresh agent that has no conversation history.

The durable memory hierarchy is:

1. Git history + current branch state,
2. canonical program/status documents,
3. frozen contracts and ADRs,
4. phase execution records,
5. reality evidence register,
6. CI evidence.

Chat memory is convenience only.

## Session start

Every new implementation session must:

1. inspect `main`,
2. fetch the current working branch,
3. inspect current open PRs,
4. inspect recent Actions for the branch/head,
5. read root `AGENTS.md`,
6. read `CURRENT_PROGRAM_STATUS.md`,
7. read `IMPLEMENTATION_ORDER.md`,
8. read only the contracts relevant to the current phase/slice,
9. confirm whether the requested work is implementation, design proof, reality closure, or activation/cutover work.

Never assume a SHA, PR state, run result, or branch position from an old conversation.

## Work unit

The preferred work unit is a complete vertical slice.

A slice can include:

`contract → migration → domain/application → auth/audit → API → client/local → UI → offline → tests → observability → smoke`

Not every slice uses every layer, but each layer that is relevant must close with the slice.

Avoid:
- building the whole DB first,
- building the whole backend before client behavior is exercised,
- building UI on invented API behavior,
- adding infrastructure products because they are familiar rather than required.

## Branch / commit discipline

- branch from the actual current `main`,
- use a branch scoped to the phase/slice/change,
- do not force-update a moved branch,
- keep commits explainable,
- generated artifacts are committed only when the repository contract explicitly requires them,
- exact tested head must match the PR head before merge.

## CI discipline

A statement such as "tests should pass" is not evidence.

Evidence is the actual run result on the actual head.

Before merge:
- relevant jobs must be green,
- security/dependency gates must be green,
- the head must not have moved,
- no hidden architecture contradiction may be waived silently.

After merge:
- main-push verification remains active for foundation-affecting changes.

## Contract change rule

There are three kinds of discoveries:

### A. Implementation detail
Can be resolved without changing product meaning.

Examples:
- container volume path,
- generated-source verifier,
- build configuration.

Handle in code + tests.

### B. Phase-local reality refinement
Useful when that domain phase arrives.

Examples:
- HILTECH daily field allowance semantics,
- stock manufacturer part numbers,
- client progress-claim terminology.

Record in the Reality Evidence Register.
Do not reopen unrelated completed phases.

### C. Cross-cutting contradiction
Would make a frozen assumption unsafe/wrong.

Examples:
- identity provider cannot satisfy required auth mode,
- offline contract cannot preserve required work,
- provider cannot meet evidence integrity requirement.

Use formal change control before continuing.

## Design continuity

The repository accepts tool-neutral rendered design proof.

Figma is optional.

The design source must make:
- hierarchy,
- states,
- permissions,
- local vs authoritative truth,
- error/conflict recovery,
- RTL,
- adaptive behavior

reviewable before production UI is considered complete.

For first slice, the accepted proof lives under:
`prototypes/first-slice-design/`

## Reality evidence continuity

Raw company documents are not durable product documentation.

When a private workbook reveals a reusable fact:

1. write the fact in `REALITY_EVIDENCE_REGISTER.md`,
2. classify confidence/source,
3. identify affected phase(s),
4. record the product consequence,
5. avoid copying sensitive rows/amounts unless truly required,
6. create sanitized test fixtures only when implementation needs representative data.

This allows future phases to use the learning without carrying private spreadsheets in Git.

## Session close

Before ending a substantial implementation session, ensure a fresh agent can answer:

- What branch/head contains the work?
- What was implemented?
- What remains?
- What CI run proves it?
- Did any canonical contract change?
- What is the exact next slice?

If the answer is not visible in GitHub, the handoff is incomplete.
