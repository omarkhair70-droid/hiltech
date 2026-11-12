# Phase 5 Slice 02 execution evidence

## Engineering baseline

Slice 01 merged at `79d451d1426a7eeb97ba43ef98d1cadb0bb18e78`. Bootstrap passed on that commit through the workflow's existing `bootstrap/**` push trigger. The verification branch points at the merge commit. [Bootstrap 43258278634](https://github.com/omarkhair70-droid/hiltech/actions/runs/43258278634)

## Stock operator flow

1. Select the Warehouse in Desktop Warehouse Setup and open Stock Inventory. Register the approved canonical UOM. Select an active Stock category revision from HILTECH configuration when creating a Stock master.
2. For an imported master, review the staged row against its private original and save the explicit candidate classification, description, unit and quantity.
3. The authorized deployment operator can record independent source verification in `stock_import_verification` after HILTECH approves the private evidence. The record names the source row and exact version, workbook SHA-256, approved location and category, verification identity, designated cutover approver, exact cutover timestamp, remaining issue codes explicitly resolved by the evidence, and a private evidence reference. The verifier and approver are distinct. Slice 02 provides the approval and opening commands and uses this record as their explicit cutover authority.
4. The designated current Warehouse Manager approves the verified row as a Stock item. At the approved cutover time, initialize its exact quantity using the approved destination. A verified zero quantity creates no movement. Check the opening receipt, balance and source row in Stock Inventory.
5. A current operator with authority at both locations can post a transfer. Refresh the item to see the resulting on-hand balances and movement. Repeating an identical operation ID returns the existing receipt. If a response is lost, retry with that operation ID before submitting a new transfer.

StockBalance is projected in the movement transaction. `stock_ledger_on_hand` derives on-hand quantities from ledger entries and supports reconciliation. Inspect differences with a full join on item and location in a disposable copy before any projection repair. Native masters can be created before cutover and show their opening evidence state.

## Acceptance boundary

Engineering tests use synthetic units, inventory, locations and source approvals. Real-data acceptance requires the original UTP, FIBER, ACTIVE and Accessories files, verified mappings, a confirmed storage location and cutover approval. Use the approved private evidence location described in the [Slice 01 operator runbook](./SLICE01_EXECUTION_EVIDENCE_2026-11-08.md). Human review, merge, and post-merge Bootstrap follow Issue #100. 
