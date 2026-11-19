-- Phase 5 / Slice 08 — Stocktake / discrepancy / adjustment.
-- Physical count is history. It never edits StockBalance directly.

CREATE TABLE stocktake_session (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    warehouse_id uuid NOT NULL,
    root_storage_location_id uuid,
    state varchar(24) NOT NULL CHECK (state IN ('DRAFT','ACTIVE','SUBMITTED','RECONCILING','CLOSED','CANCELLED')),
    scope_category_definition_ids jsonb NOT NULL DEFAULT '[]'::jsonb
        CHECK (jsonb_typeof(scope_category_definition_ids) = 'array'),
    scope_stock_item_ids jsonb NOT NULL DEFAULT '[]'::jsonb
        CHECK (jsonb_typeof(scope_stock_item_ids) = 'array'),
    snapshot_at timestamptz,
    created_by uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    submitted_by uuid REFERENCES user_identity(id) ON DELETE RESTRICT,
    submitted_at timestamptz,
    closed_by uuid REFERENCES user_identity(id) ON DELETE RESTRICT,
    closed_at timestamptz,
    correlation_id varchar(128) NOT NULL,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    UNIQUE (id, organization_id),
    FOREIGN KEY (warehouse_id, organization_id) REFERENCES warehouse(id, organization_id) ON DELETE RESTRICT,
    FOREIGN KEY (root_storage_location_id, organization_id)
        REFERENCES storage_location(id, organization_id) ON DELETE RESTRICT,
    CHECK ((root_storage_location_id IS NULL) OR warehouse_id IS NOT NULL),
    CHECK ((state = 'DRAFT' AND snapshot_at IS NULL) OR state <> 'DRAFT')
);

CREATE TABLE stocktake_snapshot_line (
    id uuid PRIMARY KEY,
    stocktake_session_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    stock_item_id uuid NOT NULL,
    storage_location_id uuid NOT NULL,
    expected_on_hand_qty numeric(20,6) NOT NULL CHECK (expected_on_hand_qty >= 0),
    expected_reserved_qty numeric(20,6) NOT NULL CHECK (expected_reserved_qty >= 0),
    expected_damaged_qty numeric(20,6) NOT NULL CHECK (expected_damaged_qty >= 0),
    expected_quarantine_qty numeric(20,6) NOT NULL CHECK (expected_quarantine_qty >= 0),
    unit_code varchar(32) NOT NULL,
    stock_balance_version bigint NOT NULL CHECK (stock_balance_version >= 1),
    stock_item_version bigint NOT NULL CHECK (stock_item_version >= 1),
    snapshot_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 1 CHECK (version = 1),
    UNIQUE (id, stocktake_session_id, organization_id),
    UNIQUE (stocktake_session_id, stock_item_id, storage_location_id),
    FOREIGN KEY (stocktake_session_id, organization_id)
        REFERENCES stocktake_session(id, organization_id) ON DELETE RESTRICT,
    FOREIGN KEY (stock_item_id, organization_id) REFERENCES stock_item(id, organization_id) ON DELETE RESTRICT,
    FOREIGN KEY (stock_item_id, unit_code) REFERENCES stock_item(id, unit_of_measure_code) ON DELETE RESTRICT,
    FOREIGN KEY (storage_location_id, organization_id)
        REFERENCES storage_location(id, organization_id) ON DELETE RESTRICT
);

CREATE TABLE stock_count_record (
    id uuid PRIMARY KEY,
    stocktake_session_id uuid NOT NULL,
    snapshot_line_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    stock_item_id uuid NOT NULL,
    storage_location_id uuid NOT NULL,
    counted_quantity numeric(20,6) NOT NULL CHECK (counted_quantity >= 0),
    unit_code varchar(32) NOT NULL,
    counted_by uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    counted_at timestamptz NOT NULL,
    scan_source varchar(255),
    notes text,
    supersedes_count_record_id uuid REFERENCES stock_count_record(id) ON DELETE RESTRICT,
    operation_id uuid NOT NULL UNIQUE,
    correlation_id varchar(128) NOT NULL,
    version bigint NOT NULL DEFAULT 1 CHECK (version = 1),
    UNIQUE (id, organization_id),
    FOREIGN KEY (snapshot_line_id, stocktake_session_id, organization_id)
        REFERENCES stocktake_snapshot_line(id, stocktake_session_id, organization_id) ON DELETE RESTRICT,
    FOREIGN KEY (stock_item_id, organization_id) REFERENCES stock_item(id, organization_id) ON DELETE RESTRICT,
    FOREIGN KEY (stock_item_id, unit_code) REFERENCES stock_item(id, unit_of_measure_code) ON DELETE RESTRICT,
    FOREIGN KEY (storage_location_id, organization_id)
        REFERENCES storage_location(id, organization_id) ON DELETE RESTRICT
);

CREATE TABLE inventory_discrepancy (
    id uuid PRIMARY KEY,
    stocktake_session_id uuid NOT NULL,
    snapshot_line_id uuid NOT NULL,
    latest_count_record_id uuid NOT NULL REFERENCES stock_count_record(id) ON DELETE RESTRICT,
    organization_id uuid NOT NULL,
    stock_item_id uuid NOT NULL,
    storage_location_id uuid NOT NULL,
    expected_quantity numeric(20,6) NOT NULL,
    counted_quantity numeric(20,6) NOT NULL,
    variance_quantity numeric(20,6) NOT NULL,
    snapshot_balance_version bigint NOT NULL CHECK (snapshot_balance_version >= 1),
    current_balance_version bigint NOT NULL CHECK (current_balance_version >= 1),
    source_changed_since_snapshot boolean NOT NULL,
    state varchar(32) NOT NULL CHECK (state IN (
        'OPEN','INVESTIGATING','RESOLVED_NO_ADJUSTMENT','ADJUSTMENT_REQUESTED','CLOSED'
    )),
    created_by uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    UNIQUE (id, organization_id),
    UNIQUE (stocktake_session_id, snapshot_line_id),
    FOREIGN KEY (stocktake_session_id, organization_id)
        REFERENCES stocktake_session(id, organization_id) ON DELETE RESTRICT,
    FOREIGN KEY (snapshot_line_id, stocktake_session_id, organization_id)
        REFERENCES stocktake_snapshot_line(id, stocktake_session_id, organization_id) ON DELETE RESTRICT,
    FOREIGN KEY (stock_item_id, organization_id) REFERENCES stock_item(id, organization_id) ON DELETE RESTRICT,
    FOREIGN KEY (storage_location_id, organization_id)
        REFERENCES storage_location(id, organization_id) ON DELETE RESTRICT
);

CREATE TABLE inventory_adjustment_request (
    id uuid PRIMARY KEY,
    inventory_discrepancy_id uuid NOT NULL UNIQUE,
    stocktake_session_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    stock_item_id uuid NOT NULL,
    storage_location_id uuid NOT NULL,
    delta_quantity numeric(20,6) NOT NULL CHECK (delta_quantity <> 0),
    unit_code varchar(32) NOT NULL,
    reason text NOT NULL CHECK (length(btrim(reason)) > 0),
    requested_by uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    requested_at timestamptz NOT NULL,
    approval_request_id uuid REFERENCES approval_request(id) ON DELETE RESTRICT,
    approval_status varchar(24) CHECK (approval_status IS NULL OR approval_status IN ('PENDING','APPROVED','REJECTED')),
    applied_movement_id uuid UNIQUE REFERENCES stock_movement(id) ON DELETE RESTRICT,
    applied_by uuid REFERENCES user_identity(id) ON DELETE RESTRICT,
    applied_at timestamptz,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    UNIQUE (id, organization_id),
    FOREIGN KEY (inventory_discrepancy_id, organization_id)
        REFERENCES inventory_discrepancy(id, organization_id) ON DELETE RESTRICT,
    FOREIGN KEY (stocktake_session_id, organization_id)
        REFERENCES stocktake_session(id, organization_id) ON DELETE RESTRICT,
    FOREIGN KEY (stock_item_id, organization_id) REFERENCES stock_item(id, organization_id) ON DELETE RESTRICT,
    FOREIGN KEY (stock_item_id, unit_code) REFERENCES stock_item(id, unit_of_measure_code) ON DELETE RESTRICT,
    FOREIGN KEY (storage_location_id, organization_id)
        REFERENCES storage_location(id, organization_id) ON DELETE RESTRICT,
    CHECK (
        (applied_movement_id IS NULL AND applied_by IS NULL AND applied_at IS NULL)
        OR
        (applied_movement_id IS NOT NULL AND applied_by IS NOT NULL AND applied_at IS NOT NULL)
    )
);

CREATE FUNCTION guard_stocktake_session_mutation() RETURNS trigger LANGUAGE plpgsql AS $stocktake_session$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Stocktake session history is durable' USING ERRCODE = '23514';
    END IF;
    IF NEW.version <> OLD.version + 1
        OR (to_jsonb(NEW) - ARRAY['state','snapshot_at','updated_at','submitted_by','submitted_at',
                                   'closed_by','closed_at','version'])
           IS DISTINCT FROM
           (to_jsonb(OLD) - ARRAY['state','snapshot_at','updated_at','submitted_by','submitted_at',
                                   'closed_by','closed_at','version']) THEN
        RAISE EXCEPTION 'Stocktake scope is immutable after creation' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$stocktake_session$;
CREATE TRIGGER stocktake_session_guard BEFORE UPDATE OR DELETE ON stocktake_session
FOR EACH ROW EXECUTE FUNCTION guard_stocktake_session_mutation();

CREATE FUNCTION reject_stocktake_snapshot_mutation() RETURNS trigger LANGUAGE plpgsql AS $stocktake_snapshot$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'Stocktake snapshot is immutable' USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM stocktake_session s
        WHERE s.id = NEW.stocktake_session_id AND s.organization_id = NEW.organization_id AND s.state = 'DRAFT'
    ) THEN
        RAISE EXCEPTION 'Snapshot lines may only be captured while Stocktake is DRAFT' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$stocktake_snapshot$;
CREATE TRIGGER stocktake_snapshot_guard BEFORE INSERT OR UPDATE OR DELETE ON stocktake_snapshot_line
FOR EACH ROW EXECUTE FUNCTION reject_stocktake_snapshot_mutation();

CREATE FUNCTION guard_stock_count_record() RETURNS trigger LANGUAGE plpgsql AS $stocktake_count$
DECLARE
    session_row stocktake_session%ROWTYPE;
    snapshot_row stocktake_snapshot_line%ROWTYPE;
    latest_id uuid;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'Stock count history is append-only' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO STRICT session_row FROM stocktake_session WHERE id = NEW.stocktake_session_id FOR NO KEY UPDATE;
    SELECT * INTO STRICT snapshot_row FROM stocktake_snapshot_line WHERE id = NEW.snapshot_line_id FOR SHARE;
    IF session_row.state <> 'ACTIVE'
        OR session_row.organization_id <> NEW.organization_id
        OR snapshot_row.stocktake_session_id <> session_row.id
        OR snapshot_row.organization_id <> NEW.organization_id
        OR snapshot_row.stock_item_id <> NEW.stock_item_id
        OR snapshot_row.storage_location_id <> NEW.storage_location_id
        OR snapshot_row.unit_code <> NEW.unit_code
        OR NEW.counted_at < snapshot_row.snapshot_at THEN
        RAISE EXCEPTION 'Count does not match active Stocktake snapshot truth' USING ERRCODE = '23514';
    END IF;
    SELECT c.id INTO latest_id FROM stock_count_record c
      WHERE c.snapshot_line_id = NEW.snapshot_line_id
        AND NOT EXISTS (
            SELECT 1 FROM stock_count_record newer
            WHERE newer.snapshot_line_id = c.snapshot_line_id
              AND newer.supersedes_count_record_id = c.id
        )
      LIMIT 1;
    IF latest_id IS NULL AND NEW.supersedes_count_record_id IS NOT NULL THEN
        RAISE EXCEPTION 'First count cannot supersede history' USING ERRCODE = '23514';
    END IF;
    IF latest_id IS NOT NULL AND NEW.supersedes_count_record_id IS DISTINCT FROM latest_id THEN
        RAISE EXCEPTION 'Recount must supersede the current count record' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$stocktake_count$;
CREATE TRIGGER stock_count_record_guard BEFORE INSERT OR UPDATE OR DELETE ON stock_count_record
FOR EACH ROW EXECUTE FUNCTION guard_stock_count_record();


-- Slice 08 final reconciliation invariants.
ALTER TABLE inventory_discrepancy
    ADD CONSTRAINT inventory_discrepancy_variance_truth_check CHECK (
        expected_quantity >= 0 AND counted_quantity >= 0
        AND variance_quantity = counted_quantity - expected_quantity
    );

CREATE FUNCTION guard_inventory_discrepancy_mutation()
RETURNS trigger LANGUAGE plpgsql AS $inventory_discrepancy_guard$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Inventory discrepancy history is durable' USING ERRCODE = '23514';
    END IF;
    IF NEW.version <> OLD.version + 1
        OR (to_jsonb(NEW) - ARRAY['state','updated_at','version'])
           IS DISTINCT FROM
           (to_jsonb(OLD) - ARRAY['state','updated_at','version'])
        OR NOT (
            (OLD.state = 'OPEN' AND NEW.state IN ('INVESTIGATING','RESOLVED_NO_ADJUSTMENT','ADJUSTMENT_REQUESTED'))
            OR (OLD.state = 'INVESTIGATING' AND NEW.state IN ('RESOLVED_NO_ADJUSTMENT','ADJUSTMENT_REQUESTED'))
            OR (OLD.state = 'ADJUSTMENT_REQUESTED' AND NEW.state = 'CLOSED')
        ) THEN
        RAISE EXCEPTION 'Invalid Inventory discrepancy transition' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$inventory_discrepancy_guard$;
CREATE TRIGGER inventory_discrepancy_guard
BEFORE UPDATE OR DELETE ON inventory_discrepancy
FOR EACH ROW EXECUTE FUNCTION guard_inventory_discrepancy_mutation();

CREATE FUNCTION guard_inventory_adjustment_request_mutation()
RETURNS trigger LANGUAGE plpgsql AS $inventory_adjustment_guard$
DECLARE
    core_same boolean :=
        (to_jsonb(NEW) - ARRAY['approval_request_id','approval_status','applied_movement_id',
                               'applied_by','applied_at','version'])
        IS NOT DISTINCT FROM
        (to_jsonb(OLD) - ARRAY['approval_request_id','approval_status','applied_movement_id',
                               'applied_by','applied_at','version']);
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Inventory adjustment request history is durable' USING ERRCODE = '23514';
    END IF;
    IF NOT core_same THEN
        RAISE EXCEPTION 'Inventory adjustment request core is immutable' USING ERRCODE = '23514';
    END IF;
    IF OLD.approval_request_id IS NULL
        AND NEW.approval_request_id IS NOT NULL
        AND NEW.approval_status = 'PENDING'
        AND NEW.applied_movement_id IS NULL
        AND NEW.applied_by IS NULL
        AND NEW.applied_at IS NULL
        AND NEW.version = OLD.version THEN
        RETURN NEW;
    END IF;
    IF OLD.approval_request_id IS NOT NULL
        AND NEW.approval_request_id = OLD.approval_request_id
        AND OLD.applied_movement_id IS NULL
        AND NEW.approval_status = 'APPROVED'
        AND NEW.applied_movement_id IS NOT NULL
        AND NEW.applied_by IS NOT NULL
        AND NEW.applied_at IS NOT NULL
        AND NEW.version = OLD.version + 1 THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'Invalid Inventory adjustment request transition' USING ERRCODE = '23514';
END;
$inventory_adjustment_guard$;
CREATE TRIGGER inventory_adjustment_request_guard
BEFORE UPDATE OR DELETE ON inventory_adjustment_request
FOR EACH ROW EXECUTE FUNCTION guard_inventory_adjustment_request_mutation();

-- Slice 08 Checkpoint 07B: approved Inventory Adjustment is an append-only Stock ledger movement.
ALTER TABLE stock_movement
    ADD COLUMN inventory_adjustment_request_id uuid UNIQUE
        REFERENCES inventory_adjustment_request(id) ON DELETE RESTRICT,
    ADD CONSTRAINT stock_movement_inventory_adjustment_reference_check CHECK (
        (movement_type = 'ADJUSTMENT' AND inventory_adjustment_request_id IS NOT NULL)
        OR
        (movement_type <> 'ADJUSTMENT' AND inventory_adjustment_request_id IS NULL)
    );

-- The existing projector continues to own every pre-Slice-08 movement.
-- ADJUSTMENT has a stricter projector because it must prove Approval + discrepancy truth.
DROP TRIGGER IF EXISTS stock_movement_projects_balance ON stock_movement;
CREATE TRIGGER stock_movement_projects_balance AFTER INSERT ON stock_movement
FOR EACH ROW WHEN (NEW.movement_type <> 'ADJUSTMENT')
EXECUTE FUNCTION project_stock_movement();

CREATE FUNCTION project_inventory_adjustment_movement()
RETURNS trigger LANGUAGE plpgsql AS $inventory_adjustment_movement$
DECLARE
    request_row inventory_adjustment_request%ROWTYPE;
    discrepancy_row inventory_discrepancy%ROWTYPE;
    session_row stocktake_session%ROWTYPE;
    approval_row approval_request%ROWTYPE;
    item_row stock_item%ROWTYPE;
    location_row storage_location%ROWTYPE;
    balance_row stock_balance%ROWTYPE;
    absolute_delta numeric(20,6);
BEGIN
    SELECT * INTO STRICT request_row
      FROM inventory_adjustment_request
      WHERE id = NEW.inventory_adjustment_request_id
      FOR UPDATE;
    SELECT * INTO STRICT discrepancy_row
      FROM inventory_discrepancy
      WHERE id = request_row.inventory_discrepancy_id
      FOR UPDATE;
    SELECT * INTO STRICT session_row
      FROM stocktake_session
      WHERE id = request_row.stocktake_session_id
      FOR UPDATE;

    IF request_row.applied_movement_id IS NOT NULL
        OR discrepancy_row.state <> 'ADJUSTMENT_REQUESTED'
        OR discrepancy_row.source_changed_since_snapshot
        OR session_row.state NOT IN ('SUBMITTED','RECONCILING')
        OR request_row.delta_quantity <> discrepancy_row.variance_quantity
        OR request_row.organization_id <> NEW.organization_id
        OR request_row.stock_item_id <> NEW.stock_item_id
        OR discrepancy_row.stock_item_id <> NEW.stock_item_id
        OR request_row.storage_location_id <> discrepancy_row.storage_location_id THEN
        RAISE EXCEPTION 'Adjustment request is not currently applicable' USING ERRCODE = '23514';
    END IF;

    IF request_row.approval_request_id IS NULL THEN
        RAISE EXCEPTION 'Adjustment requires canonical Approval' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO STRICT approval_row
      FROM approval_request
      WHERE id = request_row.approval_request_id
      FOR SHARE;
    IF approval_row.organization_id <> request_row.organization_id
        OR approval_row.subject_type <> 'INVENTORY_ADJUSTMENT_REQUEST'
        OR approval_row.subject_id <> request_row.id
        OR approval_row.subject_version <> request_row.version
        OR approval_row.policy_key <> 'INVENTORY_ADJUSTMENT_STANDARD'
        OR approval_row.state <> 'APPROVED' THEN
        RAISE EXCEPTION 'Adjustment Approval is not approved for this exact request' USING ERRCODE = '23514';
    END IF;

    SELECT * INTO STRICT item_row FROM stock_item WHERE id = NEW.stock_item_id FOR NO KEY UPDATE;
    SELECT * INTO STRICT location_row FROM storage_location WHERE id = request_row.storage_location_id FOR SHARE;
    IF NOT item_row.active
        OR item_row.organization_id <> request_row.organization_id
        OR item_row.unit_of_measure_code <> request_row.unit_code
        OR NEW.unit_code <> request_row.unit_code
        OR NOT EXISTS (
            SELECT 1 FROM stock_unit u
            WHERE u.organization_id = item_row.organization_id
              AND u.code = request_row.unit_code AND u.active
        )
        OR location_row.organization_id <> request_row.organization_id
        OR location_row.warehouse_id IS DISTINCT FROM session_row.warehouse_id
        OR location_row.lifecycle_state <> 'ACTIVE'
        OR (location_row.active_from IS NOT NULL AND location_row.active_from > NEW.recorded_at)
        OR (location_row.active_until IS NOT NULL AND location_row.active_until <= NEW.recorded_at)
        OR NOT EXISTS (
            SELECT 1 FROM warehouse w
            WHERE w.id = session_row.warehouse_id
              AND w.organization_id = request_row.organization_id
              AND w.active
        ) THEN
        RAISE EXCEPTION 'Adjustment Stock or location truth is unavailable' USING ERRCODE = '23514';
    END IF;

    absolute_delta := abs(request_row.delta_quantity);
    IF NEW.quantity <> absolute_delta
        OR NEW.reason_code <> 'ADJUSTMENT'
        OR NEW.source_import_row_id IS NOT NULL
        OR NEW.receiving_line_id IS NOT NULL
        OR NEW.project_id IS NOT NULL
        OR NEW.site_id IS NOT NULL
        OR NEW.work_order_id IS NOT NULL
        OR NEW.work_requirement_instance_id IS NOT NULL
        OR NEW.fulfilled_reservation_id IS NOT NULL
        OR NEW.recipient_target_type IS NOT NULL
        OR NEW.recipient_target_id IS NOT NULL
        OR NEW.purchase_receipt_id IS NOT NULL
        OR (request_row.delta_quantity > 0 AND
            (NEW.from_storage_location_id IS NOT NULL
             OR NEW.to_storage_location_id IS DISTINCT FROM request_row.storage_location_id))
        OR (request_row.delta_quantity < 0 AND
            (NEW.from_storage_location_id IS DISTINCT FROM request_row.storage_location_id
             OR NEW.to_storage_location_id IS NOT NULL)) THEN
        RAISE EXCEPTION 'Adjustment movement does not match approved request' USING ERRCODE = '23514';
    END IF;

    SELECT * INTO STRICT balance_row FROM stock_balance
      WHERE stock_item_id = request_row.stock_item_id
        AND storage_location_id = request_row.storage_location_id
      FOR UPDATE;
    IF balance_row.version <> discrepancy_row.current_balance_version THEN
        RAISE EXCEPTION 'Stock changed after discrepancy reconciliation' USING ERRCODE = '23514';
    END IF;

    IF request_row.delta_quantity < 0 THEN
        IF balance_row.on_hand_qty - balance_row.reserved_qty -
           balance_row.damaged_qty - balance_row.quarantine_qty < absolute_delta THEN
            RAISE EXCEPTION 'Adjustment would make available Stock negative' USING ERRCODE = '23514';
        END IF;
        UPDATE stock_balance
        SET on_hand_qty = on_hand_qty - absolute_delta,
            updated_at = greatest(updated_at, NEW.recorded_at),
            version = version + 1
        WHERE stock_item_id = request_row.stock_item_id
          AND storage_location_id = request_row.storage_location_id;
    ELSE
        UPDATE stock_balance
        SET on_hand_qty = on_hand_qty + absolute_delta,
            updated_at = greatest(updated_at, NEW.recorded_at),
            version = version + 1
        WHERE stock_item_id = request_row.stock_item_id
          AND storage_location_id = request_row.storage_location_id;
    END IF;
    RETURN NEW;
END;
$inventory_adjustment_movement$;

CREATE TRIGGER stock_movement_projects_inventory_adjustment
AFTER INSERT ON stock_movement
FOR EACH ROW WHEN (NEW.movement_type = 'ADJUSTMENT')
EXECUTE FUNCTION project_inventory_adjustment_movement();
