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


-- Defense in depth: even direct SQL writers cannot fake a Finance-review
-- release for a different source, superseded generation, missing company
-- policy, or unapproved exception. Application OpenFGA/role grants remain
-- additional mandatory checks.
CREATE FUNCTION payroll_9b_release_insert_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM payroll_authority_binding grant_row
        JOIN user_identity principal
          ON principal.id = grant_row.identity_id
        JOIN organization_membership membership
          ON membership.user_identity_id = principal.id
         AND membership.organization_id = grant_row.organization_id
        JOIN organization org
          ON org.id = grant_row.organization_id
        WHERE grant_row.organization_id = NEW.organization_id
          AND grant_row.identity_id = NEW.released_by_user_id
          AND grant_row.capability = 'REVIEW'
          AND grant_row.active = true
          AND grant_row.valid_from <= statement_timestamp()
          AND (grant_row.valid_until IS NULL OR
               grant_row.valid_until >= statement_timestamp())
          AND principal.status = 'ACTIVE'
          AND org.status = 'ACTIVE'
          AND membership.state = 'ACTIVE'
          AND membership.valid_from <= statement_timestamp()
          AND (membership.valid_until IS NULL OR
               membership.valid_until >= statement_timestamp())
    ) THEN
        RAISE EXCEPTION 'Current payroll reviewer authority and membership required';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM payroll_run_version rv
        WHERE rv.id = NEW.run_version_id
          AND rv.organization_id = NEW.organization_id
          AND rv.source_fingerprint = NEW.run_source_fingerprint
          AND rv.state = 'DRAFT'
          AND NOT EXISTS (
              SELECT 1
              FROM payroll_run_version newer
              WHERE newer.payroll_period_id = rv.payroll_period_id
                AND newer.generation > rv.generation
          )
    ) THEN
        RAISE EXCEPTION 'Payroll source or release generation mismatch';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM approval_policy_version p
        WHERE p.organization_id = NEW.organization_id
          AND p.policy_key = NEW.policy_key
          AND p.version_number = NEW.policy_version
          AND p.lifecycle_state = 'ACTIVE'
          -- Both the recorded event instant AND the live database
          -- statement time must be inside the currently active company policy.
          -- A backdated insert must never resurrect an expired policy.
          AND p.effective_from <= NEW.released_at
          AND (p.effective_to IS NULL OR p.effective_to > NEW.released_at)
          AND p.effective_from <= statement_timestamp()
          AND (p.effective_to IS NULL OR p.effective_to > statement_timestamp())
          AND (
              (NEW.decision_path = 'ROUTINE_AUTHORIZED' AND
               p.approval_required = false AND p.step_mode = 'NO_APPROVAL_REQUIRED')
              OR
              (NEW.decision_path = 'APPROVED_EXCEPTION' AND
               p.approval_required = true AND p.step_mode = 'SINGLE')
          )
    ) THEN
        RAISE EXCEPTION 'Payroll release policy is not active for this path';
    END IF;

    IF NEW.decision_path = 'APPROVED_EXCEPTION' AND NOT EXISTS (
        SELECT 1 FROM approval_request a
        WHERE a.id = NEW.approval_request_id
          AND a.organization_id = NEW.organization_id
          AND a.subject_type = 'PAYROLL_RUN_VERSION'
          AND a.subject_id = NEW.run_version_id
          AND a.subject_version = 1
          AND a.policy_key = NEW.policy_key
          AND a.policy_version = NEW.policy_version
          AND a.state = 'APPROVED'
    ) THEN
        RAISE EXCEPTION 'Payroll exception approval is not approved for this version';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER payroll_9b_release_insert_validate
    BEFORE INSERT ON payroll_run_release FOR EACH ROW
    EXECUTE FUNCTION payroll_9b_release_insert_guard();
