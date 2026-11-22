package com.hiltech.server.work

import com.hiltech.server.platform.ProductApiException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID

const val WORK_DOCUMENT_REVISION_SUBJECT: String = "WORK_DOCUMENT_REVISION"

data class WorkDocumentRequirementSource(
    val requirementKey: String,
    val requirementType: String,
    val sourceTemplateId: UUID,
    val sourceTemplateRevision: Int,
    val approvalPolicyKey: String,
    val configuredDocumentCode: String?,
)

data class WorkDocumentIdentity(
    val documentId: UUID,
    val organizationId: UUID,
    val workOrderId: UUID,
    val requirementKey: String,
    val documentCode: String,
    val title: String,
    val approvalPolicyKey: String,
    val version: Long,
)

data class WorkDocumentRevisionRecord(
    val revisionId: UUID,
    val organizationId: UUID,
    val workOrderId: UUID,
    val documentId: UUID,
    val revisionSequence: Int,
    val revisionLabel: String,
    val evidenceId: UUID,
    val supersedesRevisionId: UUID?,
    val createdByUserId: UUID,
    val createdAt: Instant,
    val version: Long,
)

data class WorkDocumentReleaseRecord(
    val revisionId: UUID,
    val organizationId: UUID,
    val workOrderId: UUID,
    val documentId: UUID,
    val policyKey: String,
    val policyVersion: Int,
    val releaseMode: String,
    val approvalRequestId: UUID?,
)

data class WorkDocumentCurrentFact(
    val documentId: UUID,
    val revisionId: UUID,
    val documentCode: String,
    val title: String,
    val revisionLabel: String,
    val revisionSequence: Int,
    val evidenceId: UUID,
    val approvedAt: Instant,
)

@Repository
class WorkDocumentRevisionPersistence(
    private val jdbc: JdbcTemplate,
) {
    fun requirement(
        workOrderId: UUID,
        requirementKey: String,
    ): WorkDocumentRequirementSource? {
        val row =
            jdbc.query(
                """
                SELECT
                    wri.requirement_key,
                    wri.requirement_type_code,
                    cr.id AS template_id,
                    cr.revision_number AS template_revision,
                    td.structured_definition::text AS template_json
                FROM work_requirement_instance wri
                JOIN config_revision cr
                  ON cr.id = wri.source_config_id
                 AND cr.revision_number = wri.source_config_revision
                 AND cr.family = 'templates'
                JOIN template_definition td
                  ON td.config_revision_id = cr.id
                WHERE wri.work_order_id = ?
                  AND wri.requirement_family = 'DOCUMENT'
                  AND wri.requirement_key = ?
                """.trimIndent(),
                { rs, _ ->
                    RequirementRow(
                        requirementKey =
                            rs.getString(
                                "requirement_key",
                            ),
                        requirementType =
                            rs.getString(
                                "requirement_type_code",
                            ),
                        templateId =
                            rs.getObject(
                                "template_id",
                                UUID::class.java,
                            ),
                        templateRevision =
                            rs.getInt(
                                "template_revision",
                            ),
                        templateJson =
                            rs.getString(
                                "template_json",
                            ),
                    )
                },
                workOrderId,
                requirementKey,
            ).singleOrNull() ?: return null

        val root =
            runCatching {
                Json.parseToJsonElement(
                    row.templateJson,
                ).jsonObject
            }.getOrElse {
                throw ProductApiException(
                    "DOCUMENT_TEMPLATE_INVALID",
                    "The bound document template is invalid.",
                    HttpStatus.CONFLICT,
                )
            }
        val items =
            (root["requirements"] as? JsonArray)
                ?: (root["items"] as? JsonArray)
                ?: JsonArray(emptyList())
        val item =
            items
                .mapNotNull {
                    it as? JsonObject
                }
                .firstOrNull {
                    it["key"]
                        ?.jsonPrimitive
                        ?.contentOrNull ==
                        row.requirementKey
                }
                ?: throw ProductApiException(
                    "DOCUMENT_REQUIREMENT_CONFIG_MISSING",
                    "The bound document requirement configuration is unavailable.",
                    HttpStatus.CONFLICT,
                )
        val policyKey =
            item["approvalPolicyKey"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.trim()
                ?.uppercase(
                    Locale.ROOT,
                )
                ?.takeIf {
                    POLICY_KEY.matches(it)
                }
                ?: throw ProductApiException(
                    "DOCUMENT_APPROVAL_POLICY_UNCONFIGURED",
                    "The document requirement has no valid approvalPolicyKey.",
                    HttpStatus.CONFLICT,
                )
        val configuredCode =
            item["documentCode"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.trim()
                ?.takeIf {
                    it.isNotEmpty()
                }
                ?.also {
                    if (it.length > 96) {
                        throw ProductApiException(
                            "DOCUMENT_CODE_INVALID",
                            "The configured document code is invalid.",
                            HttpStatus.CONFLICT,
                        )
                    }
                }
        return WorkDocumentRequirementSource(
            requirementKey =
                row.requirementKey,
            requirementType =
                row.requirementType,
            sourceTemplateId =
                row.templateId,
            sourceTemplateRevision =
                row.templateRevision,
            approvalPolicyKey =
                policyKey,
            configuredDocumentCode =
                configuredCode,
        )
    }

    fun document(
        documentId: UUID,
    ): WorkDocumentIdentity? =
        jdbc.query(
            """
            SELECT
                id,
                organization_id,
                work_order_id,
                requirement_key,
                document_code,
                title,
                approval_policy_key,
                version
            FROM work_document
            WHERE id = ?
            """.trimIndent(),
            { rs, _ ->
                WorkDocumentIdentity(
                    documentId =
                        rs.getObject(
                            "id",
                            UUID::class.java,
                        ),
                    organizationId =
                        rs.getObject(
                            "organization_id",
                            UUID::class.java,
                        ),
                    workOrderId =
                        rs.getObject(
                            "work_order_id",
                            UUID::class.java,
                        ),
                    requirementKey =
                        rs.getString(
                            "requirement_key",
                        ),
                    documentCode =
                        rs.getString(
                            "document_code",
                        ),
                    title =
                        rs.getString(
                            "title",
                        ),
                    approvalPolicyKey =
                        rs.getString(
                            "approval_policy_key",
                        ),
                    version =
                        rs.getLong(
                            "version",
                        ),
                )
            },
            documentId,
        ).singleOrNull()

    fun insertDocumentIfMissing(
        value: WorkDocumentIdentity,
        actor: UUID,
        at: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO work_document (
                id,
                organization_id,
                work_order_id,
                requirement_key,
                document_code,
                title,
                approval_policy_key,
                created_by_user_id,
                created_at,
                version
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
            value.documentId,
            value.organizationId,
            value.workOrderId,
            value.requirementKey,
            value.documentCode,
            value.title,
            value.approvalPolicyKey,
            actor,
            at.atOffset(
                ZoneOffset.UTC,
            ),
        )
    }

    fun latestRevision(
        documentId: UUID,
        lock: Boolean = false,
    ): WorkDocumentRevisionRecord? {
        val lockClause =
            if (lock) {
                "FOR UPDATE"
            } else {
                ""
            }
        return jdbc.query(
            """
            SELECT
                id,
                organization_id,
                work_order_id,
                document_id,
                revision_sequence,
                revision_label,
                evidence_id,
                supersedes_revision_id,
                created_by_user_id,
                created_at,
                version
            FROM work_document_revision
            WHERE document_id = ?
            ORDER BY revision_sequence DESC
            LIMIT 1
            $lockClause
            """.trimIndent(),
            { rs, _ ->
                rs.revision()
            },
            documentId,
        ).singleOrNull()
    }

    fun revision(
        revisionId: UUID,
    ): WorkDocumentRevisionRecord? =
        jdbc.query(
            """
            SELECT
                id,
                organization_id,
                work_order_id,
                document_id,
                revision_sequence,
                revision_label,
                evidence_id,
                supersedes_revision_id,
                created_by_user_id,
                created_at,
                version
            FROM work_document_revision
            WHERE id = ?
            """.trimIndent(),
            { rs, _ ->
                rs.revision()
            },
            revisionId,
        ).singleOrNull()

    fun insertRevision(
        value: WorkDocumentRevisionRecord,
    ) {
        jdbc.update(
            """
            INSERT INTO work_document_revision (
                id,
                organization_id,
                work_order_id,
                document_id,
                revision_sequence,
                revision_label,
                evidence_id,
                supersedes_revision_id,
                created_by_user_id,
                created_at,
                version
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
            """.trimIndent(),
            value.revisionId,
            value.organizationId,
            value.workOrderId,
            value.documentId,
            value.revisionSequence,
            value.revisionLabel,
            value.evidenceId,
            value.supersedesRevisionId,
            value.createdByUserId,
            value.createdAt.atOffset(
                ZoneOffset.UTC,
            ),
        )
    }

    fun readyEvidenceMatches(
        evidenceId: UUID,
        organizationId: UUID,
        workOrderId: UUID,
    ): Boolean =
        jdbc.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM evidence
                WHERE id = ?
                  AND organization_id = ?
                  AND work_order_id = ?
                  AND target_type = 'WORK_ORDER'
                  AND target_id = ?
                  AND storage_state = 'READY'
            )
            """.trimIndent(),
            Boolean::class.java,
            evidenceId,
            organizationId,
            workOrderId,
            workOrderId,
        ) == true

    fun insertRelease(
        value: WorkDocumentReleaseRecord,
        at: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO work_document_revision_release (
                revision_id,
                organization_id,
                work_order_id,
                document_id,
                policy_key,
                policy_version,
                release_mode,
                approval_request_id,
                created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            value.revisionId,
            value.organizationId,
            value.workOrderId,
            value.documentId,
            value.policyKey,
            value.policyVersion,
            value.releaseMode,
            value.approvalRequestId,
            at.atOffset(
                ZoneOffset.UTC,
            ),
        )
    }

    fun releaseByRevision(
        revisionId: UUID,
    ): WorkDocumentReleaseRecord? =
        jdbc.query(
            """
            SELECT
                revision_id,
                organization_id,
                work_order_id,
                document_id,
                policy_key,
                policy_version,
                release_mode,
                approval_request_id
            FROM work_document_revision_release
            WHERE revision_id = ?
            """.trimIndent(),
            { rs, _ ->
                rs.release()
            },
            revisionId,
        ).singleOrNull()

    fun releaseByApprovalRequest(
        approvalRequestId: UUID,
    ): WorkDocumentReleaseRecord? =
        jdbc.query(
            """
            SELECT
                revision_id,
                organization_id,
                work_order_id,
                document_id,
                policy_key,
                policy_version,
                release_mode,
                approval_request_id
            FROM work_document_revision_release
            WHERE approval_request_id = ?
            """.trimIndent(),
            { rs, _ ->
                rs.release()
            },
            approvalRequestId,
        ).singleOrNull()

    fun isLatestRevision(
        revisionId: UUID,
        documentId: UUID,
    ): Boolean =
        jdbc.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM work_document_revision target
                WHERE target.id = ?
                  AND target.document_id = ?
                  AND NOT EXISTS (
                      SELECT 1
                      FROM work_document_revision newer
                      WHERE newer.document_id =
                            target.document_id
                        AND newer.revision_sequence >
                            target.revision_sequence
                  )
            )
            """.trimIndent(),
            Boolean::class.java,
            revisionId,
            documentId,
        ) == true

    fun currentApproved(
        workOrderId: UUID,
        requirementKey: String? = null,
        documentCode: String? = null,
    ): List<WorkDocumentCurrentFact> =
        jdbc.query(
            """
            WITH approved AS (
                SELECT
                    d.id AS document_id,
                    r.id AS revision_id,
                    d.document_code,
                    d.title,
                    r.revision_label,
                    r.revision_sequence,
                    r.evidence_id,
                    CASE
                        WHEN rel.release_mode =
                             'NO_APPROVAL_REQUIRED'
                            THEN rel.created_at
                        ELSE ar.completed_at
                    END AS approved_at,
                    row_number() OVER (
                        PARTITION BY d.id
                        ORDER BY
                            r.revision_sequence DESC,
                            r.id DESC
                    ) AS approved_rank
                FROM work_document d
                JOIN work_document_revision r
                  ON r.document_id = d.id
                 AND r.work_order_id =
                     d.work_order_id
                 AND r.organization_id =
                     d.organization_id
                JOIN work_document_revision_release rel
                  ON rel.revision_id = r.id
                LEFT JOIN approval_request ar
                  ON ar.id =
                     rel.approval_request_id
                JOIN evidence e
                  ON e.id = r.evidence_id
                 AND e.storage_state = 'READY'
                WHERE d.work_order_id = ?
                  AND (
                      ?::varchar IS NULL
                      OR d.requirement_key = ?
                  )
                  AND (
                      ?::varchar IS NULL
                      OR d.document_code = ?
                  )
                  AND (
                      rel.release_mode =
                          'NO_APPROVAL_REQUIRED'
                      OR ar.state = 'APPROVED'
                  )
            )
            SELECT
                document_id,
                revision_id,
                document_code,
                title,
                revision_label,
                revision_sequence,
                evidence_id,
                approved_at
            FROM approved
            WHERE approved_rank = 1
            ORDER BY document_code, document_id
            """.trimIndent(),
            { rs, _ ->
                WorkDocumentCurrentFact(
                    documentId =
                        rs.getObject(
                            "document_id",
                            UUID::class.java,
                        ),
                    revisionId =
                        rs.getObject(
                            "revision_id",
                            UUID::class.java,
                        ),
                    documentCode =
                        rs.getString(
                            "document_code",
                        ),
                    title =
                        rs.getString(
                            "title",
                        ),
                    revisionLabel =
                        rs.getString(
                            "revision_label",
                        ),
                    revisionSequence =
                        rs.getInt(
                            "revision_sequence",
                        ),
                    evidenceId =
                        rs.getObject(
                            "evidence_id",
                            UUID::class.java,
                        ),
                    approvedAt =
                        rs.getObject(
                            "approved_at",
                            OffsetDateTime::class.java,
                        ).toInstant(),
                )
            },
            workOrderId,
            requirementKey,
            requirementKey,
            documentCode,
            documentCode,
        )

    fun bumpWorkSource(
        workOrderId: UUID,
        at: Instant,
    ): Long? {
        val updated =
            jdbc.query(
                """
                UPDATE work_order
                SET version = version + 1,
                    updated_at = ?
                WHERE id = ?
                  AND lifecycle_state NOT IN (
                      'CANCELLED',
                      'CLOSED',
                      'ACCEPTED'
                  )
                RETURNING version
                """.trimIndent(),
                { rs, _ ->
                    rs.getLong(
                        "version",
                    )
                },
                at.atOffset(
                    ZoneOffset.UTC,
                ),
                workOrderId,
            )
        return updated.singleOrNull()
    }

    private fun java.sql.ResultSet.revision() =
        WorkDocumentRevisionRecord(
            revisionId =
                getObject(
                    "id",
                    UUID::class.java,
                ),
            organizationId =
                getObject(
                    "organization_id",
                    UUID::class.java,
                ),
            workOrderId =
                getObject(
                    "work_order_id",
                    UUID::class.java,
                ),
            documentId =
                getObject(
                    "document_id",
                    UUID::class.java,
                ),
            revisionSequence =
                getInt(
                    "revision_sequence",
                ),
            revisionLabel =
                getString(
                    "revision_label",
                ),
            evidenceId =
                getObject(
                    "evidence_id",
                    UUID::class.java,
                ),
            supersedesRevisionId =
                getObject(
                    "supersedes_revision_id",
                    UUID::class.java,
                ),
            createdByUserId =
                getObject(
                    "created_by_user_id",
                    UUID::class.java,
                ),
            createdAt =
                getObject(
                    "created_at",
                    OffsetDateTime::class.java,
                ).toInstant(),
            version =
                getLong(
                    "version",
                ),
        )

    private fun java.sql.ResultSet.release() =
        WorkDocumentReleaseRecord(
            revisionId =
                getObject(
                    "revision_id",
                    UUID::class.java,
                ),
            organizationId =
                getObject(
                    "organization_id",
                    UUID::class.java,
                ),
            workOrderId =
                getObject(
                    "work_order_id",
                    UUID::class.java,
                ),
            documentId =
                getObject(
                    "document_id",
                    UUID::class.java,
                ),
            policyKey =
                getString(
                    "policy_key",
                ),
            policyVersion =
                getInt(
                    "policy_version",
                ),
            releaseMode =
                getString(
                    "release_mode",
                ),
            approvalRequestId =
                getObject(
                    "approval_request_id",
                    UUID::class.java,
                ),
        )

    private data class RequirementRow(
        val requirementKey: String,
        val requirementType: String,
        val templateId: UUID,
        val templateRevision: Int,
        val templateJson: String,
    )

    private companion object {
        val POLICY_KEY =
            Regex(
                "[A-Z][A-Z0-9_]{2,95}",
            )
    }
}
