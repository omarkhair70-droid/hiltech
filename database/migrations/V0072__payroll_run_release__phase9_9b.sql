-- Phase 9 / 9B — version-bound, append-only payroll release record.
-- PROVISIONAL V0072; DevOps must allocate one contiguous Flyway sequence.
-- Release is a company's Finance-REVIEW handoff, NOT a payment instruction.

ALTER TABLE payroll_run_version
    ADD CONSTRAINT uq_payroll_run_version_id_organization
    UNIQUE (id, organization_id);

CREATE TABLE payroll_run_release (
    id uuid PRIMARY KEY,
    operation_id uuid NOT NULL UNIQUE,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    run_version_id uuid NOT NULL,
    run_source_fingerprint char(64) NOT NULL,
    policy_key varchar(96) NOT NULL
        CHECK (policy_key ~ '^[A-Z][A-Z0-9_]{2,95}$'),
    policy_version integer NOT NULL CHECK (policy_version >= 1),
    decision_path varchar(32) NOT NULL
        CHECK (decision_path IN ('ROUTINE_AUTHORIZED', 'APPROVED_EXCEPTION')),
    approval_request_id uuid NULL REFERENCES approval_request(id) ON DELETE RESTRICT,
    released_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    released_at timestamptz NOT NULL,
    UNIQUE (run_version_id),
    CONSTRAINT fk_payroll_release_org_run
        FOREIGN KEY (run_version_id, organization_id)
        REFERENCES payroll_run_version(id, organization_id) ON DELETE RESTRICT,
    CHECK (
        (decision_path = 'ROUTINE_AUTHORIZED' AND approval_request_id IS NULL)
        OR
        (decision_path = 'APPROVED_EXCEPTION' AND approval_request_id IS NOT NULL)
    )
);
CREATE INDEX idx_payroll_release_org_date
    ON payroll_run_release(organization_id, released_at DESC);

CREATE TRIGGER payroll_9b_release_immutable
    BEFORE UPDATE OR DELETE ON payroll_run_release FOR EACH ROW
    EXECUTE FUNCTION payroll_9a_immutable_guard();
