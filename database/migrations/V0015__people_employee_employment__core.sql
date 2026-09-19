-- Phase 3 / Slice 01 — Employee / Employment Core.
-- Person is human identity; Employee scopes that person to an organization;
-- Employment preserves work-relationship periods/history.

CREATE TABLE person (
    id uuid PRIMARY KEY,
    display_name varchar(160) NOT NULL
        CHECK (length(trim(display_name)) BETWEEN 1 AND 160),
    legal_name varchar(240) NULL
        CHECK (
            legal_name IS NULL
            OR length(trim(legal_name)) BETWEEN 1 AND 240
        ),
    mobile varchar(64) NULL
        CHECK (
            mobile IS NULL
            OR length(trim(mobile)) BETWEEN 1 AND 64
        ),
    email varchar(320) NULL
        CHECK (
            email IS NULL
            OR length(trim(email)) BETWEEN 3 AND 320
        ),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 1
        CHECK (version >= 1),
    CHECK (updated_at >= created_at)
);

ALTER TABLE user_identity
    ADD CONSTRAINT fk_user_identity_person
    FOREIGN KEY (person_id)
    REFERENCES person(id)
    ON DELETE RESTRICT;

CREATE TABLE employee (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL
        REFERENCES organization(id) ON DELETE RESTRICT,
    person_id uuid NOT NULL
        REFERENCES person(id) ON DELETE RESTRICT,
    employee_code varchar(64) NOT NULL
        CHECK (length(trim(employee_code)) BETWEEN 1 AND 64),
    state varchar(24) NOT NULL
        CHECK (
            state IN (
                'PREBOARDING',
                'ACTIVE',
                'OFFBOARDING',
                'FORMER'
            )
        ),
    hire_date date NULL,
    end_date date NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 1
        CHECK (version >= 1),
    UNIQUE (organization_id, employee_code),
    UNIQUE (organization_id, person_id),
    CHECK (
        end_date IS NULL
        OR hire_date IS NULL
        OR end_date >= hire_date
    ),
    CHECK (updated_at >= created_at)
);

CREATE INDEX idx_employee_org_state_code
    ON employee (
        organization_id,
        state,
        employee_code,
        id
    );

CREATE TABLE employment (
    id uuid PRIMARY KEY,
    employee_id uuid NOT NULL
        REFERENCES employee(id) ON DELETE RESTRICT,
    employment_type_code varchar(80) NULL
        CHECK (
            employment_type_code IS NULL
            OR employment_type_code ~ '^[A-Z][A-Z0-9_-]{0,79}$'
        ),
    start_date date NOT NULL,
    end_date date NULL,
    state varchar(16) NOT NULL
        CHECK (state IN ('ACTIVE', 'ENDED')),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 1
        CHECK (version >= 1),
    CHECK (
        end_date IS NULL
        OR end_date >= start_date
    ),
    CHECK (
        (state = 'ACTIVE' AND end_date IS NULL)
        OR
        (state = 'ENDED' AND end_date IS NOT NULL)
    ),
    CHECK (updated_at >= created_at)
);

CREATE UNIQUE INDEX uq_employment_one_active_per_employee
    ON employment (employee_id)
    WHERE state = 'ACTIVE';

CREATE INDEX idx_employment_employee_history
    ON employment (
        employee_id,
        start_date DESC,
        id DESC
    );
