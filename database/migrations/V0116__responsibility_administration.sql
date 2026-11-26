-- Delegation is explicit and separate from the operational authority being granted.
CREATE TABLE responsibility_administration_binding (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    administrator_identity_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    authority_key varchar(64) NOT NULL CHECK (authority_key IN (
        'PEOPLE_ADMIN', 'PROJECT_ADMIN',
        'SALES_VIEW', 'SALES_WRITE', 'SALES_COST_VIEW', 'SALES_RELEASE', 'SALES_AWARD',
        'PAYROLL_READ', 'PAYROLL_PREPARE', 'PAYROLL_REVIEW', 'SUPPLIER_MASTER_DATA_ADMIN', 'APPROVAL_POLICY_ADMIN',
        'FINANCE_PAYROLL_PREPARE', 'FINANCE_PAYROLL_REVIEW'
    )),
    active boolean NOT NULL DEFAULT true,
    can_delegate_administration boolean NOT NULL DEFAULT false,
    effective_from timestamptz NOT NULL,
    effective_to timestamptz,
    source_approval_ref varchar(160) NOT NULL CHECK (length(trim(source_approval_ref)) BETWEEN 1 AND 160),
    created_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    CHECK (effective_to IS NULL OR effective_to >= effective_from)
);

CREATE UNIQUE INDEX uq_responsibility_administrator_current_key
    ON responsibility_administration_binding (organization_id, administrator_identity_id, authority_key)
    WHERE active = true AND effective_to IS NULL;

CREATE INDEX idx_responsibility_administrator_current
    ON responsibility_administration_binding (organization_id, administrator_identity_id, active, effective_from, effective_to);

-- Bounded policy setup authority is separate from both delegation and approval.
CREATE TABLE organization_administration_authority_binding (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    identity_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    authority_key varchar(64) NOT NULL CHECK (authority_key = 'APPROVAL_POLICY_ADMIN'),
    active boolean NOT NULL DEFAULT true,
    effective_from timestamptz NOT NULL,
    effective_to timestamptz,
    created_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    CHECK (effective_to IS NULL OR effective_to >= effective_from)
);

CREATE UNIQUE INDEX uq_organization_administration_current_identity
    ON organization_administration_authority_binding (organization_id, identity_id, authority_key)
    WHERE active = true AND effective_to IS NULL;
