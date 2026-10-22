# SPIKE-09 — OpenFGA HILTECH Authorization Model

Status: RUNNING

## Candidate

OpenFGA v1.20.0.

## Goal

Determine whether HILTECH object/action authorization can be expressed clearly without a giant hard-coded role matrix or an unmaintainable relationship graph.

## HILTECH-shaped model

Types:
- user
- organization
- project
- work_order
- asset
- invoice
- payroll_run
- purchase_order

Organization relations:
- member
- employee
- owner
- finance
- warehouse
- procurement
- approval_delegate

Representative checks include:
- Mohamed / PM / technician / client can view authorized Project A.
- supplier cannot view Project A.
- technician and warehouse can checkout project asset.
- client cannot checkout asset.
- Mohamed/Ahmed can view payroll.
- technician/client cannot view payroll.
- finance/client can view the authorized client invoice.
- technician/supplier cannot view that invoice.
- procurement/supplier can view the relevant PO.
- client cannot view supplier PO.
- owner/PM can approve project variation.
- Ahmed cannot approve variation until temporarily delegated.
- delegation tuple grants the permission.
- deleting delegation removes the permission.

## Boundary

OpenFGA is evaluated for **object/action authorization**.

Field-level sensitive-data filtering remains an application/server responsibility.

The spike does not move payroll field redaction or finance data classification into OpenFGA.

## Pass criteria

ACCEPT if:
- model validates on real OpenFGA,
- all allow/deny checks produce expected results,
- external organizations stay isolated,
- role + resource relationship composition remains readable,
- temporary delegation can be granted/revoked without model rewrite,
- no relation explosion is required for these representative HILTECH cases.

MODIFY/REJECT if the model becomes obscure or requires excessive tuples/relations for basic HILTECH questions.

## Production status

Disposable authorization proof only.
Not production authz infrastructure.
