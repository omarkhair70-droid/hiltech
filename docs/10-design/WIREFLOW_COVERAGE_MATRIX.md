# HILTECH Wireflow Coverage Matrix

Status: STRONG FIRST PASS / REPRESENTATIVE FLOWS COVERED

## Completed — Representative Critical Flows

| Flow | Mobile | Desktop | Offline | Conflict/Error | Cross-role | Status |
|---|---|---|---|---|---|---|
| Mohamed approval | Primary | Supported | Review only | Yes | Ahmed/requester | v0.1 |
| Ahmed payroll | Exception | Primary | No final actions | Yes | Mohamed/employee | v0.1 |
| PM project control | Secondary | Primary | Cached/limited | Yes | Field/Warehouse/Client | v0.1 |
| Technician field job | Primary | N/A | Core | Yes | Supervisor/PM | v0.1 |
| Warehouse asset checkout | Primary | Supporting | Limited | Yes | Technician/PM | v0.1 |
| Client approval/support | Primary | Supported | Read/cache | Yes | PM/Support/Finance | v0.1 |
| Sales/tender -> project | Field support | Primary | Partial draft | Yes | PM/Owner | v0.1 |
| Procurement | Secondary | Primary | Partial capture | Yes | Warehouse/Finance/Supplier | v0.1 |
| New hire/onboarding | Primary | HR desktop | Partial | Yes | HR/Manager/Assets | v0.1 |
| Offboarding | Employee limited | Primary | No final | Yes | HR/PM/Warehouse/Finance/Security | v0.1 |
| Engineer/test | Primary | Primary review | Core capture | Yes | Field/PM/Handover | v0.1 |
| Handover -> maintenance | Client mobile | Primary | Read/cache | Yes | PM/Client/Support | v0.1 |
| Supplier | Supported | Primary | Limited | Yes | Procurement/Warehouse/Finance | v0.1 |
| Security incident | Critical mobile | Primary | Provider-dependent | Yes | Owner/Warehouse | v0.1 |
| Global search/command | Supported | Primary | Recent/cache | Yes | all roles | v0.1 |

---

# Coverage Achieved

Representative flows now cover:
- every main internal role.
- client.
- supplier.
- subcontractor indirectly through work model.
- executive decision.
- financial decision.
- field offline.
- physical asset custody.
- project control.
- commercial acquisition.
- employee lifecycle.
- handover/service.
- security.
- global object navigation.

---

# Still Needed Before UX Freeze

## Detailed micro-wireflows
Not every sub-action needs its own large document, but representative prototypes must still test:
- leave/expense.
- variation.
- stocktake.
- partial delivery.
- payment unknown outcome.
- support SLA breach.
- camera provider unavailable.
- multi-role user.
- document superseded.
- sync conflict recovery.

## Visual wireframes
Current wireflows define behavior, not layout.

Need representative layout prototypes for:
1. Mohamed Home + Approval.
2. Ahmed Payroll.
3. PM Project Command Center.
4. Technician Today/Job.
5. Warehouse Scan/Asset.
6. Client My HILTECH.
7. Search/Command.
8. Shared object detail.
9. Inbox/Work queue.

## Arabic / RTL
All representative wireframes must exist/test in Arabic RTL and English LTR.

## Adaptive
Representative screens need:
- phone.
- large Android/tablet.
- Windows desktop.

---

# Coverage Rule

Wireflow stage is strong first pass when:
- every major persona has representative end-to-end flow,
- core cross-role handoffs appear,
- high-risk actions/conflicts are represented,
- offline field path exists,
- external user flows exist.

That gate is now satisfied as a planning artifact.

Next:
SCREEN INVENTORY -> REPRESENTATIVE WIREFRAMES -> VISUAL SYSTEM.
