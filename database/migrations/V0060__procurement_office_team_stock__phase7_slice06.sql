-- Phase 7 / S06: actual organizational team stock need for office/facility goods.
-- Non-stock outsourced services require their own acceptance and invoice model.
-- This never creates a fake WorkOrder, storage ledger movement or client payment.
ALTER TABLE team ADD CONSTRAINT uq_team_scoped_id UNIQUE(id, organization_id);

ALTER TABLE procurement_purchase_requirement
  ADD COLUMN office_team_id uuid,
  ADD COLUMN office_team_version bigint,
  ADD COLUMN office_delivery_location_id uuid,
  ADD COLUMN office_delivery_location_version bigint;

ALTER TABLE procurement_purchase_requirement
  ADD CONSTRAINT fk_procurement_office_need_team
    FOREIGN KEY (office_team_id, organization_id)
    REFERENCES team(id, organization_id) ON DELETE RESTRICT,
  ADD CONSTRAINT fk_procurement_office_need_location
    FOREIGN KEY (office_delivery_location_id, organization_id)
    REFERENCES storage_location(id, organization_id) ON DELETE RESTRICT;

ALTER TABLE procurement_purchase_requirement
  DROP CONSTRAINT ck_procurement_requirement_typed_source;
ALTER TABLE procurement_purchase_requirement
  ADD CONSTRAINT ck_procurement_requirement_typed_source CHECK (
    (source_type='WORK_ORDER'
      AND work_order_id IS NOT NULL AND source_work_version IS NOT NULL
      AND warehouse_storage_location_id IS NULL AND warehouse_source_version IS NULL
      AND num_nonnulls(asset_id,asset_source_version,asset_incident_id,
          asset_maintenance_id,asset_issue_version,asset_delivery_location_id,
          asset_delivery_location_version,office_team_id,office_team_version,
          office_delivery_location_id,office_delivery_location_version)=0
    ) OR
    (source_type='WAREHOUSE_REPLENISHMENT'
      AND work_order_id IS NULL AND source_work_version IS NULL
      AND warehouse_storage_location_id IS NOT NULL AND warehouse_source_version>=1
      AND num_nonnulls(asset_id,asset_source_version,asset_incident_id,
          asset_maintenance_id,asset_issue_version,asset_delivery_location_id,
          asset_delivery_location_version,office_team_id,office_team_version,
          office_delivery_location_id,office_delivery_location_version)=0
    ) OR
    (source_type='ASSET_MAINTENANCE_OR_REPLACEMENT'
      AND work_order_id IS NULL AND source_work_version IS NULL
      AND warehouse_storage_location_id IS NULL AND warehouse_source_version IS NULL
      AND asset_id IS NOT NULL AND asset_source_version>=1
      AND num_nonnulls(asset_incident_id,asset_maintenance_id)=1
      AND asset_issue_version>=1 AND asset_delivery_location_id IS NOT NULL
      AND asset_delivery_location_version>=1
      AND num_nonnulls(office_team_id,office_team_version,
          office_delivery_location_id,office_delivery_location_version)=0
    ) OR
    (source_type='OFFICE_TEAM_STOCK'
      AND work_order_id IS NULL AND source_work_version IS NULL
      AND warehouse_storage_location_id IS NULL AND warehouse_source_version IS NULL
      AND num_nonnulls(asset_id,asset_source_version,asset_incident_id,
          asset_maintenance_id,asset_issue_version,asset_delivery_location_id,
          asset_delivery_location_version)=0
      AND office_team_id IS NOT NULL AND office_team_version IS NOT NULL
      AND office_team_version>=1
      AND office_delivery_location_id IS NOT NULL
      AND office_delivery_location_version IS NOT NULL
      AND office_delivery_location_version>=1
    )
  );

CREATE INDEX idx_procurement_office_team_needs
  ON procurement_purchase_requirement
  (organization_id, office_team_id, created_at DESC, id DESC)
  WHERE source_type='OFFICE_TEAM_STOCK';

CREATE OR REPLACE FUNCTION guard_procurement_office_stock_source()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  office_team team%ROWTYPE;
  dest storage_location%ROWTYPE;
  org organization%ROWTYPE;
BEGIN
  IF NEW.source_type <> 'OFFICE_TEAM_STOCK' THEN RETURN NEW; END IF;
  SELECT * INTO office_team FROM team WHERE id=NEW.office_team_id FOR SHARE;
  SELECT * INTO dest FROM storage_location WHERE id=NEW.office_delivery_location_id FOR SHARE;
  SELECT * INTO org FROM organization WHERE id=NEW.organization_id;
  IF org.id IS NULL OR org.status <> 'ACTIVE' OR org.organization_type <> 'HILTECH'
    OR office_team.id IS NULL OR office_team.organization_id <> org.id
    OR NOT office_team.active OR office_team.version <> NEW.office_team_version
    OR dest.id IS NULL OR dest.organization_id <> org.id
    OR dest.version <> NEW.office_delivery_location_version
    OR dest.kind NOT IN ('MAIN_WAREHOUSE','WAREHOUSE')
    OR dest.warehouse_id IS NULL OR dest.project_id IS NOT NULL OR dest.site_id IS NOT NULL
    OR dest.lifecycle_state <> 'ACTIVE'
    OR NOT EXISTS (
      SELECT 1 FROM warehouse w
      WHERE w.id=dest.warehouse_id AND w.organization_id=org.id AND w.active=true
    )
  THEN RAISE EXCEPTION 'Office stock demand requires current internal team and real active company Warehouse'
    USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER procurement_office_stock_source_insert_guard
BEFORE INSERT ON procurement_purchase_requirement
FOR EACH ROW WHEN (NEW.source_type='OFFICE_TEAM_STOCK')
EXECUTE FUNCTION guard_procurement_office_stock_source();
