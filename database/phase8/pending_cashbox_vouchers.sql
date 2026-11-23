-- Phase 8W: physical cash custody, zero-opening cashboxes and source-bound
-- customer receipt / supplier payment vouchers. UNNUMBERED, DEFAULT OFF.
-- No payment service, bank API or synthetic opening money.
-- Requires P8K/L/N/O/P/Q/U (including effective bank allocations).

CREATE TABLE finance_cashbox_opening_proposal (
 id uuid PRIMARY KEY,
 operation_id uuid NOT NULL UNIQUE,
 organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
 cashbox_code varchar(64) NOT NULL CHECK(length(btrim(cashbox_code)) BETWEEN 3 AND 64),
 currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
 custodian_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
 approved_company_reference varchar(180) NOT NULL
    CHECK(length(btrim(approved_company_reference))>=8),
 opening_balance numeric(24,6) NOT NULL CHECK(opening_balance=0),
 subject_version bigint NOT NULL CHECK(subject_version>0),
 policy_key varchar(80) NOT NULL CHECK(policy_key ~ '^[A-Z][A-Z0-9_]{2,79}$'),
 prepared_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
 created_at timestamptz NOT NULL,
 UNIQUE(organization_id,id),UNIQUE(organization_id,cashbox_code),
 CHECK(custodian_user_id<>prepared_by_user_id)
);
CREATE TRIGGER finance_cashbox_opening_proposal_immutable
BEFORE UPDATE OR DELETE ON finance_cashbox_opening_proposal FOR EACH ROW
EXECUTE FUNCTION finance_ap_reject_snapshot_mutation();

CREATE TABLE finance_cashbox_register (
 id uuid PRIMARY KEY,
 organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
 cashbox_code varchar(64) NOT NULL CHECK(length(btrim(cashbox_code)) BETWEEN 3 AND 64),
 currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
 custodian_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
 approved_company_reference varchar(180) NOT NULL
    CHECK(length(btrim(approved_company_reference))>=8),
 opening_balance numeric(24,6) NOT NULL DEFAULT 0 CHECK(opening_balance=0),
 status varchar(16) NOT NULL CHECK(status='ACTIVE'),
 opening_approval_request_id uuid NOT NULL
   REFERENCES approval_request(id) ON DELETE RESTRICT,
 opening_proposal_id uuid NOT NULL,
 activation_operation_id uuid NOT NULL UNIQUE,
 created_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
 created_at timestamptz NOT NULL,
 UNIQUE(organization_id,id),UNIQUE(organization_id,cashbox_code),
 FOREIGN KEY(opening_proposal_id,organization_id)
   REFERENCES finance_cashbox_opening_proposal(id,organization_id) ON DELETE RESTRICT,
 CHECK(created_by_user_id<>custodian_user_id)
);
CREATE FUNCTION finance_cashbox_creation_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE a approval_request%ROWTYPE;
        p finance_cashbox_opening_proposal%ROWTYPE;
BEGIN
 SELECT * INTO p FROM finance_cashbox_opening_proposal
 WHERE id=NEW.opening_proposal_id AND organization_id=NEW.organization_id;
 IF p.id IS NULL OR p.id<>NEW.id
    OR p.cashbox_code<>NEW.cashbox_code
    OR p.currency<>NEW.currency
    OR p.custodian_user_id<>NEW.custodian_user_id
    OR p.approved_company_reference<>NEW.approved_company_reference
    OR p.opening_balance<>NEW.opening_balance
    OR p.prepared_by_user_id<>NEW.created_by_user_id THEN
  RAISE EXCEPTION 'Cashbox must equal approved immutable zero-opening proposal'
   USING ERRCODE='23514';
 END IF;
 SELECT * INTO a FROM approval_request
 WHERE id=NEW.opening_approval_request_id AND organization_id=NEW.organization_id;
 IF a.id IS NULL OR a.state<>'APPROVED'
    OR a.subject_type<>'FINANCE_CASHBOX_OPENING'
    OR a.subject_id<>p.id OR a.subject_version<>p.subject_version
    OR a.policy_key<>p.policy_key
    OR a.requester_user_id<>p.prepared_by_user_id
    OR a.requester_user_id<>NEW.created_by_user_id
    OR a.completed_at IS NULL OR a.completed_at>NEW.created_at
    OR a.requester_user_id=NEW.custodian_user_id
    OR NOT EXISTS(
      SELECT 1 FROM approval_decision d
      WHERE d.approval_request_id=a.id AND d.decision='APPROVE'
        AND d.subject_version=p.subject_version
        AND d.actor_user_id<>NEW.created_by_user_id
        AND d.actor_user_id<>NEW.custodian_user_id
    )
    OR NOT EXISTS(
      SELECT 1 FROM approval_policy_version v
      WHERE v.organization_id=NEW.organization_id
        AND v.policy_key=a.policy_key AND v.version_number=a.policy_version
        AND v.lifecycle_state='ACTIVE' AND v.approval_required=true
        AND v.step_mode='SINGLE' AND v.effective_from<=NEW.created_at
        AND (v.effective_to IS NULL OR v.effective_to>NEW.created_at)
    ) THEN
  RAISE EXCEPTION 'Cashbox opening needs approved company custody with zero money'
    USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER finance_cashbox_creation_guard
BEFORE INSERT ON finance_cashbox_register FOR EACH ROW
EXECUTE FUNCTION finance_cashbox_creation_guard();
CREATE TRIGGER finance_cashbox_immutable
BEFORE UPDATE OR DELETE ON finance_cashbox_register FOR EACH ROW
EXECUTE FUNCTION finance_ap_reject_snapshot_mutation();

CREATE TABLE finance_cash_voucher_proposal (
 id uuid PRIMARY KEY,
 operation_id uuid NOT NULL UNIQUE,
 organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
 cashbox_id uuid NOT NULL,
 source_kind varchar(2) NOT NULL CHECK(source_kind IN('AR','AP')),
 source_journal_id uuid NOT NULL,
 direction varchar(8) NOT NULL CHECK(direction IN('RECEIPT','PAYMENT')),
 currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
 amount numeric(24,6) NOT NULL CHECK(amount>0),
 source_ledger_sha256 char(64) NOT NULL
    CHECK(source_ledger_sha256 ~ '^[a-f0-9]{64}$'),
 voucher_reference varchar(180) NOT NULL
    CHECK(length(btrim(voucher_reference)) BETWEEN 8 AND 180),
 reason_reference varchar(180) NOT NULL
    CHECK(length(btrim(reason_reference)) BETWEEN 8 AND 180),
 approval_subject_version bigint NOT NULL CHECK(approval_subject_version>0),
 policy_key varchar(80) NOT NULL
    CHECK(policy_key ~ '^[A-Z][A-Z0-9_]{2,79}$'),
 prepared_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
 created_at timestamptz NOT NULL,
 UNIQUE(organization_id,id),
 UNIQUE(organization_id,voucher_reference),
 FOREIGN KEY(cashbox_id,organization_id)
   REFERENCES finance_cashbox_register(id,organization_id) ON DELETE RESTRICT,
 CHECK((source_kind='AR' AND direction='RECEIPT') OR
       (source_kind='AP' AND direction='PAYMENT'))
);
CREATE TRIGGER finance_cash_voucher_proposal_immutable
BEFORE UPDATE OR DELETE ON finance_cash_voucher_proposal FOR EACH ROW
EXECUTE FUNCTION finance_ap_reject_snapshot_mutation();

CREATE TABLE finance_cash_voucher_record (
 id uuid PRIMARY KEY,
 operation_id uuid NOT NULL UNIQUE,
 organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
 proposal_id uuid NOT NULL,
 cashbox_id uuid NOT NULL,
 source_kind varchar(2) NOT NULL CHECK(source_kind IN('AR','AP')),
 source_journal_id uuid NOT NULL,
 direction varchar(8) NOT NULL CHECK(direction IN('RECEIPT','PAYMENT')),
 amount numeric(24,6) NOT NULL CHECK(amount>0),
 currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
 approval_request_id uuid NOT NULL REFERENCES approval_request(id) ON DELETE RESTRICT,
 custody_evidence_id uuid NOT NULL REFERENCES evidence(id) ON DELETE RESTRICT,
 recorded_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
 recorded_at timestamptz NOT NULL,
 correlation_id varchar(128) NOT NULL CHECK(length(btrim(correlation_id))>0),
 UNIQUE(organization_id,id),UNIQUE(organization_id,proposal_id),
 FOREIGN KEY(proposal_id,organization_id)
   REFERENCES finance_cash_voucher_proposal(id,organization_id) ON DELETE RESTRICT,
 FOREIGN KEY(cashbox_id,organization_id)
   REFERENCES finance_cashbox_register(id,organization_id) ON DELETE RESTRICT
);

-- Physical cash balance is derived only from immutable approved actual
-- receipts and payments, never from a bank statement or draft invoice.
CREATE VIEW finance_cashbox_balance AS
SELECT b.organization_id,b.id AS cashbox_id,b.cashbox_code,b.currency,
       b.custodian_user_id,
       b.opening_balance+
         COALESCE(SUM(CASE WHEN v.direction='RECEIPT' THEN v.amount
                           ELSE -v.amount END),0::numeric) AS cash_on_hand,
       COUNT(v.id) AS recorded_vouchers
FROM finance_cashbox_register b
LEFT JOIN finance_cash_voucher_record v
  ON v.organization_id=b.organization_id AND v.cashbox_id=b.id
GROUP BY b.organization_id,b.id,b.cashbox_code,b.currency,
         b.custodian_user_id,b.opening_balance;

CREATE FUNCTION finance_cash_voucher_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p finance_cash_voucher_proposal%ROWTYPE;
        b finance_cashbox_register%ROWTYPE;
        a approval_request%ROWTYPE;
        src_hash char(64);
        src_amount numeric(24,6);
        src_currency char(3);
        total_bank numeric(24,6);
        total_cash numeric(24,6);
        current_cash numeric(24,6);
BEGIN
 SELECT * INTO p FROM finance_cash_voucher_proposal
 WHERE id=NEW.proposal_id AND organization_id=NEW.organization_id;
 IF p.id IS NULL OR p.cashbox_id<>NEW.cashbox_id
   OR p.source_kind<>NEW.source_kind
   OR p.source_journal_id<>NEW.source_journal_id
   OR p.direction<>NEW.direction OR p.amount<>NEW.amount
   OR p.currency<>NEW.currency OR p.prepared_by_user_id=NEW.recorded_by_user_id THEN
  RAISE EXCEPTION 'Cash voucher must equal immutable reviewed proposal'
   USING ERRCODE='23514';
 END IF;
 -- All bank and cash allocation writers serialize on the source journal
 -- BEFORE locking their cash carrier (bank statement line / cashbox).
 IF NEW.source_kind='AR' THEN
  SELECT j.gross_amount,j.currency,j.ledger_fingerprint_sha256
   INTO src_amount,src_currency,src_hash
   FROM finance_ar_posted_journal j
   WHERE j.id=NEW.source_journal_id AND j.organization_id=NEW.organization_id
   FOR UPDATE;
 ELSE
  SELECT j.gross_amount,j.currency,j.ledger_fingerprint_sha256
   INTO src_amount,src_currency,src_hash
   FROM finance_ap_posted_journal j
   WHERE j.id=NEW.source_journal_id AND j.organization_id=NEW.organization_id
   FOR UPDATE;
  IF EXISTS(SELECT 1 FROM finance_ap_journal_reversal r
   WHERE r.organization_id=NEW.organization_id
     AND r.original_journal_id=NEW.source_journal_id) THEN
    RAISE EXCEPTION 'Cannot cash-pay reversed supplier payable' USING ERRCODE='23514';
  END IF;
 END IF;
 SELECT * INTO b FROM finance_cashbox_register
  WHERE id=NEW.cashbox_id AND organization_id=NEW.organization_id FOR UPDATE;
 IF b.id IS NULL OR b.status<>'ACTIVE' OR b.currency<>NEW.currency
    OR b.custodian_user_id<>NEW.recorded_by_user_id
    OR src_amount IS NULL OR src_currency<>NEW.currency
    OR src_hash<>p.source_ledger_sha256 THEN
  RAISE EXCEPTION 'Approved company cashbox custodian and source required'
    USING ERRCODE='23514';
 END IF;
 IF (NEW.source_kind='AR' AND NEW.direction<>'RECEIPT') OR
    (NEW.source_kind='AP' AND NEW.direction<>'PAYMENT') THEN
  RAISE EXCEPTION 'Cash voucher direction contradicts accounting source'
   USING ERRCODE='23514';
 END IF;
 IF NOT EXISTS(
  SELECT 1 FROM evidence e
  JOIN evidence_upload_session u ON u.evidence_id=e.id
  WHERE e.id=NEW.custody_evidence_id AND e.organization_id=NEW.organization_id
    AND e.target_type='FINANCE_CASH_VOUCHER'
    AND e.target_id=p.id AND e.storage_state='READY'
    AND u.state='READY' AND e.finalized_at IS NOT NULL
    AND u.finalized_at IS NOT NULL AND e.sha256=u.expected_sha256
    AND e.size_bytes=u.expected_size_bytes
    AND e.object_key_ref IS NOT NULL
    AND e.client_visibility_mode='INTERNAL_ONLY'
 ) THEN
  RAISE EXCEPTION 'Physical signed receipt/payment custody evidence required'
   USING ERRCODE='23514';
 END IF;
 SELECT COALESCE(SUM(allocated_amount),0) INTO total_bank
  FROM finance_cash_allocation_effective
  WHERE organization_id=NEW.organization_id AND source_kind=NEW.source_kind
    AND source_journal_id=NEW.source_journal_id;
 SELECT COALESCE(SUM(amount),0) INTO total_cash
  FROM finance_cash_voucher_record
  WHERE organization_id=NEW.organization_id AND source_kind=NEW.source_kind
    AND source_journal_id=NEW.source_journal_id;
 IF total_bank+total_cash+NEW.amount>src_amount THEN
  RAISE EXCEPTION 'Cash and bank together exceed source invoice'
   USING ERRCODE='23514';
 END IF;
 SELECT COALESCE(SUM(CASE WHEN direction='RECEIPT' THEN amount ELSE -amount END),0)
 INTO current_cash FROM finance_cash_voucher_record
  WHERE organization_id=NEW.organization_id AND cashbox_id=NEW.cashbox_id;
 IF NEW.direction='PAYMENT' AND
    b.opening_balance+current_cash<NEW.amount THEN
  RAISE EXCEPTION 'Cashbox cannot pay money it does not hold'
   USING ERRCODE='23514';
 END IF;
 SELECT * INTO a FROM approval_request
 WHERE id=NEW.approval_request_id AND organization_id=NEW.organization_id;
 IF a.id IS NULL OR a.state<>'APPROVED'
   OR a.subject_type<>'FINANCE_CASH_VOUCHER'
   OR a.subject_id<>p.id OR a.subject_version<>p.approval_subject_version
   OR a.policy_key<>p.policy_key
   OR a.requester_user_id<>p.prepared_by_user_id
   OR a.completed_at IS NULL OR a.completed_at>NEW.recorded_at
   OR a.requester_user_id=NEW.recorded_by_user_id
   OR NOT EXISTS(
      SELECT 1 FROM approval_decision d
      WHERE d.approval_request_id=a.id AND d.decision='APPROVE'
        AND d.subject_version=a.subject_version
        AND d.actor_user_id<>p.prepared_by_user_id
        AND d.actor_user_id<>NEW.recorded_by_user_id)
   OR NOT EXISTS(
      SELECT 1 FROM approval_policy_version v
      WHERE v.organization_id=NEW.organization_id AND v.policy_key=a.policy_key
        AND v.version_number=a.policy_version
        AND v.lifecycle_state='ACTIVE' AND v.approval_required=true
        AND v.step_mode='SINGLE' AND v.effective_from<=NEW.recorded_at
        AND (v.effective_to IS NULL OR v.effective_to>NEW.recorded_at)
   ) THEN
   RAISE EXCEPTION 'Independent approved receipt/payment with custody required'
     USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER finance_cash_voucher_guard
BEFORE INSERT ON finance_cash_voucher_record FOR EACH ROW
EXECUTE FUNCTION finance_cash_voucher_guard();
CREATE TRIGGER finance_cash_voucher_immutable
BEFORE UPDATE OR DELETE ON finance_cash_voucher_record FOR EACH ROW
EXECUTE FUNCTION finance_ap_reject_snapshot_mutation();

-- The original finance_cash_allocation_record_guard comes from Phase 8U.
-- Serialize bank+cash source reservations even when one payment is split
-- between bank and a physical cashbox.
CREATE OR REPLACE FUNCTION finance_cash_bank_source_cap_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE src_amount numeric(24,6);
        bank_amount numeric(24,6);
        cash_amount numeric(24,6);
BEGIN
 IF NEW.source_kind='AR' THEN
   SELECT gross_amount INTO src_amount FROM finance_ar_posted_journal
   WHERE id=NEW.source_journal_id AND organization_id=NEW.organization_id FOR UPDATE;
 ELSE
   SELECT gross_amount INTO src_amount FROM finance_ap_posted_journal
   WHERE id=NEW.source_journal_id AND organization_id=NEW.organization_id FOR UPDATE;
 END IF;
 SELECT COALESCE(SUM(allocated_amount),0) INTO bank_amount
  FROM finance_cash_allocation_effective
  WHERE organization_id=NEW.organization_id AND source_kind=NEW.source_kind
    AND source_journal_id=NEW.source_journal_id;
 SELECT COALESCE(SUM(amount),0) INTO cash_amount
  FROM finance_cash_voucher_record
  WHERE organization_id=NEW.organization_id AND source_kind=NEW.source_kind
    AND source_journal_id=NEW.source_journal_id;
 IF src_amount IS NULL OR bank_amount+cash_amount>src_amount THEN
  RAISE EXCEPTION 'Source invoice cannot be collected/paid twice across bank and cash'
    USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER finance_cash_bank_source_cap_bank
AFTER INSERT ON finance_cash_allocation_record FOR EACH ROW
EXECUTE FUNCTION finance_cash_bank_source_cap_guard();
CREATE TRIGGER finance_cash_bank_source_cap_cash
AFTER INSERT ON finance_cash_voucher_record FOR EACH ROW
EXECUTE FUNCTION finance_cash_bank_source_cap_guard();

-- No top-up, deposit-to-bank, payroll loan recovery, fiscal adjusting entry
-- or opening stock of cash is silently created by this script. All would
-- require separate, company-approved immutable source contracts.
