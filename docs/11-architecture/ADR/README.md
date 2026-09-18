# HILTECH Architecture Decision Records (ADR)

Status: ACTIVE

## Purpose

An ADR records a consequential technical/architecture decision after research/spike evidence.

Format:

- ADR ID.
- Title.
- Status: PROPOSED / ACCEPTED / SUPERSEDED / REJECTED.
- Date.
- Context.
- Decision.
- Alternatives.
- Evidence / spike.
- Consequences.
- Revisit trigger.

## Expected ADRs Before Freeze

- ADR-001 Client platform: KMP/Compose vs alternatives.
- ADR-002 Backend framework: Spring Boot.
- ADR-003 Modular monolith / Spring Modulith.
- ADR-004 PostgreSQL.
- ADR-005 Persistence: jOOQ.
- ADR-006 Local DB: Room/SQLite.
- ADR-007 Networking: Ktor Client.
- ADR-008 Authentication: Keycloak/OIDC or alternative.
- ADR-009 Authorization: OpenFGA vs app policy.
- ADR-010 Object storage/provider.
- ADR-011 Offline/sync protocol.
- ADR-012 Windows packaging/update.
- ADR-013 Observability stack.
- ADR-014 Infrastructure provider/IaC.
- ADR-015 CI/CD.
- ADR-016 Temporal decision: exclude/conditional.
- ADR-017 Redis decision: exclude/conditional.
- ADR-018 Search strategy.

Do not mark ACCEPTED until relevant evidence exists.
