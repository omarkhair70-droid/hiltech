-- Phase 11A2: additive internal handover review history, not issuance/approval.
-- Requires 11A_handover.sql. The immutable package version stays a DRAFT;
-- client sharing, customer acceptance, warranty activation remain unsupported.
CREATE TABLE service_handover_review_event (
  id uuid PRIMARY KEY,
  package_version_id uuid NOT NULL,
  organization_id uuid NOT NULL,
  project_id uuid NOT NULL,
  event_number integer NOT NULL CHECK(event_number > 0),
  event_type varchar(32) NOT NULL CHECK(event_type IN (
    'CHECK_STARTED','FINDING_RECORDED','RECHECK_REQUESTED'
  )),
  review_state varchar(32) NOT NULL CHECK(review_state IN (
    'CHECKING','FINDINGS_RECORDED','RECHECK_REQUIRED'
  )),
  source_digest char(64) NOT NULL CHECK(source_digest ~ '^[0-9a-f]{64}$'),
  reason text NOT NULL CHECK(length(btrim(reason)) BETWEEN 1 AND 4000),
  created_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
  created_at timestamptz NOT NULL,
  operation_id uuid NOT NULL UNIQUE,
  client_visibility varchar(24) NOT NULL DEFAULT 'INTERNAL_ONLY'
    CHECK(client_visibility='INTERNAL_ONLY'),
  CONSTRAINT fk_handover_review_source FOREIGN KEY(
    package_version_id,organization_id,project_id)
    REFERENCES service_handover_package_version(id,organization_id,project_id)
    ON DELETE RESTRICT,
  UNIQUE(package_version_id,event_number)
);
CREATE INDEX idx_handover_review_timeline
  ON service_handover_review_event(package_version_id,event_number DESC);

CREATE FUNCTION service_handover_review_source_gate() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  current_digest char(64);
  source_package uuid;
  source_version integer;
  package_created_at timestamptz;
  previous_state text;
  previous_number integer;
  previous_at timestamptz;
BEGIN
  SELECT source_digest,package_id,version_number,created_at
    INTO current_digest,source_package,source_version,package_created_at
    FROM service_handover_package_version
    WHERE id=NEW.package_version_id AND organization_id=NEW.organization_id
      AND project_id=NEW.project_id AND state='DRAFT' FOR UPDATE;
  IF NOT FOUND OR current_digest IS DISTINCT FROM NEW.source_digest THEN
    RAISE EXCEPTION 'Internal review source digest must match frozen draft'
      USING ERRCODE='23514';
  END IF;
  IF EXISTS(
    SELECT 1 FROM service_handover_package_version v
    WHERE v.package_id=source_package AND v.version_number>source_version
  ) THEN
    RAISE EXCEPTION 'Use latest handover draft for new internal review'
      USING ERRCODE='23514';
  END IF;
  SELECT event_number,review_state,created_at
    INTO previous_number,previous_state,previous_at
    FROM service_handover_review_event
    WHERE package_version_id=NEW.package_version_id
    ORDER BY event_number DESC LIMIT 1;
  previous_number:=COALESCE(previous_number,0);
  IF NEW.created_at < COALESCE(previous_at,package_created_at)
     OR NEW.created_at > now() + interval '5 minutes' THEN
    RAISE EXCEPTION 'Handover review timestamp not monotonic'
      USING ERRCODE='23514';
  END IF;
  IF NEW.event_number<>previous_number+1 OR NOT (
      (NEW.event_type='CHECK_STARTED' AND previous_number=0
        AND NEW.review_state='CHECKING')
      OR (NEW.event_type='FINDING_RECORDED' AND
        previous_state IN ('CHECKING','FINDINGS_RECORDED')
        AND NEW.review_state='FINDINGS_RECORDED')
      OR (NEW.event_type='RECHECK_REQUESTED'
        AND previous_state='FINDINGS_RECORDED'
        AND NEW.review_state='RECHECK_REQUIRED')
    ) THEN
    RAISE EXCEPTION 'Invalid internal handover review sequence'
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER trg_handover_review_source
BEFORE INSERT ON service_handover_review_event
FOR EACH ROW EXECUTE FUNCTION service_handover_review_source_gate();
CREATE TRIGGER trg_handover_review_immutable
BEFORE UPDATE OR DELETE ON service_handover_review_event
FOR EACH ROW EXECUTE FUNCTION service_handover_no_snapshot_mutation();
CREATE TRIGGER trg_handover_review_no_truncate
BEFORE TRUNCATE ON service_handover_review_event
FOR EACH STATEMENT EXECUTE FUNCTION service_handover_no_snapshot_mutation();
