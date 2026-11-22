-- Phase 7 S04: typed PO -> canonical Warehouse Receiving linkage only.
-- No shadow inventory ledger, no direct stock balance or payable mutations.
CREATE UNIQUE INDEX uq_receiving_record_id_org_for_po
    ON receiving_record(id, organization_id);

CREATE TABLE procurement_po_receiving_link (
    id uuid PRIMARY KEY,
    operation_id uuid NOT NULL UNIQUE,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    purchase_order_id uuid NOT NULL,
    receiving_record_id uuid NOT NULL UNIQUE,
    source_order_version bigint NOT NULL CHECK (source_order_version >= 1),
    stock_item_id uuid NOT NULL,
    delivery_storage_location_id uuid NOT NULL,
    ordered_quantity numeric(18,6) NOT NULL CHECK (ordered_quantity > 0),
    ordered_unit_code varchar(32) NOT NULL,
    linked_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    linked_at timestamptz NOT NULL,
    correlation_id varchar(128) NOT NULL CHECK (length(btrim(correlation_id)) > 0),
    CONSTRAINT fk_po_receiving_purchase_order_org FOREIGN KEY (purchase_order_id, organization_id)
      REFERENCES procurement_purchase_order(id, organization_id) ON DELETE RESTRICT,
    CONSTRAINT fk_po_receiving_record_org FOREIGN KEY (receiving_record_id, organization_id)
      REFERENCES receiving_record(id, organization_id) ON DELETE RESTRICT,
    CONSTRAINT fk_po_receiving_stock_item_org FOREIGN KEY (stock_item_id, organization_id)
      REFERENCES stock_item(id, organization_id) ON DELETE RESTRICT,
    CONSTRAINT fk_po_receiving_location_org FOREIGN KEY (delivery_storage_location_id, organization_id)
      REFERENCES storage_location(id, organization_id) ON DELETE RESTRICT
);

CREATE INDEX idx_po_receiving_source
  ON procurement_po_receiving_link(purchase_order_id, linked_at DESC, id DESC);

CREATE TRIGGER procurement_po_receiving_link_immutable
BEFORE UPDATE OR DELETE ON procurement_po_receiving_link
FOR EACH ROW EXECUTE FUNCTION reject_procurement_draft_mutation();
