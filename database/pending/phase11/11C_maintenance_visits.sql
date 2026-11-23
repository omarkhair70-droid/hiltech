-- P11C: internal maintenance visit plans / findings. Integration owner allocates Flyway number.
-- A visit is only a DRAFT annotation of existing WorkOrder, not an approved schedule.
-- Field work, checklist, assets, stock, procurement and evidence stay in their canonical modules.
CREATE TABLE service_maintenance_visit_plan (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    work_order_id uuid NOT NULL,
    site_id uuid NOT NULL,
    planned_for timestamptz NOT NULL,
    work_order_version_at_plan bigint NOT NULL CHECK (work_order_version_at_plan > 0),
    state varchar(24) NOT NULL DEFAULT 'DRAFT' CHECK (state = 'DRAFT'),
    coverage_state varchar(24) NOT NULL DEFAULT 'UNVERIFIED' CHECK (coverage_state = 'UNVERIFIED'),
    source_operation_id uuid NOT NULL UNIQUE,
    created_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_maintenance_visit_work_context
      FOREIGN KEY(work_order_id, organization_id, project_id, site_id)
      REFERENCES work_order(id, organization_id, project_id, site_id) ON DELETE RESTRICT,
    CONSTRAINT uq_maintenance_visit_context
      UNIQUE(id, organization_id, project_id, work_order_id),
    CHECK(planned_for >= created_at)
);
CREATE INDEX idx_maintenance_visit_project_plan
 ON service_maintenance_visit_plan(project_id, planned_for, id);

CREATE TABLE service_maintenance_finding (
    id uuid PRIMARY KEY,
    visit_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    work_order_id uuid NOT NULL,
    finding_type_code varchar(64) NOT NULL
      CHECK(finding_type_code ~ '^[A-Z][A-Z0-9_]{1,63}$'),
    severity_code varchar(64) NOT NULL
      CHECK(severity_code ~ '^[A-Z][A-Z0-9_]{1,63}$'),
    description text NOT NULL CHECK(length(btrim(description)) BETWEEN 1 AND 8000),
    evidence_id uuid NOT NULL REFERENCES evidence(id) ON DELETE RESTRICT,
    state varchar(24) NOT NULL DEFAULT 'OPEN' CHECK(state = 'OPEN'),
    visibility varchar(24) NOT NULL DEFAULT 'INTERNAL_ONLY' CHECK(visibility = 'INTERNAL_ONLY'),
    source_operation_id uuid NOT NULL UNIQUE,
    created_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    FOREIGN KEY(visit_id, organization_id, project_id, work_order_id)
      REFERENCES service_maintenance_visit_plan(id, organization_id, project_id, work_order_id)
      ON DELETE RESTRICT
);
CREATE INDEX idx_maintenance_finding_visit ON service_maintenance_finding(visit_id, created_at, id);

CREATE FUNCTION enforce_service_finding_evidence() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  proposed_visit_created_at timestamptz;
BEGIN
  -- A DRAFT/PLANNED/ASSIGNED work item is not a performed inspection.
  -- Lock the real source work so it cannot change while evidence is recorded.
  PERFORM 1 FROM work_order wo
   WHERE wo.id = NEW.work_order_id
     AND wo.organization_id = NEW.organization_id
     AND wo.project_id = NEW.project_id
     AND wo.lifecycle_state IN (
       'IN_PROGRESS','BLOCKED','SUBMITTED_FOR_REVIEW',
       'REWORK_REQUIRED','ACCEPTED','CLOSED'
     ) FOR SHARE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Field finding requires executed source work'
      USING ERRCODE = '23514';
  END IF;
  -- An observation of a scheduled visit cannot predate the planned visit.
  SELECT GREATEST(created_at, planned_for) INTO proposed_visit_created_at
    FROM service_maintenance_visit_plan
   WHERE id = NEW.visit_id AND organization_id = NEW.organization_id
     AND project_id = NEW.project_id AND work_order_id = NEW.work_order_id;
  IF proposed_visit_created_at IS NULL THEN
    RAISE EXCEPTION 'Original visit source missing' USING ERRCODE = '23514';
  END IF;
  IF NEW.created_at < proposed_visit_created_at
     OR NEW.created_at > now() + interval '5 minutes' THEN
    RAISE EXCEPTION 'Finding must be recorded on or after planned visit, in server time'
      USING ERRCODE = '23514';
  END IF;
  IF NOT EXISTS(
    SELECT 1 FROM evidence e WHERE e.id = NEW.evidence_id
     AND e.organization_id = NEW.organization_id
     AND e.work_order_id = NEW.work_order_id
     AND e.target_type = 'WORK_ORDER' AND e.target_id = NEW.work_order_id
     AND e.storage_state = 'READY'
     -- Old evidence must not be labelled as a new visit's observation.
     AND e.captured_at >= proposed_visit_created_at
     AND NOT EXISTS(
       SELECT 1 FROM evidence replacement
       WHERE replacement.supersedes_evidence_id = e.id
         AND replacement.storage_state = 'READY'
     )
  ) THEN
    RAISE EXCEPTION 'Finding needs current READY Evidence captured after visit draft creation'
      USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER trg_service_finding_evidence
BEFORE INSERT ON service_maintenance_finding
FOR EACH ROW EXECUTE FUNCTION enforce_service_finding_evidence();

CREATE FUNCTION protect_service_maintenance_history() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'Phase11 maintenance draft history is append-only'
    USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER trg_service_visit_immutable
BEFORE UPDATE OR DELETE ON service_maintenance_visit_plan
FOR EACH ROW EXECUTE FUNCTION protect_service_maintenance_history();
CREATE TRIGGER trg_service_visit_no_truncate
BEFORE TRUNCATE ON service_maintenance_visit_plan
FOR EACH STATEMENT EXECUTE FUNCTION protect_service_maintenance_history();
CREATE TRIGGER trg_service_finding_no_truncate
BEFORE TRUNCATE ON service_maintenance_finding
FOR EACH STATEMENT EXECUTE FUNCTION protect_service_maintenance_history();

CREATE TRIGGER trg_service_finding_immutable
BEFORE UPDATE OR DELETE ON service_maintenance_finding
FOR EACH ROW EXECUTE FUNCTION protect_service_maintenance_history();
