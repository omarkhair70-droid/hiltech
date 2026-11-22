# P7 closure lane: office/admin goods and external services
Source branch baseline: latest Phase7 PR #158 head; create a NEW child branch / PR, do not edit #158 from two chats.
Current real functionality includes WorkOrder, Warehouse replenishment, Asset incident/maintenance spare parts, RFQ/quote, technical approval, approved PO and Warehouse receiving/delivery-note/Finance match; PRs unmerged.

OFFICE/FACILITY GOODS must have a real current requester/member, approved company cost/department context, stock-vs-asset-vs-nonstock classification, actual delivery location and separately bound manager and purchaser rights. Existing stocked consumables may use canonical StockItem. A new laptop/machine is not automatically "stock consumed": physical receipt and registration in Asset Passport are distinct. No fabricated WorkOrder, warehouse, company department or supplier.

EXTERNAL SERVICE needs independent service specification, deliverables/milestones, supplier quote and PO, service owner acceptance/rework/partial delivery, and an invoice match sourced from signed service acceptance. It CANNOT reuse StockItem and Warehouse Receiving as fake goods (existing S02/S03/S05 are stock-oriented). A service PO requires an additional typed line/acceptance model and source fingerprint. Actual accounting obligations pass only to Finance.

Work order: source contract + type discriminator + current authorization; bounded office-goods vertical to PO/receipt; bounded service vertical to service acceptance/invoice; full Android+Windows multi-role proof, wrong org/duplicate/stale/withdraw/partial/rejection, migration/rollback, ordered PR merge. No external sends or live money movement.
