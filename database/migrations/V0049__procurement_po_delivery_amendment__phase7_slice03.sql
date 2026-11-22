-- Phase 7 S03: controlled destination amendment, never an unapproved
-- price, quantity or supplier rewrite. Preserve original approved PO V1.
-- Each proposal and effective amendment event is immutable and version bound.

CREATE TABLE procurement_po_delivery_amendment_proposal (
    id uuid PRIMARY KEY,
    operation_id uuid NOT NULL UNIQUE,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    purchase_order_id uuid NOT NULL,
    base_order_version bigint NOT NULL CHECK (base_order_version >= 1),
    prior_storage_location_id uuid NOT NULL,
    proposed_storage_location_id uuid NOT NULL,
    reason varchar(1000) NOT NULL CHECK (length(btrim(reason)) BETWEEN 12 AND 1000),
    proposed_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    correlation_id varchar(128) NOT NULL CHECK (length(btrim(correlation_id)) > 0),
    CONSTRAINT fk_po_delivery_proposal_order_org
      FOREIGN KEY (purchase_order_id, organization_id)
      REFERENCES procurement_purchase_order(id, organization_id) ON DELETE RESTRICT,
    CONSTRAINT fk_po_delivery_proposal_old_location_org
      FOREIGN KEY (prior_storage_location_id, organization_id)
      REFERENCES storage_location(id, organization_id) ON DELETE RESTRICT,
    CONSTRAINT fk_po_delivery_proposal_new_location_org
      FOREIGN KEY (proposed_storage_location_id, organization_id)
      REFERENCES storage_location(id, organization_id) ON DELETE RESTRICT,
    CONSTRAINT uq_po_delivery_proposal_base
      UNIQUE (purchase_order_id, base_order_version),
    CONSTRAINT ck_po_delivery_changed_destination
      CHECK (prior_storage_location_id <> proposed_storage_location_id)
);

CREATE UNIQUE INDEX uq_po_delivery_proposal_id_org
  ON procurement_po_delivery_amendment_proposal(id, organization_id);
CREATE UNIQUE INDEX uq_po_delivery_proposal_scope
  ON procurement_po_delivery_amendment_proposal (
    id, organization_id, purchase_order_id, proposed_storage_location_id
  );

CREATE TRIGGER procurement_po_delivery_proposal_immutable
BEFORE UPDATE OR DELETE ON procurement_po_delivery_amendment_proposal
FOR EACH ROW EXECUTE FUNCTION reject_procurement_draft_mutation();

-- Keep cancellation unique but allow successive approved delivery amendments.
ALTER TABLE procurement_po_lifecycle_event
  DROP CONSTRAINT uq_po_lifecycle_terminal_cancel;
CREATE UNIQUE INDEX uq_po_lifecycle_one_cancellation
  ON procurement_po_lifecycle_event(purchase_order_id)
  WHERE event_type = 'CANCELLED';

ALTER TABLE procurement_po_lifecycle_event
  DROP CONSTRAINT procurement_po_lifecycle_event_event_type_check;
ALTER TABLE procurement_po_lifecycle_event
  ADD CONSTRAINT ck_po_lifecycle_event_type
  CHECK (event_type IN ('CANCELLED', 'DELIVERY_AMENDED'));

ALTER TABLE procurement_po_lifecycle_event
  ADD COLUMN approved_amendment_proposal_id uuid NULL,
  ADD COLUMN approval_request_id uuid NULL,
  ADD COLUMN delivery_storage_location_id uuid NULL,
  ADD CONSTRAINT fk_po_lifecycle_approved_proposal_scope
    FOREIGN KEY (
      approved_amendment_proposal_id, organization_id,
      purchase_order_id, delivery_storage_location_id
    )
    REFERENCES procurement_po_delivery_amendment_proposal (
      id, organization_id, purchase_order_id, proposed_storage_location_id
    ) ON DELETE RESTRICT,
  ADD CONSTRAINT fk_po_lifecycle_approval_request_org
    FOREIGN KEY (approval_request_id, organization_id)
    REFERENCES approval_request(id, organization_id) ON DELETE RESTRICT,
  ADD CONSTRAINT fk_po_lifecycle_new_destination_org
    FOREIGN KEY (delivery_storage_location_id, organization_id)
    REFERENCES storage_location(id, organization_id) ON DELETE RESTRICT,
  ADD CONSTRAINT ck_po_lifecycle_specific_payload
    CHECK (
      (event_type = 'CANCELLED' AND approved_amendment_proposal_id IS NULL
        AND approval_request_id IS NULL AND delivery_storage_location_id IS NULL)
      OR
      (event_type = 'DELIVERY_AMENDED' AND approved_amendment_proposal_id IS NOT NULL
        AND approval_request_id IS NOT NULL AND delivery_storage_location_id IS NOT NULL)
    );

CREATE UNIQUE INDEX uq_po_lifecycle_applied_proposal
  ON procurement_po_lifecycle_event(approved_amendment_proposal_id)
  WHERE approved_amendment_proposal_id IS NOT NULL;
