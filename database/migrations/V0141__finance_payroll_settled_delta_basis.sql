-- Establish a conservative, read-only basis for a settled payroll differential.
-- It is NOT clearance, an accounting journal, a payment instruction or recovery authority.
-- The existing no-transfer / returned-transfer path remains independent.
CREATE FUNCTION finance_payroll_settled_delta_state(
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
     OR recorded_net<>original_net
     THEN 'PRIOR_SETTLEMENT_NOT_EXACT'
   WHEN replacement_net>original_net THEN 'ADDITIONAL_DUE'
   WHEN replacement_net<original_net THEN 'RECOVERY_DUE'
   ELSE 'NO_NET_CHANGE'
 END
$$;

-- All amounts are based on exact recorded settlement of the prior entitlement.
-- Missing instruction outcomes, unresolved results, and partial or excess prior
-- payments are excluded from this initial differential path. They require
-- their own evidence reconciliation; a missing match never proves no transfer.
-- Preserve every employee's own positive or negative delta and the exact source
-- fingerprints. No netting across employees and no authorization to execute.
CREATE VIEW finance_payroll_settled_delta_basis AS
 WITH classified AS (
   SELECT r.organization_id,r.original_run_version_id,r.replacement_run_version_id,
     c.original_fingerprint,c.replacement_fingerprint,r.employee_id,r.currency,
     j.id AS original_journal_id,r.original_net,r.replacement_net,r.recorded_net,
     finance_payroll_settled_delta_state(
       r.original_net,r.replacement_net,r.recorded_net,r.posted_payments,r.no_result_count,
       j.id IS NOT NULL AND NOT EXISTS(
         SELECT 1 FROM finance_payroll_gl_journal replaced
         WHERE replaced.organization_id=j.organization_id
           AND replaced.original_journal_id=j.id AND replaced.entry_type='PAYROLL_ACCRUAL')
     ) AS basis_state
   FROM finance_payroll_correction_employee_review r
   JOIN finance_payroll_correction_candidate c
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
