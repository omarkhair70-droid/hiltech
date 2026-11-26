-- Existing project authority prepares records. Named independent policy reviewers
-- may inspect only the exact proposal and its references; no extra operational role.
CREATE TABLE service_delivery_document_slot (
 id uuid PRIMARY KEY,
 operation_id uuid NOT NULL UNIQUE,
 organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
 project_id uuid NOT NULL,
 purpose varchar(32) NOT NULL CHECK(purpose IN('AGREEMENT','CLIENT_RESPONSE','WARRANTY_SUPPORT','INCIDENT')),
 title varchar(240) NOT NULL CHECK(length(btrim(title)) BETWEEN 1 AND 240),
 created_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
 created_at timestamptz NOT NULL,
 UNIQUE(id,organization_id,project_id),
 FOREIGN KEY(project_id,organization_id) REFERENCES project(id,organization_id) ON DELETE RESTRICT
);
CREATE INDEX service_delivery_document_project ON service_delivery_document_slot(project_id,created_at DESC);

CREATE FUNCTION service_delivery_document_evidence_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE slot service_delivery_document_slot%ROWTYPE;
BEGIN
 IF NEW.target_type<>'SERVICE_DELIVERY_DOCUMENT' THEN RETURN NEW; END IF;
 SELECT * INTO slot FROM service_delivery_document_slot WHERE id=NEW.target_id AND organization_id=NEW.organization_id;
 IF slot.id IS NULL OR NEW.work_order_id IS NOT NULL OR NEW.evidence_policy_id IS NOT NULL
   OR NEW.evidence_type_code IS DISTINCT FROM 'SERVICE_DELIVERY_'||slot.purpose
   OR NEW.evidence_requirement_key IS DISTINCT FROM NEW.evidence_type_code
   OR NEW.classification_code<>'HIGHLY_RESTRICTED' OR NEW.client_visibility_mode<>'INTERNAL_ONLY'
   OR NEW.supersedes_evidence_id IS NOT NULL
   OR NOT ((NEW.content_type='application/pdf' AND NEW.security_scan_class='SIGNED_DOCUMENT')
     OR (NEW.content_type IN('image/png','image/jpeg') AND NEW.security_scan_class='NATIVE_MEDIA'))
 THEN RAISE EXCEPTION 'Service evidence must retain its exact project purpose and protected storage contract' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER service_delivery_evidence_source BEFORE INSERT OR UPDATE ON evidence
 FOR EACH ROW EXECUTE FUNCTION service_delivery_document_evidence_guard();

CREATE FUNCTION service_delivery_current_member(org uuid, actor uuid, employee_required boolean DEFAULT false)
RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM user_identity u JOIN organization_membership m ON m.user_identity_id=u.id
   JOIN organization o ON o.id=m.organization_id
   WHERE u.id=actor AND o.id=org AND u.status='ACTIVE' AND o.status='ACTIVE' AND o.organization_type='HILTECH'
     AND m.state='ACTIVE' AND m.valid_from<=statement_timestamp()
     AND (m.valid_until IS NULL OR m.valid_until>statement_timestamp())
     AND (NOT employee_required OR EXISTS(SELECT 1 FROM employee e
       WHERE e.person_id=u.person_id AND e.organization_id=org AND e.state='ACTIVE')))
$$;

-- A release marker is not permission to skip a required document approval.
-- Preserve the exact historical policy version when a later policy is retired/replaced.
CREATE FUNCTION service_delivery_work_document_released(p_revision_id uuid)
RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM work_document_revision r
   JOIN work_document d ON d.id=r.document_id AND d.organization_id=r.organization_id AND d.work_order_id=r.work_order_id
   JOIN work_document_revision_release released ON released.revision_id=r.id
     AND released.document_id=d.id AND released.organization_id=d.organization_id AND released.work_order_id=d.work_order_id
   JOIN approval_policy_version policy ON policy.organization_id=released.organization_id
     AND policy.policy_key=released.policy_key AND policy.version_number=released.policy_version
   LEFT JOIN approval_request approval ON approval.id=released.approval_request_id
   WHERE r.id=p_revision_id AND d.approval_policy_key=released.policy_key
     AND policy.lifecycle_state IN('ACTIVE','RETIRED') AND policy.effective_from<=released.created_at
     AND (policy.effective_to IS NULL OR policy.effective_to>=released.created_at)
     AND ((released.release_mode='NO_APPROVAL_REQUIRED' AND NOT policy.approval_required AND released.approval_request_id IS NULL)
       OR (released.release_mode='APPROVAL_REQUEST' AND policy.approval_required AND approval.state='APPROVED'
         AND approval.completed_at IS NOT NULL AND approval.organization_id=r.organization_id
         AND approval.subject_type='WORK_DOCUMENT_REVISION' AND approval.subject_id=r.id AND approval.subject_version=r.version
         AND approval.policy_key=released.policy_key AND approval.policy_version=released.policy_version
         AND EXISTS(SELECT 1 FROM approval_decision decision WHERE decision.approval_request_id=approval.id
             AND decision.decision='APPROVE' AND decision.subject_version=r.version)))
     AND NOT EXISTS(SELECT 1 FROM work_document_revision newer WHERE newer.document_id=r.document_id AND newer.revision_sequence>r.revision_sequence))
$$;

CREATE TABLE service_delivery_proposal (
 id uuid PRIMARY KEY,
 operation_id uuid NOT NULL UNIQUE,
 organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
 project_id uuid NOT NULL,
 action varchar(32) NOT NULL CHECK(action IN('HANDOVER_RELEASE','HANDOVER_CLIENT_RESPONSE','HANDOVER_WITHDRAW','WARRANTY_TERMS','WARRANTY_ASSESSMENT')),
 stream_key varchar(160) NOT NULL,
 base_event_number integer NOT NULL CHECK(base_event_number>=0),
 details jsonb NOT NULL CHECK(jsonb_typeof(details)='object'),
 source_stamp char(64) NOT NULL CHECK(source_stamp ~ '^[0-9a-f]{64}$'),
 review_version bigint NOT NULL CHECK(review_version>0),
 policy_key varchar(64) NOT NULL CHECK(policy_key IN('SERVICE_HANDOVER_REVIEW','SERVICE_WARRANTY_REVIEW')),
 policy_version integer NOT NULL CHECK(policy_version>0),
 authority_binding_id uuid NOT NULL REFERENCES approval_authority_binding(id) ON DELETE RESTRICT,
 authority_binding_version bigint NOT NULL CHECK(authority_binding_version>0),
 reviewer_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
 prepared_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
 prepared_at timestamptz NOT NULL,
 project_name varchar(240) NOT NULL,
 project_code varchar(64) NOT NULL,
 client_name varchar(240) NOT NULL,
 scope_name varchar(500),
 package_documents jsonb NOT NULL CHECK(jsonb_typeof(package_documents)='array'),
 package_findings jsonb NOT NULL CHECK(jsonb_typeof(package_findings)='array'),
 CHECK(reviewer_user_id<>prepared_by_user_id),
 CHECK((action LIKE 'HANDOVER_%' AND policy_key='SERVICE_HANDOVER_REVIEW' AND stream_key='HANDOVER')
   OR (action LIKE 'WARRANTY_%' AND policy_key='SERVICE_WARRANTY_REVIEW' AND stream_key<>'HANDOVER')),
 UNIQUE(id,organization_id,project_id),
 FOREIGN KEY(project_id,organization_id) REFERENCES project(id,organization_id) ON DELETE RESTRICT
);
CREATE INDEX service_delivery_proposal_project ON service_delivery_proposal(project_id,prepared_at DESC);
CREATE INDEX service_delivery_proposal_reviewer ON service_delivery_proposal(organization_id,reviewer_user_id,prepared_at DESC);

CREATE TABLE service_delivery_proposal_evidence (
 proposal_id uuid NOT NULL,
 organization_id uuid NOT NULL,
 project_id uuid NOT NULL,
 evidence_id uuid NOT NULL REFERENCES evidence(id) ON DELETE RESTRICT,
 evidence_sha256 char(64) NOT NULL CHECK(evidence_sha256 ~ '^[0-9a-f]{64}$'),
 required_to_open boolean NOT NULL,
 PRIMARY KEY(proposal_id,evidence_id),
 FOREIGN KEY(proposal_id,organization_id,project_id)
  REFERENCES service_delivery_proposal(id,organization_id,project_id) ON DELETE RESTRICT
);

CREATE FUNCTION service_delivery_evidence_current(org uuid, project uuid, evidence_id uuid, sha varchar)
RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM evidence e JOIN evidence_upload_session u ON u.evidence_id=e.id
   WHERE e.id=evidence_id AND e.organization_id=org AND e.sha256=sha
     AND e.storage_state='READY' AND u.state='READY'
     AND e.finalized_at IS NOT NULL AND u.finalized_at IS NOT NULL AND e.object_key_ref IS NOT NULL
     AND e.sha256=u.expected_sha256 AND e.size_bytes=u.expected_size_bytes
     AND (
       (e.target_type='SERVICE_DELIVERY_DOCUMENT' AND EXISTS(SELECT 1 FROM service_delivery_document_slot s
          WHERE s.id=e.target_id AND s.organization_id=org AND s.project_id=project)
         AND e.classification_code='HIGHLY_RESTRICTED' AND e.client_visibility_mode='INTERNAL_ONLY')
       OR (e.target_type='COMMERCIAL_CONTRACT' AND EXISTS(SELECT 1 FROM commercial_project_handoff h
          JOIN commercial_contract c ON c.id=h.contract_id AND c.organization_id=h.organization_id
          WHERE h.project_id=project AND h.organization_id=org AND c.id=e.target_id
            AND c.state='SIGNED' AND c.signed_evidence_id=e.id)
         AND e.evidence_type_code='COMMERCIAL_SIGNED_CONTRACT'
         AND e.classification_code='HIGHLY_RESTRICTED' AND e.client_visibility_mode='INTERNAL_ONLY')
       OR (e.target_type='WORK_ORDER' AND e.work_order_id=e.target_id AND EXISTS(SELECT 1 FROM work_order w
          WHERE w.id=e.work_order_id AND w.project_id=project))
     ))
$$;
CREATE FUNCTION service_delivery_proof_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT service_delivery_evidence_current(NEW.organization_id,NEW.project_id,NEW.evidence_id,NEW.evidence_sha256)
 THEN RAISE EXCEPTION 'Service proposal evidence must be finalized in its exact project' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER service_delivery_proof_source BEFORE INSERT ON service_delivery_proposal_evidence
 FOR EACH ROW EXECUTE FUNCTION service_delivery_proof_guard();

CREATE TABLE service_delivery_evidence_view (
 id uuid PRIMARY KEY,
 proposal_id uuid NOT NULL,
 organization_id uuid NOT NULL,
 project_id uuid NOT NULL,
 review_version bigint NOT NULL CHECK(review_version>0),
 evidence_id uuid NOT NULL,
 evidence_sha256 char(64) NOT NULL,
 viewer_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
 viewed_at timestamptz NOT NULL,
 FOREIGN KEY(proposal_id,organization_id,project_id) REFERENCES service_delivery_proposal(id,organization_id,project_id) ON DELETE RESTRICT,
 FOREIGN KEY(proposal_id,evidence_id) REFERENCES service_delivery_proposal_evidence(proposal_id,evidence_id) ON DELETE RESTRICT
);
CREATE INDEX service_delivery_view_source ON service_delivery_evidence_view(proposal_id,review_version,viewer_user_id,evidence_id);

-- Delivery of a rendered page and confirmation that the native client displayed
-- it are separate immutable facts. Neither is a claim of human reading/signature.
CREATE TABLE service_delivery_evidence_display (
 view_receipt_id uuid PRIMARY KEY REFERENCES service_delivery_evidence_view(id) ON DELETE RESTRICT,
 viewer_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
 displayed_at timestamptz NOT NULL
);
CREATE FUNCTION service_delivery_display_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM service_delivery_evidence_view v
   WHERE v.id=NEW.view_receipt_id AND v.viewer_user_id=NEW.viewer_user_id AND v.viewed_at<=NEW.displayed_at
     AND service_delivery_current_member(v.organization_id,NEW.viewer_user_id,false))
 THEN RAISE EXCEPTION 'Display receipt requires its current original viewer and delivered page' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER service_delivery_display_source BEFORE INSERT ON service_delivery_evidence_display
 FOR EACH ROW EXECUTE FUNCTION service_delivery_display_guard();

CREATE TABLE service_delivery_event (
 id uuid PRIMARY KEY,
 operation_id uuid NOT NULL UNIQUE,
 organization_id uuid NOT NULL,
 project_id uuid NOT NULL,
 proposal_id uuid NOT NULL UNIQUE,
 stream_key varchar(160) NOT NULL,
 event_number integer NOT NULL CHECK(event_number>0),
 approval_request_id uuid NOT NULL REFERENCES approval_request(id) ON DELETE RESTRICT,
 recorded_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
 recorded_at timestamptz NOT NULL,
 UNIQUE(project_id,stream_key,event_number),
 FOREIGN KEY(proposal_id,organization_id,project_id) REFERENCES service_delivery_proposal(id,organization_id,project_id) ON DELETE RESTRICT
);

CREATE FUNCTION service_delivery_event_guard() RETURNS trigger LANGUAGE plpgsql AS $$
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
   IF p.action='HANDOVER_CLIENT_RESPONSE' AND EXISTS(SELECT 1 FROM service_delivery_event old
       JOIN service_delivery_proposal op ON op.id=old.proposal_id
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
CREATE TRIGGER service_delivery_event_source BEFORE INSERT ON service_delivery_event
 FOR EACH ROW EXECUTE FUNCTION service_delivery_event_guard();

CREATE TRIGGER service_delivery_slot_immutable BEFORE UPDATE OR DELETE ON service_delivery_document_slot
 FOR EACH ROW EXECUTE FUNCTION service_handover_no_snapshot_mutation();
CREATE TRIGGER service_delivery_proposal_immutable BEFORE UPDATE OR DELETE ON service_delivery_proposal
 FOR EACH ROW EXECUTE FUNCTION service_handover_no_snapshot_mutation();
CREATE TRIGGER service_delivery_proof_immutable BEFORE UPDATE OR DELETE ON service_delivery_proposal_evidence
 FOR EACH ROW EXECUTE FUNCTION service_handover_no_snapshot_mutation();
CREATE TRIGGER service_delivery_view_immutable BEFORE UPDATE OR DELETE ON service_delivery_evidence_view
 FOR EACH ROW EXECUTE FUNCTION service_handover_no_snapshot_mutation();
CREATE TRIGGER service_delivery_display_immutable BEFORE UPDATE OR DELETE ON service_delivery_evidence_display
 FOR EACH ROW EXECUTE FUNCTION service_handover_no_snapshot_mutation();
CREATE TRIGGER service_delivery_event_immutable BEFORE UPDATE OR DELETE ON service_delivery_event
 FOR EACH ROW EXECUTE FUNCTION service_handover_no_snapshot_mutation();
CREATE TRIGGER service_delivery_slot_no_truncate BEFORE TRUNCATE ON service_delivery_document_slot
 FOR EACH STATEMENT EXECUTE FUNCTION service_handover_no_snapshot_mutation();
CREATE TRIGGER service_delivery_proposal_no_truncate BEFORE TRUNCATE ON service_delivery_proposal
 FOR EACH STATEMENT EXECUTE FUNCTION service_handover_no_snapshot_mutation();
CREATE TRIGGER service_delivery_proof_no_truncate BEFORE TRUNCATE ON service_delivery_proposal_evidence
 FOR EACH STATEMENT EXECUTE FUNCTION service_handover_no_snapshot_mutation();
CREATE TRIGGER service_delivery_view_no_truncate BEFORE TRUNCATE ON service_delivery_evidence_view
 FOR EACH STATEMENT EXECUTE FUNCTION service_handover_no_snapshot_mutation();
CREATE TRIGGER service_delivery_display_no_truncate BEFORE TRUNCATE ON service_delivery_evidence_display
 FOR EACH STATEMENT EXECUTE FUNCTION service_handover_no_snapshot_mutation();
CREATE TRIGGER service_delivery_event_no_truncate BEFORE TRUNCATE ON service_delivery_event
 FOR EACH STATEMENT EXECUTE FUNCTION service_handover_no_snapshot_mutation();

-- Project delivery and ticket coverage are source projections, not automatic
-- project closure, contractual SLA timers, billing permission or electronic signatures.
CREATE VIEW service_project_delivery_status AS
 SELECT DISTINCT ON(e.project_id) e.organization_id,e.project_id,e.id AS event_id,e.event_number,
   CASE p.action WHEN 'HANDOVER_RELEASE' THEN 'ISSUED'
     WHEN 'HANDOVER_WITHDRAW' THEN 'WITHDRAWN'
     ELSE 'CLIENT_'||(p.details->>'responseOutcome') END AS delivery_state,e.recorded_at
 FROM service_delivery_event e JOIN service_delivery_proposal p ON p.id=e.proposal_id
 WHERE e.stream_key='HANDOVER' ORDER BY e.project_id,e.event_number DESC;
CREATE VIEW service_ticket_coverage_status AS
 SELECT DISTINCT ON(e.project_id,p.details->>'ticketId') e.organization_id,e.project_id,
   (p.details->>'ticketId')::uuid AS ticket_id,e.id AS event_id,p.details->>'coverageDecision' AS coverage_state,e.recorded_at
 FROM service_delivery_event e JOIN service_delivery_proposal p ON p.id=e.proposal_id
 WHERE p.action='WARRANTY_ASSESSMENT' ORDER BY e.project_id,p.details->>'ticketId',e.event_number DESC;
