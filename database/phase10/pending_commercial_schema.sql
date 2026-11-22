-- Phase 10 Sales/Tenders/Commercial (unversioned isolation artifact).
-- DevOps/integration owner must assign the next free Flyway V-number AFTER stacked
-- Phase 7 and concurrently owned Phase 8/9/11/13 migrations are integrated.
-- Do not execute against production, merge this file as a Flyway migration, or enable
-- hiltech.commercial.enabled without HILTECH sign-off and private evidence wiring.

CREATE TABLE commercial_authority_binding (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
  authority_key varchar(32) NOT NULL CHECK (authority_key IN
    ('SALES_VIEW','SALES_WRITE','SALES_COST_VIEW','SALES_RELEASE','SALES_AWARD')),
  principal_type varchar(8) NOT NULL CHECK (principal_type IN ('USER','TEAM')),
  principal_user_id uuid REFERENCES user_identity(id) ON DELETE RESTRICT,
  principal_team_id uuid REFERENCES team(id) ON DELETE RESTRICT,
  active boolean NOT NULL DEFAULT true,
  effective_from timestamptz NOT NULL,
  effective_until timestamptz,
  created_by uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
  created_at timestamptz NOT NULL,
  version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
  CONSTRAINT ck_commercial_authority_principal CHECK (
    (principal_type='USER' AND principal_user_id IS NOT NULL AND principal_team_id IS NULL)
    OR (principal_type='TEAM' AND principal_team_id IS NOT NULL AND principal_user_id IS NULL)),
  CONSTRAINT ck_commercial_authority_dates CHECK (effective_until IS NULL OR effective_until >= effective_from)
);
CREATE INDEX idx_commercial_authority_active ON commercial_authority_binding
  (organization_id,authority_key,active,effective_from,effective_until);

CREATE TABLE commercial_opportunity (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
  client_organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
  owner_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
  source varchar(32) NOT NULL CHECK (source IN ('DIRECT','REFERRAL','RFQ','RFP','TENDER','OTHER')),
  opportunity_code varchar(64) NOT NULL,
  title varchar(240) NOT NULL CHECK (length(trim(title)) > 0),
  description text,
  state varchar(20) NOT NULL DEFAULT 'OPEN' CHECK (state IN ('OPEN','QUALIFIED','LOST','WITHDRAWN','AWARDED')),
  expected_decision_date date,
  closed_reason text,
  created_by uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
  CONSTRAINT uq_commercial_opportunity_org_code UNIQUE (organization_id,opportunity_code),
  CONSTRAINT uq_commercial_opportunity_id_org UNIQUE (id,organization_id),
  CONSTRAINT uq_commercial_opportunity_id_org_client UNIQUE (id,organization_id,client_organization_id),
  CONSTRAINT ck_commercial_closed_reason CHECK (
    (state IN ('LOST','WITHDRAWN') AND closed_reason IS NOT NULL AND length(trim(closed_reason)) > 0)
    OR (state NOT IN ('LOST','WITHDRAWN') AND closed_reason IS NULL))
);
CREATE INDEX idx_commercial_opportunities ON commercial_opportunity (organization_id,state,created_at DESC);

-- Qualification is evidence of a real discovery decision; never a guessed score.
CREATE TABLE commercial_qualification_record (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  opportunity_id uuid NOT NULL,
  discovery_summary text NOT NULL CHECK (length(trim(discovery_summary)) > 0),
  open_questions text NOT NULL,
  qualified_by uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
  qualified_at timestamptz NOT NULL,
  CONSTRAINT fk_commercial_qualification_op FOREIGN KEY (opportunity_id,organization_id)
    REFERENCES commercial_opportunity(id,organization_id) ON DELETE RESTRICT,
  CONSTRAINT uq_commercial_qualification_op UNIQUE (opportunity_id)
);

CREATE TABLE commercial_tender_revision (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  opportunity_id uuid NOT NULL,
  revision_number integer NOT NULL CHECK (revision_number > 0),
  tender_type varchar(12) NOT NULL CHECK (tender_type IN ('TENDER','RFQ','RFP','OTHER')),
  reference_number varchar(120),
  received_at timestamptz NOT NULL,
  submission_deadline timestamptz,
  clarification_deadline timestamptz,
  source_channel varchar(160),
  submission_state varchar(20) NOT NULL DEFAULT 'RECEIVED'
    CHECK (submission_state IN ('RECEIVED','PREPARING','SUBMITTED','WITHDRAWN','LOST','AWARDED')),
  created_by uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
  created_at timestamptz NOT NULL,
  CONSTRAINT fk_commercial_tender_op FOREIGN KEY (opportunity_id,organization_id)
    REFERENCES commercial_opportunity(id,organization_id) ON DELETE RESTRICT,
  CONSTRAINT uq_commercial_tender_rev UNIQUE (opportunity_id,revision_number),
  CONSTRAINT ck_commercial_tender_deadline CHECK (
    clarification_deadline IS NULL OR submission_deadline IS NULL OR clarification_deadline <= submission_deadline)
);

CREATE TABLE commercial_site_visit (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  opportunity_id uuid NOT NULL,
  site_id uuid,
  scheduled_at timestamptz NOT NULL,
  completed_at timestamptz,
  purpose varchar(240) NOT NULL CHECK (length(trim(purpose)) > 0),
  notes text,
  state varchar(20) NOT NULL DEFAULT 'SCHEDULED' CHECK (state IN ('SCHEDULED','COMPLETED','CANCELLED')),
  created_by uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
  created_at timestamptz NOT NULL,
  version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
  CONSTRAINT fk_commercial_visit_op FOREIGN KEY (opportunity_id,organization_id)
    REFERENCES commercial_opportunity(id,organization_id) ON DELETE RESTRICT,
  CONSTRAINT fk_commercial_visit_site FOREIGN KEY (site_id,organization_id)
    REFERENCES site(id,organization_id) ON DELETE RESTRICT,
  CONSTRAINT ck_commercial_visit_completion CHECK (
    (state='COMPLETED' AND completed_at IS NOT NULL)
    OR (state<>'COMPLETED' AND completed_at IS NULL))
);

CREATE TABLE commercial_scope_version (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  opportunity_id uuid NOT NULL,
  version_number integer NOT NULL CHECK (version_number > 0),
  description text NOT NULL CHECK (length(trim(description)) > 0),
  inclusions text NOT NULL,
  exclusions text NOT NULL,
  assumptions text NOT NULL,
  technical_constraints text NOT NULL,
  client_responsibilities text NOT NULL,
  created_by uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
  created_at timestamptz NOT NULL,
  CONSTRAINT fk_commercial_scope_op FOREIGN KEY (opportunity_id,organization_id)
    REFERENCES commercial_opportunity(id,organization_id) ON DELETE RESTRICT,
  CONSTRAINT uq_commercial_scope_version UNIQUE (opportunity_id,version_number),
  CONSTRAINT uq_commercial_scope_id_op UNIQUE (id,organization_id,opportunity_id)
);

CREATE TABLE commercial_boq_version (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  opportunity_id uuid NOT NULL,
  scope_version_id uuid NOT NULL,
  version_number integer NOT NULL CHECK (version_number > 0),
  currency char(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
  created_by uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
  created_at timestamptz NOT NULL,
  CONSTRAINT fk_commercial_boq_scope FOREIGN KEY (scope_version_id,organization_id,opportunity_id)
    REFERENCES commercial_scope_version(id,organization_id,opportunity_id) ON DELETE RESTRICT,
  CONSTRAINT uq_commercial_boq_version UNIQUE (opportunity_id,version_number),
  CONSTRAINT uq_commercial_boq_id_op UNIQUE (id,organization_id,opportunity_id)
);

-- PRIVATE costing truth: supplier quotations are not customer quotations.
CREATE TABLE commercial_boq_cost_line (
  id uuid PRIMARY KEY,
  boq_version_id uuid NOT NULL REFERENCES commercial_boq_version(id) ON DELETE RESTRICT,
  line_code varchar(64) NOT NULL,
  description varchar(500) NOT NULL,
  category varchar(32) NOT NULL CHECK (category IN ('MATERIAL','LABOR','SUBCONTRACT','EQUIPMENT','OVERHEAD','OTHER')),
  quantity numeric(20,4) NOT NULL CHECK (quantity > 0),
  unit varchar(48) NOT NULL,
  internal_unit_cost numeric(20,4) CHECK (internal_unit_cost IS NULL OR internal_unit_cost >= 0),
  sell_unit_price numeric(20,4) NOT NULL CHECK (sell_unit_price >= 0),
  created_at timestamptz NOT NULL,
  CONSTRAINT uq_commercial_boq_line UNIQUE (boq_version_id,line_code)
);

-- Immutable quoted commercial snapshot contains only client-facing prices.
-- No internal_unit_cost, margin, vendor quote, or supplier terms live here.
CREATE TABLE commercial_quote_version (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  opportunity_id uuid NOT NULL,
  client_organization_id uuid NOT NULL,
  scope_version_id uuid NOT NULL,
  boq_version_id uuid NOT NULL,
  version_number integer NOT NULL CHECK (version_number > 0),
  quote_code varchar(96) NOT NULL,
  currency char(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
  total_price numeric(20,4) NOT NULL CHECK (total_price >= 0),
  payment_terms text NOT NULL,
  delivery_terms text NOT NULL,
  exclusions text NOT NULL,
  validity_date date NOT NULL,
  state varchar(20) NOT NULL DEFAULT 'DRAFT' CHECK (state IN
    ('DRAFT','APPROVAL_PENDING','RELEASED','WITHDRAWN')),
  created_by uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
  created_at timestamptz NOT NULL,
  released_at timestamptz,
  version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
  CONSTRAINT fk_commercial_quote_op FOREIGN KEY (opportunity_id,organization_id,client_organization_id)
    REFERENCES commercial_opportunity(id,organization_id,client_organization_id) ON DELETE RESTRICT,
  CONSTRAINT fk_commercial_quote_scope FOREIGN KEY (scope_version_id,organization_id,opportunity_id)
    REFERENCES commercial_scope_version(id,organization_id,opportunity_id) ON DELETE RESTRICT,
  CONSTRAINT fk_commercial_quote_boq FOREIGN KEY (boq_version_id,organization_id,opportunity_id)
    REFERENCES commercial_boq_version(id,organization_id,opportunity_id) ON DELETE RESTRICT,
  CONSTRAINT uq_commercial_quote_version UNIQUE (opportunity_id,version_number),
  CONSTRAINT uq_commercial_quote_id_op UNIQUE (id,organization_id,opportunity_id)
);
CREATE TABLE commercial_quote_line (
  id uuid PRIMARY KEY,
  quote_version_id uuid NOT NULL REFERENCES commercial_quote_version(id) ON DELETE RESTRICT,
  line_code varchar(64) NOT NULL,
  description varchar(500) NOT NULL,
  quantity numeric(20,4) NOT NULL CHECK (quantity > 0),
  unit varchar(48) NOT NULL,
  unit_price numeric(20,4) NOT NULL CHECK (unit_price >= 0),
  CONSTRAINT uq_commercial_quote_line UNIQUE (quote_version_id,line_code)
);

CREATE TABLE commercial_contract (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  opportunity_id uuid NOT NULL,
  quote_version_id uuid NOT NULL,
  contract_code varchar(96) NOT NULL,
  state varchar(16) NOT NULL DEFAULT 'DRAFT'
    CHECK (state IN ('DRAFT','SIGNED','TERMINATED')),
  signed_evidence_id uuid UNIQUE REFERENCES evidence(id) ON DELETE RESTRICT,
  effective_date date,
  end_date date,
  warranty_terms text,
  maintenance_terms text,
  created_by uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
  created_at timestamptz NOT NULL,
  CONSTRAINT fk_commercial_contract_quote FOREIGN KEY (quote_version_id,organization_id,opportunity_id)
    REFERENCES commercial_quote_version(id,organization_id,opportunity_id) ON DELETE RESTRICT,
  CONSTRAINT ck_commercial_signed_evidence CHECK (state <> 'SIGNED' OR signed_evidence_id IS NOT NULL),
  CONSTRAINT ck_commercial_contract_dates CHECK (end_date IS NULL OR effective_date IS NULL OR end_date >= effective_date),
  CONSTRAINT uq_commercial_contract_code UNIQUE (organization_id,contract_code),
  CONSTRAINT uq_commercial_contract_identity UNIQUE (id,organization_id,opportunity_id)
);
CREATE TABLE commercial_client_po (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  opportunity_id uuid NOT NULL,
  contract_id uuid NOT NULL REFERENCES commercial_contract(id) ON DELETE RESTRICT,
  client_po_number varchar(128) NOT NULL,
  issued_on date NOT NULL,
  amount numeric(20,4) NOT NULL CHECK (amount >= 0),
  currency char(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
  evidence_id uuid UNIQUE REFERENCES evidence(id) ON DELETE RESTRICT,
  verified boolean NOT NULL DEFAULT false,
  created_by uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
  created_at timestamptz NOT NULL,
  CONSTRAINT fk_commercial_po_op FOREIGN KEY (opportunity_id,organization_id)
    REFERENCES commercial_opportunity(id,organization_id) ON DELETE RESTRICT,
  CONSTRAINT ck_commercial_po_verified CHECK (NOT verified OR evidence_id IS NOT NULL),
  CONSTRAINT uq_commercial_client_po UNIQUE (organization_id,client_po_number),
  CONSTRAINT uq_commercial_po_identity UNIQUE (id,organization_id,opportunity_id,contract_id),
  CONSTRAINT fk_commercial_po_contract_context FOREIGN KEY (contract_id,organization_id,opportunity_id)
    REFERENCES commercial_contract(id,organization_id,opportunity_id) ON DELETE RESTRICT
);
CREATE TABLE commercial_project_handoff (
  opportunity_id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  quote_version_id uuid NOT NULL,
  contract_id uuid NOT NULL REFERENCES commercial_contract(id) ON DELETE RESTRICT,
  client_po_id uuid NOT NULL REFERENCES commercial_client_po(id) ON DELETE RESTRICT,
  project_id uuid NOT NULL UNIQUE REFERENCES project(id) ON DELETE RESTRICT,
  created_by uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
  created_at timestamptz NOT NULL,
  CONSTRAINT fk_commercial_handoff_op FOREIGN KEY (opportunity_id,organization_id)
    REFERENCES commercial_opportunity(id,organization_id) ON DELETE RESTRICT,
  CONSTRAINT fk_commercial_handoff_contract FOREIGN KEY (contract_id,organization_id,opportunity_id)
    REFERENCES commercial_contract(id,organization_id,opportunity_id) ON DELETE RESTRICT,
  CONSTRAINT fk_commercial_handoff_po FOREIGN KEY (client_po_id,organization_id,opportunity_id,contract_id)
    REFERENCES commercial_client_po(id,organization_id,opportunity_id,contract_id) ON DELETE RESTRICT,
  CONSTRAINT fk_commercial_handoff_quote FOREIGN KEY (quote_version_id,organization_id,opportunity_id)
    REFERENCES commercial_quote_version(id,organization_id,opportunity_id) ON DELETE RESTRICT,
  CONSTRAINT fk_commercial_handoff_project FOREIGN KEY (project_id,organization_id)
    REFERENCES project(id,organization_id) ON DELETE RESTRICT
);

-- Source-versioned outbound contract only: Finance independently owns its invoices.
CREATE VIEW commercial_finance_award_source AS
SELECT h.organization_id,h.opportunity_id,h.project_id,h.quote_version_id,
       h.contract_id,h.client_po_id,q.client_organization_id,q.currency,
       q.total_price,q.payment_terms,q.version AS quote_revision
FROM commercial_project_handoff h
JOIN commercial_quote_version q ON q.id=h.quote_version_id
WHERE q.state='RELEASED';

-- An explicit client-safe projection is a financial boundary; never UNION cost source.
CREATE VIEW commercial_released_client_quote AS
SELECT q.id AS quote_id,q.organization_id,q.opportunity_id,q.client_organization_id,
       q.quote_code,q.version_number,q.currency,q.total_price,q.payment_terms,
       q.delivery_terms,q.exclusions,q.validity_date,q.released_at
FROM commercial_quote_version q
WHERE q.state='RELEASED';

-- Immutable economic/content snapshots, even to SQL callers.
CREATE FUNCTION commercial_reject_snapshot_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Commercial snapshot is immutable; create a new version';
END $$;
CREATE TRIGGER trg_commercial_scope_immutable BEFORE UPDATE OR DELETE
  ON commercial_scope_version FOR EACH ROW EXECUTE FUNCTION commercial_reject_snapshot_mutation();
CREATE TRIGGER trg_commercial_boq_immutable BEFORE UPDATE OR DELETE
  ON commercial_boq_version FOR EACH ROW EXECUTE FUNCTION commercial_reject_snapshot_mutation();
CREATE TRIGGER trg_commercial_cost_line_immutable BEFORE UPDATE OR DELETE
  ON commercial_boq_cost_line FOR EACH ROW EXECUTE FUNCTION commercial_reject_snapshot_mutation();
CREATE TRIGGER trg_commercial_quote_line_immutable BEFORE UPDATE OR DELETE
  ON commercial_quote_line FOR EACH ROW EXECUTE FUNCTION commercial_reject_snapshot_mutation();
CREATE FUNCTION commercial_guard_quote_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (NEW.organization_id,NEW.opportunity_id,NEW.client_organization_id,
     NEW.scope_version_id,NEW.boq_version_id,NEW.version_number,NEW.quote_code,
     NEW.currency,NEW.total_price,NEW.payment_terms,NEW.delivery_terms,
     NEW.exclusions,NEW.validity_date,NEW.created_by,NEW.created_at)
 IS DISTINCT FROM
    (OLD.organization_id,OLD.opportunity_id,OLD.client_organization_id,
     OLD.scope_version_id,OLD.boq_version_id,OLD.version_number,OLD.quote_code,
     OLD.currency,OLD.total_price,OLD.payment_terms,OLD.delivery_terms,
     OLD.exclusions,OLD.validity_date,OLD.created_by,OLD.created_at)
 THEN RAISE EXCEPTION 'Quoted commercial content cannot be edited';
 END IF;
 IF NEW.version <> OLD.version + 1
 THEN RAISE EXCEPTION 'Quote version must advance exactly once'; END IF;
 IF NOT (
   (OLD.state='DRAFT' AND NEW.state IN ('APPROVAL_PENDING','WITHDRAWN'))
   OR (OLD.state='APPROVAL_PENDING' AND NEW.state IN ('RELEASED','WITHDRAWN'))
 ) THEN
   RAISE EXCEPTION 'Quote state transition is not permitted';
 END IF;
 IF NEW.state = 'RELEASED' THEN
   IF NEW.released_at IS NULL
      OR EXISTS (
        SELECT 1 FROM commercial_quote_version later
        WHERE later.opportunity_id=OLD.opportunity_id AND later.version_number>OLD.version_number
      )
      OR NOT EXISTS (
        SELECT 1 FROM approval_request ar
        WHERE ar.subject_type='COMMERCIAL_QUOTE' AND ar.subject_id=OLD.id
          AND ar.organization_id=OLD.organization_id AND ar.subject_version=OLD.version
          AND ar.policy_key='COMMERCIAL_QUOTE_RELEASE' AND ar.state='APPROVED'
      ) THEN RAISE EXCEPTION 'Exact latest quote approval is required for release';
   END IF;
 ELSIF NEW.released_at IS NOT NULL THEN
   RAISE EXCEPTION 'Quote cannot have release timestamp before release';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER trg_commercial_quote_guard BEFORE UPDATE
  ON commercial_quote_version FOR EACH ROW EXECUTE FUNCTION commercial_guard_quote_update();
CREATE TRIGGER trg_commercial_quote_no_delete BEFORE DELETE
  ON commercial_quote_version FOR EACH ROW EXECUTE FUNCTION commercial_reject_snapshot_mutation();


-- Prevent editing finalized signed commercial evidence or verified customer PO.
-- Any amendment requires a new version/document and review.
CREATE FUNCTION commercial_guard_award_document_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_TABLE_NAME='commercial_contract' THEN
   IF OLD.state='SIGNED' THEN
     RAISE EXCEPTION 'Signed commercial contract is immutable';
   END IF;
   IF OLD.state <> 'DRAFT' OR NEW.state <> 'SIGNED'
      OR NEW.signed_evidence_id IS NULL THEN
     RAISE EXCEPTION 'Only verified signing of draft contract is allowed';
   END IF;
 ELSIF TG_TABLE_NAME='commercial_client_po' THEN
   IF OLD.verified=true THEN RAISE EXCEPTION 'Verified client PO is immutable'; END IF;
   IF NEW.verified<>true OR NEW.evidence_id IS NULL
      OR ROW(NEW.id,NEW.organization_id,NEW.opportunity_id,NEW.contract_id,
             NEW.client_po_number,NEW.issued_on,NEW.amount,NEW.currency)
         IS DISTINCT FROM
         ROW(OLD.id,OLD.organization_id,OLD.opportunity_id,OLD.contract_id,
             OLD.client_po_number,OLD.issued_on,OLD.amount,OLD.currency) THEN
     RAISE EXCEPTION 'Only verified evidence attachment is allowed';
   END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER trg_commercial_contract_guard BEFORE UPDATE OR DELETE
  ON commercial_contract FOR EACH ROW
  EXECUTE FUNCTION commercial_guard_award_document_update();
CREATE TRIGGER trg_commercial_client_po_guard BEFORE UPDATE OR DELETE
  ON commercial_client_po FOR EACH ROW
  EXECUTE FUNCTION commercial_guard_award_document_update();
