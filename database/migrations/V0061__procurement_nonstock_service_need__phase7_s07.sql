-- Phase 7 / S07A: non-stock external service demand and independent technical review.
-- Requires prior V0060. Explicitly not a StockItem, Warehouse receipt, payable,
-- supplier order or payment. This version is provisional until DevOps allocation.
CREATE TABLE procurement_service_need (
    id uuid PRIMARY KEY,
    operation_id uuid NOT NULL UNIQUE,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    team_id uuid NOT NULL,
    team_version bigint NOT NULL CHECK (team_version >= 1),
    specification varchar(2000) NOT NULL
      CHECK (length(btrim(specification)) BETWEEN 20 AND 2000),
    acceptance_criteria varchar(2000) NOT NULL
      CHECK (length(btrim(acceptance_criteria)) BETWEEN 20 AND 2000),
    justification varchar(500) NOT NULL
      CHECK (length(btrim(justification)) BETWEEN 12 AND 500),
    requested_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    correlation_id varchar(128) NOT NULL CHECK (length(btrim(correlation_id)) > 0),
    version bigint NOT NULL DEFAULT 1 CHECK (version = 1),
    UNIQUE (id, organization_id),
    FOREIGN KEY (team_id, organization_id) REFERENCES team(id, organization_id)
      ON DELETE RESTRICT
);
CREATE INDEX idx_procurement_service_need_team
  ON procurement_service_need (organization_id, team_id, created_at DESC, id DESC);
CREATE TRIGGER procurement_service_need_immutable
BEFORE UPDATE OR DELETE ON procurement_service_need
FOR EACH ROW EXECUTE FUNCTION reject_procurement_draft_mutation();

-- The effective state is derived exclusively from this append-only log.
-- Revisions replace displayable requirements but preserve every earlier version.
CREATE TABLE procurement_service_need_event (
    id uuid PRIMARY KEY,
    operation_id uuid NOT NULL UNIQUE,
    organization_id uuid NOT NULL,
    need_id uuid NOT NULL,
    source_version bigint NOT NULL CHECK (source_version >= 1),
    result_version bigint NOT NULL CHECK (result_version = source_version + 1),
    transition_type varchar(24) NOT NULL CHECK (transition_type IN (
      'SUBMIT', 'REVISE', 'WITHDRAW', 'TECHNICAL_READY', 'NEEDS_INFO', 'REJECT'
    )),
    from_state varchar(24) NOT NULL,
    to_state varchar(24) NOT NULL CHECK (to_state IN (
      'DRAFT', 'SUBMITTED', 'READY_FOR_SOURCING',
      'NEEDS_INFO', 'REJECTED', 'WITHDRAWN'
    )),
    reason varchar(500),
    revised_specification varchar(2000),
    revised_acceptance_criteria varchar(2000),
    revised_justification varchar(500),
    actor_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    occurred_at timestamptz NOT NULL,
    correlation_id varchar(128) NOT NULL CHECK (length(btrim(correlation_id)) > 0),
    UNIQUE (need_id, result_version),
    FOREIGN KEY (need_id, organization_id)
      REFERENCES procurement_service_need(id, organization_id) ON DELETE RESTRICT,
    CONSTRAINT ck_procurement_service_revision_shape CHECK (
      (transition_type = 'REVISE'
        AND revised_specification IS NOT NULL
        AND length(btrim(revised_specification)) BETWEEN 20 AND 2000
        AND revised_acceptance_criteria IS NOT NULL
        AND length(btrim(revised_acceptance_criteria)) BETWEEN 20 AND 2000
        AND revised_justification IS NOT NULL
        AND length(btrim(revised_justification)) BETWEEN 12 AND 500)
      OR (transition_type <> 'REVISE'
        AND num_nonnulls(revised_specification, revised_acceptance_criteria,
                         revised_justification) = 0)
    ),
    CONSTRAINT ck_procurement_service_reason_shape CHECK (
      reason IS NULL OR length(btrim(reason)) BETWEEN 3 AND 500
    )
);
CREATE INDEX idx_procurement_service_need_event_latest
  ON procurement_service_need_event (need_id, result_version DESC);
CREATE TRIGGER procurement_service_need_event_immutable
BEFORE UPDATE OR DELETE ON procurement_service_need_event
FOR EACH ROW EXECUTE FUNCTION reject_procurement_draft_mutation();

-- Fail closed at PostgreSQL as well as API. The source team is serialized with
-- event history; a direct insert cannot skip the accepted state or self-review.
CREATE FUNCTION guard_procurement_service_need_event() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  root procurement_service_need%ROWTYPE;
  current_version bigint;
  current_state varchar(24);
BEGIN
  SELECT * INTO root FROM procurement_service_need WHERE id = NEW.need_id FOR UPDATE;
  IF root.id IS NULL OR root.organization_id <> NEW.organization_id THEN
    RAISE EXCEPTION 'Service need organization/source mismatch' USING ERRCODE='23514';
  END IF;
  IF NOT EXISTS (
      SELECT 1 FROM team t JOIN organization o ON o.id=t.organization_id
      WHERE t.id=root.team_id AND t.organization_id=root.organization_id
        AND t.active=true AND t.version=root.team_version
        AND o.status='ACTIVE' AND o.organization_type='HILTECH'
  ) THEN
    RAISE EXCEPTION 'Service need source is no longer current' USING ERRCODE='23514';
  END IF;
  SELECT e.result_version, e.to_state INTO current_version, current_state
    FROM procurement_service_need_event e WHERE e.need_id=NEW.need_id
    ORDER BY e.result_version DESC LIMIT 1;
  current_version := COALESCE(current_version, 1);
  current_state := COALESCE(current_state, 'DRAFT');
  IF NEW.source_version <> current_version OR NEW.from_state <> current_state
     OR NOT (
        (NEW.transition_type='SUBMIT' AND current_state='DRAFT'
            AND NEW.to_state='SUBMITTED')
     OR (NEW.transition_type='REVISE' AND current_state IN ('DRAFT','NEEDS_INFO')
            AND NEW.to_state='DRAFT')
     OR (NEW.transition_type='WITHDRAW'
            AND current_state IN ('DRAFT','SUBMITTED','NEEDS_INFO')
            AND NEW.to_state='WITHDRAWN')
     OR (NEW.transition_type='TECHNICAL_READY' AND current_state='SUBMITTED'
            AND NEW.to_state='READY_FOR_SOURCING')
     OR (NEW.transition_type='NEEDS_INFO' AND current_state='SUBMITTED'
            AND NEW.to_state='NEEDS_INFO')
     OR (NEW.transition_type='REJECT' AND current_state='SUBMITTED'
            AND NEW.to_state='REJECTED')
     ) THEN
    RAISE EXCEPTION 'Service need invalid/stale transition' USING ERRCODE='23514';
  END IF;
  IF (NEW.transition_type IN ('SUBMIT','REVISE','WITHDRAW')
        AND NEW.actor_user_id <> root.requested_by_user_id)
     OR (NEW.transition_type IN ('TECHNICAL_READY','NEEDS_INFO','REJECT')
        AND NEW.actor_user_id = root.requested_by_user_id) THEN
    RAISE EXCEPTION 'Service need requester cannot self-review' USING ERRCODE='23514';
  END IF;
  IF NEW.transition_type IN ('NEEDS_INFO','REJECT')
     AND (NEW.reason IS NULL OR length(btrim(NEW.reason)) < 3) THEN
    RAISE EXCEPTION 'Service need review requires a reason' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER procurement_service_need_event_guard
BEFORE INSERT ON procurement_service_need_event
FOR EACH ROW EXECUTE FUNCTION guard_procurement_service_need_event();
