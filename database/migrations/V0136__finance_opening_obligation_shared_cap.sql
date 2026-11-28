-- The existing after-insert cross-channel guard must use the same locked
-- real obligation as the before-insert guards. Retain the combined bank and
-- physical-cash cap for both ordinary and cutover obligations.
CREATE OR REPLACE FUNCTION finance_cash_bank_source_cap_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE src_amount numeric(24,6);
        bank_amount numeric(24,6);
        cash_amount numeric(24,6);
BEGIN
 SELECT gross_amount INTO src_amount
 FROM finance_lock_obligation_source(NEW.organization_id,NEW.source_kind,NEW.source_journal_id);
 SELECT COALESCE(SUM(allocated_amount),0) INTO bank_amount
  FROM finance_cash_allocation_effective
  WHERE organization_id=NEW.organization_id AND source_kind=NEW.source_kind
    AND source_journal_id=NEW.source_journal_id;
 SELECT COALESCE(SUM(amount),0) INTO cash_amount
  FROM finance_cash_voucher_record
  WHERE organization_id=NEW.organization_id AND source_kind=NEW.source_kind
    AND source_journal_id=NEW.source_journal_id;
 IF src_amount IS NULL OR bank_amount+cash_amount>src_amount THEN
  RAISE EXCEPTION 'Source invoice cannot be collected/paid twice across bank and cash'
    USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$;
