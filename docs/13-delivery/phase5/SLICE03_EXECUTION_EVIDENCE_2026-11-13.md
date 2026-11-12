# Phase 5 Slice 03 execution evidence

## Baseline

Slice 02 merged through [PR #104](https://github.com/omarkhair70-droid/hiltech/pull/104) at `2dc3d1d9fae61819cfe67e344bbbcbe0d6789d9a`. Post-merge [Bootstrap 43317736430](https://github.com/omarkhair70-droid/hiltech/actions/runs/43317736430) succeeded on `main` at that commit.

## Operator review

1. In Windows Warehouse Setup, select a warehouse, search in Asset Passport, and register an Asset against an active Asset type revision. Use a test Asset code and serial in a disposable environment. Confirm that a duplicate Asset code is rejected and a duplicate normalized serial is flagged for review.
2. Issue a primary tag using an allowed type. The selectable opaque payload can be copied into an approved label workflow. Replace the tag and verify that the same Asset keeps both tags in history while only the replacement resolves.
3. Record initial storage on registration or explicitly initialize opening custody against an active storage location or Employee. The Passport shows the current location or Employee and linked Project, Site, or Work identifiers. A Warehouse Manager can correct the current custody movement by recording the reason. Check that the original movement stays in history.
4. In the Android signed-in shell, focus Asset tag lookup. Enter, paste, or scan the opaque code through a keyboard-input scanner and submit. An eligible Warehouse viewer, current location viewer, or current Employee custodian can read the safe result. An ended tag, unknown tag, or tag outside the caller's authority shows the same unavailable message. The Passport and Android lookup omit acquisition value.
5. Verify the current custody row against `asset_ledger_custody` using the Asset id, source movement id, destination, context, and ledger version. The SQL view derives the latest custody solely from the movement ledger. The application writes custody only through the movement transaction.

## Imported Asset gate

Review the staged row against the original private evidence and confirm its `COMPANY_ASSET` classification. A trusted deployment operator records independent source verification in `asset_import_verification`, using the exact row version and workbook SHA-256, the selected Warehouse and Asset type revision, verified manufacturer, model and serial where known, resolved issue codes, and a private evidence reference. Record distinct verifier and cutover approver identities. To establish opening custody, record the verified storage location or Employee and exact cutover time. Keep an unknown opening destination empty.

The designated approver uses **Approve verified Asset** in the Windows import review. Approval creates the Passport and binds it to the staged row. Opening is a separate command that requires the attested destination and timestamp. If the source row or approval has changed, recheck the private evidence before taking any action.

## Acceptance boundary

Synthetic fixtures exercise the custody transaction, duplicate identity behavior, tag lifecycle, projection reconciliation, source-gated import, current-source revocation with stale OpenFGA tuples, and safe native views. The Android fixture exercises keyboard-entry submission and clears the previous Passport after an unavailable tag. Physical scanner and label-printer use remain subject to operator acceptance.

Real Asset import and operational acceptance need the representative high-value tool register, confirmed classifications and Asset type mappings, known serials and current locations or custodians, and an authorized operator to verify the source and approve cutover. Use the approved private evidence channel described in the [Slice 01 runbook](./SLICE01_EXECUTION_EVIDENCE_2026-11-08.md). Human review and merge approval follow [Issue #100](https://github.com/omarkhair70-droid/hiltech/issues/100).
