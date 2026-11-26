# Phase 7 / Slice 05 — Verified supplier invoice → canonical three-way match → Finance review

Date: 2026-11-22
Status: **DRAFT engineering implementation on stacked PR #158; release and E2E acceptance NOT CLOSED**

## Source of truth

S03 issued PO V1 is the commercial record. S04 Warehouse `receiving_record`, `receiving_line`, `receiving_discrepancy`, and canonical StockMovement are the physical source. S05 private supplier invoice Evidence and append-only match events are review evidence. **None of the new APIs creates a payable, payment, bank instruction, settlement, or automatic supplier notification.**

### S05 schema and services

- V0052 immutable `procurement_supplier_invoice_document_slot` and `procurement_supplier_invoice_intake`. Invoice uniqueness by purchasing organization, supplier and invoice number; single trusted READY invoice Evidence bound to exact slot/org/PO and supplier; immutable commercial invoice snapshot.
- Invoice Evidence uses the existing private signed-upload, checksum, security scan and finalize flow for PDF/JPEG/PNG/WebP; requires `SUPPLIER_INVOICE_DOCUMENT` and HIGHLY_RESTRICTED/INTERNAL_ONLY classification.
- `SupplierInvoiceEvidenceTargetAdapter` authorizes the target with current organization member/Finance authority binding from canonical ApprovalPersistence. Default OFF until real company-controlled authority is provided.
- `SupplierInvoiceMatchingService` supports scoped eligible-PO queue, immutable invoice capture, explainable preview and immutable match revisions. Compares PO against **physically posted canonical Warehouse receipts** and invoice quantity, currency, price, tax and discrepancy state. Fingerprint includes exact receiving-line IDs and versions to invalidate stale source matches.
- Match state distinguishes MATCHED, PARTIAL_RECEIPT, MISSING_RECEIPT, QUANTITY_MISMATCH, PRICE_MISMATCH, TAX_MISMATCH, CURRENCY_MISMATCH, REVIEW_REQUIRED. A source-current MATCHED review can create a Finance review handoff but never a payable.
- Typed KMP online API; Windows Compose Finance invoice capture with real PDF/image file picker, SHA-256/READY Evidence pipeline, entered invoice values, queue/variance review and two-step handoff. Android contains source-bound Finance review surface with default-off UI gate; no offline financial command.

### Independent gates

- Server `hiltech.procurement.supplier-invoices.enabled=false` default; `hiltech.procurement.invoice.finance-authority-key` unconfigured.
- Desktop `HILTECH_SUPPLIER_INVOICE_FINANCE_UI_ENABLED` opt-in.
- Android Gradle `hiltech.supplierInvoiceFinanceEnabled=false` by default.
- Never seed finance approvers, supplier invoices, supplier records or purchase orders from fictional demo data.

### Verification requirements before acceptance

1. Exact-head PR #158 PostgreSQL V0052 migration test + actual JVM tests, KMP compilation, Windows/Android build and Spring container proof must pass. Do not extrapolate from earlier green S04 base.
2. Simulate authorized Finance actor, revoked member, wrong supplier/org, duplicated invoice number, non-READY/quarantined evidence, changed/partial receipts, rejected-only goods, tax/price/currency mismatch, replay/stale version, cancelled PO and old policy.
3. Confirm actual operator uses Windows uploader and Finance workspace on device with genuine permitted redacted sample PDF, and Android review works with current-source reauthorization.
4. Validate company Finance handoff semantics with Ahmed/accountant and bank/payable ownership before enabling any live AP feature.

### Engineering closeout correction (2026-11-22, verification pending)

- A supplier invoice is **evidence**, even when wrong: capture must retain an overbilled invoice and report `QUANTITY_MISMATCH` instead of discarding its source record. No match/Finance handoff is possible until the discrepancy is resolved.
- A current dispatched PO is required for new capture/match/handoff, not only an internally issued PO.
- The S05 match source fingerprint includes the exact immutable Warehouse discrepancy event and rejection IDs in addition to posted receipt line IDs/versions and PO commercial facts. Previously recorded match evidence cannot silently survive newly recorded Warehouse discrepancies.
- A separate V0055 unique case-folded supplier invoice number index protects against capitalization/edge-whitespace duplicates within the same purchasing company and supplier.
- Added deterministic classification tests for MATCHED, missing/partial receiving, over- and under-billing, wrong currency, unit price/tax mismatch and physical discrepancy. Exact-head CI confirmation remains required; do not update the closure state from code-only evidence.

Status **NOT RELEASED** until these checks and Phase 7 cross-role final gap review pass.
