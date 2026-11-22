-- Phase 8 / 8A: source-bound, non-posting Finance review candidates.
-- PROVISIONAL V0061. DevOps/integration owner must confirm final Flyway slot before merge.
-- No transfer, ledger posting, supplier message, payroll deduction or approval is created.
CREATE TABLE finance_payable_candidate (
    id uuid PRIMARY KEY,
    operation_id uuid NOT NULL UNIQUE,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    supplier_organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    purchase_order_id uuid NOT NULL,
    supplier_invoice_id uuid NOT NULL,
    finance_handoff_id uuid NOT NULL UNIQUE
        REFERENCES procurement_supplier_invoice_finance_handoff(id) ON DELETE RESTRICT,
    matched_event_id uuid NOT NULL
        REFERENCES procurement_supplier_invoice_match_event(id) ON DELETE RESTRICT,
    invoice_evidence_id uuid NOT NULL REFERENCES evidence(id) ON DELETE RESTRICT,
    invoice_number varchar(96) NOT NULL,
    source_sha256 char(64) NOT NULL CHECK(source_sha256 ~ '^[a-f0-9]{64}$'),
    match_revision bigint NOT NULL CHECK(match_revision >= 1),
    currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
    gross_amount numeric(24,6) NOT NULL CHECK (gross_amount > 0),
    state varchar(32) NOT NULL DEFAULT 'ACCOUNTING_REVIEW_REQUIRED'
        CHECK (state = 'ACCOUNTING_REVIEW_REQUIRED'),
    recorded_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    recorded_at timestamptz NOT NULL,
    correlation_id varchar(128) NOT NULL CHECK (length(btrim(correlation_id)) > 0),
    UNIQUE (organization_id, supplier_invoice_id),
    FOREIGN KEY (purchase_order_id, organization_id)
        REFERENCES procurement_purchase_order(id, organization_id) ON DELETE RESTRICT,
    FOREIGN KEY (supplier_invoice_id, organization_id)
        REFERENCES procurement_supplier_invoice_intake(id, organization_id) ON DELETE RESTRICT
);
CREATE INDEX idx_finance_payable_candidate_org
    ON finance_payable_candidate (organization_id, recorded_at DESC);

-- Database defense against forged handoff/PO/supplier/amount/evidence combinations.
-- Recomputing current warehouse/source state is additionally required in the
-- application under the canonical PO row lock, before this INSERT.
CREATE FUNCTION guard_finance_payable_candidate_source()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM procurement_supplier_invoice_finance_handoff h
        JOIN procurement_supplier_invoice_intake i ON
            i.id = h.invoice_id AND i.organization_id = h.organization_id
        JOIN procurement_supplier_invoice_match_event m ON
            m.id = h.matched_event_id AND m.invoice_id = i.id
            AND m.organization_id = i.organization_id
        JOIN procurement_purchase_order po ON
            po.id = i.purchase_order_id AND po.organization_id = i.organization_id
        JOIN evidence e ON e.id = i.evidence_id
        WHERE h.id = NEW.finance_handoff_id
          AND h.organization_id = NEW.organization_id
          AND h.invoice_id = NEW.supplier_invoice_id
          AND h.matched_event_id = NEW.matched_event_id
          AND i.purchase_order_id = NEW.purchase_order_id
          AND i.supplier_organization_id = NEW.supplier_organization_id
          AND po.supplier_organization_id = NEW.supplier_organization_id
          AND i.invoice_number = NEW.invoice_number
          AND i.currency = NEW.currency
          AND i.total_amount = NEW.gross_amount
          AND i.evidence_id = NEW.invoice_evidence_id
          AND e.organization_id = NEW.organization_id
          AND e.target_type = 'SUPPLIER_INVOICE_DOCUMENT'
          AND e.storage_state = 'READY'
          AND m.result_code = 'MATCHED'
          AND m.source_sha256 = NEW.source_sha256
          AND m.revision = NEW.match_revision
          AND m.revision = (SELECT MAX(revision)
                           FROM procurement_supplier_invoice_match_event
                           WHERE invoice_id = i.id)
    ) THEN
        RAISE EXCEPTION 'Finance candidate source mismatch or stale invoice'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER finance_payable_candidate_source_guard
BEFORE INSERT ON finance_payable_candidate
FOR EACH ROW EXECUTE FUNCTION guard_finance_payable_candidate_source();
CREATE TRIGGER finance_payable_candidate_immutable
BEFORE UPDATE OR DELETE ON finance_payable_candidate
FOR EACH ROW EXECUTE FUNCTION reject_procurement_draft_mutation();
