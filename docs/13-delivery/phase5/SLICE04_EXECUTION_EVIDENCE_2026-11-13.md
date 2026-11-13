# Phase 5 Slice 04 execution evidence

Slice 03 merged through [PR #105](https://github.com/omarkhair70-droid/hiltech/pull/105) at `64bb230c0adbf25f65a11e31f506b8a6ffc71722`. Post-merge [Bootstrap 43342566106](https://github.com/omarkhair70-droid/hiltech/actions/runs/43342566106) passed on main.

## Synthetic operator exercise

1. In Windows Warehouse Setup, select the warehouse and refresh the Asset service queue. Open a blocked Asset to see all current reasons and their source record IDs and versions.
2. Use an Asset type requiring calibration and start with a verified storage location. Confirm that missing or expired calibration blocks availability. Record a calibration with the observed timestamp and result. A passing result needs a validity end. The latest observation by calibration time governs, and a failed result supersedes an earlier pass. Validity ends at the recorded timestamp.
3. Report damage, missing or lost. Use the incident review and resolution actions as a Warehouse Manager or Asset Master Manager. An open or under-review incident blocks availability. Completing an incident leaves independent blockers visible.
4. Send the Asset for maintenance or calibration. The active service blocks availability. Complete the service with a note, then record a separate calibration result when appropriate. Maintenance due is derived from the last completed maintenance or, before the first service, Asset registration, using the pinned policy interval. The policy controls whether a due service blocks availability. A missing typed policy fails closed when a policy is referenced.
5. Enter the opaque tag on Android. Verify the safe status, blocking reasons, calibration validity, and certificate-on-file indicator. Current Warehouse readers and the current Employee custodian may see incident categories. Narrative, evidence identifiers, provider details and acquisition value stay out of the Android response.

Availability is evaluated against the current PostgreSQL records and server time. A single read evaluates all reasons against one database snapshot. Asset service commands use the Passport version and the existing Asset row lock, and record audit and domain events inside the command transaction. When a service or incident also has its own version, the command checks both versions. Employee opening custody is marked unavailable without recording a routine checkout.

## Real-data handoff

Supply the approved private equipment register with Asset type mappings and the reviewed calibration requirements, valid intervals, certificate examples and applicable maintenance policies. Confirm which operator can review certificates and complete service. Use the restricted evidence channel in the [Slice 01 runbook](./SLICE01_EXECUTION_EVIDENCE_2026-11-08.md). Real records, physical scanners and printers, and production activation remain pending. Slice 05 follows human review, merge and post-merge Bootstrap.
