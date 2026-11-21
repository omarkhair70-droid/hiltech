# Phase 6 / Slice 04B — Native Material Usage Capture & Offline Replay

Date: 2026-11-22  
Status: **ENGINEERING IMPLEMENTED / DEFAULT OFF / CANONICAL STOCK MUTATION REMAINS WAREHOUSE-OWNED**

## Purpose

The backend already accepts an immutable field material-usage report and keeps it in `PENDING_REVIEW`. The remaining field gap was native technician capture and durable replay when connectivity is lost.

This checkpoint connects that backend to the existing Phase-6 pending-command runtime. It does **not** add a second stock ledger and it does not subtract issued stock locally.

## User flow

For a technician with a live, current assigned WorkOrder and a current personal-issued-stock projection:

1. Open the issued-material line.
2. Enter the exact quantity used.
3. The app creates one durable `ReportMaterialUsage` intent with a stable operation UUID.
4. The intent is stored in the existing Room `pending_command` table.
5. If online, WorkManager/replay may send it immediately.
6. If connectivity disappears, the same operation survives process death/restart and replays later.
7. The server revalidates:
   - current assignment;
   - WorkOrder/source version;
   - requirement/item;
   - issued-stock source version;
   - canonical unit;
   - outstanding quantity.
8. Success means the immutable report reached `PENDING_REVIEW`. It still does **not** mean stock moved.
9. Stock movement occurs only through the separately reviewed canonical warehouse reconciliation/CONSUME path.

## No local stock mutation

The Android UI continues showing the authoritative outstanding issued balance returned by the server.

A local usage intent is displayed separately as:

- PENDING / RETRYABLE / SYNCING;
- APPLIED = server accepted the immutable report into PENDING_REVIEW;
- CONFLICT = source changed and explicit recovery is required;
- TERMINAL_FAILED / CANCELLED_LOCAL.

The app never computes a fake new outstanding balance by subtracting local intents.

## Capture authority boundary

A **new** usage intent requires a WorkOrder and issued-stock snapshot verified online in the current signed-in session.

A cold-start cached JobBundle is read-only. Previously captured intents can still be displayed and replayed, but stale cached Work is not authority to originate a new material report.

This keeps offline durability without turning cached Work/stock into authorization.

## Exact quantity rules

The client uses an exact decimal parser rather than binary floating point:

- positive only;
- up to 14 integer digits;
- up to 6 fractional digits;
- no implicit unit conversion or rounding.

Before queueing, the client also checks:

`unresolved local quantity for the same item/requirement/source version + new quantity <= verified outstanding`.

This is a UX/conservation guard only. The server remains authoritative and revalidates on replay.

## Durable command contract

Command type:

`ReportMaterialUsage`

Target:

`WorkOrder`

Wire endpoint:

`POST /v1/work-orders/{workOrderId}/material-usage-reports`

Durable payload records:

- assignmentId / assignmentVersion;
- workVersion;
- requirementId;
- stockItemId;
- issuedVersion;
- exact quantity;
- canonical unitCode.

Local display metadata is prefixed with `_local` and stripped by the transport before network transmission.

The existing pending-command contract provides:

- stable operationId;
- client occurrence time;
- actor/device isolation;
- process-death durability;
- retry/backoff;
- conflict preservation.

## Conflict behavior

For material usage, 403/404/409 are not silently retried as success.

The local command becomes an explicit conflict with recovery actions such as:

- refresh issued stock;
- review local work;
- contact supervisor.

The original local intent is preserved for supervised recovery/audit rather than rewritten against a new source version.

## Android surface

The existing “my issued materials” card now optionally renders:

- outstanding quantity/source version;
- exact usage quantity input;
- “record material usage” action;
- durable local intent states.

Feature flag:

`hiltech.materialUsageReportingEnabled=false`

The personal-issued-stock feature remains separately gated.

## Reuse

No new Room schema/table is introduced.

This slice reuses:

- `pending_command`;
- `PendingCommandReplayEngine`;
- `KtorPendingCommandTransport`;
- Android WorkManager sync;
- the already-built immutable server material report;
- the separately-built warehouse review + canonical reconciliation.

## Proof gates

Dedicated CI must prove:

- queue survives database close/reopen;
- stale cached session cannot originate a new report;
- multiple unresolved local intents cannot overcommit the verified outstanding amount;
- transport uses the frozen endpoint;
- local display metadata is never sent;
- conflict response preserves explicit recovery;
- server report unit contract still passes;
- Android compiles with material reporting explicitly enabled.

## Non-claims

- APPLIED does not mean stock consumed.
- No offline Warehouse CONSUME exists.
- No field technician Warehouse permission is created.
- No automatic unit conversion is added.
- Production feature flag is not enabled.
- Real technician/storekeeper acceptance remains separate from engineering CI.

`P6_S04B_NATIVE_MATERIAL_CAPTURE = IMPLEMENTED_DEFAULT_OFF`  
`P6_S04B_OFFLINE_REPLAY = EXISTING_PENDING_COMMAND_RUNTIME`  
`P6_S04B_LOCAL_STOCK_MUTATION = FORBIDDEN`
