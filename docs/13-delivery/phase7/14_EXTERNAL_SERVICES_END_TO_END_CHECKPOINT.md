# Phase 7 — External services source checkpoint

Date: 2026-11-23. Engineering Draft PR #181, stacked on #178 -> #174 -> #162.

**Engineering source exists but this is NOT tested, merged, deployed or production-accepted.**

## Simple company flow

1. Employee requests actual external service for an existing team; independent technical reviewer accepts source.
2. Buyer creates the RFQ from exact reviewed need, records supplier-specific actual quotes and private READY files. Native upload-and-record helper uses canonical Evidence reserve/upload/finalize; no fictitious StockItem.
3. Buyer selects original current quote. Canonical existing ApprovalEngine requests the company approval without bypass; current issuer records one internal service order only after approved decision.
4. Same issuer can attest real external dispatch and supplier acknowledgement, providing channel, original reference, timestamp. This service never sends emails or makes any claim about an automatic supplier delivery.
5. Authorized reviewer attaches finalized private service evidence and records ACCEPTED, NEEDS_REWORK or REJECTED; latest review controls status. Further reviews are allowed. No Warehouse stock receiving.
6. Finance captures real invoice and restricted original attachment, checks against issued service order and latest acceptance, logs exact mismatch classification. Only latest genuinely MATCHED invoice can be handed over for Finance review; this does not create payable or transfer funds.

## Existing system reuse

Current company teams/members, supplier master/link history, ApprovalEngine, source version guards, private Evidence lifecycle, audit, idempotency. Typed KMP clients and Compose work surface exist for Windows and Android. Native file-picker/shell binding remains with UX PR #171. Finance consumption remains with Phase8.

## Release gates

- Provisional migrations V0061-V0064 **collide with** Finance placeholders V0061/V0062. DevOps PR #173 allocates final gap-free sequence. Never merge these drafts as-is.
- Shared EvidenceLifecycleApi must reconcile with PR #176. No unreviewed overwrite.
- S07A prior targeted database tests succeeded, but a Boot startup constructor injection problem was found. Fixed on all three branches. New exact-head Spring Boot/PG workflows must prove it.
- PR #181 latest Flyway V0061-V0064 PostgreSQL migration, server service approval/quote/evidence/dispatch/finance tests, Windows build and Android APK are not yet green.
- Role-based device smoke and protected actual PDF bytes must pass before stage merge. No supplier sends, payment, cash/bank effects or production changes.

**Do not mark Phase7 done just because code was written or GitHub checks are queued.**
