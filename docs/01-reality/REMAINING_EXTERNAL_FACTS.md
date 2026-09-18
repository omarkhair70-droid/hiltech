# HILTECH Remaining External Facts Register

Status: ACTIVE

These are the only major company-specific facts that cannot be honestly invented from architecture documents.

## Management / Authority
- [ ] real approval thresholds.
- [ ] real delegation rules.
- [ ] exact org/reporting lines.
- [ ] asset write-off authority.
- [ ] finance/payment authority.

## Finance
- [ ] payroll inputs/process.
- [ ] bank/provider/payment method.
- [ ] accounting software.
- [ ] e-invoice/e-tax workflow.
- [ ] employee advance rules.
- [ ] financial imprest rules.
- [ ] expense/reimbursement rules.
- [ ] reconciliation process.

## Projects / Field
- [ ] one real project lifecycle.
- [ ] real progress/reporting method.
- [ ] site connectivity/device restrictions.
- [ ] drawing revision workflow.
- [ ] OTDR/Fluke/test artifacts.

## Warehouse
- [ ] real asset categories.
- [ ] stock units/categories.
- [ ] current issue/return process.
- [ ] calibration/repair process.
- [ ] physical layout.
- [ ] CCTV/access hardware.
- [ ] scanners/printers.

## Procurement
- [ ] real approval chain.
- [ ] supplier quote/PO formats.
- [ ] receiving/matching process.
- [ ] emergency purchasing.

## HR
- [ ] employee categories.
- [ ] attendance system.
- [ ] onboarding documents.
- [ ] leave/overtime rules.
- [ ] offboarding clearance.
- [ ] retention/legal requirements.

## Sales
- [ ] current BOQ/costing/quote flow.
- [ ] quote approval.
- [ ] project handoff.

## Devices / Integrations
- [ ] employee Android sample.
- [ ] office Windows sample.
- [ ] bank portal/system.
- [ ] camera/NVR vendor/model.
- [ ] access-control vendor/model.
- [ ] attendance vendor/model.
- [ ] test-equipment software.

## Important

Technical feasibility work is complete; SPIKE-01 through SPIKE-15 are accepted.

These facts now block only the directly affected reality/schema/policy/design freeze decisions.

For the first production vertical, use:
`FIRST_PRODUCTION_SLICE_REALITY_CLOSURE.md`

The first-slice subset that is genuinely blocking is primarily:
- authority/delegation for Work/Asset actions,
- one real Project/Site lifecycle,
- one representative field WorkOrder/evidence flow,
- warehouse physical/custody reality,
- field/warehouse device restrictions,
- current Project/Asset data sources/import path.

Finance/payroll/bank facts remain blocking for those later domains but should not be invented merely to make the first Project/Work/Warehouse slice look complete.
