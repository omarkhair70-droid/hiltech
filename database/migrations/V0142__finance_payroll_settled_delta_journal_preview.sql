-- A posting preview for an exactly settled prior payroll, using its immutable
-- accrual and frozen component allocations. This is not a release or posting.
-- Positive employee differences are liabilities; negative differences require
-- a separately approved employee recovery account, not a negative payable.
CREATE FUNCTION finance_payroll_settled_delta_balanced(
 gross_change numeric, deduction_change numeric, reimbursement_change numeric,
 additional_due numeric, recovery_due numeric
) RETURNS boolean LANGUAGE sql IMMUTABLE AS $$
 SELECT coalesce(additional_due>=0 AND recovery_due>=0 AND
   gross_change-deduction_change+reimbursement_change=additional_due-recovery_due,false)
$$;

CREATE VIEW finance_payroll_settled_delta_plan AS
 WITH totals AS (
   SELECT organization_id,original_run_version_id,replacement_run_version_id,currency,original_journal_id,
     count(*) AS employee_count,
     count(*) FILTER (WHERE basis_state NOT IN('ADDITIONAL_DUE','RECOVERY_DUE','NO_NET_CHANGE')) AS blocked_count,
     bool_and(all_employees_ready) AS all_ready,
     sum(additional_due) AS additional_due,sum(recovery_due) AS recovery_due
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
     WHEN NOT finance_payroll_settled_delta_balanced(newer.gross-older.gross,newer.deductions-older.deductions,
       newer.reimbursements-older.reimbursements,t.additional_due,t.recovery_due)
       THEN 'BALANCE_REQUIRED'
     WHEN t.recovery_due>0 THEN 'RECOVERY_ACCOUNT_REQUIRED'
     ELSE 'REVIEW_READY'
   END AS plan_state
 FROM totals t JOIN payroll_run_version older ON older.organization_id=t.organization_id AND older.id=t.original_run_version_id
 JOIN payroll_run_version newer ON newer.organization_id=t.organization_id AND newer.id=t.replacement_run_version_id
 LEFT JOIN finance_payroll_gl_journal j ON j.organization_id=t.organization_id AND j.id=t.original_journal_id;

CREATE VIEW finance_payroll_settled_delta_preview_line AS
 WITH eligible AS (
   SELECT p.*,j.debit_account_id,j.credit_account_id,j.component_allocations
   FROM finance_payroll_settled_delta_plan p
   JOIN finance_payroll_gl_journal j ON j.organization_id=p.organization_id AND j.id=p.original_journal_id
   WHERE p.plan_state IN('REVIEW_READY','RECOVERY_ACCOUNT_REQUIRED')
 ), changes AS (
   SELECT organization_id,replacement_run_version_id,1 AS line_number,'EARNINGS'::text AS line_kind,
     NULL::text AS component_code,debit_account_id AS account_id,gross_change AS signed_debit FROM eligible
   UNION ALL
   SELECT organization_id,replacement_run_version_id,2,'ADDITIONAL_PAYABLE',NULL,credit_account_id,-additional_due FROM eligible
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
