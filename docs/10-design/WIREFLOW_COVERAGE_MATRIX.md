# HILTECH Wireflow Coverage Matrix

Status: ACTIVE TRACKER

## Completed — Representative Critical Flows

| Flow | Mobile | Desktop | Offline | Conflict/Error | Cross-role | Status |
|---|---|---|---|---|---|---|
| Mohamed approval | Primary | Supported | Review only | Yes | Ahmed/requester | v0.1 |
| Ahmed payroll | Exception | Primary | No final actions | Yes | Mohamed/employee | v0.1 |
| PM project control | Secondary | Primary | Cached/limited | Yes | Field/Warehouse/Client | v0.1 |
| Technician field job | Primary | N/A | Core | Yes | Supervisor/PM | v0.1 |
| Warehouse asset checkout | Primary | Supporting | Limited | Yes | Technician/PM | v0.1 |
| Client approval/support | Primary | Supported | Read/cache | Yes | PM/Support/Finance | v0.1 |

---

# Next Wireflows Required

## Sales / Tender
```text
Lead -> Discovery -> BOQ/Costing -> Quote -> Approval -> Submission -> Win -> Project Handoff
```

## Procurement
```text
Requirement -> Stock Check -> RFQ -> Quotes -> Comparison -> Approval -> PO -> Delivery -> Match -> Payable
```

## New Hire
```text
Invite -> Documents -> Role -> Training -> Asset/Access -> First Day -> Active
```

## Offboarding
```text
Start -> Project Transfer -> Asset Return -> Access Revoke -> Finance -> Final Payroll -> Former
```

## Engineer/Test
```text
Assigned technical job -> Drawing -> Test -> Evidence -> Technical Review -> Accept/Rework
```

## Handover
```text
Completeness -> Missing artifacts -> Package -> Internal Review -> Client Review -> Accept -> Warranty
```

## Maintenance
```text
Schedule -> Visit -> Checklist -> Findings -> Follow-up -> Complete -> Next Due
```

## Supplier
```text
RFQ -> Quote -> PO -> Confirm -> Delivery -> Invoice -> Status
```

## Security
```text
Critical event -> Correlation -> Review -> Incident -> Resolution
```

## Search / Command
```text
Global query -> result -> authorized actions -> context navigation
```

---

# Coverage Rule

Before final UI navigation freeze:
- every major persona has at least one representative end-to-end wireflow.
- every high-risk command appears in at least one wireflow.
- every primary cross-device handoff is tested.
- representative offline/error states exist.
- client/external user flow tested.
- Arabic/RTL is applied to prototypes.

Wireflow complete as a planning artifact does NOT mean final UX complete.
