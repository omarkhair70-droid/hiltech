# HILTECH Authorization Matrix

Status: DISCOVERED / NOT FROZEN

Legend:
- FULL = broad capability within authorized context
- OWN = own/self only
- ASSIGNED = assigned project/site/work only
- CONTEXT = limited context needed to perform job
- POLICY = depends on policy/threshold/delegation
- VIEW = read only
- NO = denied by default

| Capability | Owner | Finance | PM | Engineer | Supervisor | Technician | Warehouse | Procurement | HR/Admin | Sales | Client | Supplier | Subcontractor |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Company pulse | FULL | VIEW | CONTEXT | NO | NO | NO | NO | NO | CONTEXT | CONTEXT | NO | NO | NO |
| Project ops | FULL | CONTEXT | ASSIGNED/FULL | ASSIGNED | ASSIGNED | ASSIGNED | CONTEXT | CONTEXT | CONTEXT | CONTEXT | CLIENT-OWN | NO | ASSIGNED |
| Internal project cost | FULL | FULL | POLICY | NO | NO | NO | POLICY | CONTEXT | NO | POLICY | NO | NO | NO |
| Payroll run | POLICY | FULL | NO | NO | NO | OWN payslip | NO | NO | CONTEXT | NO | OWN payslip only | NO | NO |
| Payment execution | POLICY | POLICY | NO | NO | NO | NO | NO | NO | NO | NO | NO | NO | NO |
| Employee HR record | POLICY | CONTEXT | CONTEXT | OWN | OWN | OWN | NO | NO | FULL | NO | NO | NO | NO |
| Warehouse inventory | FULL | CONTEXT | CONTEXT | CONTEXT | CONTEXT | OWN custody | FULL | CONTEXT | CONTEXT | NO | CLIENT assets only | PO context only | ASSIGNED |
| Asset checkout/return | POLICY | NO | REQUEST | REQUEST | POLICY | OWN/REQUEST | FULL | NO | NO | NO | NO | NO | POLICY |
| Stock adjustment | POLICY | CONTEXT | NO | NO | NO | NO | REQUEST | NO | NO | NO | NO | NO | NO |
| Procurement request | POLICY | CONTEXT | FULL | CONTEXT | CONTEXT | REQUEST | CONTEXT | FULL | NO | CONTEXT | NO | NO | NO |
| Supplier quote comparison | POLICY | CONTEXT | POLICY | technical context | NO | NO | NO | FULL | NO | POLICY | NO | OWN quote only | NO |
| Sales/tenders | VIEW/FULL | CONTEXT | CONTEXT | technical input | NO | NO | NO | CONTEXT | NO | FULL | client-specific | NO | opportunity-specific |
| Client invoices | FULL | FULL | CONTEXT | NO | NO | NO | NO | CONTEXT | NO | CONTEXT | CLIENT-OWN | OWN invoices | OWN milestones |
| Cameras/security | POLICY | NO | NO | NO | NO | NO | POLICY if warehouse role | NO | POLICY | NO | NO | NO | NO |
| Approvals | POLICY | POLICY | POLICY | technical only | POLICY | limited | limited | POLICY | POLICY | POLICY | client approvals | supplier confirmations | subcontract acceptance |
| Audit history | FULL/POLICY | CONTEXT | CONTEXT | CONTEXT | CONTEXT | OWN/limited | CONTEXT | CONTEXT | CONTEXT | CONTEXT | CLIENT-OWN | OWN | OWN |

This matrix is intentionally coarse. It must later be replaced/augmented by object/action/context rules.
