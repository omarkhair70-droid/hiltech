-- Phase 7 / S05 hardening: supplier invoice numbering must not be
-- accidentally duplicated through case or leading/trailing whitespace.
-- Append a new migration: never rewrite V0052's financial evidence history.
CREATE UNIQUE INDEX uq_supplier_invoice_number_casefold
ON procurement_supplier_invoice_intake (
    organization_id,
    supplier_organization_id,
    LOWER(BTRIM(invoice_number))
);
