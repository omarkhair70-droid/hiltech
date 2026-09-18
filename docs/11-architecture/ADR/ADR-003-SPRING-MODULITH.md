# ADR-003 — Backend Modular Monolith

Status: **ACCEPTED**
Date: 2026-09-18

## Context

HILTECH has many business domains, but they belong to one tightly coordinated operating system.

Premature microservices would add deployment, consistency, observability and operational cost before those boundaries have production evidence.

## Decision

Start the HILTECH backend as a **Spring Modulith modular monolith**.

Rules:
- modules own their domain/application/persistence code,
- no module directly writes another module's tables,
- cross-module behavior uses explicit interfaces/events,
- architecture verification runs in CI,
- internal asynchronous module events may use Spring Modulith's durable publication registry,
- listeners must be idempotent,
- incomplete publications are observable and explicitly recoverable.

## Evidence

SPIKE-10 / GitHub Actions run 35304479330.

Proven:
- module verification,
- transactional WorkCompleted event,
- intentional listener failure,
- durable incomplete publication,
- process/context restart,
- explicit resubmission,
- successful recovery,
- exactly one audit projection.

## Consequences

Positive:
- one deployable backend,
- simpler transactions and operations,
- enforceable module boundaries,
- reliable internal event coordination without mandatory broker.

Costs:
- module discipline is mandatory,
- shared database does not permit cross-module table ownership violations,
- event recovery/observability must be operationalized.

## Broker Position

Do not introduce Kafka/RabbitMQ solely for internal module reliability at the beginning.

A broker may be introduced later only for a verified integration/scale/decoupling requirement.

## Revisit Triggers

Revisit extraction of a module only when there is concrete evidence such as:
- independent scaling requirement,
- separate availability boundary,
- separate release cadence,
- integration isolation,
- organizational ownership,
- unacceptable contention inside the monolith.
