-- Phase 5 Slice 06 / checkpoint 01: authoritative Stock fulfilment persistence.
-- No operator API is enabled by this migration. Complete service authorization,
-- Work source integration and Asset custody before engineering merge.
ALTER TABLE stock_movement
    ADD COLUMN work_requirement_instance_id uuid,
    ADD COLUMN fulfilled_reservation_id uuid UNIQUE REFERENCES stock_reservation(id),
    ADD FOREIGN KEY (work_requirement_instance_id, work_order_id)
        REFERENCES work_requirement_instance(id, work_order_id),
    ADD FOREIGN KEY (work_order_id, organization_id) REFERENCES work_order(id, organization_id),
    ADD FOREIGN KEY (project_id, organization_id) REFERENCES project(id, organization_id),
    ADD FOREIGN KEY (site_id, organization_id) REFERENCES site(id, organization_id);

-- Only typed Slice 06 entries participate; legacy scaffold movements are not
-- promoted to verified employee custody.
CREATE INDEX idx_stock_movement_fulfillment_context
    ON stock_movement (work_requirement_instance_id, recipient_target_id)
    WHERE work_requirement_instance_id IS NOT NULL;

CREATE TABLE stock_issued_balance (
    stock_item_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    employee_id uuid NOT NULL,
    project_id uuid NOT NULL,
    site_id uuid NOT NULL,
    work_order_id uuid NOT NULL,
    requirement_id uuid NOT NULL,
    outstanding_qty numeric(20,6) NOT NULL CHECK (outstanding_qty >= 0),
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (stock_item_id, employee_id, requirement_id),
    FOREIGN KEY (stock_item_id, organization_id) REFERENCES stock_item(id, organization_id),
    FOREIGN KEY (employee_id, organization_id) REFERENCES employee(id, organization_id),
    FOREIGN KEY (project_id, organization_id) REFERENCES project(id, organization_id),
    FOREIGN KEY (site_id, organization_id) REFERENCES site(id, organization_id),
    FOREIGN KEY (work_order_id, organization_id) REFERENCES work_order(id, organization_id),
    FOREIGN KEY (requirement_id, work_order_id) REFERENCES work_requirement_instance(id, work_order_id),
    FOREIGN KEY (requirement_id) REFERENCES work_resource_request(requirement_id)
);

CREATE TRIGGER stock_issued_projection_only
    BEFORE INSERT OR UPDATE OR DELETE ON stock_issued_balance
    FOR EACH ROW EXECUTE FUNCTION guard_stock_balance_projection();
CREATE TRIGGER stock_issued_no_truncate BEFORE TRUNCATE ON stock_issued_balance
    FOR EACH STATEMENT EXECUTE FUNCTION guard_stock_balance_projection();

CREATE VIEW stock_issued_ledger AS
SELECT stock_item_id, organization_id, recipient_target_id AS employee_id,
       project_id, site_id, work_order_id, work_requirement_instance_id AS requirement_id,
       sum(CASE WHEN movement_type = 'ISSUE' THEN quantity ELSE -quantity END) AS outstanding_qty
FROM stock_movement
WHERE work_requirement_instance_id IS NOT NULL AND movement_type IN ('ISSUE', 'RETURN', 'CONSUME')
GROUP BY stock_item_id, organization_id, recipient_target_id, project_id, site_id, work_order_id, work_requirement_instance_id;

ALTER TABLE asset_movement
    ADD COLUMN fulfilled_reservation_id uuid UNIQUE REFERENCES asset_reservation(id),
    ADD COLUMN prior_custody_movement_id uuid,
    ADD FOREIGN KEY (asset_id, prior_custody_movement_id) REFERENCES asset_movement(asset_id, id);

CREATE OR REPLACE FUNCTION guard_resource_reservation() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    demand work_resource_request%ROWTYPE;
    work_context work_order%ROWTYPE;
    asset_row asset%ROWTYPE;
    item stock_item%ROWTYPE;
    balance stock_balance%ROWTYPE;
    location storage_location%ROWTYPE;
BEGIN
    IF TG_TABLE_NAME = 'asset_reservation' AND TG_OP = 'UPDATE' AND NEW.state = 'FULFILLED' THEN
        IF OLD.organization_id IS NULL OR OLD.state <> 'ACTIVE'
            OR NEW.version <> OLD.version + 1 OR NEW.ended_by IS NULL OR NEW.ended_at IS NULL
            OR NEW.ended_at < OLD.start_at OR NEW.ended_at >= OLD.end_at
            OR nullif(btrim(NEW.end_reason), '') IS NULL
            OR (to_jsonb(NEW) - ARRAY['state','version','ended_at','ended_by','end_reason'])
                IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['state','version','ended_at','ended_by','end_reason'])
            OR NOT EXISTS (
                SELECT 1 FROM asset_movement m WHERE m.fulfilled_reservation_id = OLD.id
                  AND m.movement_type = 'CHECKOUT' AND m.asset_id = OLD.asset_id
                  AND m.organization_id = OLD.organization_id AND m.work_order_id = OLD.work_order_id
                  AND m.project_id = OLD.project_id AND m.site_id = OLD.site_id
                  AND m.recorded_at = NEW.ended_at AND m.recorded_by = NEW.ended_by
            ) THEN
            RAISE EXCEPTION 'Asset fulfilment requires matching checkout' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    -- Fulfilment is allowed only as the result of an inserted matching physical ISSUE.
    IF TG_TABLE_NAME = 'stock_reservation' AND TG_OP = 'UPDATE' AND NEW.state = 'FULFILLED' THEN
        IF OLD.organization_id IS NULL OR OLD.state <> 'ACTIVE'
            OR NEW.version <> OLD.version + 1 OR NEW.ended_by IS NULL
            OR NEW.ended_at IS NULL OR NEW.ended_at < OLD.start_at OR NEW.ended_at >= OLD.end_at
            OR nullif(btrim(NEW.end_reason), '') IS NULL
            OR (to_jsonb(NEW) - ARRAY['state','version','ended_at','ended_by','end_reason'])
                IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['state','version','ended_at','ended_by','end_reason'])
            OR NOT EXISTS (
                SELECT 1 FROM stock_movement m
                WHERE m.fulfilled_reservation_id = OLD.id AND m.movement_type = 'ISSUE'
                  AND m.stock_item_id = OLD.stock_item_id AND m.organization_id = OLD.organization_id
                  AND m.from_storage_location_id = OLD.storage_location_id
                  AND m.work_requirement_instance_id = OLD.work_requirement_instance_id
                  AND m.work_order_id = OLD.work_order_id AND m.project_id = OLD.project_id AND m.site_id = OLD.site_id
                  AND m.quantity = OLD.quantity AND m.unit_code = OLD.unit_code
                  AND m.recorded_at = NEW.ended_at AND m.recorded_by = NEW.ended_by
            ) THEN
            RAISE EXCEPTION 'Fulfilment requires matching physical issue' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
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
            OR NEW.quantity + (SELECT coalesce(sum(
                    CASE m.movement_type WHEN 'ISSUE' THEN m.quantity WHEN 'RETURN' THEN -m.quantity ELSE 0 END), 0)
                    FROM stock_movement m WHERE m.work_requirement_instance_id = demand.requirement_id)
                + (SELECT coalesce(sum(r.quantity), 0) FROM stock_reservation r
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

-- Invoked only from the established append-only stock movement projector.
-- Work -> Stock -> location -> reservation/projection is the lock order.
CREATE FUNCTION post_stock_fulfillment(m stock_movement) RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    work_context work_order%ROWTYPE;
    demand work_resource_request%ROWTYPE;
    item stock_item%ROWTYPE;
    location storage_location%ROWTYPE;
    held stock_reservation%ROWTYPE;
    recipient employee%ROWTYPE;
    location_id uuid;
BEGIN
    IF m.work_requirement_instance_id IS NULL OR m.work_order_id IS NULL
        OR m.project_id IS NULL OR m.site_id IS NULL
        OR m.recipient_target_type IS DISTINCT FROM 'EMPLOYEE' OR m.recipient_target_id IS NULL
        OR m.source_import_row_id IS NOT NULL OR m.purchase_receipt_id IS NOT NULL
        OR m.occurred_at > m.recorded_at
        OR (m.movement_type = 'ISSUE' AND
            (m.from_storage_location_id IS NULL OR m.to_storage_location_id IS NOT NULL OR m.fulfilled_reservation_id IS NULL))
        OR (m.movement_type = 'RETURN' AND
            (m.from_storage_location_id IS NOT NULL OR m.to_storage_location_id IS NULL OR m.fulfilled_reservation_id IS NOT NULL))
        OR (m.movement_type = 'CONSUME' AND
            (num_nonnulls(m.from_storage_location_id, m.to_storage_location_id, m.fulfilled_reservation_id) <> 0))
        OR m.movement_type NOT IN ('ISSUE','RETURN','CONSUME') THEN
        RAISE EXCEPTION 'Invalid stock fulfilment movement shape' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO STRICT work_context FROM work_order WHERE id = m.work_order_id FOR NO KEY UPDATE;
    SELECT * INTO STRICT demand FROM work_resource_request WHERE requirement_id = m.work_requirement_instance_id;
    SELECT * INTO STRICT item FROM stock_item WHERE id = m.stock_item_id FOR NO KEY UPDATE;
    SELECT * INTO recipient FROM employee WHERE id = m.recipient_target_id FOR SHARE;
    IF work_context.organization_id <> m.organization_id
        OR work_context.project_id IS DISTINCT FROM m.project_id OR work_context.site_id IS DISTINCT FROM m.site_id
        OR demand.work_order_id <> work_context.id OR demand.organization_id <> m.organization_id
        OR demand.stock_item_id IS DISTINCT FROM item.id OR demand.unit_code IS DISTINCT FROM m.unit_code
        OR item.organization_id <> m.organization_id OR item.unit_of_measure_code <> m.unit_code
        OR recipient.id IS NULL OR recipient.organization_id <> m.organization_id
        OR (m.movement_type = 'ISSUE' AND (recipient.state <> 'ACTIVE'
            OR work_context.lifecycle_state NOT IN ('PLANNED','ASSIGNED','IN_PROGRESS','BLOCKED')))
        OR (m.movement_type <> 'RETURN' AND work_context.lifecycle_state IN ('CLOSED','CANCELLED'))
        OR NOT item.active
        OR NOT EXISTS (SELECT 1 FROM stock_unit u WHERE u.organization_id = item.organization_id AND u.code = m.unit_code AND u.active)
        OR (item.serialized AND trunc(m.quantity) <> m.quantity) THEN
        RAISE EXCEPTION 'Stock fulfilment context is not eligible' USING ERRCODE = '23514';
    END IF;

    location_id := coalesce(m.from_storage_location_id, m.to_storage_location_id);
    IF location_id IS NOT NULL THEN
        SELECT * INTO location FROM storage_location WHERE id = location_id FOR SHARE;
        IF location.id IS NULL OR location.organization_id <> m.organization_id
            OR location.lifecycle_state <> 'ACTIVE'
            OR location.active_from > m.recorded_at OR location.active_until <= m.recorded_at
            OR (location.project_id IS NOT NULL AND location.project_id <> m.project_id)
            OR (location.site_id IS NOT NULL AND location.site_id <> m.site_id)
            OR NOT EXISTS (SELECT 1 FROM warehouse w WHERE w.id = location.warehouse_id AND w.active) THEN
            RAISE EXCEPTION 'Stock fulfilment location is not eligible' USING ERRCODE = '23514';
        END IF;
    END IF;

    IF m.movement_type = 'ISSUE' THEN
        -- First path is intentionally an exact entire-hold issue. No implicit
        -- partial consumption, unreserved issue or inferred external recipient.
        SELECT * INTO held FROM stock_reservation WHERE id = m.fulfilled_reservation_id FOR UPDATE;
        IF held.id IS NULL OR held.state <> 'ACTIVE'
            OR held.organization_id IS DISTINCT FROM m.organization_id
            OR held.stock_item_id <> item.id OR held.storage_location_id IS DISTINCT FROM m.from_storage_location_id
            OR held.work_requirement_instance_id IS DISTINCT FROM demand.requirement_id
            OR held.work_order_id IS DISTINCT FROM m.work_order_id OR held.project_id <> m.project_id
            OR held.site_id IS DISTINCT FROM m.site_id OR held.quantity <> m.quantity OR held.unit_code <> m.unit_code
            OR NOT (held.start_at <= m.recorded_at AND held.end_at > m.recorded_at)
            OR NOT (held.start_at <= m.occurred_at AND held.end_at > m.occurred_at) THEN
            RAISE EXCEPTION 'Issue requires an exact current reservation' USING ERRCODE = '23514';
        END IF;
        UPDATE stock_reservation SET state = 'FULFILLED', version = version + 1,
            ended_at = m.recorded_at, ended_by = m.recorded_by, end_reason = 'Physical stock issue'
            WHERE id = held.id;
        -- Reservation projection trigger releases only that hold. Other holds
        -- remain protected and the on-hand decrement is in the same transaction.
        UPDATE stock_balance SET on_hand_qty = on_hand_qty - m.quantity,
            version = version + 1, updated_at = greatest(updated_at, m.recorded_at)
        WHERE stock_item_id = item.id AND storage_location_id = location_id
          AND on_hand_qty - reserved_qty - damaged_qty - quarantine_qty >= m.quantity;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Insufficient issue capacity' USING ERRCODE = '23514';
        END IF;
        INSERT INTO stock_issued_balance (stock_item_id, organization_id, employee_id, project_id, site_id,
            work_order_id, requirement_id, outstanding_qty, updated_at)
        VALUES (item.id, m.organization_id, recipient.id, m.project_id, m.site_id,
            m.work_order_id, demand.requirement_id, m.quantity, m.recorded_at)
        ON CONFLICT (stock_item_id, employee_id, requirement_id) DO UPDATE
        SET outstanding_qty = stock_issued_balance.outstanding_qty + EXCLUDED.outstanding_qty,
            version = stock_issued_balance.version + 1,
            updated_at = greatest(stock_issued_balance.updated_at, EXCLUDED.updated_at);
    ELSE
        UPDATE stock_issued_balance SET outstanding_qty = outstanding_qty - m.quantity,
            version = version + 1, updated_at = greatest(updated_at, m.recorded_at)
        WHERE stock_item_id = item.id AND employee_id = recipient.id AND requirement_id = demand.requirement_id
          AND organization_id = m.organization_id AND project_id = m.project_id AND site_id = m.site_id
          AND work_order_id = m.work_order_id AND outstanding_qty >= m.quantity;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Insufficient outstanding issued stock' USING ERRCODE = '23514';
        END IF;
        IF m.movement_type = 'RETURN' THEN
            INSERT INTO stock_balance (stock_item_id, storage_location_id, organization_id, on_hand_qty,
                reserved_qty, damaged_qty, quarantine_qty, updated_at)
            VALUES (item.id, location_id, m.organization_id, m.quantity, 0, 0, 0, m.recorded_at)
            ON CONFLICT (stock_item_id, storage_location_id) DO UPDATE
            SET on_hand_qty = stock_balance.on_hand_qty + EXCLUDED.on_hand_qty,
                version = stock_balance.version + 1,
                updated_at = greatest(stock_balance.updated_at, EXCLUDED.updated_at);
        END IF;
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION project_stock_movement() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    item stock_item%ROWTYPE;
    location storage_location%ROWTYPE;
BEGIN
    IF NEW.movement_type IN ('ISSUE', 'RETURN', 'CONSUME') THEN
        PERFORM post_stock_fulfillment(NEW);
        RETURN NEW;
    END IF;
    IF NEW.work_requirement_instance_id IS NOT NULL OR NEW.fulfilled_reservation_id IS NOT NULL THEN
        RAISE EXCEPTION 'Unexpected fulfilment context for stock movement' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO STRICT item FROM stock_item WHERE id = NEW.stock_item_id FOR NO KEY UPDATE;
    IF NOT item.active OR NOT EXISTS (
        SELECT 1 FROM stock_unit WHERE organization_id = item.organization_id
        AND code = item.unit_of_measure_code AND active
    ) THEN
        RAISE EXCEPTION 'Stock item or canonical unit is inactive' USING ERRCODE = '23514';
    END IF;

    FOR location IN
        SELECT * FROM storage_location WHERE id IN (NEW.from_storage_location_id, NEW.to_storage_location_id)
        ORDER BY id FOR SHARE
    LOOP
        IF location.lifecycle_state <> 'ACTIVE'
            OR (location.active_from IS NOT NULL AND location.active_from > NEW.recorded_at)
            OR (location.active_until IS NOT NULL AND location.active_until <= NEW.recorded_at) THEN
            RAISE EXCEPTION 'Stock movement location is inactive' USING ERRCODE = '23514';
        END IF;
    END LOOP;

    IF NEW.movement_type = 'INITIALIZE_OPENING' THEN
        IF NOT EXISTS (
            SELECT 1 FROM stock_import_verification v
            JOIN inventory_import_row r ON r.id = v.source_row_id
            JOIN inventory_import_batch b ON b.id = r.batch_id
            WHERE v.source_row_id = NEW.source_import_row_id
              AND v.storage_location_id = NEW.to_storage_location_id
              AND v.category_definition_id = item.category_definition_id
              AND v.cutover_approver_id = NEW.recorded_by
              AND r.id = item.source_import_row_id AND r.review_state = 'APPROVED'
              AND r.approved_target_type = 'STOCK_ITEM' AND r.approved_target_id = item.id
              AND r.version = v.source_row_version + 1
              AND r.candidate_quantity = NEW.quantity AND r.candidate_uom = NEW.unit_code
              AND b.source_sha256 = v.source_sha256
              AND b.state <> 'CANCELLED' AND v.cutover_at = NEW.occurred_at
        ) THEN
            RAISE EXCEPTION 'Opening stock needs verified import evidence' USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.movement_type = 'TRANSFER' THEN
        UPDATE stock_balance
           SET on_hand_qty = on_hand_qty - NEW.quantity, updated_at = NEW.recorded_at, version = version + 1
         WHERE stock_item_id = NEW.stock_item_id AND storage_location_id = NEW.from_storage_location_id
           AND on_hand_qty - reserved_qty - damaged_qty - quarantine_qty >= NEW.quantity;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Insufficient transferable stock' USING ERRCODE = '23514';
        END IF;
    ELSE
        RAISE EXCEPTION 'Unsupported stock movement type' USING ERRCODE = '23514';
    END IF;

    INSERT INTO stock_balance (stock_item_id, storage_location_id, organization_id,
        on_hand_qty, reserved_qty, damaged_qty, quarantine_qty, updated_at)
    VALUES (NEW.stock_item_id, NEW.to_storage_location_id, NEW.organization_id,
        NEW.quantity, 0, 0, 0, NEW.recorded_at)
    ON CONFLICT (stock_item_id, storage_location_id) DO UPDATE
    SET on_hand_qty = stock_balance.on_hand_qty + EXCLUDED.on_hand_qty,
        updated_at = EXCLUDED.updated_at, version = stock_balance.version + 1;
    RETURN NEW;
END;
$$;


-- Physical Asset transitions preserve the existing custody ledger and projector.
-- This initial path supports exact reserved checkout, return and same-warehouse
-- storage transfer. Employee reassignment and cross-warehouse policy are not inferred.
CREATE FUNCTION validate_physical_asset_movement(m asset_movement)
RETURNS asset_movement LANGUAGE plpgsql AS $$
DECLARE
    passport asset%ROWTYPE;
    prior asset_custody_projection%ROWTYPE;
    held asset_reservation%ROWTYPE;
    work_context work_order%ROWTYPE;
    source storage_location%ROWTYPE;
    destination storage_location%ROWTYPE;
    employee_row employee%ROWTYPE;
    lock_work uuid;
BEGIN
    SELECT * INTO prior FROM asset_custody_projection WHERE asset_id = m.asset_id;
    lock_work := CASE WHEN m.movement_type = 'CHECKOUT' THEN m.work_order_id ELSE prior.current_work_order_id END;
    IF lock_work IS NOT NULL THEN
        SELECT * INTO STRICT work_context FROM work_order WHERE id = lock_work FOR NO KEY UPDATE;
    END IF;
    SELECT * INTO STRICT passport FROM asset WHERE id = m.asset_id FOR NO KEY UPDATE;
    SELECT * INTO prior FROM asset_custody_projection WHERE asset_id = m.asset_id;
    IF passport.organization_id <> m.organization_id OR passport.lifecycle_state <> 'ACTIVE'
        OR prior.asset_id IS NULL OR m.prior_custody_movement_id IS DISTINCT FROM prior.source_movement_id
        OR m.from_storage_location_id IS DISTINCT FROM prior.current_storage_location_id
        OR m.from_custodian_target_id IS DISTINCT FROM prior.current_custodian_target_id
        OR m.from_custodian_target_type IS DISTINCT FROM prior.current_custodian_target_type
        OR m.source_import_row_id IS NOT NULL OR m.correction_of_movement_id IS NOT NULL
        OR m.occurred_at > m.recorded_at OR m.occurred_at < prior.custody_started_at
        OR m.condition_at_transfer IS NULL
        OR (m.movement_type <> 'CHECKOUT' AND lock_work IS DISTINCT FROM prior.current_work_order_id)
        OR NOT EXISTS (SELECT 1 FROM warehouse w WHERE w.id = passport.warehouse_id AND w.active) THEN
        RAISE EXCEPTION 'Physical asset source custody is not current' USING ERRCODE = '23514';
    END IF;
    IF m.from_storage_location_id IS NOT NULL THEN
        SELECT * INTO source FROM storage_location WHERE id = m.from_storage_location_id FOR SHARE;
        IF source.id IS NULL OR source.organization_id <> passport.organization_id
            OR source.warehouse_id IS DISTINCT FROM passport.warehouse_id OR source.lifecycle_state <> 'ACTIVE'
            OR source.active_from > m.recorded_at OR source.active_until <= m.recorded_at THEN
            RAISE EXCEPTION 'Asset source location is unavailable' USING ERRCODE = '23514';
        END IF;
    END IF;
    IF m.movement_type = 'CHECKOUT' THEN
        SELECT * INTO held FROM asset_reservation WHERE id = m.fulfilled_reservation_id FOR UPDATE;
        SELECT * INTO employee_row FROM employee WHERE id = m.to_custodian_target_id FOR SHARE;
        IF prior.current_storage_location_id IS NULL OR prior.current_custodian_target_id IS NOT NULL
            OR m.to_storage_location_id IS NOT NULL OR m.to_custodian_target_type IS DISTINCT FROM 'EMPLOYEE'
            OR employee_row.id IS NULL OR employee_row.organization_id <> passport.organization_id OR employee_row.state <> 'ACTIVE'
            OR m.condition_at_transfer IS DISTINCT FROM passport.condition_state
            OR m.condition_at_transfer NOT IN ('GOOD','FAIR')
            OR held.id IS NULL OR held.state <> 'ACTIVE' OR held.asset_id <> passport.id
            OR held.organization_id IS DISTINCT FROM passport.organization_id
            OR held.work_order_id IS DISTINCT FROM m.work_order_id OR held.project_id IS DISTINCT FROM m.project_id
            OR held.site_id IS DISTINCT FROM m.site_id OR work_context.id IS NULL
            OR work_context.organization_id <> passport.organization_id
            OR work_context.project_id IS DISTINCT FROM m.project_id OR work_context.site_id IS DISTINCT FROM m.site_id
            OR work_context.lifecycle_state NOT IN ('PLANNED','ASSIGNED','IN_PROGRESS','BLOCKED')
            OR NOT (held.start_at <= m.recorded_at AND held.end_at > m.recorded_at)
            OR NOT (held.start_at <= m.occurred_at AND held.end_at > m.occurred_at)
            OR (source.project_id IS NOT NULL AND source.project_id <> m.project_id)
            OR (source.site_id IS NOT NULL AND source.site_id <> m.site_id)
            OR EXISTS (SELECT 1 FROM asset_availability_reasons(m.asset_id, m.recorded_at) r
                WHERE NOT (r.code = 'ACTIVE_RESERVATION' AND r.source_id = held.id)) THEN
            RAISE EXCEPTION 'Asset checkout requires eligible current reservation and employee' USING ERRCODE = '23514';
        END IF;
    ELSE
        SELECT * INTO destination FROM storage_location WHERE id = m.to_storage_location_id FOR SHARE;
        IF destination.id IS NULL OR destination.organization_id <> passport.organization_id
            OR destination.warehouse_id IS DISTINCT FROM passport.warehouse_id OR destination.lifecycle_state <> 'ACTIVE'
            OR destination.active_from > m.recorded_at OR destination.active_until <= m.recorded_at
            OR m.to_custodian_target_id IS NOT NULL OR m.to_custodian_target_type IS NOT NULL
            OR m.project_id IS DISTINCT FROM destination.project_id OR m.site_id IS DISTINCT FROM destination.site_id
            OR m.work_order_id IS NOT NULL OR m.expected_return_at IS NOT NULL OR m.fulfilled_reservation_id IS NOT NULL THEN
            RAISE EXCEPTION 'Asset destination is unavailable' USING ERRCODE = '23514';
        END IF;
        IF m.movement_type = 'RETURN' THEN
            IF prior.current_custodian_target_type IS DISTINCT FROM 'EMPLOYEE' OR prior.current_custodian_target_id IS NULL THEN
                RAISE EXCEPTION 'Return requires current employee custody' USING ERRCODE = '23514';
            END IF;
        ELSIF m.movement_type = 'TRANSFER' THEN
            IF prior.current_storage_location_id IS NULL OR prior.current_storage_location_id = destination.id
                OR m.condition_at_transfer IS DISTINCT FROM passport.condition_state
                OR EXISTS (SELECT 1 FROM asset_availability_reasons(m.asset_id, m.recorded_at)) THEN
                RAISE EXCEPTION 'Transfer requires an available asset at a distinct storage location' USING ERRCODE = '23514';
            END IF;
        ELSE
            RAISE EXCEPTION 'Unsupported physical asset movement' USING ERRCODE = '23514';
        END IF;
    END IF;
    m.ledger_version := passport.version + 1;
    RETURN m;
END;
$$;

CREATE OR REPLACE FUNCTION validate_asset_custody_movement() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    passport asset%ROWTYPE;
    prior asset_custody_projection%ROWTYPE;
    destination storage_location%ROWTYPE;
BEGIN
    IF NEW.movement_type IN ('CHECKOUT','RETURN','TRANSFER') THEN
        RETURN validate_physical_asset_movement(NEW);
    END IF;
    IF NEW.fulfilled_reservation_id IS NOT NULL OR NEW.prior_custody_movement_id IS NOT NULL THEN
        RAISE EXCEPTION 'Unexpected physical custody reference' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO STRICT passport FROM asset WHERE id = NEW.asset_id FOR NO KEY UPDATE;
    SELECT * INTO prior FROM asset_custody_projection WHERE asset_id = NEW.asset_id;
    IF passport.lifecycle_state <> 'ACTIVE' OR passport.organization_id <> NEW.organization_id
        OR NEW.movement_type NOT IN ('REGISTER_INITIAL_LOCATION', 'INITIALIZE_OPENING_CUSTODY', 'CONTROLLED_CORRECTION')
        OR NEW.occurred_at > NEW.recorded_at
        OR num_nonnulls(NEW.to_storage_location_id, NEW.to_custodian_target_id) <> 1
        OR ((NEW.to_custodian_target_id IS NULL) <> (NEW.to_custodian_target_type IS NULL))
        OR NEW.condition_at_transfer IS NULL OR NEW.expected_return_at IS NOT NULL THEN
        RAISE EXCEPTION 'Invalid Asset custody movement' USING ERRCODE = '23514';
    END IF;
    IF NEW.movement_type = 'CONTROLLED_CORRECTION' THEN
        IF prior.asset_id IS NULL OR NEW.correction_of_movement_id IS DISTINCT FROM prior.source_movement_id
            OR NEW.from_storage_location_id IS DISTINCT FROM prior.current_storage_location_id
            OR NEW.from_custodian_target_type IS DISTINCT FROM prior.current_custodian_target_type
            OR NEW.from_custodian_target_id IS DISTINCT FROM prior.current_custodian_target_id
            OR nullif(btrim(NEW.notes), '') IS NULL OR NEW.source_import_row_id IS NOT NULL THEN
            RAISE EXCEPTION 'Correction must reference the current movement and record a reason' USING ERRCODE = '23514';
        END IF;
    ELSIF prior.asset_id IS NOT NULL OR NEW.correction_of_movement_id IS NOT NULL
        OR num_nonnulls(NEW.from_storage_location_id, NEW.from_custodian_target_id, NEW.from_custodian_target_type) <> 0 THEN
        RAISE EXCEPTION 'Initial custody has already been established' USING ERRCODE = '23514';
    END IF;
    IF NEW.movement_type = 'REGISTER_INITIAL_LOCATION' AND NEW.to_storage_location_id IS NULL THEN
        RAISE EXCEPTION 'Registration requires a storage location' USING ERRCODE = '23514';
    END IF;
    IF NEW.to_storage_location_id IS NOT NULL THEN
        SELECT * INTO destination FROM storage_location WHERE id = NEW.to_storage_location_id FOR SHARE;
        IF destination.id IS NULL OR destination.organization_id <> passport.organization_id
            OR destination.lifecycle_state <> 'ACTIVE'
            OR destination.active_from > NEW.recorded_at OR destination.active_until <= NEW.recorded_at
            OR NEW.project_id IS DISTINCT FROM destination.project_id OR NEW.site_id IS DISTINCT FROM destination.site_id
            OR NEW.work_order_id IS NOT NULL THEN
            RAISE EXCEPTION 'Asset destination is unavailable' USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.to_custodian_target_type <> 'EMPLOYEE' OR NOT EXISTS (
        SELECT 1 FROM employee WHERE id = NEW.to_custodian_target_id
            AND organization_id = passport.organization_id AND state = 'ACTIVE'
    ) OR (NEW.work_order_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM work_order WHERE id = NEW.work_order_id AND organization_id = passport.organization_id
            AND project_id = NEW.project_id AND site_id = NEW.site_id AND lifecycle_state NOT IN ('CLOSED', 'CANCELLED')
    )) OR (NEW.work_order_id IS NULL AND num_nonnulls(NEW.project_id, NEW.site_id) <> 0) THEN
        RAISE EXCEPTION 'Employee custody context is unavailable' USING ERRCODE = '23514';
    END IF;
    IF NEW.movement_type <> 'CONTROLLED_CORRECTION' AND passport.source_import_row_id IS NOT NULL THEN
        IF NOT EXISTS (
            SELECT 1 FROM asset_import_verification v
            JOIN inventory_import_row r ON r.id = v.source_row_id
            JOIN inventory_import_batch b ON b.id = r.batch_id
            WHERE v.source_row_id = passport.source_import_row_id AND v.source_row_id = NEW.source_import_row_id
              AND v.approver_id = NEW.recorded_by AND v.cutover_at = NEW.occurred_at
              AND v.opening_location_id IS NOT DISTINCT FROM NEW.to_storage_location_id
              AND v.opening_employee_id IS NOT DISTINCT FROM NEW.to_custodian_target_id
              AND r.review_state = 'APPROVED' AND r.approved_target_type = 'ASSET' AND r.approved_target_id = passport.id
              AND r.version = v.source_row_version + 1 AND b.source_sha256 = v.source_sha256 AND b.state <> 'CANCELLED'
              AND NEW.work_order_id IS NULL AND NEW.movement_type = 'INITIALIZE_OPENING_CUSTODY'
        ) THEN
            RAISE EXCEPTION 'Opening custody needs verified source evidence' USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.source_import_row_id IS NOT NULL THEN
        RAISE EXCEPTION 'Unexpected source provenance' USING ERRCODE = '23514';
    END IF;
    NEW.ledger_version := passport.version + 1;
    RETURN NEW;
END;
$$;
CREATE OR REPLACE FUNCTION project_asset_custody_movement() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE asset SET version = NEW.ledger_version, condition_state = NEW.condition_at_transfer WHERE id = NEW.asset_id;
    INSERT INTO asset_custody_projection (asset_id, current_storage_location_id, current_custodian_target_type,
        current_custodian_target_id, current_project_id, current_site_id, current_work_order_id,
        custody_started_at, expected_return_at, source_movement_id, asset_version, updated_at)
    VALUES (NEW.asset_id, NEW.to_storage_location_id, NEW.to_custodian_target_type, NEW.to_custodian_target_id,
        NEW.project_id, NEW.site_id, NEW.work_order_id, NEW.occurred_at, NEW.expected_return_at, NEW.id, NEW.ledger_version, NEW.recorded_at)
    ON CONFLICT (asset_id) DO UPDATE SET
        current_storage_location_id = EXCLUDED.current_storage_location_id,
        current_custodian_target_type = EXCLUDED.current_custodian_target_type,
        current_custodian_target_id = EXCLUDED.current_custodian_target_id,
        current_project_id = EXCLUDED.current_project_id, current_site_id = EXCLUDED.current_site_id,
        current_work_order_id = EXCLUDED.current_work_order_id, custody_started_at = EXCLUDED.custody_started_at,
        expected_return_at = EXCLUDED.expected_return_at, source_movement_id = EXCLUDED.source_movement_id,
        asset_version = EXCLUDED.asset_version, updated_at = EXCLUDED.updated_at;
    IF NEW.movement_type = 'CHECKOUT' THEN
        UPDATE asset_reservation SET state = 'FULFILLED', version = version + 1,
            ended_at = NEW.recorded_at, ended_by = NEW.recorded_by, end_reason = 'Physical asset checkout'
        WHERE id = NEW.fulfilled_reservation_id;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Checkout reservation is unavailable' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

-- A basic return inspection is append-only, tied to the current return.
-- Accessory policies need their actual typed checklist before being enabled.
CREATE FUNCTION guard_physical_asset_inspection() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    passport asset%ROWTYPE;
    prior asset_custody_projection%ROWTYPE;
    returned asset_movement%ROWTYPE;
    type_row asset_type_definition%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'Return inspection is immutable' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO STRICT passport FROM asset WHERE id = NEW.asset_id FOR NO KEY UPDATE;
    SELECT * INTO prior FROM asset_custody_projection WHERE asset_id = NEW.asset_id;
    SELECT * INTO returned FROM asset_movement WHERE id = NEW.return_movement_id AND asset_id = NEW.asset_id;
    SELECT * INTO STRICT type_row FROM asset_type_definition WHERE config_revision_id = passport.asset_type_definition_id;
    IF returned.id IS NULL OR returned.movement_type <> 'RETURN'
        OR prior.source_movement_id IS DISTINCT FROM returned.id OR prior.current_storage_location_id IS NULL
        OR NEW.inspected_at < returned.recorded_at OR NEW.version <> 1
        OR type_row.accessory_template_id IS NOT NULL OR NEW.accessory_template_id IS NOT NULL
        OR NEW.accessory_template_revision IS NOT NULL OR NEW.accessory_results IS NOT NULL
        OR NEW.condition_observed = 'UNKNOWN'
        OR (NEW.condition_observed IN ('DAMAGED','UNFIT') AND NOT EXISTS (
            SELECT 1 FROM asset_incident i WHERE i.id = NEW.damage_incident_id AND i.asset_id = NEW.asset_id
              AND i.incident_type = 'DAMAGE' AND i.state IN ('OPEN','UNDER_REVIEW')))
        OR (NEW.condition_observed IN ('GOOD','FAIR') AND NEW.damage_incident_id IS NOT NULL) THEN
        RAISE EXCEPTION 'Inspection requires current return and verified condition evidence' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER asset_physical_inspection_guard BEFORE INSERT OR UPDATE OR DELETE ON asset_return_inspection
FOR EACH ROW EXECUTE FUNCTION guard_physical_asset_inspection();
CREATE TRIGGER asset_physical_inspection_no_truncate BEFORE TRUNCATE ON asset_return_inspection
FOR EACH STATEMENT EXECUTE FUNCTION guard_physical_asset_inspection();

CREATE FUNCTION project_asset_return_inspection() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE asset SET condition_state = NEW.condition_observed, version = version + 1 WHERE id = NEW.asset_id;
    RETURN NEW;
END;
$$;
CREATE TRIGGER asset_return_inspection_project AFTER INSERT ON asset_return_inspection
FOR EACH ROW EXECUTE FUNCTION project_asset_return_inspection();

CREATE OR REPLACE FUNCTION asset_availability_reasons(p_asset_id uuid, p_at timestamptz)
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
    SELECT 21, 'BLOCKED_CONDITION', 'RETURN_CONDITION_UNVERIFIED', 'ASSET', a.id, a.version, NULL
    FROM passport a JOIN asset_custody_projection c ON c.asset_id = a.id
    JOIN asset_movement m ON m.id = c.source_movement_id
    WHERE m.movement_type = 'RETURN' AND a.condition_state IN ('DAMAGED','UNKNOWN')
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
      AND (r.inspection_id IS NULL OR r.condition_observed IN ('UNFIT','DAMAGED','UNKNOWN') OR
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
