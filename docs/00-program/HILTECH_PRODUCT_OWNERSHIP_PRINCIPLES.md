# HILTECH Product Ownership, Reality & External Dependency Principles

Date: 2026-09-20  
Status: **OWNER INTENT / GOVERNANCE INPUT — PROPOSED FOR CANONICAL ADOPTION**

## Purpose

This document captures the durable product intent behind HILTECH OS.

It exists to prevent a specific failure mode: an implementation can follow individual technical documents correctly and still produce a product that does not match the intended real-world system.

HILTECH must not become:
- architecture for architecture's sake;
- a collection of technically correct but humanly awkward screens;
- a project that invents business workflows from imagination;
- a product that cannot be demonstrated or used until a long phase sequence is complete;
- a system that requires a shopping list of external providers before the internal product itself works;
- or a "cheap at any cost" system that saves money by accepting avoidable data-loss or security risk.

This document is additive governance. It does **not** instruct agents to stop the active phase, reopen verified phases, discard accepted research, or silently rewrite frozen contracts.

When it exposes a genuine contradiction with a frozen ADR/contract, use the repository's existing change-control process and make the smallest explicit clarification required.

---

# 1. Product thesis

HILTECH OS is a real internal operating system for HILTECH.

It is not a technology demo.

The end state should be a system that Mohamed, Omar, Ahmed, office staff, project managers, warehouse staff, technicians, and other authorized HILTECH people can actually use as part of daily work.

The internal mental model should remain simple:

**person opens HILTECH -> sees what they need now -> performs or resolves an action -> HILTECH pulls authoritative truth from the correct source.**

The architecture may be sophisticated underneath.

The user experience should not expose that sophistication unless it is genuinely useful.

Examples:
- a technician should think in terms of Today / Site / Job / Evidence / Complete;
- Ahmed should see finance/admin work, exceptions, review and reconciliation relevant to his authority;
- Mohamed should see decisions, approvals, company pulse and meaningful exceptions;
- an employee should see the self-service facts they are permitted to see.

OpenFGA, Keycloak, idempotency, projections, sync engines, provider adapters, object storage and infrastructure topology are implementation mechanisms, not user-facing product concepts.

---

# 2. Reality before invention

HILTECH must be grounded in reality.

Before inventing an important workflow, UX pattern, integration, operating rule or provider behavior, perform the appropriate level of reality validation and research.

The preferred sequence is:

1. **HILTECH reality**
   - How does the company actually work today?
   - Who performs the action?
   - What artifact, conversation, spreadsheet, approval or physical action exists now?
   - Where are the exceptions?

2. **Existing products and operational patterns**
   - How do mature systems solve the same class of problem?
   - Which patterns are common because they are genuinely useful?
   - Which patterns would be enterprise theater for HILTECH?

3. **Official documentation / standards**
   - Required for banks, identity, Windows security/distribution, cloud services, hardware vendors, operating-system behavior, regulated integrations and security-sensitive decisions.

4. **HILTECH-specific design**
   - Keep what is useful.
   - Reject what is unnecessary.
   - Adapt the solution to HILTECH's scale, people and operating reality.

Research is part of product development, not an optional polishing step.

Do not invent the wheel when a well-understood pattern exists.

Do not copy another ERP blindly either.

---

# 3. Do not rediscover decisions that are already proven

Reality-first does **not** mean reopening the entire architecture every time a new slice starts.

If a decision is frozen, implemented and supported by repository evidence, treat it as accepted unless new evidence creates a genuine contradiction.

Examples:
- Keycloak + native OIDC/PKCE has accepted architecture and implementation evidence.
- OpenFGA is the accepted authorization projection path.
- Room/local command state, offline replay, idempotency and conflict behavior have accepted evidence.
- Ktor Client is the accepted native networking boundary.
- the modular-monolith direction is accepted.
- the S3-compatible evidence contract is accepted.

Do not ask "what auth system should we use?" again during ordinary feature work.

Research again when:
- a new domain reaches implementation;
- the company reality is not known;
- a real external provider/device/bank/vendor is involved;
- the human workflow has not been proven;
- legal/accounting/security facts are missing;
- or evidence contradicts an existing decision.

---

# 4. HILTECH must become tangible early

Do not hide the product behind many phases of backend work.

The phase plan is an engineering sequence, not permission to postpone using the product until the end.

As real surfaces become available, they should become visible and testable.

Expected progression:
- Phase 3 should make People / workforce truth tangible.
- Phase 4 should make Project / Site / Work tangible.
- Phase 5 should make Warehouse / Assets tangible.
- Phase 6 should be a major product moment: Windows + Android + server + offline + field execution working together.
- Procurement, Finance and Payroll should then accumulate on top of the same product.

By the time the internal core reaches Phase 9, Omar should be able to use HILTECH as a coherent product on laptop and phone, not merely inspect code and documents.

---

# 5. Omar's product-ownership loop

The long-term goal is not for an agent to understand HILTECH better than Omar forever.

The product must become understandable and controllable by its owner.

As each significant surface becomes usable, Omar should be able to answer from direct use:
- What does this screen do?
- Who is it for?
- Why does this action exist?
- What happens when it fails?
- Where does the data come from?
- Is this how HILTECH really works?
- Would Ahmed understand this?
- Would a technician complete this correctly on a real site?
- Is this step necessary?
- What feels awkward when the system is in hand?

That feedback is product evidence.

The system should progressively move under Omar's practical control, not remain an opaque machine run by coding agents.

---

# 6. Phase completion is not only code + CI

CI is necessary.

Passing database, API, authorization and regression tests is necessary.

For human-facing workflows, it is not sufficient.

A slice that introduces or materially changes a human workflow should produce an appropriately representative usable/rendered behavior that can be reviewed.

The product can fail while every test is green if:
- the navigation is confusing;
- a workflow requires unnecessary steps;
- the screen exposes internal technical concepts;
- a role cannot understand what to do next;
- the workflow does not match HILTECH reality.

For human workflows:

**technical correctness + representative human-flow review = meaningful completion.**

Do not call a workflow understood merely because its API passes.

---

# 7. One coherent product

HILTECH should feel like one system.

Avoid creating disconnected mini-products for every role unless a true platform constraint requires it.

Android and Windows may present different surfaces and density appropriate to their use, but they should remain expressions of one product model and one source of authoritative truth.

Role-aware UX does not mean separate business systems.

Different people may overlap responsibilities.

Do not hard-code business authority from job title alone.

Use explicit roles, teams, relationships, delegations and accepted authorization contracts.

---

# 8. External dependency principle

HILTECH should own its business logic, authoritative contracts and core workflow.

External providers should attach to HILTECH.

HILTECH should not become a thin shell around external providers.

This applies to:
- SMS providers;
- email providers;
- push providers;
- bank/payment APIs;
- CCTV/NVR providers;
- access-control systems;
- fingerprint/biometric devices;
- MDM/RMM products;
- cloud-managed infrastructure;
- commercial signing providers;
- future AI providers;
- any external hardware or SaaS introduced later.

The default architectural pattern is:

**HILTECH capability -> provider-neutral contract/adapter -> external implementation when justified.**

---

# 9. Architecture-ready is not purchase authorization

This distinction is mandatory:

**Architecture-ready != purchase**

**Adapter-ready != activation**

**Provider researched != provider required now**

**Provider selected as a production candidate != subscription authorization**

**A phase containing an integration != that integration must be live for the internal core to be usable**

An ADR may research and select a provider to remove architectural uncertainty.

That does not automatically authorize:
- payment;
- subscription;
- certificate purchase;
- hardware procurement;
- new server purchase;
- provider account activation;
- production cutover.

External activation is an operational decision.

It requires a real need and explicit review of cost, benefit, risk and alternatives.

---

# 10. Cost-aware, not cheap

The previous rule must never be interpreted as "anything paid is bad."

HILTECH is not intended to be a toy or a lowest-cost-at-any-risk system.

If a paid service materially:
- protects company data;
- reduces real probability of data loss;
- improves recovery;
- improves security;
- removes dangerous operational burden;
- or provides a reliability level HILTECH genuinely needs,

then paying for it may be the correct decision.

The governing principle is:

**Be cost-aware, not cheap.**

Do not self-host everything merely to prove ownership.

Do not buy managed everything merely because it sounds enterprise.

Choose based on real risk, operational burden, cost and HILTECH's current scale.

---

# 11. Reliability is not optional decoration

Some capabilities are important even when users never see them.

Do not dismiss the following as "overengineering" merely because they are invisible:
- backup outside the primary failure point;
- tested restore;
- safe database migrations;
- rollback / forward-recovery plans;
- audit;
- authorization;
- secret hygiene;
- encryption where appropriate;
- idempotency for critical/financial operations;
- integrity verification;
- monitoring/alerting appropriate to the deployment;
- recovery from a broken client/server release.

A backup is not proven because a backup job says SUCCESS.

A real recovery posture requires confidence that restoration works.

Do not keep the only useful backup on the same machine/storage whose failure would destroy production.

---

# 12. Capability vs provider

Agents must explicitly distinguish the required capability from one provider implementation.

Examples:

## Database

Required capability:
- PostgreSQL-compatible authoritative database;
- safe migrations;
- backup;
- restore;
- isolation/security;
- acceptable performance.

Possible provider implementation:
- OCI Database with PostgreSQL.

Do not confuse the two.

## File / evidence storage

Required capability:
- private object/file storage;
- integrity checking;
- safe delivery;
- backup/retention behavior;
- accepted S3-compatible boundary where applicable.

Possible provider implementation:
- OCI Object Storage.

The storage contract matters more than the vendor name.

## Secret/key handling

Required capability:
- secrets are not committed;
- credentials are access-controlled;
- encryption keys are handled safely;
- rotation/recovery is possible where required.

Possible provider implementation:
- OCI Secret Management / OCI KMS.

Use managed services when they provide justified benefit.

Do not make the business domain depend on an OCI SDK.

## Windows distribution

Required capability:
- safe package generation;
- package integrity;
- update;
- rollback;
- trusted publisher identity when the release context requires it.

Possible production provider implementation:
- DigiCert OV Code Signing + KeyLocker.

Do not confuse trusted signing as a capability with DigiCert as the only conceivable business requirement.

---

# 13. Specific interpretation of OCI

The existing OCI research is valuable and must not be deleted casually.

It provides:
- a researched production topology;
- managed PostgreSQL option;
- private networking;
- Object Storage;
- KMS;
- Secret Management;
- container runtime/registry options;
- observability paths;
- Terraform/IaC;
- DR research.

Preserve that research.

However, provider-level architecture must not be interpreted as "buy every OCI service before HILTECH can be used."

For each OCI-managed component, ask:
- What risk does it solve?
- What operational burden does it remove?
- What does it cost at the expected HILTECH scale?
- What happens if it is not activated yet?
- Is an existing environment safe enough for development/pilot?
- At what point does the managed option become the safer/easier choice?

If the answer supports activation, use it.

If not, keep the provider path ready without making it an artificial blocker.

Do not silently rewrite accepted infrastructure ADRs during feature work. Use explicit change control when clarification is needed.

---

# 14. Specific interpretation of DigiCert / KeyLocker

Keycloak and KeyLocker are unrelated concepts and must not be conflated.

**Keycloak**
- identity/login/session runtime;
- part of the accepted HILTECH identity architecture;
- can be operated as part of HILTECH's own runtime;
- not equivalent to buying an SMS/SaaS provider.

**DigiCert OV Code Signing + KeyLocker**
- researched production path for trusted Windows Authenticode signing and secure private-key custody;
- useful when formal Windows distribution requires that level of trust;
- not required simply to continue internal product development;
- purchase/activation should occur when the release context justifies it.

Preserve the accepted Windows package/update/rollback capability regardless of signing-provider timing.

---

# 15. Notifications

The internal notification/work-attention model should remain useful even without an external provider.

Correct layering:

business event/action  
-> HILTECH Work Queue / Inbox / notification intent  
-> optional external delivery adapter  
-> SMS/email/push/etc. if configured.

If no provider exists:
- do not fake SENT;
- record an honest unavailable/no-provider result;
- keep the internal workflow usable when semantically safe.

Do not make SMS a prerequisite for ordinary HILTECH work unless the business requirement later proves that it truly is.

---

# 16. Banking and payments

HILTECH is not a bank.

Finance/Payroll should own:
- entitlement/calculation facts;
- review;
- approvals;
- payment batches;
- execution intent;
- result recording;
- reconciliation;
- correction/history.

Actual money movement may be performed through:
- a bank API;
- bank host-to-host integration;
- approved bank files;
- bank portal/manual execution;
- cash;
- wallet/other permitted mechanisms;

depending on HILTECH's real banking process.

Do not invent a Bank API.

Do not prevent Finance/Payroll from becoming a usable internal system merely because automated bank execution is not yet available.

Payment UNKNOWN/FAILED/RETURNED states must be treated safely.

Do not blindly retry ambiguous financial execution.

---

# 17. Cameras, doors, access control and physical integrations

Do not invent hardware vendors.

Do not buy devices merely because the roadmap reaches Security/Facilities.

When the phase arrives:
- inventory actual devices/vendor/protocols;
- research official integration paths;
- define the adapter;
- integrate the real environment.

If hardware is not known yet, preserve the internal security/facilities domain and park only the external hardware adapter.

---

# 18. Safe degradation

When an optional external integration is absent:

1. **If a semantically safe internal/manual fallback exists, use it.**
2. **If no safe fallback exists, disable the external capability honestly.**
3. **Do not fake success.**
4. **Do not corrupt the internal workflow simply to claim the integration is implemented.**

Examples:
- no SMS -> HILTECH Inbox/Work Queue still works;
- no bank API -> approved payment batch + manual/portal execution + reconciliation can still work;
- no CCTV API -> other operational workflows continue;
- no production signing certificate -> development/internal test packages continue through the accepted test path, while trusted production release remains gated.

---

# 19. Advanced does not automatically mean better

Do not optimize for architectural impressiveness.

For every proposed complex capability such as:
- Kubernetes;
- microservices;
- Redis;
- Kafka/RabbitMQ;
- multiple active regions;
- elaborate DR;
- real-time hardware automation;
- managed service sprawl;
- advanced AI orchestration;

ask:
- What real problem does this solve now?
- What is the probability and impact of the failure it prevents?
- What does it cost?
- What does it cost to operate?
- Does current HILTECH scale justify it?
- Can it be added later behind an existing boundary without rewriting the product?

If the need is not real and the boundary allows later addition, preparation may be sufficient.

---

# 20. Small-company reality without hard-coding today's company

HILTECH is a real, relatively small and flexible company.

People may wear multiple hats.

Do not invent fake departments or bureaucracy merely because large ERP systems do.

At the same time, do not hard-code today's exact people/structure into the product.

Prefer configurable/versioned operating policy for legitimate company variation, including:
- teams;
- roles;
- delegations;
- approval paths;
- work types;
- evidence requirements;
- categories;
- checklists/templates;
- integration settings;
- thresholds and policy rules where appropriate.

Keep hard invariants in code when they protect correctness, security or integrity.

---

# 21. Finance / Payroll example of the intended philosophy

Finance and Payroll should consume authoritative facts from the rest of HILTECH rather than requiring unnecessary re-entry.

Potential sources include:
- employment state;
- approved overtime;
- advances;
- reimbursements;
- approved work/site facts;
- policy/configuration;
- future attendance facts if/when that domain is validated.

The human role should focus on:
- reviewing;
- handling exceptions;
- controlled adjustments;
- approvals;
- reconciliation.

Do not invent Egyptian tax/social-insurance/legal formulas from assumptions.

Validate actual legal/accounting rules when Payroll reaches implementation.

---

# 22. UX principles

HILTECH should be Arabic-first / RTL where appropriate to the actual users.

Do not expose technical infrastructure vocabulary to ordinary users.

Prefer:
- clear human labels;
- minimal steps;
- context-aware actions;
- exception-driven workflows;
- visible authoritative state;
- explicit offline/queued/conflict state where relevant;
- readable IDs/codes when operationally useful.

Avoid:
- generic CRUD screens for critical workflows;
- giant forms created from database columns;
- fake enterprise dashboards;
- duplicated truth;
- forcing users to understand internal module boundaries.

---

# 23. The surprise / first-real-use goal

The desired first substantial handoff to Mohamed is not:

"HILTECH is theoretically complete, but first buy several providers, certificates and hardware items before it works."

The desired experience is closer to:

"HILTECH is already a real working internal system. Here is the Windows app, here is the phone app, here are the people/projects/work/warehouse/finance/payroll surfaces as they become available, the data is protected, and the remaining external integrations are clearly separated upgrades or activation decisions."

This does not mean production security is optional.

It means core product usability and external provider activation are deliberately separated.

---

# 24. Human review loop for future slices

For each significant slice:

1. Read current repository truth.
2. Identify frozen contracts.
3. Identify unknown company reality.
4. Research real external systems/patterns if needed.
5. Separate capability from provider.
6. Implement the vertical slice.
7. Prove authorization/audit/integrity/regressions.
8. Render or run the human flow where applicable.
9. Put the result in front of Omar early enough to correct product misunderstandings.
10. Leave HILTECH more usable than before the slice.

Do not wait for the entire roadmap before beginning this loop.

---

# 25. Change-control rule

This document does not authorize an agent to rewrite accepted architecture silently.

If a frozen ADR/contract conflicts with these principles:

1. Identify the exact contradiction.
2. Preserve the evidence/research in the old decision.
3. Propose the smallest change.
4. Distinguish required capability from provider/activation detail.
5. Re-run the relevant compatibility/security/data review.
6. Update the canonical decision.
7. Continue implementation.

Do not erase architectural history merely because the interpretation changed.

---

# 26. Current active-work protection

This governance input is **not** a request to stop the current Phase 3 / People implementation.

Do not:
- reopen Phase 0;
- reopen Phase 1;
- reopen Phase 2;
- restart Phase 3;
- discard current Slice 03 work;
- pull onboarding/self-service scope into Slice 03;
- or rewrite unrelated code.

Continue the current slice from actual GitHub reality.

Only apply this document immediately where it changes decision interpretation without violating the frozen current slice.

Durable governance/document clarifications can be handled separately from feature implementation if that reduces risk.

---

# 27. Provider classification to use in future discussions

When an external service appears, classify it explicitly as one of:

### A. REQUIRED CORE CAPABILITY
The system cannot safely or meaningfully exist without the underlying capability.

Examples:
- authoritative database;
- safe authentication;
- authorization;
- backup/recovery;
- secure secret handling;
- audit for sensitive workflows.

### B. REQUIRED RELIABILITY / SECURITY CONTROL
May not be a user feature, but justified to protect real operation.

Examples:
- off-host backup;
- tested restore;
- production TLS;
- signing/trust when required by the distribution environment;
- monitoring proportionate to production risk.

### C. OPTIONAL MANAGED IMPLEMENTATION
A provider-operated way to deliver a capability that HILTECH could otherwise operate differently.

Examples may include:
- managed PostgreSQL;
- managed object storage;
- managed KMS;
- managed secret storage;
- managed runtime.

Activation depends on risk/cost/operations review.

### D. OPTIONAL EXTERNAL INTEGRATION
Adds convenience/automation but should not own the core workflow.

Examples:
- SMS;
- email;
- bank execution API;
- CCTV vendor adapter;
- access-control adapter;
- MDM.

### E. FUTURE / PARKED ADVANCED CAPABILITY
Prepared only when scale, contract, SLA or risk justifies it.

Examples:
- warm DR region;
- high-availability topology beyond current need;
- advanced orchestration infrastructure.

This classification is not immutable; evidence can move an item between classes.

---

# 28. Source-of-truth rule

Conversation memory is not sufficient.

If this product intent is accepted, it must become durable repository guidance and be referenced by the mandatory agent entrypoint.

Future agents should be able to discover:
- what HILTECH is;
- how reality/research should be used;
- how to distinguish capability from provider;
- what external activation means;
- how cost/reliability tradeoffs should be made;
- how Omar is expected to take practical ownership of the product.

The repository should remain the durable handoff between chats, agents and implementation phases.

---

# Final principle

Do not build HILTECH from imagination.

Do not blindly copy another ERP.

Do not turn architecture into a shopping list.

Do not save money by accepting avoidable operational risk.

Build:

**a real HILTECH system grounded in company reality, external research, strong engineering, simple human workflows, tested reliability, progressive owner control, and external services only when their value is justified.**

The product should become something Omar can actually hold, inspect, understand, challenge and operate — not merely something agents can describe.
