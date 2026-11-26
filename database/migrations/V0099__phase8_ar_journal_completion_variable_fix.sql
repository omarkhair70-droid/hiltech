-- Phase 8 original receivables: fix a verified PostgreSQL exception:
-- unqualified "journal_id" in the completion check resolved ambiguously
-- to both a PL/pgSQL variable and finance_ar_posted_journal_line.journal_id.
-- V0086 is immutable; this forward migration replaces only the function,
-- without changing its independent accounting/approval constraints.
CREATE OR REPLACE FUNCTION finance_ar_posted_complete_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target_journal_id uuid;
        j finance_ar_posted_journal%ROWTYPE;
        sums record;
        expected bigint;
BEGIN
 IF TG_TABLE_NAME='finance_ar_posted_journal' THEN
   target_journal_id:=NEW.id;
 ELSE
   target_journal_id:=NEW.journal_id;
 END IF;
 SELECT * INTO j FROM finance_ar_posted_journal
 WHERE id=target_journal_id;
 IF NOT FOUND THEN RETURN NULL; END IF;
 SELECT COUNT(*) AS n,
   COALESCE(SUM(line.amount) FILTER (WHERE line.side='DEBIT'),0) AS dr,
   COALESCE(SUM(line.amount) FILTER (WHERE line.side='CREDIT'),0) AS cr
 INTO sums
 FROM finance_ar_posted_journal_line AS line
 WHERE line.journal_id=j.id;
 SELECT COUNT(*) INTO expected
 FROM finance_ar_posting_proposal_line AS source_line
 WHERE source_line.proposal_id=j.proposal_id;
 IF sums.n<>expected OR sums.dr<>sums.cr OR sums.dr<>j.gross_amount THEN
   RAISE EXCEPTION 'Incomplete or unbalanced AR journal posting'
     USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END; $$;
