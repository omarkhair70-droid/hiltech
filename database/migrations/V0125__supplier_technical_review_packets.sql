-- A separate nonfinancial source for the existing technical-review responsibility.
-- Priced quotation evidence is never made available by these relations.
CREATE TABLE procurement_supplier_technical_document_slot (
 id uuid PRIMARY KEY,
 operation_id uuid NOT NULL UNIQUE,
 organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
 quote_id uuid NOT NULL,
 rfq_id uuid NOT NULL,
 title varchar(240) NOT NULL CHECK(length(btrim(title))>0),
 created_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
 created_at timestamptz NOT NULL,
 FOREIGN KEY(quote_id,rfq_id,organization_id) REFERENCES procurement_supplier_quote_evidence(id,rfq_id,organization_id) ON DELETE RESTRICT,
 UNIQUE(id,organization_id,quote_id)
);
CREATE TABLE procurement_supplier_technical_packet (
 id uuid PRIMARY KEY,
 operation_id uuid NOT NULL UNIQUE,
 organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
 quote_id uuid NOT NULL,
 rfq_id uuid NOT NULL,
 packet_revision integer NOT NULL CHECK(packet_revision>0),
 need_version bigint NOT NULL CHECK(need_version>=2),
 need_snapshot jsonb NOT NULL CHECK(jsonb_typeof(need_snapshot)='object'),
 details jsonb NOT NULL CHECK(jsonb_typeof(details)='object'
   AND details ?& ARRAY['offeredSpecification','deviations','supplierSource','sourceReference','nonFinancialConfirmed','correctionReason']
   AND details ? 'evidenceIds' AND jsonb_typeof(details->'evidenceIds')='array'
   AND jsonb_array_length(details->'evidenceIds')<=10
   AND jsonb_typeof(details->'nonFinancialConfirmed')='boolean' AND details->>'nonFinancialConfirmed'='true'
   AND jsonb_typeof(details->'offeredSpecification')='string' AND jsonb_typeof(details->'deviations')='string'
   AND jsonb_typeof(details->'supplierSource')='string' AND jsonb_typeof(details->'sourceReference')='string'
   AND jsonb_typeof(details->'correctionReason')='string' AND length(btrim(details->>'correctionReason'))>=8
   AND length(btrim(details->>'offeredSpecification'))>=8
   AND length(btrim(details->>'deviations'))>0
   AND length(btrim(details->>'supplierSource'))>0
   AND length(btrim(details->>'sourceReference'))>0
   AND (details-ARRAY['manufacturer','model','offeredSpecification','deviations','supplierSource','sourceReference','sourceDate','evidenceIds','nonFinancialConfirmed','correctionReason'])='{}'::jsonb),
 source_hash char(64) NOT NULL CHECK(source_hash ~ '^[a-f0-9]{64}$'),
 created_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
 created_at timestamptz NOT NULL,
 FOREIGN KEY(quote_id,rfq_id,organization_id) REFERENCES procurement_supplier_quote_evidence(id,rfq_id,organization_id) ON DELETE RESTRICT,
 UNIQUE(quote_id,packet_revision), UNIQUE(id,quote_id,organization_id)
);
CREATE TABLE procurement_supplier_technical_packet_evidence (
 packet_id uuid NOT NULL,
 quote_id uuid NOT NULL,
 organization_id uuid NOT NULL,
 evidence_id uuid NOT NULL REFERENCES evidence(id) ON DELETE RESTRICT,
 evidence_sha256 char(64) NOT NULL CHECK(evidence_sha256 ~ '^[a-f0-9]{64}$'),
 PRIMARY KEY(packet_id,evidence_id),
 FOREIGN KEY(packet_id,quote_id,organization_id) REFERENCES procurement_supplier_technical_packet(id,quote_id,organization_id) ON DELETE RESTRICT
);
CREATE TABLE procurement_supplier_technical_evidence_view (
 id uuid PRIMARY KEY,
 packet_id uuid NOT NULL,
 quote_id uuid NOT NULL,
 organization_id uuid NOT NULL,
 evidence_id uuid NOT NULL,
 evidence_sha256 char(64) NOT NULL,
 viewer_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
 delivered_at timestamptz NOT NULL,
 FOREIGN KEY(packet_id,quote_id,organization_id) REFERENCES procurement_supplier_technical_packet(id,quote_id,organization_id) ON DELETE RESTRICT,
 FOREIGN KEY(packet_id,evidence_id) REFERENCES procurement_supplier_technical_packet_evidence(packet_id,evidence_id) ON DELETE RESTRICT
);
CREATE TABLE procurement_supplier_technical_evidence_display (
 view_receipt_id uuid PRIMARY KEY REFERENCES procurement_supplier_technical_evidence_view(id) ON DELETE RESTRICT,
 viewer_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
 displayed_at timestamptz NOT NULL
);
ALTER TABLE procurement_supplier_quote_technical_assessment
 ADD COLUMN technical_packet_id uuid REFERENCES procurement_supplier_technical_packet(id) ON DELETE RESTRICT,
 ADD COLUMN packet_reviewed boolean NOT NULL DEFAULT false;

CREATE FUNCTION procurement_technical_document_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.target_type<>'SUPPLIER_TECHNICAL_DOCUMENT' THEN RETURN NEW; END IF;
 IF NOT EXISTS(SELECT 1 FROM procurement_supplier_technical_document_slot s WHERE s.id=NEW.target_id AND s.organization_id=NEW.organization_id)
   OR NEW.work_order_id IS NOT NULL OR NEW.evidence_policy_id IS NOT NULL OR NEW.supersedes_evidence_id IS NOT NULL
   OR NEW.evidence_type_code<>'SUPPLIER_TECHNICAL_SOURCE' OR NEW.evidence_requirement_key IS DISTINCT FROM 'SUPPLIER_TECHNICAL_SOURCE'
   OR NEW.classification_code<>'HIGHLY_RESTRICTED' OR NEW.client_visibility_mode<>'INTERNAL_ONLY'
   OR NOT ((NEW.content_type='application/pdf' AND NEW.security_scan_class='SIGNED_DOCUMENT')
      OR (NEW.content_type IN('image/png','image/jpeg') AND NEW.security_scan_class='NATIVE_MEDIA'))
 THEN RAISE EXCEPTION 'Technical originals must use their separate protected quote-scoped source' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER procurement_technical_document_source BEFORE INSERT OR UPDATE ON evidence FOR EACH ROW EXECUTE FUNCTION procurement_technical_document_guard();

CREATE FUNCTION procurement_technical_evidence_current(company uuid, quote uuid, proof uuid, sha varchar)
RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM evidence e JOIN evidence_upload_session u ON u.evidence_id=e.id
   JOIN procurement_supplier_technical_document_slot s ON s.id=e.target_id AND s.organization_id=e.organization_id
   WHERE e.id=proof AND e.organization_id=company AND s.quote_id=quote AND e.target_type='SUPPLIER_TECHNICAL_DOCUMENT'
     AND e.evidence_type_code='SUPPLIER_TECHNICAL_SOURCE' AND e.evidence_requirement_key='SUPPLIER_TECHNICAL_SOURCE'
     AND e.sha256=sha AND u.expected_sha256=sha AND u.expected_size_bytes=e.size_bytes
     AND e.storage_state='READY' AND u.state='READY' AND e.finalized_at IS NOT NULL AND u.finalized_at IS NOT NULL
     AND e.object_key_ref IS NOT NULL AND e.size_bytes BETWEEN 1 AND 16777216
     AND e.classification_code='HIGHLY_RESTRICTED' AND e.client_visibility_mode='INTERNAL_ONLY')
$$;
CREATE FUNCTION procurement_technical_packet_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE round procurement_supplier_rfq%ROWTYPE; version bigint; state varchar;
BEGIN
 SELECT * INTO round FROM procurement_supplier_rfq WHERE id=NEW.rfq_id AND organization_id=NEW.organization_id FOR UPDATE;
 SELECT result_version,to_state INTO version,state FROM procurement_requirement_transition WHERE requirement_id=round.requirement_id ORDER BY result_version DESC LIMIT 1;
 IF round.id IS NULL OR version IS DISTINCT FROM round.requirement_version OR NEW.need_version IS DISTINCT FROM version OR state IS DISTINCT FROM 'READY_FOR_SOURCING'
   OR NOT EXISTS(SELECT 1 FROM procurement_supplier_quote_evidence q WHERE q.id=NEW.quote_id AND q.rfq_id=round.id AND q.organization_id=NEW.organization_id
       AND NOT EXISTS(SELECT 1 FROM procurement_supplier_quote_evidence n WHERE n.rfq_id=q.rfq_id AND n.supplier_organization_id=q.supplier_organization_id AND n.revision_number>q.revision_number))
   OR NEW.packet_revision<>(SELECT COALESCE(max(p.packet_revision),0)+1 FROM procurement_supplier_technical_packet p WHERE p.quote_id=NEW.quote_id)
 THEN RAISE EXCEPTION 'Technical packet requires the current exact quote and demand source' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER procurement_technical_packet_source BEFORE INSERT ON procurement_supplier_technical_packet FOR EACH ROW EXECUTE FUNCTION procurement_technical_packet_guard();
CREATE FUNCTION procurement_technical_proof_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT procurement_technical_evidence_current(NEW.organization_id,NEW.quote_id,NEW.evidence_id,NEW.evidence_sha256)
   OR NOT EXISTS(SELECT 1 FROM procurement_supplier_technical_packet p WHERE p.id=NEW.packet_id AND p.details->'evidenceIds' ? NEW.evidence_id::text)
 THEN RAISE EXCEPTION 'Technical packet evidence must be exact separate READY originals' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER procurement_technical_proof_source BEFORE INSERT ON procurement_supplier_technical_packet_evidence FOR EACH ROW EXECUTE FUNCTION procurement_technical_proof_guard();
CREATE FUNCTION procurement_technical_display_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM procurement_supplier_technical_evidence_view v WHERE v.id=NEW.view_receipt_id AND v.viewer_user_id=NEW.viewer_user_id AND NEW.displayed_at>=v.delivered_at)
 THEN RAISE EXCEPTION 'Display confirmation belongs to the exact delivered viewer receipt' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER procurement_technical_display_source BEFORE INSERT ON procurement_supplier_technical_evidence_display FOR EACH ROW EXECUTE FUNCTION procurement_technical_display_guard();
CREATE FUNCTION procurement_technical_assessment_source_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE packet procurement_supplier_technical_packet%ROWTYPE;
BEGIN
 PERFORM 1 FROM procurement_supplier_rfq WHERE id=NEW.rfq_id AND organization_id=NEW.organization_id FOR UPDATE;
 IF NOT EXISTS(SELECT 1 FROM procurement_supplier_rfq r JOIN procurement_supplier_quote_evidence q ON q.rfq_id=r.id AND q.organization_id=r.organization_id
     JOIN LATERAL(SELECT result_version,to_state FROM procurement_requirement_transition t WHERE t.requirement_id=r.requirement_id ORDER BY result_version DESC LIMIT 1) t ON true
     WHERE r.id=NEW.rfq_id AND q.id=NEW.quote_evidence_id AND r.organization_id=NEW.organization_id AND t.result_version=r.requirement_version AND t.to_state='READY_FOR_SOURCING'
       AND NOT EXISTS(SELECT 1 FROM procurement_supplier_quote_evidence newer WHERE newer.rfq_id=q.rfq_id AND newer.supplier_organization_id=q.supplier_organization_id AND newer.revision_number>q.revision_number))
 THEN RAISE EXCEPTION 'Technical decision source is no longer current' USING ERRCODE='23514'; END IF;
 SELECT * INTO packet FROM procurement_supplier_technical_packet WHERE quote_id=NEW.quote_evidence_id ORDER BY packet_revision DESC LIMIT 1;
 IF packet.id IS NULL THEN
   IF NEW.technical_packet_id IS NOT NULL OR NEW.decision<>'NEEDS_CLARIFICATION' OR NEW.packet_reviewed
   THEN RAISE EXCEPTION 'A new technical judgment requires a published nonfinancial source' USING ERRCODE='23514'; END IF;
 ELSE
   IF NEW.technical_packet_id IS DISTINCT FROM packet.id OR packet.organization_id<>NEW.organization_id OR packet.rfq_id<>NEW.rfq_id
     OR (NEW.decision IN('COMPLIANT','NON_COMPLIANT') AND NOT NEW.packet_reviewed)
     OR (NEW.decision<>'NEEDS_CLARIFICATION' AND (jsonb_array_length(packet.details->'evidenceIds')<>(SELECT count(*) FROM procurement_supplier_technical_packet_evidence p WHERE p.packet_id=packet.id)
       OR EXISTS(SELECT 1 FROM procurement_supplier_technical_packet_evidence p WHERE p.packet_id=packet.id
         AND NOT procurement_technical_evidence_current(p.organization_id,p.quote_id,p.evidence_id,p.evidence_sha256))))
   THEN RAISE EXCEPTION 'Technical assessment must bind the current complete packet' USING ERRCODE='23514'; END IF;
   IF NEW.decision='COMPLIANT' AND EXISTS(SELECT 1 FROM procurement_supplier_technical_packet_evidence p WHERE p.packet_id=packet.id
     AND NOT EXISTS(SELECT 1 FROM procurement_supplier_technical_evidence_view v JOIN procurement_supplier_technical_evidence_display d
       ON d.view_receipt_id=v.id AND d.viewer_user_id=v.viewer_user_id
       WHERE v.packet_id=p.packet_id AND v.evidence_id=p.evidence_id AND v.evidence_sha256=p.evidence_sha256 AND v.viewer_user_id=NEW.reviewed_by_user_id))
   THEN RAISE EXCEPTION 'Required technical originals must be displayed for this packet' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER procurement_technical_assessment_source BEFORE INSERT ON procurement_supplier_quote_technical_assessment FOR EACH ROW EXECUTE FUNCTION procurement_technical_assessment_source_guard();

CREATE VIEW procurement_effective_quote_technical_assessment AS
 SELECT a.* FROM (SELECT DISTINCT ON(quote_evidence_id) * FROM procurement_supplier_quote_technical_assessment ORDER BY quote_evidence_id,review_revision DESC) a
 LEFT JOIN LATERAL(SELECT id,details FROM procurement_supplier_technical_packet p WHERE p.quote_id=a.quote_evidence_id ORDER BY packet_revision DESC LIMIT 1) current_packet ON true
 WHERE (a.technical_packet_id IS NULL AND current_packet.id IS NULL AND a.decision='NEEDS_CLARIFICATION')
    OR (a.technical_packet_id=current_packet.id AND (a.decision='NEEDS_CLARIFICATION'
      OR (a.packet_reviewed AND jsonb_array_length(current_packet.details->'evidenceIds')=(SELECT count(*) FROM procurement_supplier_technical_packet_evidence p WHERE p.packet_id=current_packet.id)
        AND NOT EXISTS(SELECT 1 FROM procurement_supplier_technical_packet_evidence p WHERE p.packet_id=current_packet.id
          AND NOT procurement_technical_evidence_current(p.organization_id,p.quote_id,p.evidence_id,p.evidence_sha256)))));

ALTER TABLE procurement_po_candidate ADD COLUMN technical_assessment_id uuid REFERENCES procurement_supplier_quote_technical_assessment(id) ON DELETE RESTRICT;
CREATE FUNCTION procurement_candidate_technical_source_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM procurement_effective_quote_technical_assessment a WHERE a.id=NEW.technical_assessment_id
     AND a.quote_evidence_id=NEW.quote_evidence_id AND a.rfq_id=NEW.rfq_id AND a.organization_id=NEW.organization_id AND a.decision='COMPLIANT')
 THEN RAISE EXCEPTION 'A new purchase candidate must pin its exact current technical assessment' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER procurement_candidate_technical_source BEFORE INSERT ON procurement_po_candidate FOR EACH ROW EXECUTE FUNCTION procurement_candidate_technical_source_guard();
CREATE FUNCTION procurement_order_technical_source_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM procurement_po_candidate c JOIN procurement_effective_quote_technical_assessment a ON a.id=c.technical_assessment_id
     WHERE c.id=NEW.candidate_id AND c.organization_id=NEW.organization_id AND a.quote_evidence_id=c.quote_evidence_id AND a.rfq_id=c.rfq_id
       AND a.organization_id=c.organization_id AND a.decision='COMPLIANT')
 THEN RAISE EXCEPTION 'Purchase issuance requires the candidate exact current technical assessment' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER procurement_order_technical_source BEFORE INSERT ON procurement_purchase_order FOR EACH ROW EXECUTE FUNCTION procurement_order_technical_source_guard();

-- Preserve all earlier technical decisions. New packet revisions make an older
-- decision ineffective for purchasing; history is never rewritten or deleted.
CREATE INDEX procurement_technical_packet_latest ON procurement_supplier_technical_packet(quote_id,packet_revision DESC);
CREATE INDEX procurement_technical_document_quote ON procurement_supplier_technical_document_slot(quote_id,created_at DESC);
CREATE TRIGGER procurement_technical_slot_immutable BEFORE UPDATE OR DELETE ON procurement_supplier_technical_document_slot FOR EACH ROW EXECUTE FUNCTION reject_procurement_draft_mutation();
CREATE TRIGGER procurement_technical_packet_immutable BEFORE UPDATE OR DELETE ON procurement_supplier_technical_packet FOR EACH ROW EXECUTE FUNCTION reject_procurement_draft_mutation();
CREATE TRIGGER procurement_technical_proof_immutable BEFORE UPDATE OR DELETE ON procurement_supplier_technical_packet_evidence FOR EACH ROW EXECUTE FUNCTION reject_procurement_draft_mutation();
CREATE TRIGGER procurement_technical_view_immutable BEFORE UPDATE OR DELETE ON procurement_supplier_technical_evidence_view FOR EACH ROW EXECUTE FUNCTION reject_procurement_draft_mutation();
CREATE TRIGGER procurement_technical_display_immutable BEFORE UPDATE OR DELETE ON procurement_supplier_technical_evidence_display FOR EACH ROW EXECUTE FUNCTION reject_procurement_draft_mutation();

CREATE TRIGGER procurement_technical_slot_no_truncate BEFORE TRUNCATE ON procurement_supplier_technical_document_slot FOR EACH STATEMENT EXECUTE FUNCTION reject_procurement_draft_mutation();
CREATE TRIGGER procurement_technical_packet_no_truncate BEFORE TRUNCATE ON procurement_supplier_technical_packet FOR EACH STATEMENT EXECUTE FUNCTION reject_procurement_draft_mutation();
CREATE TRIGGER procurement_technical_proof_no_truncate BEFORE TRUNCATE ON procurement_supplier_technical_packet_evidence FOR EACH STATEMENT EXECUTE FUNCTION reject_procurement_draft_mutation();
CREATE TRIGGER procurement_technical_view_no_truncate BEFORE TRUNCATE ON procurement_supplier_technical_evidence_view FOR EACH STATEMENT EXECUTE FUNCTION reject_procurement_draft_mutation();
CREATE TRIGGER procurement_technical_display_no_truncate BEFORE TRUNCATE ON procurement_supplier_technical_evidence_display FOR EACH STATEMENT EXECUTE FUNCTION reject_procurement_draft_mutation();
