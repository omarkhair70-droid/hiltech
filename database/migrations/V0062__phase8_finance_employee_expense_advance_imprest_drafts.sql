-- Phase 8 / 8B: distinct SELF-SERVICE DRAFT requests, no funding or approval.
-- Provisional V0062 pending DevOps migration registry allocation. Default-OFF API.
-- Expense reimbursement = employee personally spent money.
-- Employee advance = potential personal borrowing with separately approved repayment.
-- Financial imprest = company-owned cash custody, NOT employee income/borrowing.

CREATE TABLE finance_expense_claim_draft (
    id uuid PRIMARY KEY,
    operation_id uuid NOT NULL UNIQUE,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    employee_id uuid NOT NULL REFERENCES employee(id) ON DELETE RESTRICT,
    project_id uuid NULL REFERENCES project(id) ON DELETE RESTRICT,
    amount numeric(18,6) NOT NULL CHECK (amount > 0),
    currency char(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    purpose varchar(600) NOT NULL CHECK (length(btrim(purpose)) BETWEEN 5 AND 600),
    merchant varchar(160) NOT NULL CHECK (length(btrim(merchant)) BETWEEN 2 AND 160),
    spent_on date NOT NULL,
    state varchar(32) NOT NULL DEFAULT 'DRAFT_RECEIPT_PENDING'
        CHECK (state='DRAFT_RECEIPT_PENDING'),
    version bigint NOT NULL DEFAULT 1 CHECK(version=1),
    created_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    correlation_id varchar(128) NOT NULL CHECK(length(btrim(correlation_id)) > 0),
    UNIQUE(id,organization_id)
);
CREATE INDEX idx_finance_expense_employee
    ON finance_expense_claim_draft (organization_id, employee_id, created_at DESC);

CREATE TABLE finance_employee_advance_draft (
    id uuid PRIMARY KEY,
    operation_id uuid NOT NULL UNIQUE,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    employee_id uuid NOT NULL REFERENCES employee(id) ON DELETE RESTRICT,
    project_id uuid NULL REFERENCES project(id) ON DELETE RESTRICT,
    requested_amount numeric(18,6) NOT NULL CHECK(requested_amount > 0),
    currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
    purpose varchar(600) NOT NULL CHECK(length(btrim(purpose)) BETWEEN 5 AND 600),
    proposed_settlement_note varchar(500) NULL,
    state varchar(32) NOT NULL DEFAULT 'DRAFT_POLICY_PENDING'
        CHECK(state='DRAFT_POLICY_PENDING'),
    version bigint NOT NULL DEFAULT 1 CHECK(version=1),
    created_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    correlation_id varchar(128) NOT NULL CHECK(length(btrim(correlation_id)) > 0),
    UNIQUE(id,organization_id)
);
CREATE INDEX idx_finance_advance_employee
    ON finance_employee_advance_draft (organization_id, employee_id, created_at DESC);

CREATE TABLE finance_financial_imprest_draft (
    id uuid PRIMARY KEY,
    operation_id uuid NOT NULL UNIQUE,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    custodian_employee_id uuid NOT NULL REFERENCES employee(id) ON DELETE RESTRICT,
    project_id uuid NULL REFERENCES project(id) ON DELETE RESTRICT,
    requested_amount numeric(18,6) NOT NULL CHECK(requested_amount > 0),
    currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
    purpose varchar(600) NOT NULL CHECK(length(btrim(purpose)) BETWEEN 5 AND 600),
    proposed_settlement_on date NULL,
    state varchar(32) NOT NULL DEFAULT 'DRAFT_CUSTODY_POLICY_PENDING'
        CHECK(state='DRAFT_CUSTODY_POLICY_PENDING'),
    version bigint NOT NULL DEFAULT 1 CHECK(version=1),
    created_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    correlation_id varchar(128) NOT NULL CHECK(length(btrim(correlation_id)) > 0),
    UNIQUE(id,organization_id)
);
CREATE INDEX idx_finance_imprest_employee
    ON finance_financial_imprest_draft
    (organization_id, custodian_employee_id, created_at DESC);

-- Enforce current employee/real person/organization identity at insert time.
-- Works for ALL three independent draft tables without a synthetic employee.
CREATE FUNCTION guard_finance_employee_draft_origin()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    owner_employee uuid;
BEGIN
    IF TG_TABLE_NAME = 'finance_financial_imprest_draft' THEN
        owner_employee := NEW.custodian_employee_id;
    ELSE
        owner_employee := NEW.employee_id;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM employee e
        JOIN user_identity u ON u.person_id=e.person_id
        JOIN employment em ON em.employee_id=e.id AND em.state='ACTIVE'
          AND em.start_date <= NEW.created_at::date
        JOIN organization_membership om
          ON om.user_identity_id=u.id AND om.organization_id=e.organization_id
          AND om.state='ACTIVE'
          AND om.valid_from <= NEW.created_at
          AND (om.valid_until IS NULL OR om.valid_until >= NEW.created_at)
        JOIN organization o ON o.id=e.organization_id
        WHERE e.id=owner_employee AND e.organization_id=NEW.organization_id
          AND e.state='ACTIVE' AND u.id=NEW.created_by_user_id
          AND u.status='ACTIVE' AND o.status='ACTIVE'
    ) THEN
        RAISE EXCEPTION 'Finance employee draft requires current authenticated employment and membership'
            USING ERRCODE='23514';
    END IF;
    IF NEW.project_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM project p WHERE p.id=NEW.project_id
          AND p.organization_id=NEW.organization_id
    ) THEN
        RAISE EXCEPTION 'Finance employee draft project does not belong to employee organization'
            USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE FUNCTION reject_finance_immutable_draft_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Financial employee draft history is immutable'
        USING ERRCODE='23514';
END;
$$;

CREATE TRIGGER finance_expense_origin_guard
BEFORE INSERT ON finance_expense_claim_draft FOR EACH ROW
EXECUTE FUNCTION guard_finance_employee_draft_origin();
CREATE TRIGGER finance_expense_immutable
BEFORE UPDATE OR DELETE ON finance_expense_claim_draft FOR EACH ROW
EXECUTE FUNCTION reject_finance_immutable_draft_mutation();

CREATE TRIGGER finance_advance_origin_guard
BEFORE INSERT ON finance_employee_advance_draft FOR EACH ROW
EXECUTE FUNCTION guard_finance_employee_draft_origin();
CREATE TRIGGER finance_advance_immutable
BEFORE UPDATE OR DELETE ON finance_employee_advance_draft FOR EACH ROW
EXECUTE FUNCTION reject_finance_immutable_draft_mutation();

CREATE TRIGGER finance_imprest_origin_guard
BEFORE INSERT ON finance_financial_imprest_draft FOR EACH ROW
EXECUTE FUNCTION guard_finance_employee_draft_origin();
CREATE TRIGGER finance_imprest_immutable
BEFORE UPDATE OR DELETE ON finance_financial_imprest_draft FOR EACH ROW
EXECUTE FUNCTION reject_finance_immutable_draft_mutation();

-- Intentionally absent: payable/advance loan ledgers, cash custody entries,
-- evidence acceptance, approval decisions, payroll deduction or payment.
