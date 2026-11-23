-- Phase 7 / S01 initial internal demand draft. No purchase authorization or ledger mutation.
-- The WorkOrder/StockItem pair is always restricted to the same organization.
CREATE TABLE procurement_purchase_requirement (
    id uuid PRIMARY KEY,
    operation_id uuid NOT NULL UNIQUE,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    work_order_id uuid NOT NULL,
    source_work_version bigint NOT NULL CHECK (source_work_version >= 1),
    stock_item_id uuid NOT NULL,
    stock_item_version bigint NOT NULL CHECK (stock_item_version >= 1),
    requested_quantity numeric(18,6) NOT NULL CHECK (requested_quantity > 0),
    unit_code varchar(32) NOT NULL CHECK (length(btrim(unit_code)) > 0),
    justification varchar(500) NOT NULL CHECK (length(btrim(justification)) > 0),
    lifecycle_state varchar(24) NOT NULL DEFAULT 'DRAFT'
        CHECK (lifecycle_state = 'DRAFT'),
    assessment_code varchar(40) NOT NULL
        CHECK (assessment_code IN (
            'WAREHOUSE_REVIEW_REQUIRED', 'VISIBLE_STOCK_AVAILABLE',
            'VISIBLE_STOCK_SHORTAGE'
        )),
    visible_free_quantity numeric(18,6)
        CHECK (visible_free_quantity IS NULL OR visible_free_quantity >= 0),
    assessment_at timestamptz NOT NULL,
    created_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    correlation_id varchar(128) NOT NULL CHECK (length(btrim(correlation_id)) > 0),
    version bigint NOT NULL DEFAULT 1 CHECK (version = 1),
    CONSTRAINT fk_procurement_requirement_work
        FOREIGN KEY (work_order_id, organization_id)
        REFERENCES work_order(id, organization_id) ON DELETE RESTRICT,
    CONSTRAINT fk_procurement_requirement_item
        FOREIGN KEY (stock_item_id, organization_id)
        REFERENCES stock_item(id, organization_id) ON DELETE RESTRICT,
    CONSTRAINT fk_procurement_requirement_unit
        FOREIGN KEY (stock_item_id, unit_code)
        REFERENCES stock_item(id, unit_of_measure_code) ON DELETE RESTRICT,
    CONSTRAINT ck_procurement_assessment_secrecy
        CHECK (
            (assessment_code = 'WAREHOUSE_REVIEW_REQUIRED' AND visible_free_quantity IS NULL)
            OR (assessment_code <> 'WAREHOUSE_REVIEW_REQUIRED' AND visible_free_quantity IS NOT NULL)
        )
);

CREATE INDEX idx_procurement_requirement_work
    ON procurement_purchase_requirement (work_order_id, created_at DESC, id DESC);
CREATE INDEX idx_procurement_requirement_stock
    ON procurement_purchase_requirement (organization_id, stock_item_id, lifecycle_state);

-- Draft snapshots are immutable during the S01 initial safety checkpoint.
-- Future transition/version migration must define authorized changes explicitly.
CREATE FUNCTION reject_procurement_draft_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Procurement draft snapshot is immutable'
        USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER procurement_draft_immutable
BEFORE UPDATE OR DELETE ON procurement_purchase_requirement
FOR EACH ROW EXECUTE FUNCTION reject_procurement_draft_mutation();
