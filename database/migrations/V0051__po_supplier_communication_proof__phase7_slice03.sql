-- Phase 7 / S03 follow-up (after S04 V0050): immutable, operator-attested
-- supplier dispatch and acknowledgement. These events do NOT send messages.
CREATE TABLE procurement_po_supplier_communication_event (
    id uuid PRIMARY KEY,
    operation_id uuid NOT NULL UNIQUE,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    purchase_order_id uuid NOT NULL,
    event_version bigint NOT NULL CHECK (event_version BETWEEN 1 AND 2),
    event_type varchar(32) NOT NULL CHECK (event_type IN (
        'EXTERNAL_DISPATCH_ATTESTED', 'SUPPLIER_ACK_ATTESTED'
    )),
    channel varchar(24) NOT NULL CHECK (channel IN (
        'EMAIL','SUPPLIER_PORTAL','HAND_DELIVERY','OTHER_VERIFIED_CHANNEL'
    )),
    external_reference varchar(255) NOT NULL
      CHECK (length(btrim(external_reference)) BETWEEN 8 AND 255),
    recorded_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    occurred_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL,
    correlation_id varchar(128) NOT NULL
      CHECK (length(btrim(correlation_id)) > 0),
    CONSTRAINT fk_po_communication_org FOREIGN KEY (purchase_order_id, organization_id)
      REFERENCES procurement_purchase_order(id, organization_id) ON DELETE RESTRICT,
    CONSTRAINT uq_po_communication_version UNIQUE (purchase_order_id, event_version),
    CONSTRAINT uq_po_communication_type UNIQUE (purchase_order_id, event_type),
    CONSTRAINT ck_po_communication_event_version CHECK (
      (event_type = 'EXTERNAL_DISPATCH_ATTESTED' AND event_version = 1)
      OR (event_type = 'SUPPLIER_ACK_ATTESTED' AND event_version = 2)
    )
);
CREATE INDEX idx_po_supplier_communication_company
  ON procurement_po_supplier_communication_event (organization_id, recorded_at DESC);

CREATE FUNCTION guard_po_supplier_communication_insert()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    po procurement_purchase_order%ROWTYPE;
BEGIN
    SELECT * INTO po FROM procurement_purchase_order
      WHERE id = NEW.purchase_order_id FOR UPDATE;
    IF po.id IS NULL OR po.organization_id <> NEW.organization_id
        OR EXISTS (SELECT 1 FROM procurement_po_lifecycle_event e
                   WHERE e.purchase_order_id = po.id AND e.event_type = 'CANCELLED')
        OR (NEW.event_type = 'EXTERNAL_DISPATCH_ATTESTED'
            AND EXISTS (SELECT 1 FROM procurement_po_supplier_communication_event e
                        WHERE e.purchase_order_id = po.id))
        OR (NEW.event_type = 'SUPPLIER_ACK_ATTESTED'
            AND NOT EXISTS (
              SELECT 1 FROM procurement_po_supplier_communication_event e
              WHERE e.purchase_order_id = po.id
                AND e.event_type = 'EXTERNAL_DISPATCH_ATTESTED'
            )) THEN
        RAISE EXCEPTION 'PO external communication must follow live PO lifecycle'
          USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER procurement_po_supplier_communication_insert_guard
BEFORE INSERT ON procurement_po_supplier_communication_event
FOR EACH ROW EXECUTE FUNCTION guard_po_supplier_communication_insert();

CREATE TRIGGER procurement_po_supplier_communication_immutable
BEFORE UPDATE OR DELETE ON procurement_po_supplier_communication_event
FOR EACH ROW EXECUTE FUNCTION reject_procurement_draft_mutation();
