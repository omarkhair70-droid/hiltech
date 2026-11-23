package com.hiltech.server.service

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.projects.ProjectAuthorizationPort
import com.hiltech.server.projects.ProjectSnapshot
import com.hiltech.server.projects.ProjectsPersistencePort
import jakarta.servlet.http.HttpServletRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * P11A is deliberately limited to INTERNAL DRAFT handovers.
 * Client sharing, acceptance and warranty activation have no inferred authority.
 * The controller is OFF until the pending SQL migration is versioned and deployed.
 */
data class HandoverDraftRequest(
    val operationId: String,
    val baseProjectVersion: Long,
    val expectedLatestVersion: Int,
)

data class HandoverWorkFact(
    val workOrderId: UUID,
    val lifecycleState: String,
    val version: Long = 1,
)

data class HandoverRequirementFact(
    val workOrderId: UUID,
    val requirementKey: String,
    val satisfactionState: String,
    val version: Long = 1,
)

data class HandoverDocumentFact(
    val workOrderId: UUID,
    val documentId: UUID,
    val requirementKey: String,
    val revisionId: UUID,
    val evidenceId: UUID,
    val sourceSha256: String,
)

data class HandoverDocumentDto(
    val workOrderId: String,
    val documentId: String,
    val revisionId: String,
    val evidenceId: String,
    val sha256: String,
)

data class HandoverReadinessDto(
    val projectId: String,
    val organizationId: String,
    val documentCount: Int,
    val workOrderCount: Int,
    val missingReasons: List<String>,
    val canSubmitForInternalReview: Boolean,
    val canShareWithClient: Boolean,
    val canAcceptForClient: Boolean,
    val sourceDigest: String,
)

data class HandoverPackageVersionDto(
    val packageId: String,
    val packageVersionId: String,
    val projectId: String,
    val versionNumber: Int,
    val state: String,
    val projectVersionAtSnapshot: Long,
    val sourceDigest: String,
    val missingReasonsAtSnapshot: List<String>,
    val documents: List<HandoverDocumentDto>,
    val createdAt: String,
    val duplicateReplay: Boolean,
)

data class HandoverSnapshotRow(
    val packageId: UUID,
    val versionId: UUID,
    val projectId: UUID,
    val versionNumber: Int,
    val state: String,
    val projectVersion: Long,
    val digest: String,
    val blockersJson: String,
    val createdAt: Instant,
)

object HandoverReadinessPolicy {
    // No client-facing action until source-approved contract/requirements,
    // classification and acceptance roles are available from P10.
    const val MISSING_PACKAGE_POLICY = "HANDOVER_REQUIREMENT_POLICY_NOT_APPROVED"
    const val MISSING_SHARING_POLICY = "CLIENT_SHARING_POLICY_NOT_APPROVED"
    const val MISSING_ACCEPTANCE_AUTHORITY = "CLIENT_ACCEPTANCE_AUTHORITY_NOT_APPROVED"

    fun missing(
        works: List<HandoverWorkFact>,
        requirements: List<HandoverRequirementFact>,
        approved: List<HandoverDocumentFact>,
    ): List<String> {
        val present = approved.map { it.workOrderId to it.requirementKey }.toSet()
        return buildList {
            if (works.isEmpty()) add("PROJECT_WORK_ORDERS_MISSING")
            works.filter { it.lifecycleState !in setOf("ACCEPTED", "CLOSED") }
                .forEach { add("WORK_NOT_ACCEPTED:" + it.workOrderId) }
            requirements.forEach { req ->
                val ref = req.workOrderId.toString() + ":" + req.requirementKey
                when (req.satisfactionState) {
                    "SATISFIED" -> {
                        if ((req.workOrderId to req.requirementKey) !in present) {
                            add("APPROVED_DOCUMENT_MISSING:" + ref)
                        }
                    }
                    "WAIVED" -> {
                        // A Work readiness waiver is NOT a blanket Handover/Client exception.
                        // The actual Phase 11 approval authority has not been provisioned.
                        add("HANDOVER_DOCUMENT_WAIVER_NOT_APPROVED:" + ref)
                    }
                    "NOT_APPLICABLE" -> {
                        add("HANDOVER_DOCUMENT_EXCLUSION_NOT_APPROVED:" + ref)
                    }
                    else -> {
                        // Even an approved Evidence revision must not conceal an unsatisfied
                        // requirement, including PENDING or BLOCKED source states.
                        add("HANDOVER_DOCUMENT_REQUIREMENT_NOT_SATISFIED:" + ref)
                        if ((req.workOrderId to req.requirementKey) !in present) {
                            add("APPROVED_DOCUMENT_MISSING:" + ref)
                        }
                    }
                }
            }
            add(MISSING_PACKAGE_POLICY)
            add(MISSING_SHARING_POLICY)
            add(MISSING_ACCEPTANCE_AUTHORITY)
        }.distinct()
    }

    fun digest(
        documents: List<HandoverDocumentFact>,
        works: List<HandoverWorkFact> = emptyList(),
        requirements: List<HandoverRequirementFact> = emptyList(),
        projectVersion: Long = 0,
    ): String {
        // A source stamp must change if a WorkOrder acceptance or its required
        // document satisfaction changes, not only when the Evidence binary changes.
        val canonical = buildList {
            add("HANDOVER-SOURCE-V3")
            add("PROJECT_VERSION|" + projectVersion)
            works.sortedBy { it.workOrderId.toString() }.forEach {
                add(listOf("WORK", it.workOrderId, it.lifecycleState, it.version).joinToString("|"))
            }
            requirements.sortedWith(
                compareBy({ it.workOrderId.toString() }, { it.requirementKey })
            ).forEach {
                add(listOf("REQUIREMENT", it.workOrderId, it.requirementKey,
                        it.satisfactionState, it.version).joinToString("|"))
            }
            documents.sortedWith(compareBy({ it.documentId.toString() }, { it.revisionId.toString() }))
                .forEach {
                    add(listOf("DOCUMENT", it.workOrderId, it.documentId,
                        it.revisionId, it.evidenceId, it.sourceSha256).joinToString("|"))
                }
        }.joinToString("\n")
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

@Repository
class JdbcHandoverSource(
    private val jdbc: JdbcTemplate,
) {
    fun work(projectId: UUID): List<HandoverWorkFact> =
        jdbc.query(
            "SELECT id, lifecycle_state, version FROM work_order WHERE project_id = ? AND lifecycle_state <> 'CANCELLED' ORDER BY id",
            { rs, _ -> HandoverWorkFact(rs.getObject("id", UUID::class.java), rs.getString("lifecycle_state"), rs.getLong("version")) },
            projectId,
        )

    fun requirements(projectId: UUID): List<HandoverRequirementFact> =
        jdbc.query(
            """
            SELECT wri.work_order_id, wri.requirement_key, wri.satisfaction_state, wri.version
              FROM work_requirement_instance wri
              JOIN work_order wo ON wo.id = wri.work_order_id
             WHERE wo.project_id = ?
               AND wo.lifecycle_state <> 'CANCELLED'
               AND wri.requirement_family = 'DOCUMENT'
               AND wri.required = true
             ORDER BY wri.work_order_id, wri.requirement_key
            """.trimIndent(),
            { rs, _ ->
                HandoverRequirementFact(
                    rs.getObject("work_order_id", UUID::class.java),
                    rs.getString("requirement_key"),
                    rs.getString("satisfaction_state"),
                    rs.getLong("version"),
                )
            },
            projectId,
        )

    /**
     * Only latest revisions, backed by READY evidence, with actual approved
     * release. An older approved revision does not hide a newer pending one.
     * Never copies the private binary or grants client visibility.
     */
    fun approvedDocuments(projectId: UUID, organizationId: UUID): List<HandoverDocumentFact> =
        jdbc.query(
            """
            SELECT d.work_order_id, d.id AS document_id, d.requirement_key,
                   r.id AS revision_id, e.id AS evidence_id, e.sha256
              FROM work_document d
              JOIN work_order wo ON wo.id = d.work_order_id
              JOIN work_document_revision r
                ON r.document_id = d.id AND r.organization_id = d.organization_id
              JOIN work_document_revision_release rel ON rel.revision_id = r.id
              LEFT JOIN approval_request ar ON ar.id = rel.approval_request_id
              JOIN evidence e ON e.id = r.evidence_id
             WHERE wo.project_id = ?
               AND d.organization_id = ?
               AND wo.lifecycle_state <> 'CANCELLED'
               AND e.organization_id = d.organization_id
               AND e.work_order_id = d.work_order_id
               AND e.target_type = 'WORK_ORDER'
               AND e.target_id = d.work_order_id
               AND e.storage_state = 'READY'
               AND NOT EXISTS(
                   SELECT 1 FROM evidence superseding
                    WHERE superseding.supersedes_evidence_id = e.id
                      AND superseding.storage_state = 'READY'
               )
               AND (rel.release_mode = 'NO_APPROVAL_REQUIRED'
                    OR (rel.release_mode = 'APPROVAL_REQUEST' AND ar.state = 'APPROVED'))
               AND NOT EXISTS (
                   SELECT 1 FROM work_document_revision newer
                    WHERE newer.document_id = r.document_id
                      AND newer.revision_sequence > r.revision_sequence
               )
             ORDER BY d.work_order_id, d.id
            """.trimIndent(),
            { rs, _ ->
                HandoverDocumentFact(
                    workOrderId = rs.getObject("work_order_id", UUID::class.java),
                    documentId = rs.getObject("document_id", UUID::class.java),
                    requirementKey = rs.getString("requirement_key"),
                    revisionId = rs.getObject("revision_id", UUID::class.java),
                    evidenceId = rs.getObject("evidence_id", UUID::class.java),
                    sourceSha256 = rs.getString("sha256"),
                )
            },
            projectId,
            organizationId,
        )

    fun lockProject(projectId: UUID) {
        val rows = jdbc.query("SELECT id FROM project WHERE id = ? FOR UPDATE", { rs, _ -> rs.getObject(1, UUID::class.java) }, projectId)
        if (rows.isEmpty()) problem("HANDOVER_PROJECT_MISSING", HttpStatus.NOT_FOUND)
    }

    fun ensurePackage(project: ProjectSnapshot, actor: UUID, now: Instant): UUID {
        val id = stableUuid("handover-package:" + project.projectId)
        jdbc.update(
            """
            INSERT INTO service_handover_package(id, organization_id, project_id, created_by, created_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (project_id) DO NOTHING
            """.trimIndent(),
            id, project.organizationId, project.projectId, actor, now.atOffset(ZoneOffset.UTC),
        )
        return jdbc.queryForObject(
            "SELECT id FROM service_handover_package WHERE project_id = ? AND organization_id = ?",
            UUID::class.java, project.projectId, project.organizationId,
        ) ?: problem("HANDOVER_PARENT_MISSING", HttpStatus.CONFLICT)
    }

    fun latestVersion(packageId: UUID): Int =
        jdbc.queryForObject(
            "SELECT COALESCE(MAX(version_number),0) FROM service_handover_package_version WHERE package_id = ?",
            Int::class.java, packageId,
        ) ?: 0

    fun byOperation(operationId: UUID): HandoverSnapshotRow? =
        jdbc.query(
            """
            SELECT package_id, id, project_id, version_number, state, project_version_at_snapshot,
                   source_digest, blockers_json::text AS blockers, created_at
              FROM service_handover_package_version
             WHERE created_operation_id = ?
            """.trimIndent(),
            { rs, _ -> snapshotRow(rs) },
            operationId,
        ).singleOrNull()

    fun latest(projectId: UUID): HandoverSnapshotRow? =
        jdbc.query(
            """
            SELECT package_id, id, project_id, version_number, state, project_version_at_snapshot,
                   source_digest, blockers_json::text AS blockers, created_at
              FROM service_handover_package_version WHERE project_id = ?
             ORDER BY version_number DESC LIMIT 1
            """.trimIndent(),
            { rs, _ -> snapshotRow(rs) },
            projectId,
        ).singleOrNull()

    fun insertDraft(
        project: ProjectSnapshot,
        packageId: UUID,
        versionId: UUID,
        version: Int,
        operationId: UUID,
        actor: UUID,
        digest: String,
        blockers: List<String>,
        documents: List<HandoverDocumentFact>,
        now: Instant,
    ) {
        val blockersJson = buildJsonArray { blockers.forEach { add(JsonPrimitive(it)) } }.toString()
        jdbc.update(
            """
            INSERT INTO service_handover_package_version
            (id, package_id, organization_id, project_id, version_number, state,
             created_operation_id, project_version_at_snapshot, source_digest,
             blockers_json, created_by, created_at)
            VALUES (?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, CAST(? AS jsonb), ?, ?)
            """.trimIndent(),
            versionId, packageId, project.organizationId, project.projectId, version,
            operationId, project.version, digest, blockersJson, actor, now.atOffset(ZoneOffset.UTC),
        )
        documents.forEach { source ->
            jdbc.update(
                """
                INSERT INTO service_handover_package_item
                    (id, package_version_id, organization_id, project_id, work_order_id,
                     document_id, revision_id, evidence_id, source_sha256)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                stableUuid("handover-item:" + versionId + ":" + source.documentId),
                versionId, project.organizationId, project.projectId, source.workOrderId,
                source.documentId, source.revisionId, source.evidenceId, source.sourceSha256,
            )
        }
    }

    fun snapshotDocuments(versionId: UUID): List<HandoverDocumentDto> =
        jdbc.query(
            """
            SELECT work_order_id, document_id, revision_id, evidence_id, source_sha256
              FROM service_handover_package_item
             WHERE package_version_id = ?
             ORDER BY document_id
            """.trimIndent(),
            { rs, _ ->
                HandoverDocumentDto(
                    rs.getObject("work_order_id", UUID::class.java).toString(),
                    rs.getObject("document_id", UUID::class.java).toString(),
                    rs.getObject("revision_id", UUID::class.java).toString(),
                    rs.getObject("evidence_id", UUID::class.java).toString(),
                    rs.getString("source_sha256"),
                )
            },
            versionId,
        )

    private fun snapshotRow(rs: java.sql.ResultSet) =
        HandoverSnapshotRow(
            packageId = rs.getObject("package_id", UUID::class.java),
            versionId = rs.getObject("id", UUID::class.java),
            projectId = rs.getObject("project_id", UUID::class.java),
            versionNumber = rs.getInt("version_number"),
            state = rs.getString("state"),
            projectVersion = rs.getLong("project_version_at_snapshot"),
            digest = rs.getString("source_digest"),
            blockersJson = rs.getString("blockers"),
            createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
        )
}

@Service
class HandoverService(
    private val projects: ProjectsPersistencePort,
    private val authorization: ProjectAuthorizationPort,
    private val sources: JdbcHandoverSource,
    private val commands: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val clock: Clock,
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun readiness(projectId: UUID, actor: UUID): HandoverReadinessDto {
        val project = authorizedProject(projectId, actor, true)
        val works = sources.work(projectId)
        val docs = sources.approvedDocuments(projectId, project.organizationId)
        val requirements = sources.requirements(projectId)
        val missing = HandoverReadinessPolicy.missing(works, requirements, docs)
        return HandoverReadinessDto(
            projectId.toString(), project.organizationId.toString(), docs.size, works.size,
            missing, canSubmitForInternalReview = false,
            canShareWithClient = false, canAcceptForClient = false,
            sourceDigest = HandoverReadinessPolicy.digest(docs, works, requirements, project.version),
        )
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun latest(projectId: UUID, actor: UUID): HandoverPackageVersionDto? {
        authorizedProject(projectId, actor, true)
        return sources.latest(projectId)?.toDto(false)
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    fun createDraft(
        projectId: UUID,
        actor: UUID,
        operationId: UUID,
        expectedProjectVersion: Long,
        expectedLatestVersion: Int,
        correlationId: String,
    ): HandoverPackageVersionDto {
        if (expectedProjectVersion < 1 || expectedLatestVersion < 0) {
            problem("HANDOVER_INVALID_BASE_VERSION", HttpStatus.BAD_REQUEST)
        }
        val project = authorizedProject(projectId, actor, true)
        val versionId = stableUuid("handover-version:" + operationId)
        val executed = commands.execute(
            IdempotentCommandSpec(
                operationId = operationId,
                actorUserId = actor,
                commandType = "HANDOVER_DRAFT_SNAPSHOT",
                targetType = "SERVICE_HANDOVER_VERSION",
                targetId = versionId,
                requestFingerprint = IdempotencyKeyContract.fingerprint(
                    listOf(projectId, expectedProjectVersion, expectedLatestVersion).joinToString("|")
                ),
                correlationId = correlationId,
            ),
        ) {
            sources.lockProject(projectId)
            val fresh = authorizedProject(projectId, actor, true)
            if (fresh.version != expectedProjectVersion) {
                problem("HANDOVER_PROJECT_SOURCE_STALE", HttpStatus.CONFLICT)
            }
            val packageId = sources.ensurePackage(fresh, actor, clock.instant())
            val latest = sources.latestVersion(packageId)
            if (latest != expectedLatestVersion) {
                problem("HANDOVER_PACKAGE_VERSION_STALE", HttpStatus.CONFLICT)
            }
            val docs = sources.approvedDocuments(projectId, fresh.organizationId)
            val works = sources.work(projectId)
            val requirements = sources.requirements(projectId)
            val reasons = HandoverReadinessPolicy.missing(works, requirements, docs)
            val digest = HandoverReadinessPolicy.digest(docs, works, requirements, fresh.version)
            val at = clock.instant()
            sources.insertDraft(
                fresh, packageId, versionId, latest + 1, operationId,
                actor, digest, reasons, docs, at,
            )
            audit.append(
                AuditEventRecord(
                    actorUserId = actor,
                    action = "HANDOVER_DRAFT_SNAPSHOT_CREATED",
                    targetType = "SERVICE_HANDOVER_VERSION",
                    targetId = versionId,
                    newStateRef = "DRAFT",
                    safeDiffJson = """{"visibility":"INTERNAL_ONLY","requiresSourceApproval":true}""",
                    occurredAt = at,
                    correlationId = correlationId,
                )
            )
            IdempotentCommandOutcome("HANDOVER_DRAFT_CREATED")
        }
        return (sources.byOperation(operationId)
            ?: problem("HANDOVER_SNAPSHOT_MISSING", HttpStatus.CONFLICT))
            .toDto(executed.replayed)
    }

    private fun authorizedProject(projectId: UUID, actor: UUID, manage: Boolean): ProjectSnapshot {
        val project = projects.project(projectId, clock.instant())
            ?: problem("HANDOVER_PROJECT_NOT_FOUND", HttpStatus.NOT_FOUND)
        val allowed = if (manage) authorization.canManageProject(actor, project)
            else authorization.canViewProject(actor, project)
        if (!allowed) problem("HANDOVER_PROJECT_NOT_FOUND", HttpStatus.NOT_FOUND)
        return project
    }

    private fun HandoverSnapshotRow.toDto(replayed: Boolean): HandoverPackageVersionDto =
        HandoverPackageVersionDto(
            packageId.toString(), versionId.toString(), projectId.toString(), versionNumber,
            state, projectVersion, digest,
            Json.parseToJsonElement(blockersJson).jsonArray.map { it.jsonPrimitive.content },
            sources.snapshotDocuments(versionId), createdAt.toString(), replayed,
        )
}

@RestController
@RequestMapping("/v1/projects/{projectId}/handover")
@ConditionalOnProperty(
    prefix = "hiltech.phase11.handover",
    name = ["enabled"],
    havingValue = "true",
)
class HandoverController(private val service: HandoverService) {
    @GetMapping("/readiness")
    fun readiness(request: HttpServletRequest, @PathVariable projectId: UUID): HandoverReadinessDto =
        service.readiness(projectId, principal(request))

    @GetMapping("/packages/latest")
    fun latest(request: HttpServletRequest, @PathVariable projectId: UUID): HandoverPackageVersionDto =
        service.latest(projectId, principal(request))
            ?: problem("HANDOVER_PACKAGE_NOT_FOUND", HttpStatus.NOT_FOUND)

    @PostMapping("/packages")
    fun snapshot(
        request: HttpServletRequest,
        @PathVariable projectId: UUID,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestBody body: HandoverDraftRequest,
    ): HandoverPackageVersionDto {
        val operationId = runCatching { UUID.fromString(body.operationId) }
            .getOrElse { problem("HANDOVER_INVALID_OPERATION_ID", HttpStatus.BAD_REQUEST) }
        IdempotencyKeyContract.requireMatches(idempotencyKey, operationId)
        val ctx = HiltechRequestContext.current(request)
        return service.createDraft(
            projectId, principal(request), operationId,
            body.baseProjectVersion, body.expectedLatestVersion, ctx.correlationId,
        )
    }

    private fun principal(request: HttpServletRequest): UUID =
        HiltechRequestContext.current(request).identityId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: problem("UNAUTHENTICATED", HttpStatus.UNAUTHORIZED)
}

private fun stableUuid(source: String): UUID =
    UUID.nameUUIDFromBytes(source.toByteArray(StandardCharsets.UTF_8))

private fun problem(code: String, status: HttpStatus): Nothing =
    throw ProductApiException(code = code, message = "Handover action is unavailable.", status = status)
