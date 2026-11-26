-- Phase 7 S04: explicit full rejection of a PO delivery line.
-- Rejected goods never enter StockMovement or StockBalance.
CREATE TABLE procurement_po_receiving_rejection_event (
    id uuid PRIMARY KEY,
    operation_id uuid NOT NULL UNIQUE,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    receiving_record_id uuid NOT NULL,
    receiving_line_id uuid NOT NULL UNIQUE,
    rejected_quantity numeric(20,6) NOT NULL CHECK(rejected_quantity > 0),
    rejection_type varchar(32) NOT NULL CHECK(rejection_type IN
      ('WRONG_ITEM','DAMAGED_ON_RECEIPT','SERIAL_MISMATCH','UNEXPECTED_ITEM','DOCUMENT_MISMATCH')),
    reason varchar(2000) NOT NULL CHECK(length(btrim(reason)) BETWEEN 12 AND 2000),
    rejected_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    rejected_at timestamptz NOT NULL,
    correlation_id varchar(128) NOT NULL CHECK(length(btrim(correlation_id)) > 0),
    FOREIGN KEY(receiving_record_id, organization_id)
      REFERENCES receiving_record(id, organization_id) ON DELETE RESTRICT,
    FOREIGN KEY(receiving_line_id, receiving_record_id, organization_id)
      REFERENCES receiving_line(id, receiving_record_id, organization_id) ON DELETE RESTRICT
);
CREATE INDEX idx_procurement_po_rejected_delivery
  ON procurement_po_receiving_rejection_event (receiving_record_id, rejected_at DESC);

CREATE FUNCTION guard_po_receiving_rejection_insert()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    rec receiving_record%ROWTYPE;
    line receiving_line%ROWTYPE;
BEGIN
    SELECT * INTO rec FROM receiving_record WHERE id=NEW.receiving_record_id FOR UPDATE;
    SELECT * INTO line FROM receiving_line WHERE id=NEW.receiving_line_id FOR UPDATE;
    IF rec.id IS NULL OR line.id IS NULL
        OR rec.organization_id <> NEW.organization_id
        OR rec.source_type <> 'PO'
        OR rec.state <> 'IN_PROGRESS'
        OR line.receiving_record_id <> rec.id
        OR line.received_at IS NOT NULL
        OR line.candidate_class NOT IN ('STOCK_QUANTITY','STOCK_SERIALIZED')
        OR (line.expected_quantity IS NOT NULL AND NEW.rejected_quantity > line.expected_quantity)
        OR NOT EXISTS (
          SELECT 1 FROM procurement_po_receiving_link b
          WHERE b.receiving_record_id=rec.id AND b.organization_id=NEW.organization_id
        )
    THEN
        RAISE EXCEPTION 'PO line must be active and unresolved to reject physical delivery'
          USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER procurement_po_receiving_rejection_insert_guard
BEFORE INSERT ON procurement_po_receiving_rejection_event
FOR EACH ROW EXECUTE FUNCTION guard_po_receiving_rejection_insert();
CREATE TRIGGER procurement_po_receiving_rejection_immutable
BEFORE UPDATE OR DELETE ON procurement_po_receiving_rejection_event
FOR EACH ROW EXECUTE FUNCTION reject_procurement_draft_mutation();

-- A rejected line cannot subsequently be posted to canonical StockLedger.
CREATE FUNCTION guard_rejected_po_line_physical_receipt()
RETURNS trigger LANGUAGE plpgsql AS $po_rejection$
BEGIN
    IF EXISTS (
        SELECT 1 FROM procurement_po_receiving_rejection_event x
        WHERE x.receiving_line_id=OLD.id
    ) THEN
        RAISE EXCEPTION 'An explicitly rejected PO delivery line cannot be physically received'
          USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$po_rejection$;
CREATE TRIGGER receiving_rejected_po_line_receipt_guard
BEFORE UPDATE ON receiving_line
FOR EACH ROW
WHEN (NEW.received_at IS NOT NULL AND OLD.received_at IS NULL)
EXECUTE FUNCTION guard_rejected_po_line_physical_receipt();

-- Only the canonical receiving-record completion guard is extended, not
-- historic migrations, stock-movement code or the old physical ledger.
CREATE OR REPLACE FUNCTION guard_receiving_record_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Receiving history is append-only' USING ERRCODE = '23514';
    END IF;
    IF OLD.state IN ('RECEIVED','RECEIVED_WITH_DISCREPANCY','CANCELLED')
        OR NEW.version <> OLD.version + 1
        OR (to_jsonb(NEW) - ARRAY['state','received_by','received_at','updated_at','version'])
           IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['state','received_by','received_at','updated_at','version'])
        OR NOT (
            (OLD.state = 'DRAFT' AND NEW.state IN ('IN_PROGRESS','CANCELLED'))
            OR (OLD.state = 'IN_PROGRESS' AND NEW.state IN ('IN_PROGRESS','RECEIVED','RECEIVED_WITH_DISCREPANCY','CANCELLED'))
        ) THEN
        RAISE EXCEPTION 'Invalid Receiving state transition' USING ERRCODE = '23514';
    END IF;
    IF NEW.state = 'CANCELLED' AND EXISTS (
        SELECT 1 FROM receiving_line WHERE receiving_record_id = OLD.id AND received_at IS NOT NULL
        UNION ALL
        SELECT 1 FROM procurement_po_receiving_rejection_event WHERE receiving_record_id=OLD.id
    ) THEN
        RAISE EXCEPTION 'A Receiving record with physical receipts cannot be cancelled' USING ERRCODE = '23514';
    END IF;
    IF NEW.state IN ('RECEIVED','RECEIVED_WITH_DISCREPANCY') THEN
        IF NOT EXISTS (SELECT 1 FROM receiving_line WHERE receiving_record_id = OLD.id)
            OR EXISTS (
                SELECT 1 FROM receiving_line l
                WHERE l.receiving_record_id=OLD.id
                  AND l.received_at IS NULL
                  AND NOT EXISTS (
                    SELECT 1 FROM procurement_po_receiving_rejection_event x
                    WHERE x.receiving_line_id=l.id
                  )
            ) THEN
            RAISE EXCEPTION 'Receiving completion requires physical receipt or explicit rejected delivery on every line'
              USING ERRCODE = '23514';
        END IF;
        IF (NEW.state = 'RECEIVED_WITH_DISCREPANCY') <>
           EXISTS (SELECT 1 FROM receiving_discrepancy WHERE receiving_record_id = OLD.id) THEN
            RAISE EXCEPTION 'Receiving completion state must match discrepancy truth' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

