-- Phase 7 / S03 commercial replacement link. A replacement is a NEW,
-- independently approved and issued PO, never a revision of old prices.
CREATE TABLE procurement_po_supersession_link (
    id uuid PRIMARY KEY,
    operation_id uuid NOT NULL UNIQUE,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    replaced_purchase_order_id uuid NOT NULL UNIQUE,
    replacement_purchase_order_id uuid NOT NULL UNIQUE,
    replaced_effective_version bigint NOT NULL CHECK(replaced_effective_version >= 2),
    replacement_effective_version bigint NOT NULL CHECK(replacement_effective_version >= 1),
    reason varchar(1000) NOT NULL CHECK(length(btrim(reason)) BETWEEN 12 AND 1000),
    linked_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    linked_at timestamptz NOT NULL,
    correlation_id varchar(128) NOT NULL CHECK(length(btrim(correlation_id)) > 0),
    CHECK(replaced_purchase_order_id <> replacement_purchase_order_id),
    FOREIGN KEY(replaced_purchase_order_id, organization_id)
      REFERENCES procurement_purchase_order(id, organization_id) ON DELETE RESTRICT,
    FOREIGN KEY(replacement_purchase_order_id, organization_id)
      REFERENCES procurement_purchase_order(id, organization_id) ON DELETE RESTRICT
);

CREATE FUNCTION guard_procurement_po_supersession_insert()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    old_po procurement_purchase_order%ROWTYPE;
    new_po procurement_purchase_order%ROWTYPE;
BEGIN
    -- Order-independent lock acquisition avoids concurrent replacement races.
    PERFORM id FROM procurement_purchase_order
      WHERE id IN (NEW.replaced_purchase_order_id, NEW.replacement_purchase_order_id)
      ORDER BY id FOR UPDATE;
    SELECT * INTO old_po FROM procurement_purchase_order
      WHERE id=NEW.replaced_purchase_order_id;
    SELECT * INTO new_po FROM procurement_purchase_order
      WHERE id=NEW.replacement_purchase_order_id;
    IF old_po.id IS NULL OR new_po.id IS NULL
       OR old_po.organization_id <> NEW.organization_id
       OR new_po.organization_id <> NEW.organization_id
       OR old_po.stock_item_id <> new_po.stock_item_id
       OR NOT EXISTS(
          SELECT 1 FROM procurement_po_candidate oc
          JOIN procurement_po_candidate nc ON oc.requirement_id=nc.requirement_id
          WHERE oc.id=old_po.candidate_id AND nc.id=new_po.candidate_id
        )
       OR NOT EXISTS(
          SELECT 1 FROM procurement_po_lifecycle_event e
          WHERE e.purchase_order_id=old_po.id AND e.event_type='CANCELLED'
            AND e.event_version=NEW.replaced_effective_version
        )
       OR EXISTS(SELECT 1 FROM procurement_po_lifecycle_event e
                 WHERE e.purchase_order_id=new_po.id)
       OR EXISTS(SELECT 1 FROM procurement_po_supplier_communication_event e
                 WHERE e.purchase_order_id IN (old_po.id,new_po.id))
       OR EXISTS(SELECT 1 FROM procurement_po_receiving_link r
                 WHERE r.purchase_order_id IN (old_po.id,new_po.id))
    THEN
      RAISE EXCEPTION 'Replacement PO must be a new, unsent, independently approved order for same requirement'
        USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER procurement_po_supersession_insert_guard
BEFORE INSERT ON procurement_po_supersession_link
FOR EACH ROW EXECUTE FUNCTION guard_procurement_po_supersession_insert();
CREATE TRIGGER procurement_po_supersession_immutable
BEFORE UPDATE OR DELETE ON procurement_po_supersession_link
FOR EACH ROW EXECUTE FUNCTION reject_procurement_draft_mutation();
