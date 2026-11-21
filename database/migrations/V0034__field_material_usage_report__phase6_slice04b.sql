-- Phase 6 Slice 04B / first vertical: an immutable *proposed* material usage report.
-- No stock movement, approval, balance projection, warehouse rights or auto-apply.
-- A later reviewed slice must add authorized review/reconciliation without
-- turning these reports into a second stock ledger.
CREATE TABLE field_material_usage_report (
    operation_id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    site_id uuid NOT NULL,
    work_order_id uuid NOT NULL,
    actor_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    employee_id uuid NOT NULL,
    assignment_id uuid NOT NULL,
    assignment_version bigint NOT NULL CHECK (assignment_version > 0),
    work_version bigint NOT NULL CHECK (work_version > 0),
    requirement_id uuid NOT NULL,
    stock_item_id uuid NOT NULL,
    issued_version bigint NOT NULL CHECK (issued_version > 0),
    quantity numeric(20,6) NOT NULL CHECK (quantity > 0),
    unit_code varchar(64) NOT NULL,
    state varchar(32) NOT NULL DEFAULT 'PENDING_REVIEW'
        CHECK (state = 'PENDING_REVIEW'),
    client_occurred_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    correlation_id varchar(128) NOT NULL,
    FOREIGN KEY (work_order_id, organization_id, project_id)
        REFERENCES work_order(id, organization_id, project_id) ON DELETE RESTRICT,
    FOREIGN KEY (site_id, organization_id)
        REFERENCES site(id, organization_id) ON DELETE RESTRICT,
    FOREIGN KEY (employee_id, organization_id)
        REFERENCES employee(id, organization_id) ON DELETE RESTRICT,
    FOREIGN KEY (assignment_id, work_order_id)
        REFERENCES work_assignment(id, work_order_id) ON DELETE RESTRICT,
    FOREIGN KEY (requirement_id, work_order_id)
        REFERENCES work_requirement_instance(id, work_order_id) ON DELETE RESTRICT,
    FOREIGN KEY (requirement_id)
        REFERENCES work_resource_request(requirement_id) ON DELETE RESTRICT,
    FOREIGN KEY (stock_item_id, organization_id)
        REFERENCES stock_item(id, organization_id) ON DELETE RESTRICT,
    FOREIGN KEY (stock_item_id, unit_code)
        REFERENCES stock_item(id, unit_of_measure_code) ON DELETE RESTRICT,
    CHECK (length(btrim(unit_code)) > 0),
    CHECK (length(btrim(correlation_id)) > 0)
);

CREATE INDEX idx_field_material_usage_pending_source
    ON field_material_usage_report
    (work_order_id, employee_id, requirement_id, stock_item_id)
    WHERE state = 'PENDING_REVIEW';

-- Prevent accidental deletion/editing of an original field claim.
-- Future review decisions require their own append-only records / deliberate migration.
CREATE FUNCTION guard_field_material_usage_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Original field material usage reports are immutable'
        USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER field_material_usage_immutable
    BEFORE UPDATE OR DELETE ON field_material_usage_report
    FOR EACH ROW EXECUTE FUNCTION guard_field_material_usage_immutable();
