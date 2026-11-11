# Phase 5 Slice 01 execution evidence

Status **Draft, acceptance pending**

[Issue #100](https://github.com/omarkhair70-droid/hiltech/issues/100) tracks execution. [PR #67](https://github.com/omarkhair70-droid/hiltech/pull/67) remains the Slice 01 branch.

## Operator setup

After applying V0026, an authorized deployment operator may use the packaged server's explicit provisioning command in a disposable or owner-approved environment. The command runs without an HTTP listener. Use deployment credentials from the approved secret source and confirm the organization, Warehouse and manager against the change record. Supply the current organization identity of the operator who performs the change.

```text
java -jar <server-boot-jar> provision-warehouse-manager <org-id> <warehouse-id> <operator-id> <manager-id> <code> "<warehouse name>"
```

The command requires explicit `HILTECH_DB_URL`, `HILTECH_DB_USER`, `HILTECH_DB_PASSWORD`, `HILTECH_FGA_API_URL`, `HILTECH_FGA_STORE_ID` and `HILTECH_FGA_MODEL_ID`. Supply a new Warehouse UUID for a verified new facility or the existing Warehouse UUID and its matching code and name. Both identities must be current organization members. PostgreSQL commits the initial binding, audit event and projection intents together. The normal projector must apply the intents before the manager can use the API. Repeating the same setup returns the existing current binding. Ended manager history requires a separate authority review.

## Workbook staging

Choose the primary sheet and confirm the physical header and last item row against the private workbook. Exclude totals and duplicated filtered views. Run a local intake check before sending anything.

```text
python scripts/stage_legacy_inventory.py <workbook.xlsx> --sheet <sheet> --header-row <n> --last-row <n>
```

To stage the checked range in an approved environment, add `--api-url <https-base-url>`, `--organization-id <uuid>`, `--device-id <registered-installation-uuid>`, `--operation-id <new-uuid>` and `--occurred-at <original-iso-8601-timestamp>`. Enter the authorized access token at the prompt. For a retry, reuse the same workbook, sheet, range, organization, actor, operation ID and timestamp. The script hashes and parses the same immutable bytes, submits physical row numbers, and leaves candidates unclassified. Formula and error cells are preserved as source evidence. Inspect the batch ID in Warehouse Setup.

### Compare the staged batch with the private workbook

Use the original sheet and row range. Copy the batch ID from Warehouse Setup. When the source custodian has recorded a SHA-256, supply it as `--expected-sha256` in the initial intake and in the comparison. A mismatch stops the command before authentication or network access.

```text
python scripts/stage_legacy_inventory.py <workbook.xlsx> --sheet <sheet> --header-row <n> --last-row <n> --api-url <https-base-url> --organization-id <uuid> --device-id <registered-installation-uuid> --compare-batch <uuid> --expected-sha256 <custodian-digest>
```

Comparison reads the authorized batch and checks its recorded digest, selected sheet's exact physical row numbers, and the redacted values returned by the API. The report gives counts and physical row numbers. Formula, restricted, and unmapped cells still require inspection in the original workbook by the authorized operator. Record the source custodian, digest, sheet and range, batch ID, unresolved mappings, and operator decision in the private acceptance record.

## Evidence

- Baseline `870bba0c1e8d79ee8199a11771f2f35bba9e5887` passed all 30 GitHub checks, including provisioning, V0026, synthetic Desktop render, and the Android native OIDC regression. [Bootstrap](https://github.com/omarkhair70-droid/hiltech/actions/runs/42422666802) · [Warehouse Setup](https://github.com/omarkhair70-droid/hiltech/actions/runs/42422666864) · [Native OIDC](https://github.com/omarkhair70-droid/hiltech/actions/runs/42422666773)
- Handoff head `96e6aebf23f6b4fc5cf37858610f160bca5c89f4` had a cancelled native OIDC run. Logs for its cancelled Android job were unavailable. The same regression passed at `fc8e72101fb2f3d82c771ae5af7c9e5df769d5e8`. [Native OIDC run 42421237449](https://github.com/omarkhair70-droid/hiltech/actions/runs/42421237449)
- Bootstrap at `fc8e72101fb2f3d82c771ae5af7c9e5df769d5e8` passed real PostgreSQL migration and JDBC staging/review and OpenFGA stale USER/TEAM authorization. This evidence predates the operator provisioning command. [Bootstrap run 42421237571](https://github.com/omarkhair70-droid/hiltech/actions/runs/42421237571)
- The import preview uses a source-layout column allowlist. Ordinary review accepts candidate edits and rejection with a reason. Approval requires verified source and mapping evidence. A declared SHA-256 and deduplication check do not verify the workbook bytes.
- Candidate issue clearance requires an explanation and server-side validation of the revised fields. Source mapping, category, serial and opening-location issues remain until the relevant evidence exists. Updated receipt fingerprints distinguish field boundaries in Warehouse and import commands. Receipts from the earlier draft format may return a conflict after upgrade. Inspect existing staged state before retrying.

## Remaining closure gates

- Verify the current head, including trusted provisioning, site/organization context constraints, and Windows Warehouse Setup renders.
- Verify the authorized intake against the real UTP, FIBER, ACTIVE and Accessories inputs with a HILTECH operator. The server currently records the submitted digest and rows. An independently verified source and field-mapping path is still required before approval can be enabled. Keep raw workbooks in the approved private evidence location.
- Capture operator acceptance for Warehouse Setup and import review, then complete the Slice 01 gap review.
- Obtain human review and merge approval, then verify post-merge Bootstrap on `main`. Slice 02 begins after those gates close.

## Private workbook handoff

The original workbooks are currently unavailable. When the files become available, share a restricted link to the HILTECH-approved private evidence location with the authorized operator. Keep original workbook bytes out of the repository, PR attachments and public artifact links.

| Private original | Primary sheet |
| --- | --- |
| `UTP 01-04-2026(3).xlsx` | `Main` |
| `FIBER 01-04-2026(3).xlsx` | `FIBER` |
| `ACTIVE 01-04-2026 -(3).xlsx` | `SAMPLE` |
| Legacy Accessories workbook recorded as `.xlsx(3)` | `أكسسوارات` |
