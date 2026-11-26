-- Period-scoped payroll input records. Amounts and policy references are
-- entered by authorized preparers and require independent ApprovalEngine review.
-- This migration supplies no compensation, rates, legal rules or authorities.
CREATE TABLE payroll_source_record (
    id uuid PRIMARY KEY,
    operation_id uuid NOT NULL UNIQUE,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    payroll_period_id uuid NOT NULL REFERENCES payroll_period(id) ON DELETE RESTRICT,
    employee_id uuid REFERENCES employee(id) ON DELETE RESTRICT,
    source_domain varchar(24) NOT NULL CHECK (source_domain IN ('POLICY','COMPENSATION','APPROVED_TIME','FINANCE')),
    revision bigint NOT NULL CHECK (revision > 0),
    replaces_record_id uuid REFERENCES payroll_source_record(id) ON DELETE RESTRICT,
    request_fingerprint char(64) NOT NULL CHECK (request_fingerprint ~ '^[a-f0-9]{64}$'),
    source_reference varchar(240) NOT NULL CHECK (length(btrim(source_reference)) BETWEEN 3 AND 240),
    currency char(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    no_inputs boolean NOT NULL DEFAULT false,
    fractional_digits integer CHECK (fractional_digits BETWEEN 0 AND 6),
    rounding_mode varchar(16) CHECK (rounding_mode IN ('UP','DOWN','CEILING','FLOOR','HALF_UP','HALF_DOWN','HALF_EVEN','UNNECESSARY')),
    company_authorization_reference varchar(240),
    statutory_policy_reference varchar(240),
    deduction_policy_reference varchar(240),
    prepared_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    creation_transaction_id bigint NOT NULL DEFAULT txid_current(),
    UNIQUE (organization_id,id),
    CHECK (
        (source_domain = 'POLICY' AND employee_id IS NULL AND no_inputs = false
         AND fractional_digits IS NOT NULL AND rounding_mode IS NOT NULL
         AND company_authorization_reference IS NOT NULL AND statutory_policy_reference IS NOT NULL
         AND length(btrim(company_authorization_reference)) BETWEEN 3 AND 240
         AND length(btrim(statutory_policy_reference)) BETWEEN 3 AND 240)
        OR
        (source_domain <> 'POLICY' AND employee_id IS NOT NULL
         AND fractional_digits IS NULL AND rounding_mode IS NULL
         AND company_authorization_reference IS NULL AND statutory_policy_reference IS NULL
         AND deduction_policy_reference IS NULL)
    ),
    CHECK (source_domain <> 'COMPENSATION' OR no_inputs = false)
);
CREATE UNIQUE INDEX uq_payroll_source_employee_revision
    ON payroll_source_record(organization_id,payroll_period_id,source_domain,employee_id,revision)
    WHERE employee_id IS NOT NULL;
CREATE UNIQUE INDEX uq_payroll_source_policy_revision
    ON payroll_source_record(organization_id,payroll_period_id,source_domain,revision)
    WHERE employee_id IS NULL;

CREATE TABLE payroll_source_component (
    source_record_id uuid NOT NULL REFERENCES payroll_source_record(id) ON DELETE RESTRICT,
    component_code varchar(80) NOT NULL CHECK (component_code ~ '^[A-Z][A-Z0-9_-]{0,79}$'),
    component_kind varchar(20) NOT NULL CHECK (component_kind IN ('EARNING','DEDUCTION','REIMBURSEMENT')),
    description varchar(240) NOT NULL CHECK (length(btrim(description)) BETWEEN 3 AND 240),
    amount numeric(20,6) NOT NULL CHECK (amount > 0),
    deduction_plan_reference varchar(240),
    PRIMARY KEY (source_record_id,component_code),
    CHECK ((component_kind = 'DEDUCTION' AND deduction_plan_reference IS NOT NULL AND length(btrim(deduction_plan_reference)) BETWEEN 3 AND 240)
           OR (component_kind <> 'DEDUCTION' AND deduction_plan_reference IS NULL))
);

CREATE FUNCTION payroll_source_scope_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE period_org uuid; prior payroll_source_record%ROWTYPE;
BEGIN
    SELECT organization_id INTO period_org FROM payroll_period WHERE id = NEW.payroll_period_id FOR UPDATE;
    IF period_org IS DISTINCT FROM NEW.organization_id OR
       (NEW.employee_id IS NOT NULL AND NOT EXISTS (
           SELECT 1 FROM payroll_employee_input_snapshot s
           WHERE s.payroll_period_id = NEW.payroll_period_id
             AND s.organization_id = NEW.organization_id AND s.employee_id = NEW.employee_id
       )) THEN
        RAISE EXCEPTION 'Payroll source must match the period and captured employee' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO prior FROM payroll_source_record
    WHERE organization_id = NEW.organization_id AND payroll_period_id = NEW.payroll_period_id
      AND source_domain = NEW.source_domain AND employee_id IS NOT DISTINCT FROM NEW.employee_id
    ORDER BY revision DESC LIMIT 1;
    IF (prior.id IS NULL AND (NEW.revision <> 1 OR NEW.replaces_record_id IS NOT NULL)) OR
       (prior.id IS NOT NULL AND (NEW.revision <> prior.revision + 1 OR NEW.replaces_record_id IS DISTINCT FROM prior.id)) THEN
        RAISE EXCEPTION 'Payroll replacement must reference the latest exact source revision' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER payroll_source_scope BEFORE INSERT ON payroll_source_record
    FOR EACH ROW EXECUTE FUNCTION payroll_source_scope_guard();
CREATE TRIGGER payroll_source_immutable BEFORE UPDATE OR DELETE ON payroll_source_record
    FOR EACH ROW EXECUTE FUNCTION payroll_9a_immutable_guard();
CREATE TRIGGER payroll_source_component_immutable BEFORE UPDATE OR DELETE ON payroll_source_component
    FOR EACH ROW EXECUTE FUNCTION payroll_9a_immutable_guard();

CREATE FUNCTION payroll_source_component_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE source payroll_source_record%ROWTYPE;
BEGIN
    SELECT * INTO source FROM payroll_source_record WHERE id = NEW.source_record_id FOR UPDATE;
    IF source.id IS NULL OR source.creation_transaction_id <> txid_current() OR source.source_domain = 'POLICY' OR source.no_inputs OR
       (source.source_domain = 'FINANCE' AND NEW.component_kind = 'EARNING') OR
       (source.source_domain <> 'FINANCE' AND NEW.component_kind <> 'EARNING') OR
       EXISTS (SELECT 1 FROM approval_request a WHERE a.organization_id = source.organization_id
               AND a.subject_type = 'PAYROLL_SOURCE_RECORD' AND a.subject_id = source.id) THEN
        RAISE EXCEPTION 'Source components must belong to their owner and precede review' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER payroll_source_component_scope BEFORE INSERT ON payroll_source_component
    FOR EACH ROW EXECUTE FUNCTION payroll_source_component_guard();

CREATE FUNCTION payroll_source_coverage_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE component_count integer;
BEGIN
    SELECT count(*) INTO component_count FROM payroll_source_component WHERE source_record_id = NEW.id;
    IF (NEW.source_domain = 'POLICY' AND component_count <> 0) OR
       (NEW.source_domain <> 'POLICY' AND
           ((NEW.no_inputs AND component_count <> 0) OR (NOT NEW.no_inputs AND component_count = 0))) THEN
        RAISE EXCEPTION 'Payroll source requires components or an explicit no-input attestation' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER payroll_source_coverage AFTER INSERT ON payroll_source_record
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION payroll_source_coverage_guard();
