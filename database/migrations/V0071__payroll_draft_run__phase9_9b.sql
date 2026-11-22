-- Phase 9 / 9B — immutable, policy-checked draft runs.
-- PROVISIONAL V0071: DevOps integration must allocate final contiguous versions.
-- No approval, bank execution, payment or tax-rate assumption is created.

ALTER TABLE payroll_period ADD CONSTRAINT uq_payroll_period_id_organization
    UNIQUE (id, organization_id);

CREATE TABLE payroll_run_version (
    id uuid PRIMARY KEY,
    operation_id uuid NOT NULL UNIQUE,
    request_fingerprint char(64) NOT NULL,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    payroll_period_id uuid NOT NULL,
    generation integer NOT NULL CHECK (generation > 0),
    policy_version_id uuid NOT NULL,
    policy_version bigint NOT NULL CHECK (policy_version > 0),
    currency char(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    source_fingerprint char(64) NOT NULL,
    gross numeric(20,6) NOT NULL CHECK (gross >= 0),
    deductions numeric(20,6) NOT NULL CHECK (deductions >= 0),
    reimbursements numeric(20,6) NOT NULL CHECK (reimbursements >= 0),
    net numeric(20,6) NOT NULL CHECK (net >= 0),
    state varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK (state = 'DRAFT'),
    created_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 1 CHECK (version = 1),
    UNIQUE (payroll_period_id, generation),
    UNIQUE (id, payroll_period_id),
    CONSTRAINT fk_payroll_run_period_org
        FOREIGN KEY (payroll_period_id, organization_id)
        REFERENCES payroll_period(id, organization_id) ON DELETE RESTRICT,
    CHECK (net = gross + reimbursements - deductions)
);
CREATE INDEX idx_payroll_run_period ON payroll_run_version(payroll_period_id, generation DESC);

CREATE TABLE payroll_run_employee_line (
    id uuid PRIMARY KEY,
    run_version_id uuid NOT NULL,
    payroll_period_id uuid NOT NULL,
    employee_snapshot_id uuid NOT NULL,
    employee_id uuid NOT NULL REFERENCES employee(id) ON DELETE RESTRICT,
    source_fingerprint char(64) NOT NULL,
    currency char(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    gross numeric(20,6) NOT NULL CHECK (gross >= 0),
    deductions numeric(20,6) NOT NULL CHECK (deductions >= 0),
    reimbursements numeric(20,6) NOT NULL CHECK (reimbursements >= 0),
    net numeric(20,6) NOT NULL CHECK (net >= 0),
    UNIQUE (run_version_id, employee_id),
    UNIQUE (run_version_id, employee_snapshot_id),
    CONSTRAINT fk_payroll_line_run_period FOREIGN KEY (run_version_id, payroll_period_id)
        REFERENCES payroll_run_version(id, payroll_period_id) ON DELETE RESTRICT,
    CONSTRAINT fk_payroll_line_snapshot_period FOREIGN KEY (employee_snapshot_id, payroll_period_id)
        REFERENCES payroll_employee_input_snapshot(id, payroll_period_id) ON DELETE RESTRICT,
    CHECK (net = gross + reimbursements - deductions)
);
CREATE INDEX idx_payroll_run_employee_lookup
    ON payroll_run_employee_line(employee_id, run_version_id);

CREATE TABLE payroll_run_component_ref (
    id uuid PRIMARY KEY,
    run_employee_line_id uuid NOT NULL REFERENCES payroll_run_employee_line(id) ON DELETE RESTRICT,
    component_code varchar(80) NOT NULL CHECK (length(trim(component_code)) BETWEEN 1 AND 80),
    component_kind varchar(16) NOT NULL CHECK (
        component_kind IN ('EARNING', 'DEDUCTION', 'REIMBURSEMENT')
    ),
    source_domain varchar(80) NOT NULL CHECK (length(trim(source_domain)) BETWEEN 1 AND 80),
    source_id uuid NOT NULL,
    source_version bigint NOT NULL CHECK (source_version > 0),
    source_approval_ref varchar(240) NOT NULL CHECK (
        length(trim(source_approval_ref)) BETWEEN 1 AND 240
    ),
    amount numeric(20,6) NOT NULL CHECK (amount > 0),
    UNIQUE(run_employee_line_id, source_domain, source_id, component_code)
);

CREATE TRIGGER payroll_9b_run_immutable
    BEFORE UPDATE OR DELETE ON payroll_run_version FOR EACH ROW
    EXECUTE FUNCTION payroll_9a_immutable_guard();
CREATE TRIGGER payroll_9b_line_immutable
    BEFORE UPDATE OR DELETE ON payroll_run_employee_line FOR EACH ROW
    EXECUTE FUNCTION payroll_9a_immutable_guard();
CREATE TRIGGER payroll_9b_component_immutable
    BEFORE UPDATE OR DELETE ON payroll_run_component_ref FOR EACH ROW
    EXECUTE FUNCTION payroll_9a_immutable_guard();
