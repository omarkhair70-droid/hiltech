-- Integration-only pending DDL; DevOps must sequence it after Payroll 9A-9C.
-- Finance review envelope, NOT payable/ledger, bank transfer or salary payment.
-- Deliberately does not copy per-employee salary lines outside Payroll.
CREATE TABLE finance_payroll_review_batch (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    payroll_period_id uuid NOT NULL REFERENCES payroll_period(id) ON DELETE RESTRICT,
    run_version_id uuid NOT NULL,
    release_id uuid NOT NULL REFERENCES payroll_run_release(id) ON DELETE RESTRICT,
    operation_id uuid NOT NULL,
    requested_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    run_source_fingerprint char(64) NOT NULL CHECK (run_source_fingerprint ~ '^[a-f0-9]{64}$'),
    manifest_sha256 char(64) NOT NULL CHECK (manifest_sha256 ~ '^[a-f0-9]{64}$'),
    policy_key varchar(96) NOT NULL CHECK (policy_key ~ '^[A-Z][A-Z0-9_]{2,95}$'),
    policy_version integer NOT NULL CHECK (policy_version > 0),
    currency varchar(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    payable_total numeric(24,6) NOT NULL CHECK (payable_total >= 0),
    line_count integer NOT NULL CHECK (line_count BETWEEN 1 AND 10000),
    positive_transfer_count integer NOT NULL
        CHECK (positive_transfer_count >= 0 AND positive_transfer_count <= line_count),
    review_state varchar(32) NOT NULL DEFAULT 'REVIEW_REQUIRED'
        CHECK (review_state = 'REVIEW_REQUIRED'),
    bank_execution_available boolean NOT NULL DEFAULT false
        CHECK (bank_execution_available = false),
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT fk_finance_payroll_review_run_org
        FOREIGN KEY (run_version_id, organization_id)
        REFERENCES payroll_run_version(id, organization_id) ON DELETE RESTRICT,
    UNIQUE (organization_id, run_version_id),
    UNIQUE (organization_id, release_id),
    UNIQUE (organization_id, operation_id)
);

-- DB guards prevent direct SQL from creating a review envelope against an
-- unrelated or superseded Payroll source. This is still NOT Finance approval.
CREATE FUNCTION finance_payroll_review_batch_source_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM payroll_run_release pr
        JOIN payroll_run_version run
          ON run.id = pr.run_version_id
         AND run.organization_id = pr.organization_id
        WHERE pr.id = NEW.release_id
          AND pr.organization_id = NEW.organization_id
          AND pr.run_version_id = NEW.run_version_id
          AND pr.run_source_fingerprint = NEW.run_source_fingerprint
          AND pr.policy_key = NEW.policy_key
          AND pr.policy_version = NEW.policy_version
          AND run.source_fingerprint = NEW.run_source_fingerprint
          AND run.payroll_period_id = NEW.payroll_period_id
          AND run.currency = NEW.currency
          AND run.net = NEW.payable_total
    ) THEN
        RAISE EXCEPTION 'Finance Payroll review requires the exact released Payroll source';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER finance_payroll_review_source_insert
    BEFORE INSERT ON finance_payroll_review_batch FOR EACH ROW
    EXECUTE FUNCTION finance_payroll_review_batch_source_guard();

CREATE TRIGGER finance_payroll_review_immutable
    BEFORE UPDATE OR DELETE ON finance_payroll_review_batch FOR EACH ROW
    EXECUTE FUNCTION payroll_9a_immutable_guard();

CREATE INDEX idx_finance_payroll_review_org_created
    ON finance_payroll_review_batch(organization_id, created_at DESC);
