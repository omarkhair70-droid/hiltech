-- Phase 3 / Slice 02 — Workforce Assignment / Reporting Structure.
-- Reuse Phase 1 Team/team_membership authority instead of creating duplicate team truth.

ALTER TABLE employee
    ADD CONSTRAINT uq_employee_id_organization
    UNIQUE (id, organization_id);

ALTER TABLE team
    ADD CONSTRAINT uq_team_id_organization
    UNIQUE (id, organization_id);

CREATE TABLE workforce_assignment (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL
        REFERENCES organization(id) ON DELETE RESTRICT,
    employee_id uuid NOT NULL,
    team_id uuid NULL,
    role_code varchar(80) NOT NULL
        CHECK (
            role_code ~ '^[A-Z][A-Z0-9_-]{0,79}$'
        ),
    role_label varchar(160) NULL
        CHECK (
            role_label IS NULL
            OR length(trim(role_label)) BETWEEN 1 AND 160
        ),
    reports_to_employee_id uuid NULL,
    state varchar(16) NOT NULL
        CHECK (state IN ('ACTIVE', 'ENDED')),
    effective_from timestamptz NOT NULL,
    effective_to timestamptz NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 1
        CHECK (version >= 1),

    CONSTRAINT fk_workforce_assignment_employee_org
        FOREIGN KEY (employee_id, organization_id)
        REFERENCES employee(id, organization_id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_workforce_assignment_team_org
        FOREIGN KEY (team_id, organization_id)
        REFERENCES team(id, organization_id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_workforce_assignment_manager_org
        FOREIGN KEY (
            reports_to_employee_id,
            organization_id
        )
        REFERENCES employee(id, organization_id)
        ON DELETE RESTRICT,

    CHECK (
        reports_to_employee_id IS NULL
        OR reports_to_employee_id <> employee_id
    ),
    CHECK (
        effective_to IS NULL
        OR effective_to >= effective_from
    ),
    CHECK (
        (state = 'ACTIVE' AND effective_to IS NULL)
        OR
        (state = 'ENDED' AND effective_to IS NOT NULL)
    ),
    CHECK (updated_at >= created_at)
);

CREATE UNIQUE INDEX uq_workforce_assignment_one_active_employee
    ON workforce_assignment (employee_id)
    WHERE state = 'ACTIVE';

CREATE INDEX idx_workforce_assignment_org_current
    ON workforce_assignment (
        organization_id,
        state,
        team_id,
        role_code,
        employee_id
    );

CREATE INDEX idx_workforce_assignment_manager_current
    ON workforce_assignment (
        organization_id,
        reports_to_employee_id,
        state,
        employee_id
    );

ALTER TABLE team_membership
    ADD COLUMN source_workforce_assignment_id uuid NULL
        REFERENCES workforce_assignment(id)
        ON DELETE RESTRICT;

CREATE UNIQUE INDEX uq_team_membership_source_workforce_assignment
    ON team_membership (
        source_workforce_assignment_id
    )
    WHERE source_workforce_assignment_id IS NOT NULL;

-- Multiple legitimate membership sources may map to the same OpenFGA team.member
-- tuple. This aggregate provides monotonic source ordering for that shared tuple
-- so ending one source cannot revoke authority while another remains current.
CREATE TABLE team_membership_authority_aggregate (
    team_id uuid NOT NULL
        REFERENCES team(id) ON DELETE RESTRICT,
    user_identity_id uuid NOT NULL
        REFERENCES user_identity(id) ON DELETE RESTRICT,
    generation bigint NOT NULL DEFAULT 0
        CHECK (generation >= 0),
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (
        team_id,
        user_identity_id
    )
);
