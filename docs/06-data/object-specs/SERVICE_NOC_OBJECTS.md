# Exact Object Specs — Maintenance, Managed Service & NOC

Status: DOMAIN DATA MODEL v0.1 / FUTURE-CAPABILITY / NOT SCHEMA-FROZEN

---

# ServiceContract

## Fields
- id
- contractCode
- clientOrganizationId
- sourceProjectId — O
- serviceType
- startDate
- endDate
- renewalMode
- commercialTermsRef — RESTRICTED
- state
- documentRef
- version

---

# CoverageRule

## Purpose
Defines what is covered.

## Fields
- id
- serviceContractId
- targetType: PROJECT/SITE/ASSET/ASSET_CLASS/SERVICE
- targetRef
- coverageType
- inclusions
- exclusions
- partsIncluded
- laborIncluded
- validFrom/to
- priority/order

---

# SLADefinition

## Fields
- id
- serviceContractId
- severity
- responseTargetDuration
- resolutionTargetDuration
- serviceHours/calendarRef
- pauseConditions
- escalationPolicyRef
- version

---

# PreventiveSchedule

## Fields
- id
- serviceContractId
- targetRef
- scheduleRule
- checklistTemplateRef
- requiredSkills
- requiredAssets/tools
- nextDueAt
- active
- version

---

# MaintenanceChecklistTemplate

## Fields
- id
- code
- versionNumber
- name
- applicableAsset/site type
- steps
- evidence requirements
- state

Approved template version immutable.

---

# ManagedService

## Fields
- id
- clientOrganizationId
- serviceContractId
- name
- serviceType
- monitoredScope
- state
- startedAt
- endedAt — O

---

# MonitoringProfile

## Fields
- id
- managedServiceId
- integrationConnectionId
- protocol/provider
- collectionPolicy
- healthRuleSetRef
- active

---

# MonitoredNode

## Fields
- id
- monitoringProfileId
- externalNodeId
- assetId — O
- siteId
- name
- nodeType
- vendor/model — O
- managementAddress — HIGHLY_RESTRICTED
- healthState
- lastSeenAt
- metadata

---

# MonitoredEdge

## Fields
- id
- monitoringProfileId
- fromNodeId
- toNodeId
- edgeType
- interface/port refs — O
- healthState
- lastSeenAt

---

# MonitoringSignal

Potential high-volume telemetry object/stream.

Fields conceptually:
- node/edge
- metric
- timestamp
- value
- quality/source

Important:
High-volume telemetry may require separate storage/service later and should not be forced into primary OLTP tables.

---

# MonitoringIncidentLink

## Fields
- id
- monitoringAlertRef
- supportTicketId
- detectedAt
- linkedBy/system rule
- state

---

# ServiceHealthSummary

Derived:
- open incidents
- SLA risk
- preventive compliance
- availability metrics if contractually measured
- repeat failures

Not source of raw telemetry.

---

# Invariants

- SLA comes from exact contract/version.
- Coverage explicit; never assume all client assets covered.
- monitoring health UNKNOWN != HEALTHY.
- telemetry system outage distinguished from client asset outage.
- monitored device credentials remain secret store.
- NOC object relationship can link to canonical Asset/Site without duplicating asset identity.

## Next
Only deepen/freeze if HILTECH actual managed-service/NOC roadmap warrants near-term implementation.
