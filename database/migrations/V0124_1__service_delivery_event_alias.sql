-- Additive repair: keep V0121 immutable and avoid the PL/pgSQL OLD record name
-- in the exact client-response proof/release guard. All decision predicates remain.
CREATE OR REPLACE FUNCTION service_delivery_event_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p service_delivery_proposal%ROWTYPE;
        a approval_request%ROWTYPE;
        reviewer_key text;
        subject_kind text;
        referenced service_delivery_event%ROWTYPE;
        previous service_delivery_event%ROWTYPE;
        reference_proposal service_delivery_proposal%ROWTYPE;
BEGIN
 SELECT * INTO p FROM service_delivery_proposal WHERE id=NEW.proposal_id AND organization_id=NEW.organization_id AND project_id=NEW.project_id;
 PERFORM 1 FROM project WHERE id=NEW.project_id AND organization_id=NEW.organization_id FOR UPDATE;
 IF p.id IS NULL OR NEW.stream_key<>p.stream_key OR NEW.event_number<>p.base_event_number+1
   OR NEW.recorded_by_user_id<>p.reviewer_user_id OR p.prepared_by_user_id=NEW.recorded_by_user_id
   OR NOT service_delivery_current_member(NEW.organization_id,NEW.recorded_by_user_id,true)
   OR NOT service_delivery_current_member(NEW.organization_id,p.prepared_by_user_id,false)
 THEN RAISE EXCEPTION 'Service decision requires its current scoped independent participants' USING ERRCODE='23514'; END IF;
 SELECT * INTO previous FROM service_delivery_event WHERE project_id=p.project_id AND stream_key=p.stream_key ORDER BY event_number DESC LIMIT 1;
 IF COALESCE(previous.event_number,0)<>p.base_event_number OR NEW.recorded_at<p.prepared_at
 THEN RAISE EXCEPTION 'Service decision stream changed' USING ERRCODE='23514'; END IF;
 reviewer_key:=CASE WHEN p.action LIKE 'HANDOVER_%' THEN 'SERVICE_HANDOVER_REVIEWER' ELSE 'SERVICE_WARRANTY_REVIEWER' END;
 subject_kind:=CASE WHEN p.action LIKE 'HANDOVER_%' THEN 'SERVICE_HANDOVER_ACTION' ELSE 'SERVICE_WARRANTY_ACTION' END;
 SELECT * INTO a FROM approval_request WHERE id=NEW.approval_request_id AND organization_id=NEW.organization_id;
 IF a.id IS NULL OR a.state<>'APPROVED' OR a.subject_type<>subject_kind OR a.subject_id<>p.id
   OR a.subject_version<>p.review_version OR a.policy_key<>p.policy_key OR a.policy_version<>p.policy_version
   OR a.requester_user_id<>p.prepared_by_user_id OR a.completed_at IS NULL
   OR NOT EXISTS(SELECT 1 FROM approval_policy_version policy WHERE policy.organization_id=p.organization_id
      AND policy.policy_key=p.policy_key AND policy.version_number=p.policy_version AND policy.lifecycle_state='ACTIVE'
      AND policy.approval_required AND policy.reason_required AND policy.step_mode='SINGLE' AND policy.authority_key=reviewer_key
      AND policy.effective_from<=statement_timestamp() AND (policy.effective_to IS NULL OR policy.effective_to>=statement_timestamp()))
   OR NOT EXISTS(SELECT 1 FROM approval_authority_binding b WHERE b.id=p.authority_binding_id AND b.version=p.authority_binding_version
      AND b.organization_id=p.organization_id AND b.authority_key=reviewer_key AND b.principal_type='USER'
      AND b.principal_user_id=NEW.recorded_by_user_id AND b.active AND b.effective_from<=statement_timestamp()
      AND (b.effective_to IS NULL OR b.effective_to>=statement_timestamp()))
   OR NOT EXISTS(SELECT 1 FROM approval_decision d WHERE d.approval_request_id=a.id AND d.decision='APPROVE'
      AND d.subject_version=p.review_version AND d.actor_user_id=NEW.recorded_by_user_id)
   OR EXISTS(SELECT 1 FROM service_delivery_proposal_evidence proof WHERE proof.proposal_id=p.id AND
      (NOT service_delivery_evidence_current(p.organization_id,p.project_id,proof.evidence_id,proof.evidence_sha256)
       OR (proof.required_to_open AND NOT EXISTS(SELECT 1 FROM service_delivery_evidence_view viewed
          JOIN service_delivery_evidence_display displayed ON displayed.view_receipt_id=viewed.id AND displayed.viewer_user_id=viewed.viewer_user_id
          WHERE viewed.proposal_id=p.id AND viewed.review_version=p.review_version AND viewed.viewer_user_id=NEW.recorded_by_user_id
            AND viewed.evidence_id=proof.evidence_id AND viewed.evidence_sha256=proof.evidence_sha256))))
 THEN RAISE EXCEPTION 'Service decision requires exact independent approval and linked proof review' USING ERRCODE='23514'; END IF;
 IF p.action='HANDOVER_RELEASE' THEN
   IF NOT EXISTS(SELECT 1 FROM service_handover_package_version v JOIN project project ON project.id=v.project_id
      WHERE v.id=(p.details->>'packageVersionId')::uuid AND v.organization_id=p.organization_id AND v.project_id=p.project_id
        AND v.project_version_at_snapshot=project.version
        AND NOT EXISTS(SELECT 1 FROM service_handover_package_version newer WHERE newer.package_id=v.package_id AND newer.version_number>v.version_number))
     OR EXISTS(SELECT 1 FROM service_handover_package_item item WHERE item.package_version_id=(p.details->>'packageVersionId')::uuid
        AND (NOT service_delivery_work_document_released(item.revision_id)
          OR NOT EXISTS(SELECT 1 FROM service_delivery_proposal_evidence proof WHERE proof.proposal_id=p.id AND proof.evidence_id=item.evidence_id AND proof.evidence_sha256=item.source_sha256)))
     OR COALESCE((p.details->>'disclosureConfirmed')::boolean,false)=false
     OR NOT EXISTS(SELECT 1 FROM service_delivery_proposal_evidence proof WHERE proof.proposal_id=p.id
       AND proof.evidence_id=(p.details->>'agreementEvidenceId')::uuid AND proof.required_to_open)
   THEN RAISE EXCEPTION 'Handover release requires exact current package and reviewed agreement' USING ERRCODE='23514'; END IF;
   IF previous.id IS NOT NULL AND (SELECT action FROM service_delivery_proposal WHERE id=previous.proposal_id)<>'HANDOVER_WITHDRAW'
   THEN RAISE EXCEPTION 'Withdraw the prior release before issuing its replacement' USING ERRCODE='23514'; END IF;
 ELSIF p.action IN('HANDOVER_CLIENT_RESPONSE','HANDOVER_WITHDRAW') THEN
   SELECT * INTO referenced FROM service_delivery_event WHERE id=(p.details->>'releaseEventId')::uuid
     AND organization_id=p.organization_id AND project_id=p.project_id;
   IF referenced.id IS NULL OR (SELECT action FROM service_delivery_proposal WHERE id=referenced.proposal_id)<>'HANDOVER_RELEASE'
     OR EXISTS(SELECT 1 FROM service_delivery_event newer JOIN service_delivery_proposal np ON np.id=newer.proposal_id
       WHERE newer.project_id=p.project_id AND newer.stream_key='HANDOVER' AND newer.event_number>referenced.event_number
         AND np.action IN('HANDOVER_RELEASE','HANDOVER_WITHDRAW'))
   THEN RAISE EXCEPTION 'Client response and withdrawal require the active exact release' USING ERRCODE='23514'; END IF;
   IF p.action='HANDOVER_CLIENT_RESPONSE' AND (
       COALESCE(p.details->>'responseOutcome','') NOT IN('ACCEPTED','REJECTED','ACCEPTED_WITH_RESERVATIONS')
       OR COALESCE((p.details->>'representativeVerified')::boolean,false)=false
       OR length(btrim(COALESCE(p.details->>'representativeName','')))=0
       OR length(btrim(COALESCE(p.details->>'representativeCapacity','')))=0
       OR length(btrim(COALESCE(p.details->>'representativeAuthoritySource','')))=0
       OR p.details->>'responseOn' IS NULL OR (p.details->>'responseOn')::date<referenced.recorded_at::date
       OR (p.details->>'responseOn')::date>statement_timestamp()::date
       OR NOT EXISTS(SELECT 1 FROM service_delivery_proposal_evidence proof WHERE proof.proposal_id=p.id
          AND proof.evidence_id=(p.details->>'proofEvidenceId')::uuid AND proof.required_to_open))
   THEN RAISE EXCEPTION 'Recorded client response needs actual representative source and linked proof' USING ERRCODE='23514'; END IF;
   IF p.action='HANDOVER_CLIENT_RESPONSE' AND previous.id<>referenced.id
     AND (p.details->>'correctsEventId')::uuid IS DISTINCT FROM previous.id
   THEN RAISE EXCEPTION 'Client response correction must reference the preceding response' USING ERRCODE='23514'; END IF;
   IF p.action='HANDOVER_CLIENT_RESPONSE' AND EXISTS(SELECT 1 FROM service_delivery_event prior_response_event
       JOIN service_delivery_proposal op ON op.id=prior_response_event.proposal_id
       WHERE op.project_id=p.project_id AND op.action='HANDOVER_CLIENT_RESPONSE'
         AND op.details->>'proofEvidenceId'=p.details->>'proofEvidenceId'
         AND op.details->>'releaseEventId' IS DISTINCT FROM p.details->>'releaseEventId')
   THEN RAISE EXCEPTION 'Client response evidence belongs to its recorded release' USING ERRCODE='23514'; END IF;
 ELSIF p.action='WARRANTY_TERMS' THEN
   IF COALESCE(p.details->>'scopeType','') NOT IN('SITE','WORK')
     OR p.details->>'startsOn' IS NULL OR p.details->>'endsOn' IS NULL
     OR (p.details->>'startsOn')::date>(p.details->>'endsOn')::date
     OR length(btrim(COALESCE(p.details->>'writtenTerms','')))=0
     OR COALESCE((p.details->>'scopeConfirmed')::boolean,false)=false
     OR NOT EXISTS(SELECT 1 FROM service_delivery_proposal_evidence proof WHERE proof.proposal_id=p.id
        AND proof.evidence_id=(p.details->>'agreementEvidenceId')::uuid AND proof.required_to_open)
     OR (p.details->>'scopeType'='SITE' AND NOT EXISTS(SELECT 1 FROM project_site s WHERE s.project_id=p.project_id AND s.site_id=(p.details->>'scopeId')::uuid))
     OR (p.details->>'scopeType'='WORK' AND NOT EXISTS(SELECT 1 FROM work_order w WHERE w.project_id=p.project_id AND w.id=(p.details->>'scopeId')::uuid))
   THEN RAISE EXCEPTION 'Warranty scope, written terms and dates must come from the reviewed project agreement' USING ERRCODE='23514'; END IF;
   IF previous.id IS NOT NULL AND (p.details->>'correctsEventId')::uuid IS DISTINCT FROM previous.id
   THEN RAISE EXCEPTION 'Warranty terms revision must preserve its predecessor' USING ERRCODE='23514'; END IF;
 ELSIF p.action='WARRANTY_ASSESSMENT' THEN
   IF NOT EXISTS(SELECT 1 FROM service_support_ticket t WHERE t.id=(p.details->>'ticketId')::uuid AND t.project_id=p.project_id AND t.organization_id=p.organization_id)
     OR COALESCE(p.details->>'coverageDecision','') NOT IN('COVERED','NOT_COVERED','INSUFFICIENT_INFORMATION')
   THEN RAISE EXCEPTION 'Coverage decision requires its exact project incident' USING ERRCODE='23514'; END IF;
   IF p.details->>'coverageDecision'<>'INSUFFICIENT_INFORMATION' THEN
     SELECT * INTO referenced FROM service_delivery_event WHERE id=(p.details->>'termsEventId')::uuid AND project_id=p.project_id AND organization_id=p.organization_id;
     SELECT * INTO reference_proposal FROM service_delivery_proposal WHERE id=referenced.proposal_id;
     IF referenced.id IS NULL OR reference_proposal.action<>'WARRANTY_TERMS' OR p.details->>'incidentOn' IS NULL
       OR (p.details->>'incidentOn')::date>statement_timestamp()::date
       OR EXISTS(SELECT 1 FROM service_delivery_event newer WHERE newer.project_id=p.project_id AND newer.stream_key=referenced.stream_key AND newer.event_number>referenced.event_number)
       OR NOT EXISTS(SELECT 1 FROM service_delivery_proposal_evidence proof WHERE proof.proposal_id=p.id
         AND proof.evidence_id=(p.details->>'proofEvidenceId')::uuid AND proof.required_to_open)
     THEN RAISE EXCEPTION 'Coverage requires current approved terms and incident evidence' USING ERRCODE='23514'; END IF;
     IF p.details->>'coverageDecision'='COVERED' AND (
       (p.details->>'incidentOn')::date NOT BETWEEN (reference_proposal.details->>'startsOn')::date AND (reference_proposal.details->>'endsOn')::date
       OR COALESCE((p.details->>'exclusionApplies')::boolean,false) OR NOT COALESCE((p.details->>'scopeConfirmed')::boolean,false))
     THEN RAISE EXCEPTION 'Covered decision contradicts its chosen dates, scope or exclusions' USING ERRCODE='23514'; END IF;
   END IF;
   IF previous.id IS NOT NULL AND (p.details->>'correctsEventId')::uuid IS DISTINCT FROM previous.id
   THEN RAISE EXCEPTION 'Coverage correction must preserve its predecessor' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END; $$;
