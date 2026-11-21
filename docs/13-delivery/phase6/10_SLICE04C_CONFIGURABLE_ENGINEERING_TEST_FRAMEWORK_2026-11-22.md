# Phase 6 / Slice 04C â€” Configurable Engineering Test Framework

Date: 2026-11-22
Status: **ENGINEERING FRAMEWORK IMPLEMENTED / DEFAULT OFF / REAL PROJECT TEMPLATES NOT SEEDED**

## Decision

S04C no longer hard-codes an OTDR, Fluke or other vendor form.

The system uses the existing versioned Configuration + EvidencePolicy model:

`WorkType â†’ EvidencePolicy revision â†’ Evidence requirement â†’ ENGINEERING_TEST_SCHEMA template revision`.

A project can therefore bind a real Fiber, Copper, commissioning or other engineering test without changing the application domain. The template defines the data shape; the Work/Evidence policy remains the source of what is required for that WorkType.

No HILTECH-specific threshold is seeded by this framework.

## Generic schema contract

An `ENGINEERING_TEST_SCHEMA` template may define:

- display label and tested-subject label;
- whether raw Evidence is required;
- whether an instrument Asset is required;
- whether valid calibration is required;
- whether a device-reported status may be preserved;
- metric definitions.

Each metric can define:

- stable key + label;
- value type: `DECIMAL | INTEGER | TEXT | BOOLEAN | ENUM`;
- required/optional;
- engineering dimension;
- unit code;
- numeric precision;
- optional min/max acceptance limits;
- ENUM `options`;
- optional `acceptedValues` used for configured PASS/FAIL evaluation.

`options` controls what may be entered. `acceptedValues` controls which allowed qualitative values are considered passing. They are deliberately separate.

If a metric has no configured acceptance rule, its evaluation is `UNKNOWN`; the server never invents a PASS/FAIL threshold.

## Version and provenance binding

Every captured result records exact:

- Organization;
- WorkOrder + WorkOrder version;
- WorkAssignment + assignment version;
- capturing identity + device installation;
- EvidencePolicy ID + revision;
- requirement key;
- ENGINEERING_TEST_SCHEMA template ID + revision + schema version;
- tested subject reference;
- normalized values;
- optional instrument Asset;
- optional raw READY Evidence;
- optional device-reported status;
- server evaluated status;
- capture/client timestamps;
- correlation ID;
- optional superseded result.

The write path rechecks current assignment, Work authorization, WorkOrder version, policy revision and schema revision before insert.

## Instrument and calibration

Instrument identity is a canonical HILTECH Asset, not free-form equipment text.

Android accepts an opaque AssetTag for the instrument, resolves it through the existing assigned-Work Asset lookup, then sends the resulting Asset ID.

If the schema requires calibration, the server reads canonical Asset service/calibration state. A locally typed â€œcalibratedâ€ flag cannot bypass that source.

## Raw machine/report evidence

A schema can require a raw Evidence artifact.

The structured result may link only to Evidence that is already `READY` and belongs to the same:

- Organization;
- WorkOrder;
- requirement;
- EvidencePolicy revision;
- capturing actor.

This lets future HILTECH templates combine typed values with an OTDR/Fluke PDF/file/image without treating the file alone as structured truth.

## Immutable results and retests

`field_engineering_test_result` is append-only.

UPDATE/DELETE are rejected by PostgreSQL. A retest/correction creates a new result and may reference the exact prior result through `supersedes_result_id`. Only one direct successor is allowed for a given prior result.

No old test result is rewritten when configuration or measurements change.

## Android surface

The assigned WorkOrder JobBundle now carries the structured test definition with its Evidence requirement.

The Android field UI renders the metrics dynamically from that definition:

- labels;
- units;
- choices;
- configured range;
- precision;
- subject reference;
- instrument AssetTag when required.

Submission is live-only in this checkpoint. A cached/offline JobBundle can display the definition but cannot authorize a structured-result write.

If raw Evidence is required, the field UI blocks structured submission until matching READY Evidence exists.

## Rollout

Server:
`HILTECH_FIELD_ENGINEERING_TESTS_ENABLED=false`

Android:
`hiltech.engineeringTestsEnabled=false`

Both remain OFF by default.

The framework is not a production declaration and does not seed an imaginary HILTECH Fiber acceptance policy.

## What still remains project configuration, not application code

For a real Fiber project HILTECH can later create, for example:

- `OTDR_FIBER_ACCEPTANCE`;
- `FLUKE_COPPER_CERTIFICATION`;
- another internal/custom engineering test.

The configuration must supply its actual metrics, units, limits and requirements. Different client/project rules become different immutable template/policy revisions instead of code branches.

## Drawing context remains independent

This framework does not fabricate the missing authoritative drawing-revision source.

The existing JobBundle document-ref surface stays read-only. Drawing revision lifecycle remains a separate reality/source integration gate.

## Proof gates

Dedicated CI must prove:

- test-engine unit behavior;
- shared API client contract;
- Android compile with engineering tests explicitly enabled;
- V0036 migration on real PostgreSQL;
- immutable result storage;
- default-OFF rollout.

Before production activation, representative project configuration and technician/engineer acceptance remain required.

`P6_S04C_TEST_FRAMEWORK = IMPLEMENTED_DEFAULT_OFF`
`P6_S04C_VENDOR_HARDCODING = NONE`
`P6_S04C_REAL_PROJECT_TEMPLATE_SEED = NOT_YET_CONFIGURED`
`P6_S04C_DRAWING_SOURCE = STILL_SEPARATE`
