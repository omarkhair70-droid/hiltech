-- Read-only reconciliation evidence for payroll corrections.
-- A matched bank event is counted only with its exact posted GL journal.
-- Voiding a bank match is NOT a return of funds. Retain its ambiguity.
CREATE VIEW finance_payroll_instruction_review AS
 SELECT i.organization_id,i.id AS instruction_id,i.run_version_id,i.employee_id,i.currency,
   coalesce(f.payments,0::numeric) AS posted_payments,
   coalesce(f.returns,0::numeric) AS posted_returns,
   f.event_count,f.unposted_count,f.void_count,q.pending_count,
   (CASE WHEN f.event_count=0 THEN 1 ELSE 0 END)::bigint AS no_result_count
 FROM finance_payroll_instruction i
 CROSS JOIN LATERAL (
   SELECT count(*) AS event_count,
     count(*) FILTER(WHERE j.id IS NULL OR j.amount<>e.amount OR j.currency<>e.currency
       OR j.entry_type<>CASE e.action WHEN 'SUCCESS' THEN 'PAYROLL_PAYMENT'
            WHEN 'RETURNED' THEN 'PAYROLL_RETURN' ELSE 'PAYROLL_VOID' END) AS unposted_count,
     count(*) FILTER(WHERE e.action='VOID_MATCH') AS void_count,
     sum(e.amount) FILTER(WHERE e.action='SUCCESS' AND j.entry_type='PAYROLL_PAYMENT'
       AND j.amount=e.amount AND j.currency=e.currency AND NOT EXISTS(
         SELECT 1 FROM finance_payroll_result_event v WHERE v.organization_id=e.organization_id
           AND v.corrects_event_id=e.id AND v.action='VOID_MATCH')) AS payments,
     sum(e.amount) FILTER(WHERE e.action='RETURNED' AND j.entry_type='PAYROLL_RETURN'
       AND j.amount=e.amount AND j.currency=e.currency) AS returns
   FROM finance_payroll_result_event e
   LEFT JOIN finance_payroll_gl_journal j ON j.organization_id=e.organization_id AND j.source_event_id=e.id
   WHERE e.organization_id=i.organization_id AND e.instruction_id=i.id
 ) f
 CROSS JOIN LATERAL (
   SELECT count(*) AS pending_count FROM finance_payroll_result_proposal p
   WHERE p.organization_id=i.organization_id AND p.instruction_id=i.id
     AND NOT EXISTS(SELECT 1 FROM finance_payroll_result_event e
       WHERE e.organization_id=p.organization_id AND e.proposal_id=p.id)
     AND NOT EXISTS(SELECT 1 FROM approval_request a WHERE a.organization_id=p.organization_id
       AND a.subject_id=p.id AND a.subject_type='FINANCE_PAYROLL_RESULT'
       AND a.subject_version=p.review_version AND a.state='REJECTED')
 ) q;

-- Each row compares one person. Do not net one employee's shortfall against
-- another employee's overpayment, and never make this view payment authority.
CREATE VIEW finance_payroll_correction_employee_review AS
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
 FROM finance_payroll_correction_candidate v
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
