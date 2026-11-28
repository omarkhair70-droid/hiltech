# Financing source contract — Financial Core

A company-approved financing agreement is setup, not a receipt or a journal.
The same Company Settings authority, recent reauthentication, idempotent command
and audit contract as other financial configuration is used. No new permissions
are automatically granted.

Source classifications:
- OWNER_CAPITAL: approved EQUITY account.
- OWNER_LOAN: approved OTHER_LIABILITY account.
- EXTERNAL_LOAN: approved OTHER_LIABILITY account.

Each immutable source requires a company-attested authorization reference,
unique agreement reference, funder name/reference, currency, positive exact
authorized amount, and an eligible company chart account. The registered
amount is an agreed ceiling, not paid-in capital, bank balance, or loan
principal outstanding. No production funder, amount, interest rate or maturity
date is assumed. The funding agreement reference is not a bank account number.

The existing native financial setup surface lists and registers these sources.
The service validates account eligibility and database triggers enforce scope,
classification and immutability. Creation does not create an AP/AR record,
expense, bank movement or ledger journal.

Follow-up: independently reviewed confirmed financing receipts, shared bank
allocation capacity, separate principal repayments and GL posting against
the approved source; historical loan schedules and cash handling require
their own source contracts. Do not report a live balance until these flows
are integrated and verified.
