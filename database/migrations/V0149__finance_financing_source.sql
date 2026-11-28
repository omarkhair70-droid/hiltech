-- Approved financing terms are not bank receipts or accounting journals.
CREATE TABLE finance_financing_source (
 id uuid PRIMARY KEY,
 operation_id uuid NOT NULL UNIQUE,
 organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
 source_kind varchar(24) NOT NULL CHECK(source_kind IN('OWNER_CAPITAL','OWNER_LOAN','EXTERNAL_LOAN')),
 agreement_reference varchar(64) NOT NULL CHECK(length(agreement_reference) BETWEEN 2 AND 64 AND agreement_reference=btrim(agreement_reference) AND agreement_reference !~ '[[:cntrl:]]'),
 funder_reference varchar(180) NOT NULL CHECK(length(funder_reference) BETWEEN 2 AND 180 AND funder_reference=btrim(funder_reference) AND funder_reference !~ '[[:cntrl:]]'),
 currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
 authorized_amount numeric(24,6) NOT NULL CHECK(authorized_amount>0),
 control_account_id uuid NOT NULL,
 authorization_reference varchar(160) NOT NULL CHECK(length(authorization_reference) BETWEEN 8 AND 160 AND authorization_reference=btrim(authorization_reference) AND authorization_reference !~ '[[:cntrl:]]'),
 registered_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
 registered_at timestamptz NOT NULL,
 UNIQUE(organization_id,id),
 FOREIGN KEY(organization_id,control_account_id) REFERENCES finance_gl_account(organization_id,id) ON DELETE RESTRICT
);
CREATE UNIQUE INDEX finance_financing_source_agreement_unique
 ON finance_financing_source(organization_id,lower(agreement_reference));
CREATE FUNCTION finance_financing_source_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE expected_kind text; account finance_gl_account%ROWTYPE;
BEGIN
 PERFORM 1 FROM organization WHERE id=NEW.organization_id AND status='ACTIVE' AND organization_type='HILTECH' FOR UPDATE;
 IF NOT FOUND OR NOT EXISTS(
    SELECT 1 FROM organization_membership m JOIN user_identity u ON u.id=m.user_identity_id
    WHERE m.organization_id=NEW.organization_id AND u.id=NEW.registered_by_user_id AND u.status='ACTIVE'
      AND m.state='ACTIVE' AND m.valid_from<=NEW.registered_at
      AND (m.valid_until IS NULL OR m.valid_until>NEW.registered_at))
 THEN RAISE EXCEPTION 'Current company membership required' USING ERRCODE='23514'; END IF;
 expected_kind:=CASE WHEN NEW.source_kind='OWNER_CAPITAL' THEN 'EQUITY'
                     WHEN NEW.source_kind IN('OWNER_LOAN','EXTERNAL_LOAN') THEN 'OTHER_LIABILITY' END;
 SELECT * INTO account FROM finance_gl_account a WHERE a.organization_id=NEW.organization_id AND a.id=NEW.control_account_id FOR SHARE;
 IF expected_kind IS NULL OR NOT FOUND OR account.state<>'APPROVED'
    OR account.account_kind<>expected_kind OR (account.currency IS NOT NULL AND account.currency<>NEW.currency)
 THEN RAISE EXCEPTION 'Financing classification must match the approved company equity or liability account' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER finance_financing_source_guard BEFORE INSERT ON finance_financing_source
 FOR EACH ROW EXECUTE FUNCTION finance_financing_source_guard();
CREATE TRIGGER finance_financing_source_immutable BEFORE UPDATE OR DELETE ON finance_financing_source
 FOR EACH ROW EXECUTE FUNCTION finance_ap_reject_snapshot_mutation();
