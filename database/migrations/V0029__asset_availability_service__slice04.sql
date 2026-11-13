-- Phase 5 Slice 04. These records provide source truth for availability.
ALTER TABLE asset_type_definition
    ADD COLUMN calibration_due_lead_days integer NOT NULL DEFAULT 0 CHECK (calibration_due_lead_days >= 0);

CREATE TABLE asset_maintenance_policy (
    config_revision_id uuid PRIMARY KEY REFERENCES config_revision(id) ON DELETE RESTRICT,
    interval_days integer NOT NULL CHECK (interval_days > 0),
    blocks_when_due boolean NOT NULL
);

CREATE TABLE asset_incident (
    id uuid PRIMARY KEY,
    asset_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    incident_type varchar(16) NOT NULL CHECK (incident_type IN ('DAMAGE','MISSING','LOST')),
    state varchar(16) NOT NULL CHECK (state IN ('OPEN','UNDER_REVIEW','RESOLVED','CLOSED')),
    description varchar(2000) NOT NULL CHECK (btrim(description) <> ''),
    reported_by uuid NOT NULL REFERENCES user_identity(id),
    reported_at timestamptz NOT NULL,
    project_id uuid,
    site_id uuid,
    work_order_id uuid,
    evidence_id uuid REFERENCES evidence(id),
    investigation_owner_id uuid,
    resolution varchar(2000),
    resolved_by uuid REFERENCES user_identity(id),
    resolved_at timestamptz,
    closed_by uuid REFERENCES user_identity(id),
    closed_at timestamptz,
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    FOREIGN KEY (asset_id, organization_id) REFERENCES asset(id, organization_id),
    FOREIGN KEY (project_id, organization_id) REFERENCES project(id, organization_id),
    FOREIGN KEY (site_id, organization_id) REFERENCES site(id, organization_id),
    FOREIGN KEY (work_order_id, organization_id) REFERENCES work_order(id, organization_id),
    FOREIGN KEY (investigation_owner_id, organization_id) REFERENCES employee(id, organization_id),
    UNIQUE (asset_id, id),
    CHECK (
        (state IN ('OPEN','UNDER_REVIEW') AND num_nonnulls(resolution, resolved_by, resolved_at) = 0)
        OR (state IN ('RESOLVED','CLOSED') AND num_nonnulls(resolution, resolved_by, resolved_at) = 3
            AND btrim(resolution) <> '' AND resolved_at >= reported_at)
    ),
    CHECK (
        (state <> 'CLOSED' AND num_nonnulls(closed_by, closed_at) = 0)
        OR (state = 'CLOSED' AND closed_by IS NOT NULL AND closed_at IS NOT NULL AND closed_at >= resolved_at)
    )
);
CREATE INDEX idx_asset_incident_history ON asset_incident (asset_id, reported_at DESC, id);
CREATE INDEX idx_asset_incident_active ON asset_incident (asset_id) WHERE state IN ('OPEN','UNDER_REVIEW');
ALTER TABLE asset_return_inspection
    ADD FOREIGN KEY (asset_id, damage_incident_id) REFERENCES asset_incident(asset_id, id) NOT VALID;

CREATE TABLE asset_calibration_record (
    id uuid PRIMARY KEY,
    asset_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    calibration_type varchar(64) NOT NULL CHECK (btrim(calibration_type) <> ''),
    provider_ref varchar(160),
    certificate_evidence_id uuid REFERENCES evidence(id),
    result varchar(16) NOT NULL CHECK (result IN ('PASSED','FAILED')),
    calibrated_at timestamptz NOT NULL,
    valid_until timestamptz,
    notes varchar(2000),
    recorded_by uuid NOT NULL REFERENCES user_identity(id),
    recorded_at timestamptz NOT NULL,
    FOREIGN KEY (asset_id, organization_id) REFERENCES asset(id, organization_id),
    UNIQUE (asset_id, calibrated_at),
    CHECK (calibrated_at <= recorded_at),
    CHECK (
        (result = 'PASSED' AND valid_until IS NOT NULL AND valid_until > calibrated_at)
        OR (result = 'FAILED' AND valid_until IS NULL)
    )
);
CREATE INDEX idx_asset_service_warehouse ON asset (warehouse_id, asset_code);

CREATE TABLE asset_maintenance_record (
    id uuid PRIMARY KEY,
    asset_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    service_type varchar(16) NOT NULL CHECK (service_type IN ('MAINTENANCE','CALIBRATION')),
    state varchar(16) NOT NULL CHECK (state IN ('IN_PROGRESS','COMPLETED')),
    description varchar(2000) NOT NULL CHECK (btrim(description) <> ''),
    provider_ref varchar(160),
    sent_by uuid NOT NULL REFERENCES user_identity(id),
    sent_at timestamptz NOT NULL,
    completed_by uuid REFERENCES user_identity(id),
    completed_at timestamptz,
    completion_notes varchar(2000),
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    FOREIGN KEY (asset_id, organization_id) REFERENCES asset(id, organization_id),
    CHECK (
        (state = 'IN_PROGRESS' AND num_nonnulls(completed_by, completed_at, completion_notes) = 0)
        OR (state = 'COMPLETED' AND num_nonnulls(completed_by, completed_at, completion_notes) = 3
            AND btrim(completion_notes) <> '' AND completed_at >= sent_at)
    )
);
CREATE UNIQUE INDEX uq_asset_maintenance_active ON asset_maintenance_record (asset_id) WHERE state = 'IN_PROGRESS';
CREATE INDEX idx_asset_maintenance_history ON asset_maintenance_record (asset_id, sent_at DESC, id);

CREATE FUNCTION guard_asset_service_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP <> 'UPDATE' OR TG_TABLE_NAME = 'asset_calibration_record' THEN
        RAISE EXCEPTION 'Asset service history is retained' USING ERRCODE = '23514';
    END IF;
    IF NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'Asset service history requires the next version' USING ERRCODE = '23514';
    END IF;
    IF TG_TABLE_NAME = 'asset_incident' THEN
        IF NOT (
            (OLD.state = 'OPEN' AND NEW.state IN ('UNDER_REVIEW','RESOLVED'))
            OR (OLD.state = 'UNDER_REVIEW' AND NEW.state = 'RESOLVED')
            OR (OLD.state = 'RESOLVED' AND NEW.state = 'CLOSED')
        ) OR (to_jsonb(NEW) - ARRAY['state','investigation_owner_id','resolution','resolved_by','resolved_at','closed_by','closed_at','version'])
          IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['state','investigation_owner_id','resolution','resolved_by','resolved_at','closed_by','closed_at','version'])
          OR (OLD.state = 'RESOLVED' AND (NEW.resolution, NEW.resolved_by, NEW.resolved_at)
              IS DISTINCT FROM (OLD.resolution, OLD.resolved_by, OLD.resolved_at)) THEN
            RAISE EXCEPTION 'Invalid incident transition' USING ERRCODE = '23514';
        END IF;
    ELSE
        IF OLD.state <> 'IN_PROGRESS' OR NEW.state <> 'COMPLETED'
          OR (to_jsonb(NEW) - ARRAY['state','completed_by','completed_at','completion_notes','version'])
          IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['state','completed_by','completed_at','completion_notes','version']) THEN
            RAISE EXCEPTION 'Invalid maintenance transition' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER asset_incident_history BEFORE UPDATE OR DELETE ON asset_incident
FOR EACH ROW EXECUTE FUNCTION guard_asset_service_history();
CREATE TRIGGER asset_incident_no_truncate BEFORE TRUNCATE ON asset_incident
FOR EACH STATEMENT EXECUTE FUNCTION guard_asset_service_history();
CREATE TRIGGER asset_calibration_history BEFORE UPDATE OR DELETE ON asset_calibration_record
FOR EACH ROW EXECUTE FUNCTION guard_asset_service_history();
CREATE TRIGGER asset_calibration_no_truncate BEFORE TRUNCATE ON asset_calibration_record
FOR EACH STATEMENT EXECUTE FUNCTION guard_asset_service_history();
CREATE TRIGGER asset_maintenance_history BEFORE UPDATE OR DELETE ON asset_maintenance_record
FOR EACH ROW EXECUTE FUNCTION guard_asset_service_history();
CREATE TRIGGER asset_maintenance_no_truncate BEFORE TRUNCATE ON asset_maintenance_record
FOR EACH STATEMENT EXECUTE FUNCTION guard_asset_service_history();

-- Shared read for the Passport and warehouse queue. As-of time is supplied by the server.
CREATE FUNCTION asset_availability_reasons(p_asset_id uuid, p_at timestamptz)
RETURNS TABLE (rank integer, state text, code text, source_type text, source_id uuid, source_version bigint, due_at timestamptz)
LANGUAGE sql STABLE AS $$
    WITH passport AS (
        SELECT a.*, t.calibration_required, t.inspection_required_on_return, t.maintenance_policy_id,
            policy.interval_days, policy.blocks_when_due, policy_revision.revision_number AS policy_revision
        FROM asset a
        LEFT JOIN asset_type_definition t ON t.config_revision_id = a.asset_type_definition_id
        LEFT JOIN asset_maintenance_policy policy ON policy.config_revision_id = t.maintenance_policy_id
        LEFT JOIN config_revision policy_revision ON policy_revision.id = policy.config_revision_id
        WHERE a.id = p_asset_id
    ), calibration AS (
        SELECT * FROM asset_calibration_record WHERE asset_id = p_asset_id AND calibrated_at <= p_at
        ORDER BY calibrated_at DESC LIMIT 1
    ), service AS (
        SELECT * FROM asset_maintenance_record WHERE asset_id = p_asset_id AND state = 'IN_PROGRESS'
    ), last_service AS (
        SELECT * FROM asset_maintenance_record WHERE asset_id = p_asset_id AND state = 'COMPLETED'
          AND service_type = 'MAINTENANCE' ORDER BY completed_at DESC, id DESC LIMIT 1
    ), return_movement AS (
        SELECT m.*, i.id AS inspection_id, i.condition_observed, i.version AS inspection_version,
            i.accessory_results, i.accessory_template_id AS inspected_template
        FROM asset_movement m LEFT JOIN asset_return_inspection i ON i.return_movement_id = m.id AND i.asset_id = m.asset_id
        WHERE m.asset_id = p_asset_id AND m.movement_type IN ('RETURN', 'RETURN_ASSET')
        ORDER BY m.ledger_version DESC NULLS LAST, m.recorded_at DESC, m.id DESC LIMIT 1
    )
    SELECT 10, 'RETIRED', CASE WHEN a.lifecycle_state = 'RETIRED' THEN 'ASSET_RETIRED' ELSE 'RETIREMENT_REQUESTED' END,
           'ASSET', a.id, a.version, NULL::timestamptz
    FROM passport a WHERE a.lifecycle_state <> 'ACTIVE'
    UNION ALL
    SELECT 20, 'BLOCKED_CONDITION', 'CONDITION_UNFIT', 'ASSET', a.id, a.version, NULL
    FROM passport a WHERE a.condition_state = 'UNFIT'
    UNION ALL
    SELECT 30, 'BLOCKED_INCIDENT', 'INCIDENT_' || i.incident_type, 'ASSET_INCIDENT', i.id, i.version, NULL
    FROM asset_incident i WHERE i.asset_id = p_asset_id AND i.state IN ('OPEN','UNDER_REVIEW')
    UNION ALL
    SELECT 40, 'BLOCKED_INSPECTION',
           CASE WHEN r.inspection_id IS NULL THEN 'INSPECTION_MISSING'
                WHEN r.condition_observed = 'UNFIT' THEN 'INSPECTION_UNFIT'
                ELSE 'ACCESSORY_INSPECTION_INCOMPLETE' END,
           CASE WHEN r.inspection_id IS NULL THEN 'ASSET_MOVEMENT' ELSE 'ASSET_RETURN_INSPECTION' END,
           coalesce(r.inspection_id, r.id), coalesce(r.inspection_version, r.ledger_version, 1), NULL
    FROM passport a JOIN asset_type_definition t ON t.config_revision_id = a.asset_type_definition_id
    CROSS JOIN return_movement r
    WHERE (a.inspection_required_on_return OR t.accessory_template_id IS NOT NULL)
      AND (r.inspection_id IS NULL OR r.condition_observed = 'UNFIT' OR
        (t.accessory_template_id IS NOT NULL AND
            (r.inspected_template IS DISTINCT FROM t.accessory_template_id OR r.accessory_results IS NULL)))
    UNION ALL
    SELECT 50, 'BLOCKED_CALIBRATION', 'ASSET_TYPE_POLICY_UNAVAILABLE', 'ASSET_TYPE', a.asset_type_definition_id,
           a.asset_type_revision, NULL
    FROM passport a WHERE a.calibration_required IS NULL
    UNION ALL
    SELECT 51, 'BLOCKED_CALIBRATION',
           CASE WHEN c.id IS NULL THEN 'CALIBRATION_MISSING' WHEN c.result = 'FAILED' THEN 'CALIBRATION_FAILED'
                ELSE 'CALIBRATION_EXPIRED' END,
           CASE WHEN c.id IS NULL THEN 'ASSET_TYPE' ELSE 'ASSET_CALIBRATION' END,
           coalesce(c.id, a.asset_type_definition_id), CASE WHEN c.id IS NULL THEN a.asset_type_revision ELSE 1 END,
           c.valid_until
    FROM passport a LEFT JOIN calibration c ON true
    WHERE a.calibration_required AND (c.id IS NULL OR c.result = 'FAILED' OR c.valid_until <= p_at)
    UNION ALL
    SELECT 52, 'BLOCKED_CALIBRATION', 'CALIBRATION_IN_PROGRESS', 'ASSET_MAINTENANCE', s.id, s.version, NULL
    FROM service s WHERE s.service_type = 'CALIBRATION'
    UNION ALL
    SELECT 60, 'BLOCKED_MAINTENANCE', 'MAINTENANCE_IN_PROGRESS', 'ASSET_MAINTENANCE', s.id, s.version, NULL
    FROM service s WHERE s.service_type = 'MAINTENANCE'
    UNION ALL
    SELECT 61, 'BLOCKED_MAINTENANCE', 'MAINTENANCE_POLICY_UNAVAILABLE', 'MAINTENANCE_POLICY',
           a.maintenance_policy_id, NULL, NULL
    FROM passport a WHERE a.maintenance_policy_id IS NOT NULL AND a.interval_days IS NULL
    UNION ALL
    SELECT 62, 'BLOCKED_MAINTENANCE', 'MAINTENANCE_DUE', 'MAINTENANCE_POLICY',
           a.maintenance_policy_id, a.policy_revision, schedule.due
    FROM passport a LEFT JOIN last_service s ON true
    CROSS JOIN LATERAL (SELECT coalesce(s.completed_at, a.created_at) + a.interval_days * interval '24 hours' AS due) schedule
    WHERE a.blocks_when_due AND schedule.due <= p_at
    UNION ALL
    SELECT 70, CASE WHEN m.movement_type IN ('CHECKOUT', 'CHECKOUT_ASSET') THEN 'CHECKED_OUT' ELSE 'BLOCKED_CUSTODY' END,
           'ASSET_IN_EMPLOYEE_CUSTODY', 'ASSET_MOVEMENT', c.source_movement_id, c.asset_version, NULL
    FROM passport a JOIN asset_custody_projection c ON c.asset_id = a.id
    JOIN asset_movement m ON m.id = c.source_movement_id WHERE c.current_custodian_target_id IS NOT NULL
    UNION ALL
    SELECT 71, 'BLOCKED_CUSTODY', 'STORAGE_CUSTODY_UNVERIFIED',
           CASE WHEN c.asset_id IS NULL THEN 'ASSET' ELSE 'ASSET_MOVEMENT' END,
           coalesce(c.source_movement_id, a.id), coalesce(c.asset_version, a.version), NULL
    FROM passport a LEFT JOIN asset_custody_projection c ON c.asset_id = a.id
    LEFT JOIN storage_location l ON l.id = c.current_storage_location_id AND l.organization_id = a.organization_id
    LEFT JOIN warehouse w ON w.id = l.warehouse_id
    WHERE c.current_custodian_target_id IS NULL AND
        (l.id IS NULL OR l.lifecycle_state <> 'ACTIVE' OR l.active_from > p_at OR l.active_until <= p_at
            OR (l.warehouse_id IS NOT NULL AND NOT coalesce(w.active, false)))
    UNION ALL
    SELECT 80, 'RESERVED', 'ACTIVE_RESERVATION', 'ASSET_RESERVATION', r.id, r.version, r.end_at
    FROM asset_reservation r WHERE r.asset_id = p_asset_id AND r.state = 'ACTIVE'
      AND (r.start_at IS NULL OR r.start_at <= p_at) AND (r.end_at IS NULL OR r.end_at > p_at);
$$;
