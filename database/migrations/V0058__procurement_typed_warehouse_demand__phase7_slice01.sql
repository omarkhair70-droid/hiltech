-- Phase 7 / S01: typed Warehouse replenishment as a real source of the
-- SAME procurement_purchase_requirement, not a simulated WorkOrder.
-- The older work_order_id/source_work_version remain valid only for WORK_ORDER.
ALTER TABLE procurement_purchase_requirement
  ALTER COLUMN work_order_id DROP NOT NULL,
  ALTER COLUMN source_work_version DROP NOT NULL,
  ADD COLUMN source_type varchar(32) NOT NULL DEFAULT 'WORK_ORDER',
  ADD COLUMN warehouse_storage_location_id uuid,
  ADD COLUMN warehouse_source_version bigint;

ALTER TABLE procurement_purchase_requirement
  ADD CONSTRAINT fk_procurement_requirement_warehouse_source
    FOREIGN KEY (warehouse_storage_location_id, organization_id)
    REFERENCES storage_location(id, organization_id) ON DELETE RESTRICT,
  ADD CONSTRAINT ck_procurement_requirement_typed_source CHECK (
    (source_type='WORK_ORDER'
      AND work_order_id IS NOT NULL
      AND source_work_version IS NOT NULL
      AND warehouse_storage_location_id IS NULL
      AND warehouse_source_version IS NULL)
    OR
    (source_type='WAREHOUSE_REPLENISHMENT'
      AND work_order_id IS NULL
      AND source_work_version IS NULL
      AND warehouse_storage_location_id IS NOT NULL
      AND warehouse_source_version IS NOT NULL
      AND warehouse_source_version >= 1)
  );

CREATE INDEX idx_procurement_warehouse_demand
  ON procurement_purchase_requirement
  (organization_id, warehouse_storage_location_id, created_at DESC, id DESC)
  WHERE source_type='WAREHOUSE_REPLENISHMENT';

-- Revisions continue to be immutable and sequence-checked. The legacy
-- spec_work_version field denotes the exact typed source version for revision
-- events (WORK_ORDER or WAREHOUSE_REPLENISHMENT); root columns remain explicit.
-- No guessed role bindings, supplier approvals or financial posting.
