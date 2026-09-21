-- Phase 4 / Slice 04 — readiness + WorkAssignment.
-- Forward-only hardening over V0023. Do not edit earlier migrations.

ALTER TABLE assignment_policy
    ADD COLUMN execution_mode varchar(24) NOT NULL DEFAULT 'MANUAL_ELIGIBLE'
        CHECK (execution_mode IN ('AUTO','SUGGEST_CONFIRM','MANUAL_ELIGIBLE')),
    ADD COLUMN allow_preboarding_employee boolean NOT NULL DEFAULT false;

ALTER TABLE readiness_policy_requirement
    ADD COLUMN waiver_allowed boolean NOT NULL DEFAULT false,
    ADD COLUMN waiver_reason_required boolean NOT NULL DEFAULT true;

ALTER TABLE work_requirement_instance
    ADD COLUMN requirement_type_code varchar(64) NULL,
    ADD COLUMN evaluation_reason_code varchar(80) NULL,
    ADD COLUMN source_as_of timestamptz NULL,
    ADD COLUMN evaluated_at timestamptz NULL,
    ADD COLUMN waived_by_user_id uuid NULL
        REFERENCES user_identity(id) ON DELETE RESTRICT,
    ADD COLUMN waiver_reason text NULL;

UPDATE work_requirement_instance wri
SET requirement_type_code = rpr.requirement_type_code
FROM readiness_policy_requirement rpr
WHERE wri.requirement_family = 'READINESS'
  AND wri.source_config_id = rpr.config_revision_id
  AND wri.requirement_key = rpr.requirement_key;

UPDATE work_requirement_instance
SET requirement_type_code = requirement_family
WHERE requirement_type_code IS NULL;

ALTER TABLE work_requirement_instance
    ALTER COLUMN requirement_type_code SET NOT NULL,
    ADD CONSTRAINT uq_work_requirement_id_work_order
        UNIQUE (id, work_order_id);

CREATE TABLE work_readiness_waiver (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL
        REFERENCES organization(id) ON DELETE RESTRICT,
    work_order_id uuid NOT NULL,
    requirement_id uuid NOT NULL,
    requirement_version bigint NOT NULL CHECK (requirement_version >= 1),
    reason text NOT NULL CHECK (length(trim(reason)) BETWEEN 1 AND 2000),
    waived_by_user_id uuid NOT NULL
        REFERENCES user_identity(id) ON DELETE RESTRICT,
    source_operation_id uuid NOT NULL UNIQUE,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_work_readiness_waiver_work
        FOREIGN KEY (work_order_id, organization_id)
        REFERENCES work_order(id, organization_id) ON DELETE RESTRICT,
    CONSTRAINT fk_work_readiness_waiver_requirement
        FOREIGN KEY (requirement_id, work_order_id)
        REFERENCES work_requirement_instance(id, work_order_id) ON DELETE RESTRICT,
    UNIQUE (requirement_id, requirement_version)
);

ALTER TABLE work_requirement_instance
    ADD CONSTRAINT fk_work_requirement_waiver
        FOREIGN KEY (waiver_ref)
        REFERENCES work_readiness_waiver(id) ON DELETE RESTRICT;

ALTER TABLE work_blocker
    ADD COLUMN source_requirement_id uuid NULL,
    ADD COLUMN explanation_code varchar(80) NULL,
    ADD CONSTRAINT fk_work_blocker_source_requirement
        FOREIGN KEY (source_requirement_id)
        REFERENCES work_requirement_instance(id) ON DELETE RESTRICT;

CREATE UNIQUE INDEX uq_work_blocker_open_requirement
    ON work_blocker (work_order_id, source_requirement_id)
    WHERE state = 'OPEN' AND source_requirement_id IS NOT NULL;

CREATE INDEX idx_work_requirement_readiness_eval
    ON work_requirement_instance (
        work_order_id,
        requirement_family,
        satisfaction_state,
        requirement_type_code
    );

CREATE TABLE work_crew (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL
        REFERENCES organization(id) ON DELETE RESTRICT,
    project_id uuid NULL,
    crew_code varchar(64) NOT NULL,
    name varchar(160) NOT NULL,
    state varchar(16) NOT NULL CHECK (state IN ('ACTIVE','INACTIVE')),
    effective_from timestamptz NOT NULL,
    effective_to timestamptz NULL,
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL
        REFERENCES user_identity(id) ON DELETE RESTRICT,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    CONSTRAINT fk_work_crew_project_org
        FOREIGN KEY (project_id, organization_id)
        REFERENCES project(id, organization_id) ON DELETE RESTRICT,
    CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CHECK (updated_at >= created_at),
    UNIQUE (organization_id, crew_code),
    UNIQUE (id, organization_id)
);

CREATE TABLE work_crew_member (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    crew_id uuid NOT NULL,
    employee_id uuid NOT NULL,
    effective_from timestamptz NOT NULL,
    effective_to timestamptz NULL,
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL
        REFERENCES user_identity(id) ON DELETE RESTRICT,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    CONSTRAINT fk_work_crew_member_crew_org
        FOREIGN KEY (crew_id, organization_id)
        REFERENCES work_crew(id, organization_id) ON DELETE RESTRICT,
    CONSTRAINT fk_work_crew_member_employee_org
        FOREIGN KEY (employee_id, organization_id)
        REFERENCES employee(id, organization_id) ON DELETE RESTRICT,
    CHECK (effective_to IS NULL OR effective_to >= effective_from)
);

CREATE UNIQUE INDEX uq_work_crew_member_current
    ON work_crew_member (crew_id, employee_id)
    WHERE effective_to IS NULL;

CREATE INDEX idx_work_crew_current_project
    ON work_crew (organization_id, project_id, state, effective_from, effective_to);

ALTER TABLE work_assignment
    ADD COLUMN organization_id uuid NULL,
    ADD COLUMN project_id uuid NULL,
    ADD COLUMN supersedes_assignment_id uuid NULL,
    ADD COLUMN reason text NULL;

UPDATE work_assignment wa
SET organization_id = wo.organization_id,
    project_id = wo.project_id
FROM work_order wo
WHERE wo.id = wa.work_order_id;

ALTER TABLE work_assignment
    ALTER COLUMN organization_id SET NOT NULL,
    ALTER COLUMN project_id SET NOT NULL,
    ADD CONSTRAINT fk_work_assignment_work_context
        FOREIGN KEY (work_order_id, organization_id, project_id)
        REFERENCES work_order(id, organization_id, project_id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_work_assignment_supersedes
        FOREIGN KEY (supersedes_assignment_id)
        REFERENCES work_assignment(id) ON DELETE RESTRICT,
    ADD CONSTRAINT uq_work_assignment_id_work
        UNIQUE (id, work_order_id),
    ADD CONSTRAINT ck_work_assignment_reason_length
        CHECK (reason IS NULL OR length(trim(reason)) BETWEEN 1 AND 2000);

CREATE UNIQUE INDEX uq_work_assignment_one_active
    ON work_assignment (work_order_id)
    WHERE state = 'ACTIVE';

CREATE INDEX idx_work_assignment_current_target
    ON work_assignment (target_type, target_id, state, valid_from, valid_until);

CREATE INDEX idx_work_assignment_project_current
    ON work_assignment (project_id, state, assigned_at DESC);

CREATE OR REPLACE FUNCTION enforce_work_assignment_target_context()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.target_type = 'USER' THEN
        IF NOT EXISTS (
            SELECT 1
            FROM organization_membership om
            WHERE om.organization_id = NEW.organization_id
              AND om.user_identity_id = NEW.target_id
        ) THEN
            RAISE EXCEPTION 'WORK_ASSIGNMENT_USER_CONTEXT_INVALID';
        END IF;
    ELSIF NEW.target_type = 'TEAM' THEN
        IF NOT EXISTS (
            SELECT 1
            FROM team t
            WHERE t.id = NEW.target_id
              AND t.organization_id = NEW.organization_id
        ) THEN
            RAISE EXCEPTION 'WORK_ASSIGNMENT_TEAM_CONTEXT_INVALID';
        END IF;
    ELSIF NEW.target_type = 'CREW' THEN
        IF NOT EXISTS (
            SELECT 1
            FROM work_crew c
            WHERE c.id = NEW.target_id
              AND c.organization_id = NEW.organization_id
              AND (c.project_id IS NULL OR c.project_id = NEW.project_id)
        ) THEN
            RAISE EXCEPTION 'WORK_ASSIGNMENT_CREW_CONTEXT_INVALID';
        END IF;
    ELSIF NEW.target_type = 'SUBCONTRACTOR_ORGANIZATION' THEN
        IF NOT EXISTS (
            SELECT 1
            FROM organization o
            WHERE o.id = NEW.target_id
              AND o.organization_type = 'SUBCONTRACTOR'
        ) THEN
            RAISE EXCEPTION 'WORK_ASSIGNMENT_SUBCONTRACTOR_CONTEXT_INVALID';
        END IF;
    ELSE
        RAISE EXCEPTION 'WORK_ASSIGNMENT_TARGET_TYPE_INVALID';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_work_assignment_target_context
BEFORE INSERT OR UPDATE OF target_type, target_id, organization_id, project_id
ON work_assignment
FOR EACH ROW EXECUTE FUNCTION enforce_work_assignment_target_context();
