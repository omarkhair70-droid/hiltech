-- Correct against cumulative verified prior payroll payments, not the original
-- entitlement alone. A prior instruction without a recorded result is unknown,
-- never proof of zero payment. Keep the original exact-settlement classifier for
-- existing contract consumers and reuse the same clearance, posting and bank
-- instruction guards. No new result type, arbitrary partial bank allocation, or
-- external execution is introduced.
CREATE FUNCTION finance_payroll_verified_delta_state(
 original_net numeric, replacement_net numeric, recorded_net numeric,
 posted_payments numeric, no_result_count bigint, active_accrual boolean
) RETURNS text LANGUAGE sql IMMUTABLE AS $$
 SELECT CASE
   WHEN recorded_net IS NULL OR no_result_count IS DISTINCT FROM 0::bigint
     THEN 'UNRESOLVED_EVIDENCE'
   WHEN NOT coalesce(active_accrual,false)
     THEN 'MISSING_ACTIVE_ACCRUAL'
   WHEN original_net IS NULL OR replacement_net IS NULL OR original_net<=0
     OR replacement_net<0 OR posted_payments IS NULL OR posted_payments<=0
     OR recorded_net<0 OR recorded_net>original_net OR recorded_net>posted_payments
     THEN 'PRIOR_SETTLEMENT_NOT_EXACT'
   WHEN replacement_net>recorded_net THEN 'ADDITIONAL_DUE'
   WHEN replacement_net<recorded_net THEN 'RECOVERY_DUE'
   ELSE 'NO_NET_CHANGE'
 END
$$;

-- The previous unpaid liability is already in the ledger. The movement in
-- payable during correction is remaining payable minus that prior liability.
-- Recovery is a separate asset. Do not confuse payment instructions with the
-- incremental movement in the payable GL account.
CREATE FUNCTION finance_payroll_verified_delta_balanced(
 gross_change numeric, deduction_change numeric, reimbursement_change numeric,
 additional_due numeric, recovery_due numeric, prior_outstanding numeric
) RETURNS boolean LANGUAGE sql IMMUTABLE AS $$
 SELECT coalesce(prior_outstanding>=0 AND additional_due>=0 AND recovery_due>=0 AND
   gross_change-deduction_change+reimbursement_change=additional_due-recovery_due-prior_outstanding,false)
$$;

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
     OR facts.no_result_count>0 OR facts.currency_conflicts>0 OR facts.posted_returns>facts.posted_payments AS unresolved
 ) flags;

CREATE OR REPLACE VIEW finance_payroll_settled_delta_basis AS
 WITH classified AS (
   SELECT r.organization_id,r.original_run_version_id,r.replacement_run_version_id,
     c.original_fingerprint,c.replacement_fingerprint,r.employee_id,r.currency,
     j.id AS original_journal_id,r.original_net,r.replacement_net,r.recorded_net,
     finance_payroll_verified_delta_state(
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
   CASE WHEN basis_state='ADDITIONAL_DUE' THEN CASE WHEN recorded_net=original_net THEN replacement_net-original_net ELSE replacement_net-recorded_net END
        WHEN basis_state IN('RECOVERY_DUE','NO_NET_CHANGE') THEN 0::numeric END AS additional_due,
   CASE WHEN basis_state='RECOVERY_DUE' THEN CASE WHEN recorded_net=original_net THEN original_net-replacement_net ELSE recorded_net-replacement_net END
        WHEN basis_state IN('ADDITIONAL_DUE','NO_NET_CHANGE') THEN 0::numeric END AS recovery_due,
   bool_and(basis_state IN('ADDITIONAL_DUE','RECOVERY_DUE','NO_NET_CHANGE'))
     OVER(PARTITION BY organization_id,replacement_run_version_id) AS all_employees_ready
 FROM classified;

CREATE OR REPLACE VIEW finance_payroll_settled_delta_plan AS
 WITH totals AS (
   SELECT organization_id,original_run_version_id,replacement_run_version_id,currency,original_journal_id,
     count(*) AS employee_count,
     count(*) FILTER (WHERE basis_state NOT IN('ADDITIONAL_DUE','RECOVERY_DUE','NO_NET_CHANGE')) AS blocked_count,
     bool_and(all_employees_ready) AS all_ready,
     sum(additional_due) AS additional_due,sum(recovery_due) AS recovery_due,
     sum(original_net-recorded_net) AS prior_outstanding
   FROM finance_payroll_settled_delta_basis
   GROUP BY organization_id,original_run_version_id,replacement_run_version_id,currency,original_journal_id
 )
 SELECT t.organization_id,t.original_run_version_id,t.replacement_run_version_id,t.currency,t.original_journal_id,
   t.employee_count,t.blocked_count,
   CASE WHEN t.all_ready THEN t.additional_due END AS additional_due,
   CASE WHEN t.all_ready THEN t.recovery_due END AS recovery_due,
   newer.gross-older.gross AS gross_change,newer.deductions-older.deductions AS deduction_change,
   newer.reimbursements-older.reimbursements AS reimbursement_change,
   CASE
     WHEN NOT t.all_ready THEN 'EVIDENCE_REQUIRED'
     WHEN NOT finance_payroll_gl_components_complete(t.organization_id,older.id)
       OR NOT finance_payroll_gl_components_complete(t.organization_id,newer.id)
       THEN 'COMPONENTS_REQUIRED'
     WHEN j.id IS NULL OR NOT finance_payroll_gl_allocations_valid(t.organization_id,older.id,t.currency,j.component_allocations)
       OR NOT finance_payroll_gl_allocations_valid(t.organization_id,newer.id,t.currency,j.component_allocations)
       OR NOT EXISTS(SELECT 1 FROM finance_gl_account a WHERE a.organization_id=t.organization_id
         AND a.id=j.debit_account_id AND a.state='APPROVED' AND a.account_kind='PAYROLL_EXPENSE'
         AND (a.currency IS NULL OR a.currency=t.currency))
       OR NOT EXISTS(SELECT 1 FROM finance_gl_account a WHERE a.organization_id=t.organization_id
         AND a.id=j.credit_account_id AND a.state='APPROVED' AND a.account_kind='PAYROLL_PAYABLE'
         AND (a.currency IS NULL OR a.currency=t.currency))
       THEN 'ACCOUNT_TREATMENT_REQUIRED'
     WHEN NOT finance_payroll_verified_delta_balanced(newer.gross-older.gross,newer.deductions-older.deductions,
       newer.reimbursements-older.reimbursements,t.additional_due,t.recovery_due,t.prior_outstanding)
       THEN 'BALANCE_REQUIRED'
     WHEN t.recovery_due>0 THEN 'RECOVERY_ACCOUNT_REQUIRED'
     ELSE 'REVIEW_READY'
   END AS plan_state
 FROM totals t JOIN payroll_run_version older ON older.organization_id=t.organization_id AND older.id=t.original_run_version_id
 JOIN payroll_run_version newer ON newer.organization_id=t.organization_id AND newer.id=t.replacement_run_version_id
 LEFT JOIN finance_payroll_gl_journal j ON j.organization_id=t.organization_id AND j.id=t.original_journal_id;

CREATE OR REPLACE VIEW finance_payroll_settled_delta_preview_line AS
 WITH eligible AS (
   SELECT p.*,j.debit_account_id,j.credit_account_id,j.component_allocations,
     (SELECT sum(b.original_net-b.recorded_net) FROM finance_payroll_settled_delta_basis b
      WHERE b.organization_id=p.organization_id AND b.replacement_run_version_id=p.replacement_run_version_id) AS prior_outstanding
   FROM finance_payroll_settled_delta_plan p
   JOIN finance_payroll_gl_journal j ON j.organization_id=p.organization_id AND j.id=p.original_journal_id
   WHERE p.plan_state IN('REVIEW_READY','RECOVERY_ACCOUNT_REQUIRED')
 ), changes AS (
   SELECT organization_id,replacement_run_version_id,1 AS line_number,'EARNINGS'::text AS line_kind,
     NULL::text AS component_code,debit_account_id AS account_id,gross_change AS signed_debit FROM eligible
   UNION ALL
   SELECT organization_id,replacement_run_version_id,2,'ADDITIONAL_PAYABLE',NULL,credit_account_id,CASE WHEN prior_outstanding=0 THEN -additional_due ELSE prior_outstanding-additional_due END FROM eligible
   UNION ALL
   SELECT organization_id,replacement_run_version_id,3,'EMPLOYEE_RECOVERY',NULL,NULL::uuid,recovery_due FROM eligible
   UNION ALL
   SELECT p.organization_id,p.replacement_run_version_id,(3+i.ordinality)::integer,
     old.component_kind::text,old.component_code::text,(i.value->>'accountId')::uuid,
     CASE WHEN old.component_kind='DEDUCTION' THEN old.amount-newer.amount ELSE newer.amount-old.amount END
   FROM eligible p CROSS JOIN LATERAL jsonb_array_elements(p.component_allocations) WITH ORDINALITY i(value,ordinality)
   JOIN finance_payroll_gl_component_group old ON old.organization_id=p.organization_id
     AND old.run_version_id=p.original_run_version_id AND old.component_kind=i.value->>'kind'
     AND old.source_domain=i.value->>'domain' AND old.component_code=i.value->>'code'
   JOIN finance_payroll_gl_component_group newer ON newer.organization_id=p.organization_id
     AND newer.run_version_id=p.replacement_run_version_id AND newer.component_kind=old.component_kind
     AND newer.source_domain=old.source_domain AND newer.component_code=old.component_code
 )
 SELECT c.organization_id,c.replacement_run_version_id,c.line_number,c.line_kind,c.component_code,c.account_id,
   a.account_code,a.account_name,CASE WHEN c.signed_debit>0 THEN 'DEBIT' ELSE 'CREDIT' END AS side,
   abs(c.signed_debit) AS amount
 FROM changes c LEFT JOIN finance_gl_account a ON a.organization_id=c.organization_id AND a.id=c.account_id
 WHERE c.signed_debit<>0;

-- Preserve the V1 source fingerprint for already exact-settled clearances.
-- A partially settled source has a positive net payment and all evidence resolved;
-- fully returned runs continue through the existing reversal path.
CREATE OR REPLACE FUNCTION finance_payroll_settled_delta_fingerprint(org uuid,replacement uuid)
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

   AND EXISTS(SELECT 1 FROM finance_payroll_settled_delta_basis b WHERE b.organization_id=org
     AND b.replacement_run_version_id=replacement AND b.recorded_net>0)
   -- A partially settled correction must retain the complete employee cohort.
   -- Otherwise its frozen payment basis would not match the released payroll.
   AND NOT EXISTS(SELECT 1 FROM finance_payroll_settled_delta_basis b
     WHERE b.organization_id=org AND b.replacement_run_version_id=replacement
       AND b.recorded_net<b.original_net
       AND (NOT EXISTS(SELECT 1 FROM payroll_run_employee_line l WHERE l.run_version_id=b.original_run_version_id AND l.employee_id=b.employee_id)
         OR NOT EXISTS(SELECT 1 FROM payroll_run_employee_line l WHERE l.run_version_id=b.replacement_run_version_id AND l.employee_id=b.employee_id)))
   AND ((p.plan_state='REVIEW_READY' AND p.recovery_due=0 AND (p.additional_due>0 OR EXISTS(
     SELECT 1 FROM finance_payroll_settled_delta_basis b WHERE b.organization_id=org
       AND b.replacement_run_version_id=replacement AND b.recorded_net<b.original_net)))
     OR (p.plan_state='RECOVERY_ACCOUNT_REQUIRED' AND p.recovery_due>0
       -- For now require the same frozen employee cohort. A removed employee
       -- needs a separate former-employee recovery review.
       AND NOT EXISTS(SELECT 1 FROM finance_payroll_settled_delta_basis b
         WHERE b.organization_id=org AND b.replacement_run_version_id=replacement
           AND (NOT EXISTS(SELECT 1 FROM payroll_run_employee_line l
                  WHERE l.run_version_id=b.original_run_version_id AND l.employee_id=b.employee_id)
             OR NOT EXISTS(SELECT 1 FROM payroll_run_employee_line l
                  WHERE l.run_version_id=b.replacement_run_version_id AND l.employee_id=b.employee_id)))))
$$;
