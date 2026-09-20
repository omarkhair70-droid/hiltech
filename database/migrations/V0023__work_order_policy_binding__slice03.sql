-- Phase 4 / Slice 03 — revision-bound WorkOrder planning.
-- Forward-only hardening of the V0003 configuration and V0005 work scaffolds.

ALTER TABLE work_order
    ADD COLUMN organization_id uuid NULL,
    ADD COLUMN baseline_version integer NULL;

UPDATE work_order wo
SET organization_id = p.organization_id,
    baseline_version = p.baseline_version
FROM project p
WHERE p.id = wo.project_id;

ALTER TABLE work_order
    ALTER COLUMN organization_id SET NOT NULL,
    ALTER COLUMN baseline_version SET NOT NULL,
    ADD CONSTRAINT ck_work_order_baseline_version CHECK (baseline_version >= 1),
    ADD CONSTRAINT fk_work_order_project_org
        FOREIGN KEY (project_id, organization_id)
        REFERENCES project(id, organization_id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_work_order_site_org
        FOREIGN KEY (site_id, organization_id)
        REFERENCES site(id, organization_id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_work_order_project_site_org
        FOREIGN KEY (project_site_id, organization_id, project_id, site_id)
        REFERENCES project_site(id, organization_id, project_id, site_id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_work_order_area_context
        FOREIGN KEY (area_id, organization_id, site_id)
        REFERENCES area(id, organization_id, site_id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_work_order_package_context
        FOREIGN KEY (work_package_id, organization_id, project_id)
        REFERENCES work_package(id, organization_id, project_id) ON DELETE RESTRICT,
    ADD CONSTRAINT uq_work_order_id_org UNIQUE (id, organization_id),
    ADD CONSTRAINT uq_work_order_id_org_project UNIQUE (id, organization_id, project_id),
    ADD CONSTRAINT uq_work_order_id_org_project_site
        UNIQUE (id, organization_id, project_id, site_id),
    ADD CONSTRAINT ck_work_order_project_site_required
        CHECK (project_site_id IS NOT NULL);

CREATE INDEX idx_work_order_project_baseline_state
    ON work_order (project_id, baseline_version, lifecycle_state, id);

CREATE INDEX idx_work_order_site_area
    ON work_order (site_id, area_id, lifecycle_state, id);

CREATE OR REPLACE FUNCTION enforce_work_order_package_site_context()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    package_site_id uuid;
BEGIN
    IF NEW.work_package_id IS NULL THEN
        RETURN NEW;
    END IF;

    SELECT wp.site_id
    INTO package_site_id
    FROM work_package wp
    WHERE wp.id = NEW.work_package_id
      AND wp.organization_id = NEW.organization_id
      AND wp.project_id = NEW.project_id;

    IF NOT FOUND OR (package_site_id IS NOT NULL AND package_site_id <> NEW.site_id) THEN
        RAISE EXCEPTION 'WorkOrder WorkPackage must belong to the same Project and compatible Site context'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_work_order_package_site_context
BEFORE INSERT OR UPDATE OF work_package_id, organization_id, project_id, site_id
ON work_order
FOR EACH ROW EXECUTE FUNCTION enforce_work_order_package_site_context();

ALTER TABLE work_policy_binding
    ADD CONSTRAINT uq_work_policy_binding_id_work_order UNIQUE (id, work_order_id);

CREATE UNIQUE INDEX uq_work_policy_binding_current
    ON work_policy_binding (work_order_id)
    WHERE superseded_at IS NULL;

ALTER TABLE work_instruction_revision
    ADD CONSTRAINT uq_work_instruction_revision_id_work_order UNIQUE (id, work_order_id);

ALTER TABLE work_order
    DROP CONSTRAINT fk_work_order_policy_binding,
    DROP CONSTRAINT fk_work_order_instruction_revision,
    ADD CONSTRAINT fk_work_order_current_policy_same_work
        FOREIGN KEY (current_policy_binding_id, id)
        REFERENCES work_policy_binding(id, work_order_id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_work_order_current_instruction_same_work
        FOREIGN KEY (current_instruction_revision_id, id)
        REFERENCES work_instruction_revision(id, work_order_id) ON DELETE RESTRICT;

CREATE TABLE work_task (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    work_order_id uuid NOT NULL,
    task_code varchar(64) NULL,
    title varchar(240) NOT NULL,
    description text NULL,
    sort_order integer NOT NULL DEFAULT 0 CHECK (sort_order >= 0),
    mandatory boolean NOT NULL DEFAULT true,
    estimated_duration_minutes integer NULL CHECK (estimated_duration_minutes IS NULL OR estimated_duration_minutes > 0),
    evidence_requirement_key varchar(64) NULL,
    state varchar(16) NOT NULL CHECK (state IN ('PLANNED', 'CANCELLED')),
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    CONSTRAINT fk_work_task_work_order_context
        FOREIGN KEY (work_order_id, organization_id)
        REFERENCES work_order(id, organization_id) ON DELETE RESTRICT,
    CONSTRAINT uq_work_task_code UNIQUE NULLS NOT DISTINCT (work_order_id, task_code),
    CONSTRAINT uq_work_task_id_work_order UNIQUE (id, work_order_id)
);

CREATE INDEX idx_work_task_work_order_order
    ON work_task (work_order_id, state, sort_order, id);

CREATE TABLE work_order_dependency (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    predecessor_work_order_id uuid NOT NULL,
    successor_work_order_id uuid NOT NULL,
    dependency_type varchar(32) NOT NULL CHECK (dependency_type = 'FINISH_TO_START'),
    lag_minutes bigint NOT NULL DEFAULT 0 CHECK (lag_minutes >= 0),
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    CONSTRAINT fk_work_dependency_project_org
        FOREIGN KEY (project_id, organization_id)
        REFERENCES project(id, organization_id) ON DELETE RESTRICT,
    CONSTRAINT fk_work_dependency_predecessor_context
        FOREIGN KEY (predecessor_work_order_id, organization_id, project_id)
        REFERENCES work_order(id, organization_id, project_id) ON DELETE RESTRICT,
    CONSTRAINT fk_work_dependency_successor_context
        FOREIGN KEY (successor_work_order_id, organization_id, project_id)
        REFERENCES work_order(id, organization_id, project_id) ON DELETE RESTRICT,
    CONSTRAINT ck_work_dependency_not_self
        CHECK (predecessor_work_order_id <> successor_work_order_id),
    CONSTRAINT uq_work_dependency_edge
        UNIQUE (project_id, predecessor_work_order_id, successor_work_order_id, dependency_type)
);

CREATE INDEX idx_work_dependency_project
    ON work_order_dependency (project_id, predecessor_work_order_id, successor_work_order_id);
