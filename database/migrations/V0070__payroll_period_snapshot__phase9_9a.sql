-- Phase 9 / 9A: source-bound payroll periods and immutable input eligibility snapshots.
-- V0070 is PROVISIONAL until the multi-lane integration owner confirms Flyway ordering.
-- Never infer wages, statutory formulas, payment permission, or Finance balances.

CREATE TABLE payroll_authority_binding (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    identity_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    capability varchar(16) NOT NULL
        CHECK (capability IN ('READ', 'PREPARE', 'REVIEW')),
    valid_from timestamptz NOT NULL,
    valid_until timestamptz NULL,
    active boolean NOT NULL DEFAULT false,
    source_approval_ref varchar(160) NOT NULL
        CHECK (length(trim(source_approval_ref)) BETWEEN 1 AND 160),
    created_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    CHECK (valid_until IS NULL OR valid_until >= valid_from)
);

CREATE INDEX idx_payroll_authority_binding_current
    ON payroll_authority_binding(organization_id, identity_id, capability, active, valid_from, valid_until);

CREATE TABLE payroll_period (
    id uuid PRIMARY KEY,
    operation_id uuid NOT NULL UNIQUE,
    request_fingerprint char(64) NOT NULL,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    period_code varchar(64) NOT NULL
        CHECK (period_code ~ '^[A-Z][A-Z0-9_-]{0,63}$'),
    start_date date NOT NULL,
    end_date date NOT NULL,
    cutoff_date date NOT NULL,
    target_payment_date date NULL,
    state varchar(24) NOT NULL DEFAULT 'OPEN'
        CHECK (state IN ('OPEN', 'INPUTS_SNAPSHOTTED')),
    snapshot_at timestamptz NULL,
    snapshot_by_user_id uuid NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    created_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    UNIQUE (organization_id, period_code),
    CHECK (end_date >= start_date),
    CHECK (cutoff_date >= start_date AND cutoff_date <= end_date),
    CHECK (target_payment_date IS NULL OR target_payment_date >= start_date),
    CHECK (
        (state = 'OPEN' AND snapshot_at IS NULL AND snapshot_by_user_id IS NULL)
        OR
        (state = 'INPUTS_SNAPSHOTTED' AND snapshot_at IS NOT NULL AND snapshot_by_user_id IS NOT NULL)
    )
);

CREATE INDEX idx_payroll_period_org_date ON payroll_period(organization_id, start_date DESC, id);

CREATE TABLE payroll_employee_input_snapshot (
    id uuid PRIMARY KEY,
    payroll_period_id uuid NOT NULL REFERENCES payroll_period(id) ON DELETE RESTRICT,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    employee_id uuid NOT NULL,
    employee_code varchar(64) NOT NULL,
    display_name varchar(160) NOT NULL,
    employee_state varchar(24) NOT NULL
        CHECK (employee_state IN ('ACTIVE', 'OFFBOARDING', 'FORMER')),
    employee_version bigint NOT NULL CHECK (employee_version >= 1),
    employment_id uuid NOT NULL REFERENCES employment(id) ON DELETE RESTRICT,
    employment_version bigint NOT NULL CHECK (employment_version >= 1),
    employment_start_date date NOT NULL,
    employment_end_date date NULL,
    employment_type_code varchar(80) NULL,
    workforce_assignment_id uuid NULL REFERENCES workforce_assignment(id) ON DELETE RESTRICT,
    workforce_assignment_version bigint NULL CHECK (workforce_assignment_version >= 1),
    captured_at timestamptz NOT NULL,
    UNIQUE (payroll_period_id, employee_id),
    UNIQUE (id, payroll_period_id),
    CONSTRAINT fk_payroll_snapshot_employee_org
        FOREIGN KEY (employee_id, organization_id)
        REFERENCES employee(id, organization_id) ON DELETE RESTRICT,
    CHECK (employment_end_date IS NULL OR employment_end_date >= employment_start_date),
    CHECK (
        (workforce_assignment_id IS NULL AND workforce_assignment_version IS NULL)
        OR
        (workforce_assignment_id IS NOT NULL AND workforce_assignment_version IS NOT NULL)
    )
);

CREATE INDEX idx_payroll_snapshot_employee ON payroll_employee_input_snapshot(employee_id, payroll_period_id);

CREATE TABLE payroll_input_exception (
    id uuid PRIMARY KEY,
    payroll_period_id uuid NOT NULL REFERENCES payroll_period(id) ON DELETE RESTRICT,
    employee_snapshot_id uuid NOT NULL,
    exception_code varchar(64) NOT NULL CHECK (
        exception_code IN (
            'COMPENSATION_POLICY_NOT_CONNECTED',
            'APPROVED_TIME_SOURCE_NOT_CONNECTED',
            'FINANCE_DEDUCTION_SOURCE_NOT_CONNECTED',
            'OFFBOARDING_REQUIRES_REVIEW',
            'WORKFORCE_ASSIGNMENT_MISSING'
        )
    ),
    source_domain varchar(24) NOT NULL CHECK (source_domain IN ('PAYROLL', 'PEOPLE', 'WORKFORCE', 'FINANCE')),
    captured_at timestamptz NOT NULL,
    UNIQUE (employee_snapshot_id, exception_code),
    UNIQUE (id, payroll_period_id),
    CONSTRAINT fk_payroll_exception_snapshot_period
        FOREIGN KEY (employee_snapshot_id, payroll_period_id)
        REFERENCES payroll_employee_input_snapshot(id, payroll_period_id) ON DELETE RESTRICT
);

CREATE INDEX idx_payroll_exception_period ON payroll_input_exception(payroll_period_id, exception_code, id);

-- Append-only review comments. Acknowledgement NEVER clears a calculation blocker.
CREATE TABLE payroll_exception_review (
    id uuid PRIMARY KEY,
    operation_id uuid NOT NULL UNIQUE,
    exception_id uuid NOT NULL REFERENCES payroll_input_exception(id) ON DELETE RESTRICT,
    reviewed_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    review_note varchar(500) NOT NULL CHECK (length(trim(review_note)) BETWEEN 1 AND 500),
    reviewed_at timestamptz NOT NULL
);

CREATE INDEX idx_payroll_exception_review_exception ON payroll_exception_review(exception_id, reviewed_at);

-- The ledger intentionally has NO salary value, statutory deduction, bank instruction,
-- paid status or editable Advance balance. Those require approved later source contracts.
