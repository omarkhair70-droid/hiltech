# Native product verification

Date: 2026-11-25

Status: active engineering contract; acceptance remains open

## Source and publication

PR #242 is the reviewed integrated candidate. PR #243 contains follow-up code,
tests and sanitized engineering documentation and targets the candidate branch.
Always fetch and inspect their current heads. Reconcile unique work in other
checkouts before merging; preserve dirty files, local history and evidence.

This repository is public. Detailed internal audits, company-specific matrices,
role assignments, deployment evidence and the canonical closeout register remain
private. Do not commit copies, private links, secrets or raw business evidence.
Use synthetic fixtures for public tests. A public PR may describe a technical
defect, its fix and the scope of validation without reproducing private findings.

## One journey, explicit evidence

For each bounded repair, connect the source requirement, entry route, controls,
form fields, permissions, command, response and resulting source record. Review
Windows and Android separately, including Arabic and English, narrow and wide
layouts, keyboard/focus behaviour and errors. Shared code does not prove that
both native hosts mount or execute the interaction.

Use these evidence states independently:

| Evidence | Meaning |
| --- | --- |
| Implemented | The inspected source contains the change. |
| Test passed | The named test passed against recorded source and configuration. |
| Test failed | The attempted test failed; preserve the failure and its scope. |
| Not tested | No successful attempt exists, including inaccessible environments. |
| Decision required | A specific unresolved policy decision prevents that part. |
| Journey accepted | The complete role journey passed on the intended device and server. |

Record the actual commit or source hashes, build/runtime, actor scope, test
fixture, command result and limitations. Compilation, a screenshot, a source
inventory and a green CI job are useful evidence of different things; none is
automatically complete product acceptance. A render fixture must return a
failure when capture fails. Registry publication is not deployment.

## Interaction and authority contracts

- Dates use date controls and preserve date-only API values across time zones.
- Record references use source-backed choices. Labels do not become authority.
- Invalid numeric input produces a field error, never an implicit zero or null.
- Preserve an editing draft until the matching command succeeds. Separate
  validation/rejection, version conflict and an unknown network outcome.
- Retry an unknown outcome using the same operation ID, timestamp and payload.
  Do not silently substitute a new operation or overwrite a newer server version.
- Discard stale callbacks when the account, organization, session or route changes.
- Read access and delegation are distinct. Reuse source-backed delegation ceilings
  and scopes; do not infer power to grant a responsibility from merely holding it.
- Preserve source guards, independent approvals, current membership, audit and
  idempotency. No bypass is justified by making a screen easier to reach.

## Completion discipline

Update the existing private closeout record with new defects, results and
remaining limits. The public change description should identify the repaired
behaviour and tested paths. Do not claim that all forms, roles, devices or
integrations are closed because one interaction passed. Keep the current draft
review open until its required verification and source reconciliation are met.
