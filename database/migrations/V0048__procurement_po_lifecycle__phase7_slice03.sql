-- Phase 7 S03 — immutable post-issue lifecycle; never mutate a signed PO.
-- Cancellation can only be recorded while a PO has not been sent or received.
CREATE UNIQUE INDEX uq_procurement_purchase_order_id_org
  ON procurement_purchase_order(id, organization_id);

CREATE TABLE procurement_po_lifecycle_event (
    id uuid PRIMARY KEY,
    operation_id uuid NOT NULL UNIQUE,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    purchase_order_id uuid NOT NULL,
    event_version bigint NOT NULL CHECK (event_version >= 2),
    event_type varchar(32) NOT NULL CHECK (event_type IN ('CANCELLED')),
    reason varchar(1000) NOT NULL CHECK (length(btrim(reason)) BETWEEN 12 AND 1000),
    recorded_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    recorded_at timestamptz NOT NULL,
    correlation_id varchar(128) NOT NULL CHECK (length(btrim(correlation_id)) > 0),
    CONSTRAINT fk_po_lifecycle_org
      FOREIGN KEY (purchase_order_id, organization_id)
      REFERENCES procurement_purchase_order(id, organization_id) ON DELETE RESTRICT,
    CONSTRAINT uq_po_lifecycle_version UNIQUE (purchase_order_id, event_version),
    CONSTRAINT uq_po_lifecycle_terminal_cancel UNIQUE (purchase_order_id, event_type)
);

CREATE INDEX idx_po_lifecycle_company_time
  ON procurement_po_lifecycle_event (organization_id, recorded_at DESC, id DESC);

CREATE TRIGGER procurement_po_lifecycle_immutable
BEFORE UPDATE OR DELETE ON procurement_po_lifecycle_event
FOR EACH ROW EXECUTE FUNCTION reject_procurement_draft_mutation();
