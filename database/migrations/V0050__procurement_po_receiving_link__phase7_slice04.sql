-- Phase 7 / S04: link canonical Warehouse Receiving to a real issued PO.
-- This table is an immutable provenance edge, NOT another stock/receipt ledger.
CREATE TABLE procurement_po_receiving_link (
    receiving_record_id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    purchase_order_id uuid NOT NULL,
    linked_order_version bigint NOT NULL CHECK (linked_order_version >= 1),
    destination_storage_location_id uuid NOT NULL,
    stock_item_id uuid NOT NULL,
    linked_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    linked_at timestamptz NOT NULL,
    correlation_id varchar(128) NOT NULL CHECK (length(btrim(correlation_id)) > 0),
    CONSTRAINT fk_po_receipt_record FOREIGN KEY (receiving_record_id, organization_id)
        REFERENCES receiving_record(id, organization_id) ON DELETE RESTRICT,
    CONSTRAINT fk_po_receipt_order FOREIGN KEY (purchase_order_id, organization_id)
        REFERENCES procurement_purchase_order(id, organization_id) ON DELETE RESTRICT,
    CONSTRAINT fk_po_receipt_destination FOREIGN KEY (destination_storage_location_id, organization_id)
        REFERENCES storage_location(id, organization_id) ON DELETE RESTRICT,
    CONSTRAINT fk_po_receipt_stock FOREIGN KEY (stock_item_id, organization_id)
        REFERENCES stock_item(id, organization_id) ON DELETE RESTRICT
);
CREATE INDEX idx_po_receipt_order ON procurement_po_receiving_link
    (purchase_order_id, organization_id, receiving_record_id);

CREATE FUNCTION guard_procurement_po_receiving_link_insert() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    r receiving_record%ROWTYPE;
    p procurement_purchase_order%ROWTYPE;
BEGIN
    SELECT * INTO r FROM receiving_record WHERE id = NEW.receiving_record_id;
    SELECT * INTO p FROM procurement_purchase_order WHERE id = NEW.purchase_order_id FOR UPDATE;
    IF r.id IS NULL OR p.id IS NULL
        OR r.organization_id <> NEW.organization_id
        OR p.organization_id <> NEW.organization_id
        OR r.source_type <> 'PO'
        OR r.source_ref IS DISTINCT FROM NEW.purchase_order_id::text
        OR r.destination_storage_location_id <> NEW.destination_storage_location_id
        OR p.stock_item_id <> NEW.stock_item_id
        OR r.state <> 'DRAFT'
        OR EXISTS (SELECT 1 FROM procurement_po_lifecycle_event e
                   WHERE e.purchase_order_id = p.id AND e.event_type = 'CANCELLED') THEN
        RAISE EXCEPTION 'Receiving must be bound to a current company PO'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER procurement_po_receiving_link_insert_guard
BEFORE INSERT ON procurement_po_receiving_link
FOR EACH ROW EXECUTE FUNCTION guard_procurement_po_receiving_link_insert();
CREATE TRIGGER procurement_po_receiving_link_immutable
BEFORE UPDATE OR DELETE ON procurement_po_receiving_link
FOR EACH ROW EXECUTE FUNCTION reject_procurement_draft_mutation();
