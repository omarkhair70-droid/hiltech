-- Finance clearance for an exact corrected payroll draft. A reviewer attests
-- the documented absence of earlier transfers, or the documented returned
-- transfers. A missing match alone is not proof that no transfer occurred.
-- Settled differences/voided bank matches stay blocked, not full re-payments.
CREATE FUNCTION finance_payroll_prior_disposition(org uuid,replacement uuid) RETURNS varchar
LANGUAGE plpgsql STABLE AS $$
DECLARE run payroll_run_version%ROWTYPE;
BEGIN
 SELECT * INTO run FROM payroll_run_version WHERE organization_id=org AND id=replacement;
 IF run.id IS NULL THEN RETURN NULL; END IF;
 IF NOT EXISTS(SELECT 1 FROM finance_payroll_instruction i
     JOIN payroll_run_version older ON older.organization_id=i.organization_id AND older.id=i.run_version_id
     WHERE i.organization_id=org AND older.payroll_period_id=run.payroll_period_id AND older.generation<run.generation
       AND (EXISTS(SELECT 1 FROM finance_payroll_result_event e WHERE e.organization_id=org AND e.instruction_id=i.id)
         OR EXISTS(SELECT 1 FROM finance_payroll_result_proposal p WHERE p.organization_id=org AND p.instruction_id=i.id)))
 THEN RETURN 'NO_PRIOR_TRANSFER_VERIFIED'; END IF;
 -- Every older recorded successful transfer must have an actual immediately
 -- following return, and both accounting entries must be posted. A void only
 -- changes a match, and does not prove that funds returned.
 IF NOT EXISTS(SELECT 1 FROM finance_payroll_result_event e
     JOIN finance_payroll_instruction i ON i.organization_id=e.organization_id AND i.id=e.instruction_id
     JOIN payroll_run_version older ON older.organization_id=i.organization_id AND older.id=i.run_version_id
     WHERE e.organization_id=org AND older.payroll_period_id=run.payroll_period_id AND older.generation<run.generation
       AND e.action='RETURNED')
 OR EXISTS(SELECT 1 FROM finance_payroll_result_event e
     JOIN finance_payroll_instruction i ON i.organization_id=e.organization_id AND i.id=e.instruction_id
     JOIN payroll_run_version older ON older.organization_id=i.organization_id AND older.id=i.run_version_id
     WHERE e.organization_id=org AND older.payroll_period_id=run.payroll_period_id AND older.generation<run.generation
       AND (e.action NOT IN('SUCCESS','RETURNED') OR
         (e.action='SUCCESS' AND NOT EXISTS(
           SELECT 1 FROM finance_payroll_result_event returned
           JOIN finance_payroll_gl_journal payment ON payment.organization_id=e.organization_id
               AND payment.source_event_id=e.id AND payment.entry_type='PAYROLL_PAYMENT'
           JOIN finance_payroll_gl_journal refund ON refund.organization_id=returned.organization_id
               AND refund.source_event_id=returned.id AND refund.original_journal_id=payment.id
               AND refund.entry_type='PAYROLL_RETURN'
           WHERE returned.organization_id=e.organization_id AND returned.instruction_id=e.instruction_id
             AND returned.version=e.version+1 AND returned.action='RETURNED' AND returned.outcome='RETURNED'))
         OR (e.action='RETURNED' AND NOT EXISTS(SELECT 1 FROM finance_payroll_gl_journal refund
             WHERE refund.organization_id=e.organization_id AND refund.source_event_id=e.id AND refund.entry_type='PAYROLL_RETURN'))))
 OR EXISTS(SELECT 1 FROM finance_payroll_result_proposal p
     JOIN finance_payroll_instruction i ON i.organization_id=p.organization_id AND i.id=p.instruction_id
     JOIN payroll_run_version older ON older.organization_id=i.organization_id AND older.id=i.run_version_id
     WHERE p.organization_id=org AND older.payroll_period_id=run.payroll_period_id AND older.generation<run.generation
       AND NOT EXISTS(SELECT 1 FROM finance_payroll_result_event e WHERE e.organization_id=org AND e.proposal_id=p.id)
       AND NOT EXISTS(SELECT 1 FROM approval_request a WHERE a.organization_id=org AND a.subject_id=p.id
           AND a.subject_type='FINANCE_PAYROLL_RESULT' AND a.subject_version=p.review_version AND a.state='REJECTED'))
 THEN RETURN NULL; END IF;
 RETURN 'REVERSAL_VERIFIED';
END; $$;

CREATE OR REPLACE VIEW finance_payroll_gl_correction_source AS
 SELECT c.organization_id,c.id AS correction_id,c.replacement_run_version_id,c.original_run_version_id,
   c.finance_clearance_ref,c.finance_clearance_version,original.id AS original_journal_id,original.posted_at AS original_posted_at,c.finance_disposition
 FROM payroll_run_correction c
 JOIN payroll_run_version newer ON newer.organization_id=c.organization_id AND newer.id=c.replacement_run_version_id
 JOIN payroll_run_version older ON older.organization_id=c.organization_id AND older.id=c.original_run_version_id
 JOIN payroll_run_release published ON published.organization_id=c.organization_id AND published.run_version_id=newer.id
 JOIN payroll_run_release previous ON previous.organization_id=c.organization_id AND previous.id=c.original_release_id
    AND previous.run_version_id=older.id
 LEFT JOIN finance_payroll_gl_journal original ON original.organization_id=c.organization_id
    AND original.run_version_id=older.id AND original.entry_type='PAYROLL_ACCRUAL'
 WHERE finance_payroll_prior_disposition(c.organization_id,c.replacement_run_version_id)=c.finance_disposition
   AND c.original_source_fingerprint=older.source_fingerprint AND c.replacement_source_fingerprint=newer.source_fingerprint
   AND published.run_source_fingerprint=newer.source_fingerprint AND previous.run_source_fingerprint=older.source_fingerprint
   AND older.currency=newer.currency AND older.payroll_period_id=newer.payroll_period_id AND older.generation<newer.generation
   AND NOT EXISTS(SELECT 1 FROM finance_payroll_gl_journal j
     WHERE j.organization_id=c.organization_id AND j.payroll_period_id=newer.payroll_period_id
       AND j.entry_type='PAYROLL_ACCRUAL' AND j.run_version_id<>newer.id
       AND j.id IS DISTINCT FROM original.id
       AND NOT EXISTS(SELECT 1 FROM finance_payroll_gl_journal replaced WHERE replaced.organization_id=j.organization_id
           AND replaced.entry_type='PAYROLL_ACCRUAL' AND replaced.original_journal_id=j.id))
   AND (original.id IS NULL OR NOT EXISTS(SELECT 1 FROM finance_payroll_gl_journal replaced
       WHERE replaced.organization_id=original.organization_id AND replaced.original_journal_id=original.id
          AND replaced.run_version_id<>newer.id));

CREATE VIEW finance_payroll_correction_candidate AS
 SELECT newer.organization_id,newer.id AS replacement_run_version_id,older.id AS original_run_version_id,
   newer.source_fingerprint AS replacement_fingerprint,older.source_fingerprint AS original_fingerprint,
   newer.generation AS replacement_generation,older.generation AS original_generation,
   newer.created_by_user_id AS draft_creator,period.start_date,period.end_date,
   newer.currency,newer.net AS replacement_net,older.net AS original_net,
   finance_payroll_prior_disposition(newer.organization_id,newer.id) AS eligible_disposition
 FROM payroll_run_version newer
 JOIN payroll_period period ON period.organization_id=newer.organization_id AND period.id=newer.payroll_period_id
 JOIN LATERAL (
   SELECT r.* FROM payroll_run_version r JOIN payroll_run_release pub ON pub.organization_id=r.organization_id AND pub.run_version_id=r.id
   WHERE r.organization_id=newer.organization_id AND r.payroll_period_id=newer.payroll_period_id AND r.generation<newer.generation
   ORDER BY r.generation DESC LIMIT 1
 ) older ON older.currency=newer.currency AND older.source_fingerprint<>newer.source_fingerprint
 WHERE newer.state='DRAFT'
   AND NOT EXISTS(SELECT 1 FROM payroll_run_version later WHERE later.organization_id=newer.organization_id
     AND later.payroll_period_id=newer.payroll_period_id AND later.generation>newer.generation)
   AND NOT EXISTS(SELECT 1 FROM payroll_run_release pub WHERE pub.organization_id=newer.organization_id AND pub.run_version_id=newer.id);

CREATE TABLE finance_payroll_correction_clearance (
 id uuid PRIMARY KEY, operation_id uuid NOT NULL UNIQUE,
 organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
 original_run_version_id uuid NOT NULL, replacement_run_version_id uuid NOT NULL,
 original_fingerprint char(64) NOT NULL, replacement_fingerprint char(64) NOT NULL,
 revision bigint NOT NULL CHECK(revision>0),
 disposition varchar(40) NOT NULL CHECK(disposition IN('NO_PRIOR_TRANSFER_VERIFIED','REVERSAL_VERIFIED')),
 reason_reference varchar(240) NOT NULL CHECK(length(btrim(reason_reference)) BETWEEN 8 AND 240 AND btrim(reason_reference)=reason_reference AND reason_reference !~ '[[:cntrl:]]'),
 finance_reference varchar(240) NOT NULL CHECK(length(btrim(finance_reference)) BETWEEN 8 AND 240 AND btrim(finance_reference)=finance_reference AND finance_reference !~ '[[:cntrl:]]'),
 reviewed_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
 reviewed_at timestamptz NOT NULL,
 UNIQUE(organization_id,replacement_run_version_id,revision),
 FOREIGN KEY(original_run_version_id,organization_id) REFERENCES payroll_run_version(id,organization_id) ON DELETE RESTRICT,
 FOREIGN KEY(replacement_run_version_id,organization_id) REFERENCES payroll_run_version(id,organization_id) ON DELETE RESTRICT,
 CHECK(original_run_version_id<>replacement_run_version_id AND original_fingerprint<>replacement_fingerprint)
);
CREATE TRIGGER finance_payroll_correction_clearance_immutable BEFORE UPDATE OR DELETE ON finance_payroll_correction_clearance
 FOR EACH ROW EXECUTE FUNCTION finance_ap_reject_snapshot_mutation();

CREATE FUNCTION finance_payroll_correction_clearance_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE c record; prior bigint;
BEGIN
 PERFORM 1 FROM payroll_period p JOIN payroll_run_version r ON r.payroll_period_id=p.id
 WHERE r.organization_id=NEW.organization_id AND r.id=NEW.replacement_run_version_id FOR UPDATE OF p;
 SELECT * INTO c FROM finance_payroll_correction_candidate v WHERE v.organization_id=NEW.organization_id
    AND v.replacement_run_version_id=NEW.replacement_run_version_id;
 SELECT coalesce(max(revision),0) INTO prior FROM finance_payroll_correction_clearance r
 WHERE r.organization_id=NEW.organization_id AND r.replacement_run_version_id=NEW.replacement_run_version_id;
 IF c.replacement_run_version_id IS NULL OR c.original_run_version_id<>NEW.original_run_version_id
   OR c.original_fingerprint<>NEW.original_fingerprint OR c.replacement_fingerprint<>NEW.replacement_fingerprint
   OR c.eligible_disposition IS DISTINCT FROM NEW.disposition OR NEW.revision<>prior+1
   OR c.draft_creator=NEW.reviewed_by_user_id
   OR NOT finance_payroll_current_authority(NEW.organization_id,NEW.reviewed_by_user_id,'REVIEW')
 THEN RAISE EXCEPTION 'Exact current correction and independent finance clearance required' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER finance_payroll_correction_clearance_guard BEFORE INSERT ON finance_payroll_correction_clearance
 FOR EACH ROW EXECUTE FUNCTION finance_payroll_correction_clearance_guard();

-- Do not prepare a full replacement payroll against an outstanding earlier
-- successful transfer or an unresolved accounting correction. Serialize with
-- the same period lock used by release and the successful result writer.
CREATE FUNCTION finance_payroll_replacement_payment_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE org uuid; run_id uuid;
BEGIN
 org:=NEW.organization_id;
 IF TG_TABLE_NAME='finance_payroll_payment_batch' THEN
   run_id:=NEW.run_version_id;
 ELSE
   IF NEW.action<>'SUCCESS' THEN RETURN NEW; END IF;
   SELECT i.run_version_id INTO run_id FROM finance_payroll_instruction i
     WHERE i.organization_id=org AND i.id=NEW.instruction_id FOR UPDATE;
 END IF;
 PERFORM 1 FROM payroll_period p JOIN payroll_run_version r ON r.payroll_period_id=p.id
   WHERE r.organization_id=org AND r.id=run_id FOR UPDATE OF p;
 IF NOT FOUND OR NOT EXISTS(
   SELECT 1 FROM payroll_run_version r JOIN payroll_run_release pub ON pub.organization_id=r.organization_id AND pub.run_version_id=r.id
   WHERE r.organization_id=org AND r.id=run_id AND NOT EXISTS(
     SELECT 1 FROM payroll_run_version newer JOIN payroll_run_release release ON release.organization_id=newer.organization_id AND release.run_version_id=newer.id
     WHERE newer.organization_id=r.organization_id AND newer.payroll_period_id=r.payroll_period_id AND newer.generation>r.generation))
 OR (EXISTS(SELECT 1 FROM payroll_run_correction c WHERE c.organization_id=org AND c.replacement_run_version_id=run_id)
   AND NOT EXISTS(SELECT 1 FROM finance_payroll_gl_correction_source c WHERE c.organization_id=org AND c.replacement_run_version_id=run_id))
 THEN RAISE EXCEPTION 'Earlier payment clearance is unresolved or payroll is superseded' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER finance_payroll_replacement_batch_guard BEFORE INSERT ON finance_payroll_payment_batch
 FOR EACH ROW EXECUTE FUNCTION finance_payroll_replacement_payment_guard();
CREATE TRIGGER finance_payroll_replacement_proposal_guard BEFORE INSERT ON finance_payroll_result_proposal
 FOR EACH ROW EXECUTE FUNCTION finance_payroll_replacement_payment_guard();
CREATE TRIGGER finance_payroll_replacement_result_guard BEFORE INSERT ON finance_payroll_result_event
 FOR EACH ROW EXECUTE FUNCTION finance_payroll_replacement_payment_guard();
