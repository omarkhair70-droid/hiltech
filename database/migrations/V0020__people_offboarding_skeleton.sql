-- Phase 3 / Slice 06 — Offboarding Skeleton.
-- People coordinates leaving-work state while authoritative later-domain truth remains external.

CREATE TABLE offboarding_case (
    id uuid PRIMARY KEY,
    operation_id uuid NOT NULL UNIQUE,
    organization_id uuid NOT NULL
        REFERENCES organization(id) ON DELETE RESTRICT,
    employee_id uuid NOT NULL,
    state varchar(16) NOT NULL
        CHECK (state IN ('OPEN', 'COMPLETED')),
    last_working_date date NOT NULL,
    reason_category_code varchar(80) NOT NULL
        CHECK (
            reason_category_code ~
                '^[A-Z][A-Z0-9_-]{0,79}$'
        ),
    note varchar(500) NULL
        CHECK (
            note IS NULL
            OR length(trim(note)) BETWEEN 1 AND 500
        ),
    started_at timestamptz NOT NULL,
    started_by_user_id uuid NOT NULL
        REFERENCES user_identity(id) ON DELETE RESTRICT,
    completed_at timestamptz NULL,
    completed_by_user_id uuid NULL
        REFERENCES user_identity(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 1
        CHECK (version >= 1),

    CONSTRAINT fk_offboarding_case_employee_org
        FOREIGN KEY (employee_id, organization_id)
        REFERENCES employee(id, organization_id)
        ON DELETE RESTRICT,

    CHECK (
        (
            state = 'OPEN'
            AND completed_at IS NULL
            AND completed_by_user_id IS NULL
        )
        OR
        (
            state = 'COMPLETED'
            AND completed_at IS NOT NULL
            AND completed_by_user_id IS NOT NULL
        )
    ),
    CHECK (updated_at >= created_at)
);

CREATE UNIQUE INDEX uq_offboarding_case_one_open_employee
    ON offboarding_case (employee_id)
    WHERE state = 'OPEN';

CREATE INDEX idx_offboarding_case_org_state
    ON offboarding_case (
        organization_id,
        state,
        last_working_date,
        employee_id
    );

CREATE TABLE offboarding_clearance (
    id uuid PRIMARY KEY,
    offboarding_case_id uuid NOT NULL
        REFERENCES offboarding_case(id) ON DELETE RESTRICT,
    clearance_type varchar(16) NOT NULL
        CHECK (
            clearance_type IN (
                'HR',
                'ACCESS',
                'PROJECT',
                'ASSET',
                'FINANCE',
                'PAYROLL'
            )
        ),
    state varchar(24) NOT NULL
        CHECK (
            state IN (
                'PENDING',
                'CLEAR',
                'NOT_APPLICABLE',
                'EXCEPTION_ACCEPTED'
            )
        ),
    reason varchar(500) NULL
        CHECK (
            reason IS NULL
            OR length(trim(reason)) BETWEEN 1 AND 500
        ),
    resolved_at timestamptz NULL,
    resolved_by_user_id uuid NULL
        REFERENCES user_identity(id) ON DELETE RESTRICT,
    source varchar(24) NOT NULL
        CHECK (
            source IN (
                'PEOPLE',
                'IDENTITY',
                'EXTERNAL_DOMAIN'
            )
        ),
    version bigint NOT NULL DEFAULT 1
        CHECK (version >= 1),

    UNIQUE (
        offboarding_case_id,
        clearance_type
    ),

    CHECK (
        (
            state = 'PENDING'
            AND resolved_at IS NULL
            AND resolved_by_user_id IS NULL
        )
        OR
        (
            state <> 'PENDING'
            AND resolved_at IS NOT NULL
            AND resolved_by_user_id IS NOT NULL
        )
    ),
    CHECK (
        state <> 'EXCEPTION_ACCEPTED'
        OR reason IS NOT NULL
    ),
    CHECK (
        clearance_type <> 'ACCESS'
        OR source = 'IDENTITY'
    )
);

CREATE INDEX idx_offboarding_clearance_case_state
    ON offboarding_clearance (
        offboarding_case_id,
        state,
        clearance_type
    );
