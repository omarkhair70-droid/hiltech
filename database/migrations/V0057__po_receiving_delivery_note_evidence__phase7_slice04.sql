-- Phase 7 / S04: canonical, checksum-verified delivery note on real PO receipts.
-- Never trust receiving_record.delivery_document_ref alone as binary Evidence.
-- Multiple quarantined/rejected attempts are allowed; only one READY note.
CREATE UNIQUE INDEX uq_po_receiving_ready_delivery_note
    ON evidence(organization_id, target_id)
    WHERE target_type='PO_RECEIVING_DELIVERY_NOTE'
      AND storage_state='READY';

CREATE FUNCTION guard_po_delivery_note_evidence_insert()
RETURNS trigger LANGUAGE plpgsql AS $po_delivery_note$
DECLARE
    rec receiving_record%ROWTYPE;
BEGIN
    IF NEW.target_type <> 'PO_RECEIVING_DELIVERY_NOTE' THEN RETURN NEW; END IF;
    SELECT * INTO rec FROM receiving_record WHERE id=NEW.target_id;
    IF rec.id IS NULL OR rec.organization_id <> NEW.organization_id
      OR rec.source_type <> 'PO'
      OR rec.state NOT IN ('DRAFT','IN_PROGRESS')
      OR NEW.evidence_requirement_key <> 'PO_DELIVERY_NOTE_BINARY'
      OR NEW.evidence_type_code <> 'PO_DELIVERY_NOTE'
      OR NEW.classification_code <> 'HIGHLY_RESTRICTED'
      OR NEW.client_visibility_mode <> 'INTERNAL_ONLY'
      OR NEW.work_order_id IS NOT NULL
      OR NOT EXISTS (
        SELECT 1 FROM procurement_po_receiving_link b
        WHERE b.receiving_record_id=rec.id
          AND b.organization_id=rec.organization_id
          AND b.destination_storage_location_id=rec.destination_storage_location_id
      )
    THEN
       RAISE EXCEPTION 'PO delivery note requires real company-bound Receiving and restricted Evidence'
         USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$po_delivery_note$;
CREATE TRIGGER po_receiving_note_evidence_insert_guard
BEFORE INSERT ON evidence
FOR EACH ROW
WHEN (NEW.target_type='PO_RECEIVING_DELIVERY_NOTE')
EXECUTE FUNCTION guard_po_delivery_note_evidence_insert();

CREATE FUNCTION guard_po_receiving_close_requires_delivery_note()
RETURNS trigger LANGUAGE plpgsql AS $po_delivery_note_close$
BEGIN
    IF NEW.state IN ('RECEIVED','RECEIVED_WITH_DISCREPANCY')
      AND OLD.state <> NEW.state
      AND EXISTS (
        SELECT 1 FROM procurement_po_receiving_link b
        WHERE b.receiving_record_id=NEW.id
          AND b.organization_id=NEW.organization_id
      )
      AND NOT EXISTS (
        SELECT 1 FROM evidence e
        WHERE e.organization_id=NEW.organization_id
          AND e.target_type='PO_RECEIVING_DELIVERY_NOTE'
          AND e.target_id=NEW.id
          AND e.evidence_requirement_key='PO_DELIVERY_NOTE_BINARY'
          AND e.evidence_type_code='PO_DELIVERY_NOTE'
          AND e.storage_state='READY'
          AND e.classification_code='HIGHLY_RESTRICTED'
          AND e.client_visibility_mode='INTERNAL_ONLY'
      )
    THEN
       RAISE EXCEPTION 'Cannot close PO receipt without READY canonical delivery note Evidence'
         USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$po_delivery_note_close$;
CREATE TRIGGER po_receiving_close_delivery_note_guard
BEFORE UPDATE ON receiving_record
FOR EACH ROW
WHEN (NEW.state IN ('RECEIVED','RECEIVED_WITH_DISCREPANCY'))
EXECUTE FUNCTION guard_po_receiving_close_requires_delivery_note();
