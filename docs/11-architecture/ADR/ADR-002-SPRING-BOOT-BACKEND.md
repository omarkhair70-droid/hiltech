# ADR-002 — Backend Application Framework

Status: **ACCEPTED**
Date: 2026-09-18

## Decision

Use **Kotlin/JVM + Spring Boot** as the primary HILTECH backend application framework.

Current proven line:
- Kotlin 2.4.20,
- Java 21,
- Spring Boot 4.1.1.

## Evidence

SPIKE-10 / GitHub Actions run 35304479330 successfully booted, transacted, persisted, restarted and recovered a representative HILTECH backend scenario.

## Why

The framework supports:
- transactional application services,
- JDBC/PostgreSQL ecosystem,
- OIDC/security integration,
- modular architecture tooling,
- observability ecosystem,
- mature operations/deployment.

## Boundary

This ADR does not mandate microservices. The accepted initial architecture is a modular monolith.

## Revisit Triggers

Revisit only if a verified HILTECH requirement cannot be met operably with the JVM/Spring ecosystem.
