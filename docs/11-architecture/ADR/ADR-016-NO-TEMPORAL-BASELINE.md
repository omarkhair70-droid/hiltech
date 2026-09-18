# ADR-016 — Temporal / Workflow Engine Baseline

Status: **ACCEPTED — EXCLUDE FROM INITIAL BASELINE**
Date: 2026-09-18

## Context

HILTECH has business workflows, approvals, reminders, payment reconciliation and maintenance schedules.

A workflow engine can be valuable for very long-running distributed orchestration, but adding one prematurely introduces:
- another state store,
- worker fleet,
- deployment/upgrade surface,
- operational semantics developers must understand,
- duplicated state if the domain model is not disciplined.

## Decision

Do **not** include Temporal or another durable workflow engine in the initial HILTECH production stack.

Initial orchestration uses:
- explicit domain state machines,
- authoritative PostgreSQL state,
- Spring transactions,
- Spring Modulith events for internal reactions,
- background/scheduled application jobs,
- idempotent commands,
- explicit UNKNOWN/reconciliation states for external outcomes.

This is an exclusion decision, not a permanent ban.

## Why This Fits Current HILTECH

Current verified requirements are primarily:
- stateful business objects,
- approvals,
- scheduled reminders/checks,
- offline command replay,
- event-driven internal reactions.

These are representable without a separate orchestration platform.

## Introduce Temporal Only If Evidence Shows

Examples:
- multi-day/month workflows with many durable timers and compensations,
- high-volume fan-out orchestration that becomes difficult to operate in the modular monolith,
- repeated hand-built retry/state machinery across many integrations,
- service extraction creates genuinely distributed sagas.

## Consequences

Positive:
- fewer moving parts,
- one authoritative business state model,
- lower operations burden,
- avoids workflow-engine-shaped domain design.

Cost:
- application code owns scheduler/job discipline initially.

## Revisit Trigger

A concrete workflow must demonstrate that PostgreSQL state + jobs + Modulith events are becoming materially less reliable or maintainable than a durable workflow engine.
