-- Phase 3 / Slice 03 — HR Documents / Certifications.

CREATE TABLE employee_document (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL
        REFERENCES organization(id) ON DELETE RESTRICT,
    employee_id uuid NOT NULL,
    document_type_code varchar(80) NOT NULL
        CHECK (
            document_type_code ~ '^[A-Z][A-Z0-9_-]{0,79}$'
        ),
    document_label varchar(160) NULL
        CHECK (
            document_label IS NULL
            OR length(trim(document_label)) BETWEEN 1 AND 160
        ),
    issue_date date NULL,
    expiry_date date NULL,
    verification_state varchar(16) NOT NULL
        CHECK (
            verification_state IN (
                'UNVERIFIED',
                'VERIFIED',
                'REJECTED'
            )
        ),
    verified_at timestamptz NULL,
    verified_by_user_id uuid NULL
        REFERENCES user_identity(id) ON DELETE RESTRICT,
    evidence_id uuid NULL
        REFERENCES evidence(id) ON DELETE RESTRICT,
    retention_policy_code varchar(80) NULL
        CHECK (
            retention_policy_code IS NULL
            OR retention_policy_code ~ '^[A-Z][A-Z0-9_-]{0,79}$'
        ),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 1
        CHECK (version >= 1),

    CONSTRAINT fk_employee_document_employee_org
        FOREIGN KEY (employee_id, organization_id)
        REFERENCES employee(id, organization_id)
        ON DELETE RESTRICT,

    CONSTRAINT uq_employee_document_identity
        UNIQUE (id, employee_id, organization_id),

    CONSTRAINT uq_employee_document_evidence
        UNIQUE (evidence_id),

    CHECK (
        expiry_date IS NULL
        OR issue_date IS NULL
        OR expiry_date >= issue_date
    ),
    CHECK (
        (
            verification_state = 'UNVERIFIED'
            AND verified_at IS NULL
            AND verified_by_user_id IS NULL
        )
        OR
        (
            verification_state IN ('VERIFIED', 'REJECTED')
            AND verified_at IS NOT NULL
            AND verified_by_user_id IS NOT NULL
        )
    ),
    CHECK (updated_at >= created_at)
);

CREATE INDEX idx_employee_document_employee
    ON employee_document (
        organization_id,
        employee_id,
        document_type_code,
        created_at DESC,
        id
    );

CREATE INDEX idx_employee_document_expiry
    ON employee_document (
        organization_id,
        expiry_date,
        verification_state
    )
    WHERE expiry_date IS NOT NULL;

CREATE TABLE certification (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL
        REFERENCES organization(id) ON DELETE RESTRICT,
    employee_id uuid NOT NULL,
    certification_type_code varchar(80) NOT NULL
        CHECK (
            certification_type_code ~ '^[A-Z][A-Z0-9_-]{0,79}$'
        ),
    certification_label varchar(160) NULL
        CHECK (
            certification_label IS NULL
            OR length(trim(certification_label)) BETWEEN 1 AND 160
        ),
    issuer varchar(200) NULL
        CHECK (
            issuer IS NULL
            OR length(trim(issuer)) BETWEEN 1 AND 200
        ),
    issued_at timestamptz NULL,
    valid_until timestamptz NULL,
    verification_state varchar(16) NOT NULL
        CHECK (
            verification_state IN (
                'UNVERIFIED',
                'VERIFIED',
                'REJECTED'
            )
        ),
    verified_at timestamptz NULL,
    verified_by_user_id uuid NULL
        REFERENCES user_identity(id) ON DELETE RESTRICT,
    employee_document_id uuid NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 1
        CHECK (version >= 1),

    CONSTRAINT fk_certification_employee_org
        FOREIGN KEY (employee_id, organization_id)
        REFERENCES employee(id, organization_id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_certification_document_same_employee
        FOREIGN KEY (
            employee_document_id,
            employee_id,
            organization_id
        )
        REFERENCES employee_document (
            id,
            employee_id,
            organization_id
        )
        ON DELETE RESTRICT,

    CHECK (
        valid_until IS NULL
        OR issued_at IS NULL
        OR valid_until >= issued_at
    ),
    CHECK (
        (
            verification_state = 'UNVERIFIED'
            AND verified_at IS NULL
            AND verified_by_user_id IS NULL
        )
        OR
        (
            verification_state IN ('VERIFIED', 'REJECTED')
            AND verified_at IS NOT NULL
            AND verified_by_user_id IS NOT NULL
        )
    ),
    CHECK (updated_at >= created_at)
);

CREATE INDEX idx_certification_employee
    ON certification (
        organization_id,
        employee_id,
        certification_type_code,
        created_at DESC,
        id
    );

CREATE INDEX idx_certification_validity
    ON certification (
        organization_id,
        certification_type_code,
        verification_state,
        valid_until
    );
