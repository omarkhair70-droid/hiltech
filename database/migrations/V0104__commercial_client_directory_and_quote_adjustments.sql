CREATE TABLE commercial_client_relationship (
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    client_organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    PRIMARY KEY (organization_id,client_organization_id)
);
INSERT INTO commercial_client_relationship(organization_id,client_organization_id)
SELECT DISTINCT organization_id,client_organization_id FROM commercial_opportunity ON CONFLICT DO NOTHING;
ALTER TABLE commercial_quote_version
    ADD COLUMN discount_amount numeric(20,4) NOT NULL DEFAULT 0 CHECK (discount_amount >= 0),
    ADD COLUMN tax_amount numeric(20,4) NOT NULL DEFAULT 0 CHECK (tax_amount >= 0);
CREATE OR REPLACE FUNCTION commercial_guard_quote_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT' THEN
   IF NEW.state<>'DRAFT' OR NEW.released_at IS NOT NULL OR NEW.version<>1 THEN
     RAISE EXCEPTION 'Quote must begin as an unreleased draft';
   END IF;
   RETURN NEW;
 END IF;
 IF (NEW.organization_id,NEW.opportunity_id,NEW.client_organization_id,
     NEW.scope_version_id,NEW.boq_version_id,NEW.version_number,NEW.quote_code,
     NEW.currency,NEW.total_price,NEW.discount_amount,NEW.tax_amount,NEW.payment_terms,NEW.delivery_terms,
     NEW.exclusions,NEW.validity_date,NEW.created_by,NEW.created_at)
 IS DISTINCT FROM
    (OLD.organization_id,OLD.opportunity_id,OLD.client_organization_id,
     OLD.scope_version_id,OLD.boq_version_id,OLD.version_number,OLD.quote_code,
     OLD.currency,OLD.total_price,OLD.discount_amount,OLD.tax_amount,OLD.payment_terms,OLD.delivery_terms,
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
 IF NEW.state = 'APPROVAL_PENDING' AND (
   NOT EXISTS(SELECT 1 FROM commercial_quote_line WHERE quote_version_id=OLD.id)
   OR OLD.discount_amount > (SELECT SUM(quantity*unit_price) FROM commercial_quote_line WHERE quote_version_id=OLD.id)
   OR OLD.total_price IS DISTINCT FROM (
     SELECT SUM(quantity*unit_price) - OLD.discount_amount + OLD.tax_amount FROM commercial_quote_line WHERE quote_version_id=OLD.id
   )
 ) THEN
   RAISE EXCEPTION 'Cannot approve an empty or amount-mismatched quotation';
 END IF;
 IF NEW.state = 'RELEASED' THEN
   IF NEW.released_at IS NULL
      OR EXISTS (
        SELECT 1 FROM commercial_quote_version later
        WHERE later.opportunity_id=OLD.opportunity_id AND later.version_number>OLD.version_number
      )
      OR OLD.validity_date < (now() AT TIME ZONE 'UTC')::date
      OR EXISTS (SELECT 1 FROM commercial_scope_version newer
                 WHERE newer.opportunity_id=OLD.opportunity_id
                   AND newer.version_number > (
                     SELECT src.version_number FROM commercial_scope_version src WHERE src.id=OLD.scope_version_id))
      OR EXISTS (SELECT 1 FROM commercial_boq_version newer
                 WHERE newer.opportunity_id=OLD.opportunity_id
                   AND newer.version_number > (
                     SELECT src.version_number FROM commercial_boq_version src WHERE src.id=OLD.boq_version_id))
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
