-- Positive settled differentials only. A correction can be released and prepared
-- only against an exact, independently reviewed, immutable settlement source.
-- Excess payments, unresolved/missing evidence and changed allocation treatments
-- still fail closed. No bank execution is introduced by this migration.
CREATE VIEW finance_payroll_settled_delta_run_pair AS
 SELECT newer.organization_id,newer.id AS replacement_run_version_id,older.id AS original_run_version_id,
   newer.source_fingerprint AS replacement_fingerprint,older.source_fingerprint AS original_fingerprint,newer.currency
 FROM payroll_run_version newer
 JOIN LATERAL (
   SELECT r.* FROM payroll_run_version r JOIN payroll_run_release pub ON pub.organization_id=r.organization_id AND pub.run_version_id=r.id
   WHERE r.organization_id=newer.organization_id AND r.payroll_period_id=newer.payroll_period_id AND r.generation<newer.generation
   ORDER BY r.generation DESC LIMIT 1
 ) older ON older.currency=newer.currency AND older.source_fingerprint<>newer.source_fingerprint
 WHERE (newer.state='DRAFT'
   AND NOT EXISTS(SELECT 1 FROM payroll_run_version later WHERE later.organization_id=newer.organization_id
     AND later.payroll_period_id=newer.payroll_period_id AND later.generation>newer.generation)
   AND NOT EXISTS(SELECT 1 FROM payroll_run_release pub WHERE pub.organization_id=newer.organization_id AND pub.run_version_id=newer.id))
 OR EXISTS(SELECT 1 FROM payroll_run_correction c JOIN payroll_run_release pub
      ON pub.organization_id=c.organization_id AND pub.run_version_id=c.replacement_run_version_id
    WHERE c.organization_id=newer.organization_id AND c.replacement_run_version_id=newer.id
      AND c.original_run_version_id=older.id AND c.finance_disposition='SETTLED_DIFFERENTIAL_AUTHORIZED'
      AND c.original_source_fingerprint=older.source_fingerprint AND c.replacement_source_fingerprint=newer.source_fingerprint);

CREATE OR REPLACE VIEW finance_payroll_correction_employee_review AS
 SELECT v.organization_id,v.original_run_version_id,v.replacement_run_version_id,v.currency,
   people.employee_id,e.employee_code,p.display_name AS employee_name,
   coalesce(old.net,0::numeric) AS original_net,coalesce(newer.net,0::numeric) AS replacement_net,
   coalesce(newer.net,0::numeric)-coalesce(old.net,0::numeric) AS entitlement_delta,
   facts.posted_payments,facts.posted_returns,facts.pending_count,facts.unposted_count,facts.void_count,facts.no_result_count,
   CASE WHEN flags.unresolved THEN NULL ELSE facts.posted_payments-facts.posted_returns END AS recorded_net,
   CASE WHEN flags.unresolved THEN NULL ELSE coalesce(newer.net,0::numeric)-facts.posted_payments+facts.posted_returns END AS difference_from_recorded,
   CASE WHEN flags.unresolved THEN 'UNRESOLVED'
        WHEN facts.posted_payments>facts.posted_returns THEN 'RECORDED_NET_PAYMENT'
        WHEN facts.posted_payments>0 THEN 'RECORDED_RETURN'
        ELSE 'NO_RECORDED_TRANSFER' END AS evidence_state
 FROM finance_payroll_settled_delta_run_pair v
 JOIN payroll_run_version replacement ON replacement.organization_id=v.organization_id AND replacement.id=v.replacement_run_version_id
 CROSS JOIN LATERAL (
   SELECT l.employee_id FROM payroll_run_employee_line l WHERE l.run_version_id=v.original_run_version_id
   UNION SELECT l.employee_id FROM payroll_run_employee_line l WHERE l.run_version_id=v.replacement_run_version_id
   UNION SELECT i.employee_id FROM finance_payroll_instruction i
     JOIN payroll_run_version r ON r.organization_id=i.organization_id AND r.id=i.run_version_id
     WHERE i.organization_id=v.organization_id AND r.payroll_period_id=replacement.payroll_period_id
       AND r.generation<replacement.generation
 ) people
 JOIN employee e ON e.organization_id=v.organization_id AND e.id=people.employee_id
 JOIN person p ON p.id=e.person_id
 LEFT JOIN payroll_run_employee_line old ON old.run_version_id=v.original_run_version_id AND old.employee_id=e.id
 LEFT JOIN payroll_run_employee_line newer ON newer.run_version_id=v.replacement_run_version_id AND newer.employee_id=e.id
 CROSS JOIN LATERAL (
   SELECT coalesce(sum(i.posted_payments),0::numeric) AS posted_payments,
     coalesce(sum(i.posted_returns),0::numeric) AS posted_returns,
     coalesce(sum(i.pending_count),0)::bigint AS pending_count,
     coalesce(sum(i.unposted_count),0)::bigint AS unposted_count,
     coalesce(sum(i.void_count),0)::bigint AS void_count,
     coalesce(sum(i.no_result_count),0)::bigint AS no_result_count,
     count(*) FILTER(WHERE i.currency<>v.currency) AS currency_conflicts
   FROM finance_payroll_instruction_review i
   JOIN payroll_run_version r ON r.organization_id=i.organization_id AND r.id=i.run_version_id
   WHERE i.organization_id=v.organization_id AND i.employee_id=e.id
     AND r.payroll_period_id=replacement.payroll_period_id AND r.generation<replacement.generation
 ) facts
 CROSS JOIN LATERAL (
   SELECT facts.pending_count>0 OR facts.unposted_count>0 OR facts.void_count>0
     OR facts.currency_conflicts>0 OR facts.posted_returns>facts.posted_payments AS unresolved
 ) flags;

CREATE OR REPLACE VIEW finance_payroll_settled_delta_basis AS
 WITH classified AS (
   SELECT r.organization_id,r.original_run_version_id,r.replacement_run_version_id,
     c.original_fingerprint,c.replacement_fingerprint,r.employee_id,r.currency,
     j.id AS original_journal_id,r.original_net,r.replacement_net,r.recorded_net,
     finance_payroll_settled_delta_state(
       r.original_net,r.replacement_net,r.recorded_net,r.posted_payments,r.no_result_count,
       j.id IS NOT NULL AND NOT EXISTS(
         SELECT 1 FROM finance_payroll_gl_journal replaced
         WHERE replaced.organization_id=j.organization_id
           AND replaced.original_journal_id=j.id AND replaced.entry_type='PAYROLL_ACCRUAL'
           AND replaced.run_version_id<>r.replacement_run_version_id)
     ) AS basis_state
   FROM finance_payroll_correction_employee_review r
   JOIN finance_payroll_settled_delta_run_pair c
     ON c.organization_id=r.organization_id
     AND c.original_run_version_id=r.original_run_version_id
     AND c.replacement_run_version_id=r.replacement_run_version_id
   LEFT JOIN finance_payroll_gl_journal j
     ON j.organization_id=r.organization_id AND j.run_version_id=r.original_run_version_id
     AND j.entry_type='PAYROLL_ACCRUAL' AND j.currency=r.currency
 )
 SELECT *,
   CASE WHEN basis_state='ADDITIONAL_DUE' THEN replacement_net-original_net
        WHEN basis_state IN('RECOVERY_DUE','NO_NET_CHANGE') THEN 0::numeric END AS additional_due,
   CASE WHEN basis_state='RECOVERY_DUE' THEN original_net-replacement_net
        WHEN basis_state IN('ADDITIONAL_DUE','NO_NET_CHANGE') THEN 0::numeric END AS recovery_due,
   bool_and(basis_state IN('ADDITIONAL_DUE','RECOVERY_DUE','NO_NET_CHANGE'))
     OVER(PARTITION BY organization_id,replacement_run_version_id) AS all_employees_ready
 FROM classified;

-- Fingerprint includes the employee amounts, exact previous journal and
-- immutable prior result evidence. Later source changes invalidate clearance.
CREATE FUNCTION finance_payroll_settled_delta_fingerprint(org uuid,replacement uuid)
 RETURNS text LANGUAGE sql STABLE AS $$
 SELECT encode(sha256(convert_to(jsonb_build_object(
   'schema','SETTLED_PAYROLL_POSITIVE_V1','plan',to_jsonb(p),
   'employees',(SELECT jsonb_agg(to_jsonb(b) ORDER BY b.employee_id)
     FROM finance_payroll_settled_delta_basis b
     WHERE b.organization_id=org AND b.replacement_run_version_id=replacement),
   'priorEvidence',(SELECT jsonb_agg(jsonb_build_object('instruction',i.id,'event',e.id,'version',e.version,
       'action',e.action,'outcome',e.outcome,'amount',e.amount,'currency',e.currency,
       'bankLine',e.bank_statement_line_id,'evidence',e.evidence_id,'evidenceSha256',e.evidence_sha256,
       'corrects',e.corrects_event_id,'journal',j.id) ORDER BY i.id,e.id)
     FROM finance_payroll_instruction i
     JOIN payroll_run_version r ON r.organization_id=i.organization_id AND r.id=i.run_version_id
     JOIN payroll_run_version newer ON newer.organization_id=r.organization_id AND newer.id=replacement
     JOIN finance_payroll_result_event e ON e.organization_id=i.organization_id AND e.instruction_id=i.id
     JOIN finance_payroll_gl_journal j ON j.organization_id=e.organization_id AND j.source_event_id=e.id
     WHERE i.organization_id=org AND r.payroll_period_id=newer.payroll_period_id AND r.generation<newer.generation)
 )::text,'UTF8')),'hex')
 FROM finance_payroll_settled_delta_plan p
 WHERE p.organization_id=org AND p.replacement_run_version_id=replacement
   AND p.plan_state='REVIEW_READY' AND p.additional_due>0 AND p.recovery_due=0
$$;

ALTER TABLE finance_payroll_correction_clearance DROP CONSTRAINT finance_payroll_correction_clearance_disposition_check;
ALTER TABLE finance_payroll_correction_clearance ADD CONSTRAINT finance_payroll_correction_clearance_disposition_check
 CHECK(disposition IN('NO_PRIOR_TRANSFER_VERIFIED','REVERSAL_VERIFIED','SETTLED_DIFFERENTIAL_AUTHORIZED'));
ALTER TABLE finance_payroll_correction_clearance ADD COLUMN settlement_fingerprint char(64);
ALTER TABLE finance_payroll_correction_clearance ADD CONSTRAINT finance_payroll_correction_settlement_fingerprint_check
 CHECK((disposition='SETTLED_DIFFERENTIAL_AUTHORIZED')=(settlement_fingerprint IS NOT NULL)
   AND (settlement_fingerprint IS NULL OR settlement_fingerprint ~ '^[a-f0-9]{64}$'));

CREATE OR REPLACE FUNCTION finance_payroll_prior_disposition(org uuid,replacement uuid) RETURNS varchar
LANGUAGE plpgsql STABLE AS $$
DECLARE run payroll_run_version%ROWTYPE;
BEGIN
 SELECT * INTO run FROM payroll_run_version WHERE organization_id=org AND id=replacement;
 IF run.id IS NULL THEN RETURN NULL; END IF;
 IF finance_payroll_settled_delta_fingerprint(org,replacement) IS NOT NULL THEN RETURN 'SETTLED_DIFFERENTIAL_AUTHORIZED'; END IF;
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

CREATE OR REPLACE FUNCTION finance_payroll_correction_clearance_guard() RETURNS trigger LANGUAGE plpgsql AS $$
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
   OR (NEW.disposition='SETTLED_DIFFERENTIAL_AUTHORIZED' AND
       (NEW.settlement_fingerprint IS NULL OR NEW.settlement_fingerprint IS DISTINCT FROM finance_payroll_settled_delta_fingerprint(NEW.organization_id,NEW.replacement_run_version_id)))
   OR c.draft_creator=NEW.reviewed_by_user_id
   OR NOT finance_payroll_current_authority(NEW.organization_id,NEW.reviewed_by_user_id,'REVIEW')
 THEN RAISE EXCEPTION 'Exact current correction and independent finance clearance required' USING ERRCODE='23514'; END IF;
 RETURN NEW;
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
   AND (c.finance_disposition<>'SETTLED_DIFFERENTIAL_AUTHORIZED' OR EXISTS(
     SELECT 1 FROM finance_payroll_correction_clearance clearance
     WHERE clearance.organization_id=c.organization_id
       AND clearance.original_run_version_id=c.original_run_version_id
       AND clearance.replacement_run_version_id=c.replacement_run_version_id
       AND clearance.disposition=c.finance_disposition
       AND clearance.original_fingerprint=c.original_source_fingerprint
       AND clearance.replacement_fingerprint=c.replacement_source_fingerprint
       AND c.finance_clearance_ref='FINANCE_CLEARANCE:'||clearance.id::text
       AND c.finance_clearance_version=clearance.revision
       AND clearance.settlement_fingerprint=finance_payroll_settled_delta_fingerprint(c.organization_id,c.replacement_run_version_id)))
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

-- Direct writers cannot bypass the real independent Finance attestation for
-- this new disposition. Existing historic correction dispositions are unchanged.
CREATE FUNCTION finance_payroll_settled_release_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.finance_disposition<>'SETTLED_DIFFERENTIAL_AUTHORIZED' THEN RETURN NEW; END IF;
 PERFORM 1 FROM payroll_period p JOIN payroll_run_version r ON r.payroll_period_id=p.id
 WHERE r.organization_id=NEW.organization_id AND r.id=NEW.replacement_run_version_id FOR UPDATE OF p;
 IF NOT EXISTS(SELECT 1 FROM finance_payroll_correction_candidate v
   JOIN finance_payroll_correction_clearance c ON c.organization_id=v.organization_id
     AND c.replacement_run_version_id=v.replacement_run_version_id
     AND c.original_run_version_id=v.original_run_version_id
   WHERE v.organization_id=NEW.organization_id AND v.replacement_run_version_id=NEW.replacement_run_version_id
     AND v.original_run_version_id=NEW.original_run_version_id
     AND v.eligible_disposition=NEW.finance_disposition AND c.disposition=NEW.finance_disposition
     AND c.original_fingerprint=NEW.original_source_fingerprint AND c.replacement_fingerprint=NEW.replacement_source_fingerprint
     AND c.settlement_fingerprint=finance_payroll_settled_delta_fingerprint(v.organization_id,v.replacement_run_version_id)
     AND NEW.finance_clearance_ref='FINANCE_CLEARANCE:'||c.id::text AND NEW.finance_clearance_version=c.revision
     AND c.revision=(SELECT max(x.revision) FROM finance_payroll_correction_clearance x
         WHERE x.organization_id=c.organization_id AND x.replacement_run_version_id=c.replacement_run_version_id)
     AND c.reviewed_by_user_id<>v.draft_creator
     AND finance_payroll_current_authority(c.organization_id,c.reviewed_by_user_id,'REVIEW'))
 THEN RAISE EXCEPTION 'Exact independent settled payroll clearance required' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER finance_payroll_settled_release_guard BEFORE INSERT ON payroll_run_correction
 FOR EACH ROW EXECUTE FUNCTION finance_payroll_settled_release_guard();

-- Serialize every earlier result/proposal with the same period lock, including
-- returns and voids, so a concurrent old-money change cannot race the source.
CREATE FUNCTION finance_payroll_settled_evidence_lock() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE source_run uuid;
BEGIN
 SELECT run_version_id INTO source_run FROM finance_payroll_instruction
  WHERE organization_id=NEW.organization_id AND id=NEW.instruction_id FOR UPDATE;
 PERFORM 1 FROM payroll_period p JOIN payroll_run_version r ON r.payroll_period_id=p.id
  WHERE r.organization_id=NEW.organization_id AND r.id=source_run FOR UPDATE OF p;
 RETURN NEW;
END; $$;
CREATE TRIGGER finance_payroll_settled_evidence_proposal_lock BEFORE INSERT ON finance_payroll_result_proposal
 FOR EACH ROW EXECUTE FUNCTION finance_payroll_settled_evidence_lock();
CREATE TRIGGER finance_payroll_settled_evidence_result_lock BEFORE INSERT ON finance_payroll_result_event
 FOR EACH ROW EXECUTE FUNCTION finance_payroll_settled_evidence_lock();

-- Positive-only differential accrual reuses the existing reversal journal.
-- Its account mapping must be exactly the reviewed original treatment.
CREATE FUNCTION finance_payroll_gl_settled_mapping_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE old_journal finance_payroll_gl_journal%ROWTYPE;
BEGIN
 IF NEW.entry_type<>'PAYROLL_ACCRUAL' OR NOT EXISTS(
   SELECT 1 FROM payroll_run_correction c WHERE c.organization_id=NEW.organization_id
   AND c.replacement_run_version_id=NEW.run_version_id AND c.finance_disposition='SETTLED_DIFFERENTIAL_AUTHORIZED')
 THEN RETURN NEW; END IF;
 SELECT * INTO old_journal FROM finance_payroll_gl_journal
 WHERE organization_id=NEW.organization_id AND id=NEW.original_journal_id AND entry_type='PAYROLL_ACCRUAL';
 IF old_journal.id IS NULL OR old_journal.debit_account_id<>NEW.debit_account_id
 OR old_journal.credit_account_id<>NEW.credit_account_id
 OR NOT (old_journal.component_allocations @> NEW.component_allocations AND NEW.component_allocations @> old_journal.component_allocations)
 THEN RAISE EXCEPTION 'Settled payroll correction must retain exact approved prior accounting treatment' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER finance_payroll_gl_settled_mapping_guard BEFORE INSERT ON finance_payroll_gl_journal
 FOR EACH ROW EXECUTE FUNCTION finance_payroll_gl_settled_mapping_guard();

CREATE VIEW finance_payroll_instruction_entitlement AS
 SELECT r.organization_id,r.id AS run_version_id,l.employee_id,l.currency,l.net AS amount
 FROM payroll_run_version r JOIN payroll_run_employee_line l ON l.run_version_id=r.id
 WHERE NOT EXISTS(SELECT 1 FROM payroll_run_correction c WHERE c.organization_id=r.organization_id
     AND c.replacement_run_version_id=r.id AND c.finance_disposition='SETTLED_DIFFERENTIAL_AUTHORIZED')
 UNION ALL
 SELECT b.organization_id,b.replacement_run_version_id,b.employee_id,b.currency,b.additional_due AS amount
 FROM finance_payroll_settled_delta_basis b JOIN finance_payroll_gl_correction_source c
   ON c.organization_id=b.organization_id AND c.replacement_run_version_id=b.replacement_run_version_id
   AND c.finance_disposition='SETTLED_DIFFERENTIAL_AUTHORIZED'
 WHERE b.all_employees_ready AND b.additional_due IS NOT NULL AND b.recovery_due=0;

CREATE OR REPLACE FUNCTION finance_payroll_batch_complete_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE b finance_payroll_payment_batch%ROWTYPE;
        batch uuid;
BEGIN
 IF TG_TABLE_NAME='finance_payroll_payment_batch' THEN batch:=NEW.id; ELSE batch:=NEW.batch_id; END IF;
 SELECT * INTO b FROM finance_payroll_payment_batch WHERE id=batch;
 IF b.id IS NULL OR NOT finance_payroll_current_authority(b.organization_id,b.prepared_by_user_id,'PREPARE') OR NOT EXISTS (
   SELECT 1 FROM finance_payroll_review_batch r JOIN payroll_run_version v ON v.id=r.run_version_id
   JOIN payroll_run_release p ON p.id=r.release_id AND p.run_version_id=v.id
   WHERE r.id=b.review_batch_id AND r.organization_id=b.organization_id AND r.run_version_id=b.run_version_id
     AND v.organization_id=b.organization_id AND r.run_source_fingerprint=v.source_fingerprint
     AND p.run_source_fingerprint=v.source_fingerprint AND r.payable_total=v.net AND r.currency=v.currency

 ) OR NOT EXISTS (
   SELECT 1 FROM finance_payroll_instruction_entitlement l
   WHERE l.organization_id=b.organization_id AND l.run_version_id=b.run_version_id
 ) OR (SELECT count(*) FROM finance_payroll_instruction i WHERE i.batch_id=batch) <>
      (SELECT count(*) FROM finance_payroll_instruction_entitlement l
       WHERE l.organization_id=b.organization_id AND l.run_version_id=b.run_version_id AND l.amount>0)
 OR EXISTS (
   SELECT 1 FROM finance_payroll_instruction i LEFT JOIN finance_payroll_instruction_entitlement l
     ON l.organization_id=b.organization_id AND l.run_version_id=b.run_version_id AND l.employee_id=i.employee_id
   JOIN payroll_run_version v ON v.id=b.run_version_id
   WHERE i.batch_id=batch AND (i.organization_id<>b.organization_id OR i.run_version_id<>b.run_version_id
     OR l.employee_id IS NULL OR i.amount<>l.amount OR i.currency<>l.currency OR i.source_fingerprint<>v.source_fingerprint)
 ) THEN RAISE EXCEPTION 'Payroll instructions must match one complete released payroll source' USING ERRCODE='23514'; END IF;
 RETURN NULL;
END; $$;
