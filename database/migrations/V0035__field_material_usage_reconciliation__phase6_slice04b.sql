-- Phase 6 Slice 04B / warehouse reconciliation of immutable field usage reports.
-- The original field report remains immutable. This table records one terminal
-- review outcome and, for CONSUME, the exact canonical stock movement used.
CREATE TABLE field_material_usage_reconciliation (
    operation_id uuid PRIMARY KEY,
    report_operation_id uuid NOT NULL UNIQUE
        REFERENCES field_material_usage_report(operation_id) ON DELETE RESTRICT,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    warehouse_id uuid NOT NULL REFERENCES warehouse(id) ON DELETE RESTRICT,
    reviewer_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    decision varchar(32) NOT NULL
        CHECK (decision IN ('CONSUME','REJECT')),
    stock_operation_id uuid NULL,
    stock_movement_id uuid NULL REFERENCES stock_movement(id) ON DELETE RESTRICT,
    reason varchar(500) NULL,
    created_at timestamptz NOT NULL,
    correlation_id varchar(128) NOT NULL,
    CHECK (length(btrim(correlation_id)) > 0),
    CHECK (
        (decision = 'CONSUME' AND stock_operation_id IS NOT NULL AND stock_movement_id IS NOT NULL)
        OR
        (decision = 'REJECT' AND stock_operation_id IS NULL AND stock_movement_id IS NULL
            AND reason IS NOT NULL AND length(btrim(reason)) > 0)
    )
);

CREATE INDEX idx_field_material_usage_reconciliation_warehouse
    ON field_material_usage_reconciliation (warehouse_id, created_at, report_operation_id);

CREATE FUNCTION guard_field_material_usage_reconciliation_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Field material usage reconciliation records are immutable'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER field_material_usage_reconciliation_immutable
    BEFORE UPDATE OR DELETE ON field_material_usage_reconciliation
    FOR EACH ROW EXECUTE FUNCTION guard_field_material_usage_reconciliation_immutable();
