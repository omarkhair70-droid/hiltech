-- S01 asset-service / replacement stock demand, NOT a service purchase or new Asset.
-- Each need binds to an existing unresolved incident or in-progress maintenance.
-- V0058 is immutable; append the new typed source here instead of rewriting history.
ALTER TABLE procurement_purchase_requirement
  ADD COLUMN asset_id uuid,
  ADD COLUMN asset_source_version bigint,
  ADD COLUMN asset_incident_id uuid,
  ADD COLUMN asset_maintenance_id uuid,
  ADD COLUMN asset_issue_version bigint,
  ADD COLUMN asset_delivery_location_id uuid,
  ADD COLUMN asset_delivery_location_version bigint;

ALTER TABLE asset_maintenance_record
  ADD CONSTRAINT uq_asset_maintenance_record_source UNIQUE (asset_id, id);

ALTER TABLE procurement_purchase_requirement
  ADD CONSTRAINT fk_requirement_asset_company
    FOREIGN KEY (asset_id, organization_id)
    REFERENCES asset(id, organization_id) ON DELETE RESTRICT,
  ADD CONSTRAINT fk_requirement_asset_incident
    FOREIGN KEY (asset_id, asset_incident_id)
    REFERENCES asset_incident(asset_id, id) ON DELETE RESTRICT,
  ADD CONSTRAINT fk_requirement_asset_maintenance
    FOREIGN KEY (asset_id, asset_maintenance_id)
    REFERENCES asset_maintenance_record(asset_id, id) ON DELETE RESTRICT,
  ADD CONSTRAINT fk_requirement_asset_delivery_location
    FOREIGN KEY (asset_delivery_location_id, organization_id)
    REFERENCES storage_location(id, organization_id) ON DELETE RESTRICT;

ALTER TABLE procurement_purchase_requirement
  DROP CONSTRAINT ck_procurement_requirement_typed_source;
ALTER TABLE procurement_purchase_requirement
  ADD CONSTRAINT ck_procurement_requirement_typed_source CHECK (
    (
      source_type='WORK_ORDER'
      AND work_order_id IS NOT NULL AND source_work_version IS NOT NULL
      AND warehouse_storage_location_id IS NULL AND warehouse_source_version IS NULL
      AND num_nonnulls(asset_id, asset_source_version, asset_incident_id,
          asset_maintenance_id, asset_issue_version, asset_delivery_location_id,
          asset_delivery_location_version)=0
    ) OR (
      source_type='WAREHOUSE_REPLENISHMENT'
      AND work_order_id IS NULL AND source_work_version IS NULL
      AND warehouse_storage_location_id IS NOT NULL
      AND warehouse_source_version IS NOT NULL AND warehouse_source_version >= 1
      AND num_nonnulls(asset_id, asset_source_version, asset_incident_id,
          asset_maintenance_id, asset_issue_version, asset_delivery_location_id,
          asset_delivery_location_version)=0
    ) OR (
      source_type='ASSET_MAINTENANCE_OR_REPLACEMENT'
      AND work_order_id IS NULL AND source_work_version IS NULL
      AND warehouse_storage_location_id IS NULL AND warehouse_source_version IS NULL
      AND asset_id IS NOT NULL AND asset_source_version IS NOT NULL
      AND asset_source_version >= 1
      AND num_nonnulls(asset_incident_id, asset_maintenance_id)=1
      AND asset_issue_version IS NOT NULL AND asset_issue_version >= 1
      AND asset_delivery_location_id IS NOT NULL
      AND asset_delivery_location_version IS NOT NULL
      AND asset_delivery_location_version >= 1
    )
  );

CREATE INDEX idx_procurement_asset_purchase_need
  ON procurement_purchase_requirement
  (organization_id, asset_id, created_at DESC, id DESC)
  WHERE source_type='ASSET_MAINTENANCE_OR_REPLACEMENT';

-- Defensive database rule: a spare-part demand must be linked to actual
-- unresolved equipment evidence and a real delivery Warehouse of that asset.
CREATE FUNCTION validate_asset_procurement_source()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    src asset%ROWTYPE;
    dest storage_location%ROWTYPE;
    facility warehouse%ROWTYPE;
    issue_found boolean;
BEGIN
    IF NEW.source_type <> 'ASSET_MAINTENANCE_OR_REPLACEMENT' THEN
        RETURN NEW;
    END IF;

    SELECT * INTO src FROM asset WHERE id=NEW.asset_id FOR SHARE;
    SELECT * INTO dest FROM storage_location
      WHERE id=NEW.asset_delivery_location_id FOR SHARE;
    IF src.id IS NULL OR src.organization_id <> NEW.organization_id
       OR src.lifecycle_state <> 'ACTIVE' OR src.version <> NEW.asset_source_version
       OR src.warehouse_id IS NULL
       OR dest.id IS NULL OR dest.organization_id <> NEW.organization_id
       OR dest.warehouse_id IS DISTINCT FROM src.warehouse_id
       OR dest.project_id IS NOT NULL OR dest.site_id IS NOT NULL
       OR dest.kind NOT IN ('MAIN_WAREHOUSE','WAREHOUSE')
       OR dest.lifecycle_state <> 'ACTIVE'
       OR dest.version <> NEW.asset_delivery_location_version
       OR (dest.active_from IS NOT NULL AND dest.active_from > transaction_timestamp())
       OR (dest.active_until IS NOT NULL AND dest.active_until <= transaction_timestamp())
    THEN
        RAISE EXCEPTION 'Asset purchase demand needs an active matching company source and delivery Warehouse'
          USING ERRCODE='23514';
    END IF;
    SELECT * INTO facility FROM warehouse WHERE id=src.warehouse_id;
    IF facility.id IS NULL OR NOT facility.active OR facility.organization_id <> src.organization_id THEN
        RAISE EXCEPTION 'Asset demand cannot source an inactive Warehouse'
          USING ERRCODE='23514';
    END IF;

    IF NEW.asset_incident_id IS NOT NULL THEN
        SELECT EXISTS(
          SELECT 1 FROM asset_incident i
          WHERE i.id=NEW.asset_incident_id AND i.asset_id=src.id
            AND i.organization_id=src.organization_id
            AND i.incident_type IN ('DAMAGE','MISSING','LOST')
            AND i.state IN ('OPEN','UNDER_REVIEW')
            AND i.version=NEW.asset_issue_version
        ) INTO issue_found;
    ELSE
        SELECT EXISTS(
          SELECT 1 FROM asset_maintenance_record m
          WHERE m.id=NEW.asset_maintenance_id AND m.asset_id=src.id
            AND m.organization_id=src.organization_id
            AND m.state='IN_PROGRESS'
            AND m.version=NEW.asset_issue_version
        ) INTO issue_found;
    END IF;
    IF NOT issue_found THEN
        RAISE EXCEPTION 'Asset purchasing source incident or maintenance is missing, closed or stale'
          USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER procurement_asset_source_insert_guard
BEFORE INSERT ON procurement_purchase_requirement
FOR EACH ROW WHEN (NEW.source_type='ASSET_MAINTENANCE_OR_REPLACEMENT')
EXECUTE FUNCTION validate_asset_procurement_source();
