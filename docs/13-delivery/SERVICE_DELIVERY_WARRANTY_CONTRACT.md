# Service delivery and warranty source contract

## Operational boundary

The Windows and Android handover workspace uses the same source-owned commands for handover versions, internal document checks, reviewed release, recorded client response, withdrawal, warranty terms, incident creation and coverage decisions. Projects, agreements, original documents, scopes and incidents are selected by name. Dates are explicit calendar selections. No warranty duration, contractual response time, client signature, acceptance, billing permission or external communication is inferred.

Handover preparation reuses current project management authority. A separately named, current active employee reviews the exact proposal and its linked evidence. That review assignment does not grant general project management or access to unrelated project files. Company administration exposes two fixed, required independent review workflows: `SERVICE_HANDOVER_REVIEW` and `SERVICE_WARRANTY_REVIEW`. There is no additional service preparation role layered over project management.

`hiltech.service.delivery.enabled` is enabled by default and remains an explicit incident switch. It is separate from source authority, evidence readiness and independent review. Legacy support and handover source endpoints retain their documented configuration boundaries.

## Frozen source and historical records

Migration V0121 adds immutable proposals, proof references, review display receipts and recorded events. Each proposal pins the project version, current workflow policy and reviewer binding, exact predecessor, scope, agreement, approved work-document revisions and evidence hashes. Document waiver and exclusion findings, including their recorded reasons, are part of the review packet and source stamp. A release requires accepted work, current approved document releases and an explicit assessment of any permitted source exceptions. A no-approval document release is accepted only when its exact source policy did not require approval.

The general approval engine checks the same source-owned independence and proof constraints as the service controller. An approved request alone does not issue a package or record a coverage result: the service command records the corresponding immutable event after validating the source again. A stale pending request can be superseded without approval. Reviewer replacement and stale policy maintenance use the bounded company administration workflow.

Client responses record what was actually received: outcome, date, representative name and capacity, source confirming that representative's authority, and the original response document. This is an internal record of evidence, not a generated client electronic signature. A response cannot be carried from an earlier release to a replacement release. Corrections reference the preceding event; withdrawal stops export of that release without deleting it or its previous response.

Warranty terms record explicit scope, item, dates, agreement reference, written terms, exclusions and response obligations. An incident is opened using the existing support service and its project/version checks. Human-readable impact and internal queue priority choices do not create an SLA. Coverage proposals use the actual incident, approved terms and original incident proof. A covered result must fit the approved dates and scope and cannot contradict a recorded applicable exclusion. An insufficient-information result remains distinct from covered and not covered. Later corrections retain the earlier decision. Support ticket coverage and warranty readiness read the latest recorded source decision.

## Original evidence and export

Service evidence uses the existing reservation, private upload and finalization lifecycle. A document slot is scoped to a project and purpose, and its creator must still manage that project when reserving or finalizing. Internal agreements and response evidence are highly restricted. The independent reviewer can access only evidence pinned to their currently assigned proposal.

The source reader rechecks current access before and after storage reads and verifies original byte size and SHA-256. `ProtectedDocumentRasterizer` has no endpoint or authorization responsibility. It renders one authorized PDF, PNG or JPEG page at a time, with a 16 MiB input limit, PDF page limits, image dimension/pixel limits and a maximum page edge of 1800 pixels. Unsupported formats fail without a review receipt. The shared viewer validates PNG dimensions before decoding and keeps pages in session memory, without a document cache or external viewer.

Page delivery and native display are separate receipts. Approval requires a matching current viewer, proposal version, proof hash and confirmed display for each required original. A display receipt records that the document was opened in the workflow; it does not attest to human comprehension. Current reviewer revocation, policy replacement, source changes and proof mismatches prevent a current-source approval. Decision endpoints use the existing recent reauthentication policy.

An authorized project manager can export an active approved handover as a ZIP containing a bilingual index and the exact work-document originals, or save those originals individually. The package excludes private agreements, client responses and review notes. Export does not send anything externally. Native export verifies the expected hash before the user's system save operation.

## Command recovery

Commands preserve an operation identifier and frozen request across uncertain transport responses. A confirmed write with a failed refresh is shown separately from an unconfirmed write. Reauthentication pauses the saved request. Access denial following an earlier uncertain result does not discard that unresolved request. Upload recovery checks the exact finalized evidence metadata before requesting a file again; a lost finalization response does not create a second document.

## Verification surfaces

The source tests are `ServiceDeliverySourcesTest`, `ServiceDeliveryApprovalSubjectTest`, `ServiceDeliveryEvidenceReaderTest`, `ProtectedDocumentRasterizerTest` and `ServiceDeliveryWorkflowTest`. `ServiceDeliveryPostgresContractTest` exercises actual PostgreSQL migrations, project source authority, support ticket creation/replay, the approval engine, proof display, handover export, client response/correction/withdrawal, warranty terms, coverage/correction, stale source and revocation. Its private storage and authorization transport use explicit synthetic fixtures; it does not claim a real device, object-store upload or customer signature.

The PostgreSQL contract runs with `HILTECH_DB_CONTRACT_TEST=1` and the existing `HILTECH_DB_URL`, `HILTECH_DB_USER`, `HILTECH_DB_PASSWORD` and `HILTECH_MIGRATIONS_PATH` settings. Unit, contract, composition, actual device and deployment evidence must be reported separately. Compilation or a mocked transport journey alone is not operational acceptance.
