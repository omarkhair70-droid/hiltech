-- Phase 4 / Slice 02 — Project planning structure.
-- Forward-only hardening of Area plus Milestone, WorkPackage and planning dependencies.

ALTER TABLE area
    ADD COLUMN organization_id uuid NULL,
    ADD COLUMN created_at timestamptz NULL,
    ADD COLUMN created_by uuid NULL,
    ADD COLUMN updated_at timestamptz NULL;

UPDATE area a
SET organization_id = s.organization_id,
    created_at = s.created_at,
    created_by = s.created_by,
    updated_at = s.updated_at
FROM site s
WHERE s.id = a.site_id;

ALTER TABLE area
    ALTER COLUMN organization_id SET NOT NULL,
    ALTER COLUMN created_at SET NOT NULL,
    ALTER COLUMN created_by SET NOT NULL,
    ALTER COLUMN updated_at SET NOT NULL,
    ADD CONSTRAINT fk_area_organization
        FOREIGN KEY (organization_id)
        REFERENCES organization(id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_area_created_by
        FOREIGN KEY (created_by)
        REFERENCES user_identity(id) ON DELETE RESTRICT,
    ADD CONSTRAINT uq_area_id_org_site
        UNIQUE (id, organization_id, site_id),
    ADD CONSTRAINT fk_area_site_org
        FOREIGN KEY (site_id, organization_id)
        REFERENCES site(id, organization_id) ON DELETE RESTRICT;

ALTER TABLE area
    DROP CONSTRAINT IF EXISTS area_parent_area_id_fkey,
    DROP CONSTRAINT IF EXISTS area_site_id_code_key,
    ADD CONSTRAINT fk_area_parent_same_site
        FOREIGN KEY (parent_area_id, organization_id, site_id)
        REFERENCES area(id, organization_id, site_id) ON DELETE RESTRICT,
    ADD CONSTRAINT uq_area_org_site_code
        UNIQUE (organization_id, site_id, code),
    ADD CONSTRAINT ck_area_sequence_nonnegative
        CHECK (sequence IS NULL OR sequence >= 0);

CREATE INDEX idx_area_org_site_parent
    ON area (organization_id, site_id, parent_area_id, sequence, id);

ALTER TABLE project_site
    ADD CONSTRAINT uq_project_site_id_org_project_site
        UNIQUE (id, organization_id, project_id, site_id);

CREATE TABLE milestone (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    code varchar(64) NOT NULL,
    name varchar(240) NOT NULL,
    state varchar(16) NOT NULL DEFAULT 'PLANNED'
        CHECK (state IN ('PLANNED', 'ACHIEVED', 'CANCELLED')),
    planned_date date NULL,
    actual_date date NULL,
    sequence integer NULL CHECK (sequence IS NULL OR sequence >= 0),
    client_visible boolean NOT NULL DEFAULT false,
    acceptance_requirement text NULL,
    baseline_version integer NOT NULL CHECK (baseline_version >= 1),
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    CONSTRAINT fk_milestone_project_org
        FOREIGN KEY (project_id, organization_id)
        REFERENCES project(id, organization_id) ON DELETE RESTRICT,
    CONSTRAINT uq_milestone_org_project_code
        UNIQUE (organization_id, project_id, code),
    CONSTRAINT uq_milestone_id_org_project
        UNIQUE (id, organization_id, project_id),
    CONSTRAINT ck_milestone_actual_state
        CHECK (actual_date IS NULL OR state = 'ACHIEVED')
);

CREATE INDEX idx_milestone_project_order
    ON milestone (project_id, state, sequence, planned_date, id);

CREATE TABLE work_package (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    project_site_id uuid NULL,
    site_id uuid NULL,
    milestone_id uuid NULL,
    code varchar(64) NOT NULL,
    name varchar(240) NOT NULL,
    description text NULL,
    owner_principal_type varchar(16) NULL
        CHECK (owner_principal_type IN ('EMPLOYEE', 'TEAM')),
    owner_employee_id uuid NULL,
    owner_team_id uuid NULL,
    state varchar(16) NOT NULL DEFAULT 'PLANNED'
        CHECK (state IN ('PLANNED', 'ACTIVE', 'COMPLETED', 'CANCELLED')),
    planned_start date NULL,
    planned_end date NULL,
    sequence integer NULL CHECK (sequence IS NULL OR sequence >= 0),
    baseline_version integer NOT NULL CHECK (baseline_version >= 1),
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    CONSTRAINT fk_work_package_project_org
        FOREIGN KEY (project_id, organization_id)
        REFERENCES project(id, organization_id) ON DELETE RESTRICT,
    CONSTRAINT fk_work_package_project_site_context
        FOREIGN KEY (project_site_id, organization_id, project_id, site_id)
        REFERENCES project_site(id, organization_id, project_id, site_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_work_package_milestone_context
        FOREIGN KEY (milestone_id, organization_id, project_id)
        REFERENCES milestone(id, organization_id, project_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_work_package_owner_employee_org
        FOREIGN KEY (owner_employee_id, organization_id)
        REFERENCES employee(id, organization_id) ON DELETE RESTRICT,
    CONSTRAINT fk_work_package_owner_team_org
        FOREIGN KEY (owner_team_id, organization_id)
        REFERENCES team(id, organization_id) ON DELETE RESTRICT,
    CONSTRAINT uq_work_package_project_code
        UNIQUE (project_id, code),
    CONSTRAINT uq_work_package_id_org_project
        UNIQUE (id, organization_id, project_id),
    CONSTRAINT ck_work_package_site_context
        CHECK (
            (project_site_id IS NULL AND site_id IS NULL)
            OR
            (project_site_id IS NOT NULL AND site_id IS NOT NULL)
        ),
    CONSTRAINT ck_work_package_owner_shape
        CHECK (
            (
                owner_principal_type IS NULL
                AND owner_employee_id IS NULL
                AND owner_team_id IS NULL
            )
            OR
            (
                owner_principal_type = 'EMPLOYEE'
                AND owner_employee_id IS NOT NULL
                AND owner_team_id IS NULL
            )
            OR
            (
                owner_principal_type = 'TEAM'
                AND owner_team_id IS NOT NULL
                AND owner_employee_id IS NULL
            )
        ),
    CONSTRAINT ck_work_package_dates
        CHECK (planned_end IS NULL OR planned_start IS NULL OR planned_end >= planned_start)
);

CREATE INDEX idx_work_package_project_order
    ON work_package (project_id, state, sequence, planned_start, id);

CREATE INDEX idx_work_package_site_context
    ON work_package (project_site_id, site_id, state);

CREATE INDEX idx_work_package_milestone
    ON work_package (milestone_id, state);

CREATE TABLE project_plan_dependency (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    predecessor_milestone_id uuid NULL,
    predecessor_work_package_id uuid NULL,
    successor_milestone_id uuid NULL,
    successor_work_package_id uuid NULL,
    dependency_type varchar(32) NOT NULL
        CHECK (dependency_type = 'FINISH_TO_START'),
    lag_minutes bigint NOT NULL DEFAULT 0 CHECK (lag_minutes >= 0),
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    CONSTRAINT fk_plan_dependency_project_org
        FOREIGN KEY (project_id, organization_id)
        REFERENCES project(id, organization_id) ON DELETE RESTRICT,
    CONSTRAINT fk_plan_dependency_pred_milestone
        FOREIGN KEY (predecessor_milestone_id, organization_id, project_id)
        REFERENCES milestone(id, organization_id, project_id) ON DELETE RESTRICT,
    CONSTRAINT fk_plan_dependency_pred_package
        FOREIGN KEY (predecessor_work_package_id, organization_id, project_id)
        REFERENCES work_package(id, organization_id, project_id) ON DELETE RESTRICT,
    CONSTRAINT fk_plan_dependency_succ_milestone
        FOREIGN KEY (successor_milestone_id, organization_id, project_id)
        REFERENCES milestone(id, organization_id, project_id) ON DELETE RESTRICT,
    CONSTRAINT fk_plan_dependency_succ_package
        FOREIGN KEY (successor_work_package_id, organization_id, project_id)
        REFERENCES work_package(id, organization_id, project_id) ON DELETE RESTRICT,
    CONSTRAINT ck_plan_dependency_predecessor_shape
        CHECK (num_nonnulls(predecessor_milestone_id, predecessor_work_package_id) = 1),
    CONSTRAINT ck_plan_dependency_successor_shape
        CHECK (num_nonnulls(successor_milestone_id, successor_work_package_id) = 1),
    CONSTRAINT ck_plan_dependency_not_self
        CHECK (
            predecessor_milestone_id IS NULL
            OR successor_milestone_id IS NULL
            OR predecessor_milestone_id <> successor_milestone_id
        ),
    CONSTRAINT ck_plan_dependency_package_not_self
        CHECK (
            predecessor_work_package_id IS NULL
            OR successor_work_package_id IS NULL
            OR predecessor_work_package_id <> successor_work_package_id
        )
);

CREATE UNIQUE INDEX uq_project_plan_dependency_edge
    ON project_plan_dependency (
        project_id,
        COALESCE(predecessor_milestone_id, predecessor_work_package_id),
        (predecessor_milestone_id IS NOT NULL),
        COALESCE(successor_milestone_id, successor_work_package_id),
        (successor_milestone_id IS NOT NULL),
        dependency_type
    );

CREATE INDEX idx_project_plan_dependency_project
    ON project_plan_dependency (project_id, id);
