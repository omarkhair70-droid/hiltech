package com.hiltech.server.work

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

data class ControlledDocumentBindingSnapshot(
    val bindingId: UUID,
    val bindingVersion: Long,
    val organizationId: UUID,
    val projectId: UUID,
    val siteId: UUID?,
    val documentId: UUID,
    val documentCode: String,
    val title: String,
    val documentState: String,
    val boundRevisionId: UUID,
    val boundRevisionCode: String,
    val boundRevisionState: String,
    val boundRevisionVersion: Long,
    val currentRevisionId: UUID?,
    val currentRevisionCode: String?,
    val currentRevisionVersion: Long?,
    val contentSourceType: String,
    val boundAt: Instant,
)

interface ControlledDocumentReadPort {
    fun activeBinding(workOrderId: UUID, requirementId: UUID): ControlledDocumentBindingSnapshot?
}

@Repository
class JdbcControlledDocumentReadPort(
    private val jdbc: JdbcTemplate,
) : ControlledDocumentReadPort {
    override fun activeBinding(
        workOrderId: UUID,
        requirementId: UUID,
    ): ControlledDocumentBindingSnapshot? =
        jdbc.query(
            """
            SELECT
                b.id binding_id,
                b.version binding_version,
                d.organization_id,
                d.project_id,
                d.site_id,
                d.id document_id,
                d.code document_code,
                d.title,
                d.lifecycle_state document_state,
                bound.id bound_revision_id,
                bound.revision_code bound_revision_code,
                bound.lifecycle_state bound_revision_state,
                bound.version bound_revision_version,
                current.id current_revision_id,
                current.revision_code current_revision_code,
                current.version current_revision_version,
                CASE
                    WHEN bound.content_evidence_id IS NOT NULL THEN 'EVIDENCE'
                    ELSE 'EXTERNAL_REF'
                END content_source_type,
                b.bound_at
            FROM work_document_revision_binding b
            JOIN work_requirement_instance ri
              ON ri.id = b.requirement_instance_id
             AND ri.work_order_id = b.work_order_id
             AND ri.requirement_family = 'DOCUMENT'
            JOIN controlled_document d
              ON d.id = b.document_id
            JOIN controlled_document_revision bound
              ON bound.id = b.revision_id
             AND bound.document_id = d.id
            LEFT JOIN controlled_document_revision current
              ON current.id = d.current_revision_id
             AND current.document_id = d.id
            WHERE b.work_order_id = ?
              AND b.requirement_instance_id = ?
              AND b.superseded_at IS NULL
            """.trimIndent(),
            { rs, _ ->
                ControlledDocumentBindingSnapshot(
                    bindingId = rs.getObject("binding_id", UUID::class.java),
                    bindingVersion = rs.getLong("binding_version"),
                    organizationId = rs.getObject("organization_id", UUID::class.java),
                    projectId = rs.getObject("project_id", UUID::class.java),
                    siteId = rs.getObject("site_id", UUID::class.java),
                    documentId = rs.getObject("document_id", UUID::class.java),
                    documentCode = rs.getString("document_code"),
                    title = rs.getString("title"),
                    documentState = rs.getString("document_state"),
                    boundRevisionId = rs.getObject("bound_revision_id", UUID::class.java),
                    boundRevisionCode = rs.getString("bound_revision_code"),
                    boundRevisionState = rs.getString("bound_revision_state"),
                    boundRevisionVersion = rs.getLong("bound_revision_version"),
                    currentRevisionId = rs.getObject("current_revision_id", UUID::class.java),
                    currentRevisionCode = rs.getString("current_revision_code"),
                    currentRevisionVersion =
                        (rs.getObject("current_revision_version") as? Number)?.toLong(),
                    contentSourceType = rs.getString("content_source_type"),
                    boundAt = rs.getObject("bound_at", OffsetDateTime::class.java).toInstant(),
                )
            },
            workOrderId,
            requirementId,
        ).singleOrNull()
}

fun interface WorkDocumentSource {
    fun evaluate(
        order: WorkOrderSnapshot,
        requirement: ReadinessRequirementExecutionSnapshot,
        at: Instant,
    ): WorkResourceFact?
}

@Component
class ControlledWorkDocumentSource(
    private val read: ControlledDocumentReadPort,
) : WorkDocumentSource {
    override fun evaluate(
        order: WorkOrderSnapshot,
        requirement: ReadinessRequirementExecutionSnapshot,
        at: Instant,
    ): WorkResourceFact {
        val binding =
            read.activeBinding(order.workOrderId, requirement.requirementId)
                ?: return WorkResourceFact(
                    satisfied = false,
                    reason = "DOCUMENT_REVISION_NOT_BOUND",
                    sourceId = requirement.requirementId,
                    sourceVersion = requirement.version,
                    asOf = at,
                )

        if (binding.organizationId != order.organizationId ||
            binding.projectId != order.projectId ||
            (binding.siteId != null && binding.siteId != order.siteId)
        ) {
            return WorkResourceFact(
                false,
                "DOCUMENT_SCOPE_INVALID",
                binding.bindingId,
                binding.bindingVersion,
                at,
            )
        }

        if (binding.documentState != "ACTIVE") {
            return WorkResourceFact(
                false,
                "DOCUMENT_WITHDRAWN",
                binding.documentId,
                binding.bindingVersion,
                at,
            )
        }

        if (binding.boundRevisionState == "WITHDRAWN") {
            return WorkResourceFact(
                false,
                "DOCUMENT_REVISION_WITHDRAWN",
                binding.boundRevisionId,
                binding.boundRevisionVersion,
                at,
            )
        }

        val currentRevisionId =
            binding.currentRevisionId
                ?: return WorkResourceFact(
                    false,
                    "DOCUMENT_CURRENT_REVISION_MISSING",
                    binding.documentId,
                    binding.bindingVersion,
                    at,
                )

        if (currentRevisionId != binding.boundRevisionId) {
            return WorkResourceFact(
                false,
                "DOCUMENT_REVISION_STALE",
                currentRevisionId,
                binding.currentRevisionVersion,
                at,
            )
        }

        if (binding.boundRevisionState != "APPROVED") {
            return WorkResourceFact(
                false,
                "DOCUMENT_REVISION_NOT_APPROVED",
                binding.boundRevisionId,
                binding.boundRevisionVersion,
                at,
            )
        }

        return WorkResourceFact(
            true,
            "DOCUMENT_REVISION_CURRENT",
            binding.boundRevisionId,
            binding.boundRevisionVersion,
            at,
        )
    }
}
