-- Phase 7 / S04 integrity correction: a "reject line" command
-- resolves exactly one entire inspected line. Partial rejection may be
-- represented by separate receiving lines/records, never by marking the
-- unresolved remainder as if it were fully rejected.
CREATE FUNCTION enforce_full_po_receiving_rejection()
RETURNS trigger LANGUAGE plpgsql AS $full_po_rejection$
DECLARE expected_qty numeric(20,6);
BEGIN
    SELECT expected_quantity INTO expected_qty
      FROM receiving_line WHERE id=NEW.receiving_line_id FOR SHARE;
    IF expected_qty IS NULL OR NEW.rejected_quantity <> expected_qty THEN
        RAISE EXCEPTION 'PO full-line rejection must equal expected inspected quantity'
          USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$full_po_rejection$;
CREATE TRIGGER po_rejection_full_line_guard
BEFORE INSERT ON procurement_po_receiving_rejection_event
FOR EACH ROW EXECUTE FUNCTION enforce_full_po_receiving_rejection();
