-- Phase8O UNNUMBERED: approved P10 commercial source -> Finance draft candidate.
-- This is NOT an issued customer tax invoice, AR journal or cash collection.
-- No default tax policy, invoice sequence, maturity, billing milestones seeded.

CREATE TABLE finance_ar_invoice_candidate (
 id uuid PRIMARY KEY,
 operation_id uuid NOT NULL UNIQUE,
 organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
 opportunity_id uuid NOT NULL,
 project_id uuid NOT NULL,
 customer_organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
 signed_contract_id uuid NOT NULL,
 verified_client_po_id uuid NOT NULL,
 commercial_source_sha256 char(64) NOT NULL
     CHECK(commercial_source_sha256 ~ '^[a-f0-9]{64}$'),
 billing_milestone_evidence_id uuid NOT NULL REFERENCES evidence(id) ON DELETE RESTRICT,
 contractual_billable_reference varchar(180) NOT NULL
     CHECK(length(btrim(contractual_billable_reference))>=8),
 legal_tax_review_reference varchar(180) NOT NULL
     CHECK(length(btrim(legal_tax_review_reference))>=8),
 invoice_issue_date date NOT NULL,
 invoice_due_date date NOT NULL,
 currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
 requested_amount numeric(24,6) NOT NULL CHECK(requested_amount>0),
 state varchar(18) NOT NULL DEFAULT 'DRAFT' CHECK(state='DRAFT'),
 created_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
 created_at timestamptz NOT NULL,
 correlation_id varchar(128) NOT NULL CHECK(length(btrim(correlation_id))>0),
 CHECK(invoice_due_date>=invoice_issue_date),
 CHECK(organization_id<>customer_organization_id),
 UNIQUE(organization_id,id),
 UNIQUE(organization_id,opportunity_id,billing_milestone_evidence_id)
);
CREATE INDEX idx_finance_ar_candidate_org_customer
    ON finance_ar_invoice_candidate(organization_id,customer_organization_id);
CREATE TRIGGER finance_ar_candidate_immutable
BEFORE UPDATE OR DELETE ON finance_ar_invoice_candidate FOR EACH ROW
EXECUTE FUNCTION finance_ap_reject_snapshot_mutation();

-- Future issuance must check under a single locked source+billing schedule:
-- verified billing evidence, approved legal tax reference, real numbering,
-- sum of already ISSUED invoices <= award/milestone authorized amount.
-- NEVER infer invoice number or tax treatment from these draft records.
