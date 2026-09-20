-- Phase 4 / Slice 01 — Project + Site / ProjectSite production contract.
-- Forward-only refinement over the pre-code V0004 scaffold after Phase 3 People truth.

ALTER TABLE project
    ADD COLUMN source_type varchar(16) NULL,
    ADD COLUMN source_external_reference varchar(255) NULL;

UPDATE project
SET source_type = 'INTERNAL'
WHERE source_type IS NULL;

ALTER TABLE project
    ALTER COLUMN source_type SET NOT NULL,
    ADD CONSTRAINT ck_project_source_type
        CHECK (source_type IN ('INTERNAL', 'IMPORT')),
    ADD CONSTRAINT ck_project_source_reference
        CHECK (
            (
                source_type = 'INTERNAL'
                AND source_external_reference IS NULL
            )
            OR
            (
                source_type = 'IMPORT'
                AND source_external_reference IS NOT NULL
                AND length(trim(source_external_reference)) BETWEEN 1 AND 255
            )
        ),
    ADD CONSTRAINT uq_project_id_organization
        UNIQUE (id, organization_id);

-- These pre-Phase-10 placeholders are intentionally non-authoritative in
-- Slice 01. Production Project creation leaves them NULL until the Commercial
-- phase owns their actual lifecycle and foreign-key semantics.
ALTER TABLE project
    ADD CONSTRAINT ck_project_future_source_placeholders_empty
        CHECK (
            source_opportunity_id IS NULL
            AND contract_id IS NULL
            AND client_po_id IS NULL
        );

-- project_manager_id was a login-only scaffold. Phase 3 established Employee
-- as business workforce truth and Team as reusable authority truth, so Project
-- responsibility is now effective-dated and history-preserving below.
ALTER TABLE project
    DROP COLUMN project_manager_id;

CREATE TABLE project_authority_binding (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL
        REFERENCES organization(id) ON DELETE RESTRICT,
    authority_key varchar(64) NOT NULL
        CHECK (authority_key = 'PROJECT_ADMIN'),
    principal_type varchar(16) NOT NULL
        CHECK (principal_type IN ('USER', 'TEAM')),
    principal_user_id uuid NULL
        REFERENCES user_identity(id) ON DELETE RESTRICT,
    principal_team_id uuid NULL
        REFERENCES team(id) ON DELETE RESTRICT,
    effective_from timestamptz NOT NULL,
    effective_to timestamptz NULL,
    active boolean NOT NULL DEFAULT true,
    created_by_user_id uuid NOT NULL
        REFERENCES user_identity(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 1
        CHECK (version >= 1),
    CHECK (
        (
            principal_type = 'USER'
            AND principal_user_id IS NOT NULL
            AND principal_team_id IS NULL
        )
        OR
        (
            principal_type = 'TEAM'
            AND principal_team_id IS NOT NULL
            AND principal_user_id IS NULL
        )
    ),
    CHECK (
        effective_to IS NULL
        OR effective_to >= effective_from
    )
);

CREATE UNIQUE INDEX uq_project_authority_binding_current_user
    ON project_authority_binding (
        organization_id,
        authority_key,
        principal_user_id
    )
    WHERE active = true
      AND principal_type = 'USER'
      AND effective_to IS NULL;

CREATE UNIQUE INDEX uq_project_authority_binding_current_team
    ON project_authority_binding (
        organization_id,
        authority_key,
        principal_team_id
    )
    WHERE active = true
      AND principal_type = 'TEAM'
      AND effective_to IS NULL;

CREATE INDEX idx_project_authority_binding_org_current
    ON project_authority_binding (
        organization_id,
        authority_key,
        active,
        effective_from,
        effective_to
    );

CREATE TABLE project_responsibility (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    responsibility_key varchar(64) NOT NULL
        CHECK (responsibility_key = 'PROJECT_MANAGER'),
    principal_type varchar(16) NOT NULL
        CHECK (principal_type IN ('EMPLOYEE', 'TEAM')),
    principal_employee_id uuid NULL,
    principal_team_id uuid NULL,
    state varchar(16) NOT NULL
        CHECK (state IN ('ACTIVE', 'ENDED')),
    effective_from timestamptz NOT NULL,
    effective_to timestamptz NULL,
    assigned_by_user_id uuid NOT NULL
        REFERENCES user_identity(id) ON DELETE RESTRICT,
    source_operation_id uuid NOT NULL UNIQUE,
    reason text NULL,
    created_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 1
        CHECK (version >= 1),

    CONSTRAINT fk_project_responsibility_project_org
        FOREIGN KEY (project_id, organization_id)
        REFERENCES project(id, organization_id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_project_responsibility_employee_org
        FOREIGN KEY (principal_employee_id, organization_id)
        REFERENCES employee(id, organization_id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_project_responsibility_team_org
        FOREIGN KEY (principal_team_id, organization_id)
        REFERENCES team(id, organization_id)
        ON DELETE RESTRICT,

    CHECK (
        (
            principal_type = 'EMPLOYEE'
            AND principal_employee_id IS NOT NULL
            AND principal_team_id IS NULL
        )
        OR
        (
            principal_type = 'TEAM'
            AND principal_team_id IS NOT NULL
            AND principal_employee_id IS NULL
        )
    ),
    CHECK (
        effective_to IS NULL
        OR effective_to >= effective_from
    ),
    CHECK (
        (
            state = 'ACTIVE'
            AND effective_to IS NULL
        )
        OR
        (
            state = 'ENDED'
            AND effective_to IS NOT NULL
        )
    )
);

CREATE UNIQUE INDEX uq_project_responsibility_current
    ON project_responsibility (
        project_id,
        responsibility_key
    )
    WHERE state = 'ACTIVE';

CREATE INDEX idx_project_responsibility_principal_employee
    ON project_responsibility (
        principal_employee_id,
        state,
        project_id
    )
    WHERE principal_employee_id IS NOT NULL;

CREATE INDEX idx_project_responsibility_principal_team
    ON project_responsibility (
        principal_team_id,
        state,
        project_id
    )
    WHERE principal_team_id IS NOT NULL;

CREATE INDEX idx_project_source_type
    ON project (
        organization_id,
        source_type,
        lifecycle_state
    );
