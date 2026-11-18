-- Phase 5 Slice 05. Work owns the requirement. Warehouse owns the resource mapping and reservation.
CREATE TABLE project_resource_request_delegate (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    user_id uuid NOT NULL REFERENCES user_identity(id),
    role varchar(16) NOT NULL CHECK (role IN ('engineer','supervisor')),
    effective_from timestamptz NOT NULL,
    effective_until timestamptz,
    FOREIGN KEY (project_id, organization_id) REFERENCES project(id, organization_id),
    CHECK (effective_until IS NULL OR effective_until > effective_from)
);
CREATE INDEX idx_project_resource_delegate ON project_resource_request_delegate (project_id, user_id);

CREATE TABLE work_resource_request (
    requirement_id uuid PRIMARY KEY,
    work_order_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    asset_type_id uuid REFERENCES asset_type_definition(config_revision_id),
    stock_item_id uuid,
    quantity numeric(20,6) NOT NULL CHECK (quantity > 0),
    unit_code varchar(64),
    requested_by uuid NOT NULL REFERENCES user_identity(id),
    requested_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    FOREIGN KEY (requirement_id, work_order_id) REFERENCES work_requirement_instance(id, work_order_id),
    FOREIGN KEY (work_order_id, organization_id) REFERENCES work_order(id, organization_id),
    FOREIGN KEY (stock_item_id, organization_id) REFERENCES stock_item(id, organization_id),
    FOREIGN KEY (stock_item_id, unit_code) REFERENCES stock_item(id, unit_of_measure_code),
    CHECK ((asset_type_id IS NOT NULL AND stock_item_id IS NULL AND unit_code IS NULL AND quantity = 1)
        OR (asset_type_id IS NULL AND stock_item_id IS NOT NULL AND unit_code IS NOT NULL))
);

ALTER TABLE asset_reservation ADD COLUMN organization_id uuid,
    ADD COLUMN work_requirement_instance_id uuid,
    ADD COLUMN recorded_at timestamptz,
    ADD COLUMN ended_at timestamptz,
    ADD COLUMN ended_by uuid REFERENCES user_identity(id),
    ADD COLUMN end_reason varchar(2000),
    ADD FOREIGN KEY (asset_id, organization_id) REFERENCES asset(id, organization_id),
    ADD FOREIGN KEY (work_order_id, organization_id) REFERENCES work_order(id, organization_id),
    ADD FOREIGN KEY (work_requirement_instance_id, work_order_id) REFERENCES work_requirement_instance(id, work_order_id),
    ADD FOREIGN KEY (work_requirement_instance_id) REFERENCES work_resource_request(requirement_id),
    ADD CHECK (organization_id IS NULL OR
        (work_order_id IS NOT NULL AND site_id IS NOT NULL AND work_requirement_instance_id IS NOT NULL
            AND recorded_at IS NOT NULL AND start_at IS NOT NULL AND end_at IS NOT NULL));

ALTER TABLE stock_reservation ADD COLUMN organization_id uuid,
    ADD COLUMN work_requirement_instance_id uuid,
    ADD COLUMN storage_location_id uuid,
    ADD COLUMN recorded_at timestamptz,
    ADD COLUMN ended_at timestamptz,
    ADD COLUMN ended_by uuid REFERENCES user_identity(id),
    ADD COLUMN end_reason varchar(2000),
    ADD FOREIGN KEY (stock_item_id, organization_id) REFERENCES stock_item(id, organization_id),
    ADD FOREIGN KEY (stock_item_id, unit_code) REFERENCES stock_item(id, unit_of_measure_code) NOT VALID,
    ADD FOREIGN KEY (storage_location_id, organization_id) REFERENCES storage_location(id, organization_id),
    ADD FOREIGN KEY (stock_item_id, storage_location_id) REFERENCES stock_balance(stock_item_id, storage_location_id),
    ADD FOREIGN KEY (work_order_id, organization_id) REFERENCES work_order(id, organization_id),
    ADD FOREIGN KEY (work_requirement_instance_id, work_order_id) REFERENCES work_requirement_instance(id, work_order_id),
    ADD FOREIGN KEY (work_requirement_instance_id) REFERENCES work_resource_request(requirement_id),
    ADD CHECK (organization_id IS NULL OR
        (work_order_id IS NOT NULL AND site_id IS NOT NULL AND work_requirement_instance_id IS NOT NULL
            AND storage_location_id IS NOT NULL AND recorded_at IS NOT NULL AND start_at IS NOT NULL AND end_at IS NOT NULL));

CREATE INDEX idx_asset_reservation_demand ON asset_reservation (work_requirement_instance_id, state, end_at);
CREATE INDEX idx_asset_reservation_capacity ON asset_reservation (asset_id, end_at) WHERE state = 'ACTIVE';
CREATE INDEX idx_stock_reservation_demand ON stock_reservation (work_requirement_instance_id, state, end_at);
CREATE INDEX idx_stock_reservation_capacity ON stock_reservation (stock_item_id, storage_location_id, end_at) WHERE state = 'ACTIVE';
CREATE INDEX idx_asset_reservation_due ON asset_reservation (end_at, id) WHERE state = 'ACTIVE' AND organization_id IS NOT NULL;
CREATE INDEX idx_stock_reservation_due ON stock_reservation (end_at, id) WHERE state = 'ACTIVE' AND organization_id IS NOT NULL;

CREATE FUNCTION current_reserved_stock(p_item uuid, p_location uuid, p_at timestamptz)
RETURNS numeric LANGUAGE sql STABLE AS $$
    SELECT coalesce(sum(quantity), 0) FROM stock_reservation
    WHERE stock_item_id = p_item AND storage_location_id = p_location
        AND state = 'ACTIVE' AND start_at <= p_at AND end_at > p_at;
$$;

CREATE FUNCTION guard_resource_reservation() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    demand work_resource_request%ROWTYPE;
    work_context work_order%ROWTYPE;
    asset_row asset%ROWTYPE;
    item stock_item%ROWTYPE;
    balance stock_balance%ROWTYPE;
    location storage_location%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        IF TG_OP <> 'UPDATE' OR OLD.organization_id IS NULL
            OR OLD.state <> 'ACTIVE' OR NEW.state NOT IN ('RELEASED', 'EXPIRED')
            OR NEW.version <> OLD.version + 1 OR NEW.ended_at IS NULL OR NEW.end_reason IS NULL
            OR (NEW.state = 'EXPIRED' AND NEW.ended_at < OLD.end_at)
            OR (NEW.state = 'RELEASED' AND (NEW.ended_by IS NULL OR NEW.ended_at >= OLD.end_at))
            OR (to_jsonb(NEW) - ARRAY['state','version','ended_at','ended_by','end_reason'])
                IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['state','version','ended_at','ended_by','end_reason']) THEN
            RAISE EXCEPTION 'Invalid reservation transition' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.organization_id IS NULL OR NEW.state <> 'ACTIVE' OR NEW.start_at IS DISTINCT FROM NEW.recorded_at
        OR NEW.ended_at IS NOT NULL OR NEW.version <> 1 THEN
        RAISE EXCEPTION 'Invalid reservation creation' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO STRICT work_context FROM work_order WHERE id = NEW.work_order_id FOR NO KEY UPDATE;
    SELECT * INTO STRICT demand FROM work_resource_request WHERE requirement_id = NEW.work_requirement_instance_id;
    IF work_context.lifecycle_state NOT IN ('PLANNED','ASSIGNED','IN_PROGRESS','BLOCKED')
        OR work_context.project_id <> NEW.project_id OR work_context.site_id <> NEW.site_id
        OR demand.work_order_id <> NEW.work_order_id OR demand.organization_id <> NEW.organization_id THEN
        RAISE EXCEPTION 'Invalid Work resource context' USING ERRCODE = '23514';
    END IF;
    IF TG_TABLE_NAME = 'asset_reservation' THEN
        SELECT * INTO STRICT asset_row FROM asset WHERE id = NEW.asset_id FOR NO KEY UPDATE;
        IF demand.asset_type_id IS DISTINCT FROM asset_row.asset_type_definition_id
            OR EXISTS (SELECT 1 FROM asset_availability_reasons(NEW.asset_id, NEW.recorded_at))
            OR EXISTS (SELECT 1 FROM asset_reservation r WHERE r.asset_id = NEW.asset_id AND r.state = 'ACTIVE'
                AND (r.start_at IS NULL OR r.start_at < NEW.end_at) AND (r.end_at IS NULL OR r.end_at > NEW.start_at))
            OR EXISTS (SELECT 1 FROM asset_reservation r WHERE r.work_requirement_instance_id = demand.requirement_id
                AND r.state = 'ACTIVE' AND r.end_at > NEW.recorded_at) THEN
            RAISE EXCEPTION 'Asset is unavailable or demand is already reserved' USING ERRCODE = '23514';
        END IF;
        SELECT l.* INTO location FROM asset_custody_projection c JOIN storage_location l ON l.id = c.current_storage_location_id
            WHERE c.asset_id = NEW.asset_id FOR SHARE OF l;
    ELSE
        SELECT * INTO STRICT item FROM stock_item WHERE id = NEW.stock_item_id FOR NO KEY UPDATE;
        SELECT * INTO STRICT location FROM storage_location WHERE id = NEW.storage_location_id FOR SHARE;
        SELECT * INTO STRICT balance FROM stock_balance
            WHERE stock_item_id = NEW.stock_item_id AND storage_location_id = NEW.storage_location_id FOR UPDATE;
        IF demand.stock_item_id IS DISTINCT FROM item.id OR demand.unit_code IS DISTINCT FROM NEW.unit_code OR NOT item.active
            OR NOT EXISTS (SELECT 1 FROM stock_unit u WHERE u.organization_id = item.organization_id AND u.code = NEW.unit_code AND u.active)
            OR (item.serialized AND trunc(NEW.quantity) <> NEW.quantity)
            OR balance.on_hand_qty - balance.damaged_qty - balance.quarantine_qty -
                current_reserved_stock(item.id, NEW.storage_location_id, NEW.recorded_at) < NEW.quantity
            OR NEW.quantity + (SELECT coalesce(sum(r.quantity), 0) FROM stock_reservation r
                WHERE r.work_requirement_instance_id = demand.requirement_id AND r.state = 'ACTIVE'
                  AND r.end_at > NEW.recorded_at) > demand.quantity THEN
            RAISE EXCEPTION 'Insufficient stock or quantity exceeds Work demand' USING ERRCODE = '23514';
        END IF;
    END IF;
    IF location.id IS NULL OR location.lifecycle_state <> 'ACTIVE' OR location.active_from > NEW.recorded_at
        OR location.active_until <= NEW.recorded_at
        OR (location.project_id IS NOT NULL AND location.project_id <> NEW.project_id)
        OR (location.site_id IS NOT NULL AND location.site_id <> NEW.site_id)
        OR (location.warehouse_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM warehouse w WHERE w.id = location.warehouse_id AND w.active)) THEN
        RAISE EXCEPTION 'Resource location is ineligible' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER asset_reservation_guard BEFORE INSERT OR UPDATE OR DELETE ON asset_reservation
FOR EACH ROW EXECUTE FUNCTION guard_resource_reservation();
CREATE TRIGGER stock_reservation_guard BEFORE INSERT OR UPDATE OR DELETE ON stock_reservation
FOR EACH ROW EXECUTE FUNCTION guard_resource_reservation();
CREATE TRIGGER asset_reservation_no_truncate BEFORE TRUNCATE ON asset_reservation
FOR EACH STATEMENT EXECUTE FUNCTION guard_resource_reservation();
CREATE TRIGGER stock_reservation_no_truncate BEFORE TRUNCATE ON stock_reservation
FOR EACH STATEMENT EXECUTE FUNCTION guard_resource_reservation();

CREATE FUNCTION project_reserved_stock() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    projection_at timestamptz := coalesce(NEW.ended_at, NEW.recorded_at);
BEGIN
    PERFORM id FROM stock_item WHERE id = NEW.stock_item_id FOR NO KEY UPDATE;
    UPDATE stock_balance SET reserved_qty = current_reserved_stock(NEW.stock_item_id, NEW.storage_location_id, projection_at),
        updated_at = greatest(updated_at, projection_at), version = version + 1
    WHERE stock_item_id = NEW.stock_item_id AND storage_location_id = NEW.storage_location_id;
    RETURN NEW;
END;
$$;
CREATE TRIGGER stock_reservation_projects_balance AFTER INSERT OR UPDATE ON stock_reservation
FOR EACH ROW EXECUTE FUNCTION project_reserved_stock();

-- Reconcile elapsed reservations before the existing movement projector checks transferable capacity.
CREATE FUNCTION refresh_reserved_stock_before_movement() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    reserved numeric;
BEGIN
    IF NEW.movement_type = 'TRANSFER' THEN
        PERFORM id FROM stock_item WHERE id = NEW.stock_item_id FOR NO KEY UPDATE;
        reserved := current_reserved_stock(NEW.stock_item_id, NEW.from_storage_location_id, NEW.recorded_at);
        UPDATE stock_balance SET reserved_qty = reserved, updated_at = greatest(updated_at, NEW.recorded_at), version = version + 1
        WHERE stock_item_id = NEW.stock_item_id AND storage_location_id = NEW.from_storage_location_id AND reserved_qty <> reserved;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER stock_movement_refresh_reservations BEFORE INSERT ON stock_movement
FOR EACH ROW EXECUTE FUNCTION refresh_reserved_stock_before_movement();
