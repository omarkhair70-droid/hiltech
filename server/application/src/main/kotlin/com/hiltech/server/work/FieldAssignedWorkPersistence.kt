package com.hiltech.server.work

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

interface FieldAssignedWorkPersistencePort {
    fun activeAssignmentCandidateWorkOrderIds(
        at: Instant,
    ): List<UUID>

    fun fieldContext(
        workOrderId: UUID,
    ): FieldWorkContext?

    fun evidenceRequirements(
        evidencePolicyId: UUID,
    ): List<FieldEvidenceRequirement>

    fun readyEvidenceMetadata(
        workOrderId: UUID,
        evidencePolicyId: UUID,
        evidencePolicyRevision: Int,
    ): List<FieldEvidenceMetadata>

    fun safeDocumentRefs(
        workOrderId: UUID,
    ): List<FieldSafeDocumentRef>
}

data class FieldWorkContext(
    val project: FieldProjectContext,
    val site: FieldSiteContext,
    val area: FieldAreaContext?,
)

@Component
class JdbcFieldAssignedWorkPersistence(
    private val jdbc: JdbcTemplate,
) : FieldAssignedWorkPersistencePort {
    override fun activeAssignmentCandidateWorkOrderIds(
        at: Instant,
    ): List<UUID> =
        jdbc.query(
            """
            SELECT DISTINCT wa.work_order_id
            FROM work_assignment wa
            JOIN work_order wo
              ON wo.id = wa.work_order_id
            WHERE wa.state = 'ACTIVE'
              AND wa.valid_from <= ?
              AND (
                  wa.valid_until IS NULL
                  OR wa.valid_until > ?
              )
              AND wo.lifecycle_state NOT IN (
                  'DRAFT',
                  'CANCELLED',
                  'CLOSED',
                  'ACCEPTED'
              )
            ORDER BY wa.work_order_id
            """.trimIndent(),
            { rs, _ ->
                rs.getObject(
                    "work_order_id",
                    UUID::class.java,
                )
            },
            at.atOffset(ZoneOffset.UTC),
            at.atOffset(ZoneOffset.UTC),
        )

    override fun fieldContext(
        workOrderId: UUID,
    ): FieldWorkContext? =
        jdbc.query(
            """
            SELECT
                p.id AS project_id,
                p.project_code,
                p.name AS project_name,
                p.lifecycle_state AS project_lifecycle_state,
                p.baseline_version,
                s.id AS site_id,
                s.site_code,
                s.name AS site_name,
                s.timezone,
                ps.id AS project_site_id,
                ps.project_site_code,
                ps.lifecycle_state AS project_site_lifecycle_state,
                ps.access_instructions,
                a.id AS area_id,
                a.name AS area_name,
                a.restricted_access
            FROM work_order wo
            JOIN project p
              ON p.id = wo.project_id
             AND p.organization_id =
                 wo.organization_id
            JOIN project_site ps
              ON ps.id = wo.project_site_id
             AND ps.project_id = wo.project_id
             AND ps.site_id = wo.site_id
             AND ps.organization_id =
                 wo.organization_id
            JOIN site s
              ON s.id = wo.site_id
             AND s.organization_id =
                 wo.organization_id
            LEFT JOIN area a
              ON a.id = wo.area_id
             AND a.site_id = wo.site_id
             AND a.organization_id =
                 wo.organization_id
            WHERE wo.id = ?
            """.trimIndent(),
            { rs, _ ->
                FieldWorkContext(
                    project =
                        FieldProjectContext(
                            projectId =
                                rs.getObject(
                                    "project_id",
                                    UUID::class.java,
                                ),
                            projectCode =
                                rs.getString(
                                    "project_code",
                                ),
                            name =
                                rs.getString(
                                    "project_name",
                                ),
                            lifecycleState =
                                rs.getString(
                                    "project_lifecycle_state",
                                ),
                            baselineVersion =
                                rs.getInt(
                                    "baseline_version",
                                ),
                        ),
                    site =
                        FieldSiteContext(
                            siteId =
                                rs.getObject(
                                    "site_id",
                                    UUID::class.java,
                                ),
                            siteCode =
                                rs.getString(
                                    "site_code",
                                ),
                            name =
                                rs.getString(
                                    "site_name",
                                ),
                            projectSiteId =
                                rs.getObject(
                                    "project_site_id",
                                    UUID::class.java,
                                ),
                            projectSiteCode =
                                rs.getString(
                                    "project_site_code",
                                ),
                            lifecycleState =
                                rs.getString(
                                    "project_site_lifecycle_state",
                                ),
                            accessInstructions =
                                rs.getString(
                                    "access_instructions",
                                ),
                            timezone =
                                rs.getString(
                                    "timezone",
                                ),
                        ),
                    area =
                        rs.getObject(
                            "area_id",
                            UUID::class.java,
                        )?.let { areaId ->
                            val restricted =
                                rs.getBoolean(
                                    "restricted_access",
                                )
                            FieldAreaContext(
                                areaId = areaId,
                                label =
                                    if (restricted) {
                                        "Restricted area"
                                    } else {
                                        rs.getString(
                                            "area_name",
                                        )
                                    },
                                restricted =
                                    restricted,
                            )
                        },
                )
            },
            workOrderId,
        ).singleOrNull()

    override fun evidenceRequirements(
        evidencePolicyId: UUID,
    ): List<FieldEvidenceRequirement> =
        jdbc.query(
            """
            SELECT
                epr.requirement_key,
                epr.evidence_type_code,
                epr.stage,
                epr.min_count,
                epr.max_count,
                epr.offline_capture_allowed,
                epr.allowed_content_types,
                epr.reviewer_relationship_code,
                tr.id AS test_template_id,
                tr.code AS test_template_code,
                tr.name AS test_template_name,
                tr.revision_number AS test_template_revision,
                td.schema_version AS test_schema_version,
                td.structured_definition::text AS test_schema_json
            FROM evidence_policy_requirement epr
            LEFT JOIN config_revision tr
              ON tr.id = epr.structured_test_template_id
            LEFT JOIN template_definition td
              ON td.config_revision_id = tr.id
             AND td.template_type = 'ENGINEERING_TEST_SCHEMA'
            WHERE epr.config_revision_id = ?
            ORDER BY epr.stage,
                     epr.requirement_key,
                     epr.id
            """.trimIndent(),
            { rs, _ ->
                val templateId =
                    rs.getObject(
                        "test_template_id",
                        UUID::class.java,
                    )
                FieldEvidenceRequirement(
                    requirementKey =
                        rs.getString(
                            "requirement_key",
                        ),
                    evidenceTypeCode =
                        rs.getString(
                            "evidence_type_code",
                        ),
                    stage =
                        rs.getString("stage"),
                    minCount =
                        rs.getInt("min_count"),
                    maxCount =
                        (rs.getObject(
                            "max_count",
                        ) as? Number)?.toInt(),
                    offlineCaptureAllowedByPolicy =
                        rs.getBoolean(
                            "offline_capture_allowed",
                        ),
                    allowedContentTypes =
                        rs.getArray(
                            "allowed_content_types",
                        )
                            ?.array
                            ?.let {
                                it as Array<*>
                            }
                            ?.map {
                                it.toString()
                            }
                            ?: emptyList(),
                    structuredTest =
                        templateId?.let {
                            FieldEngineeringTestSchema.parseTemplate(
                                templateId = it,
                                templateCode =
                                    rs.getString(
                                        "test_template_code",
                                    ),
                                templateName =
                                    rs.getString(
                                        "test_template_name",
                                    ),
                                templateRevision =
                                    rs.getInt(
                                        "test_template_revision",
                                    ),
                                schemaVersion =
                                    rs.getInt(
                                        "test_schema_version",
                                    ),
                                schemaJson =
                                    rs.getString(
                                        "test_schema_json",
                                    ),
                                reviewerRelationshipCode =
                                    rs.getString(
                                        "reviewer_relationship_code",
                                    ),
                            )
                        },
                )
            },
            evidencePolicyId,
        )

    override fun readyEvidenceMetadata(
        workOrderId: UUID,
        evidencePolicyId: UUID,
        evidencePolicyRevision: Int,
    ): List<FieldEvidenceMetadata> =
        jdbc.query(
            """
            SELECT
                e.id,
                e.evidence_requirement_key,
                e.evidence_type_code,
                e.content_type,
                e.storage_state,
                e.captured_at,
                e.version
            FROM evidence e
            WHERE e.work_order_id = ?
              AND e.target_type = 'WORK_ORDER'
              AND e.target_id = ?
              AND e.evidence_policy_id = ?
              AND e.evidence_policy_revision = ?
              AND e.storage_state = 'READY'
              AND NOT EXISTS (
                  SELECT 1
                  FROM evidence newer
                  WHERE newer.supersedes_evidence_id =
                        e.id
                    AND newer.storage_state = 'READY'
              )
            ORDER BY e.captured_at DESC,
                     e.id
            """.trimIndent(),
            { rs, _ ->
                FieldEvidenceMetadata(
                    evidenceId =
                        rs.getObject(
                            "id",
                            UUID::class.java,
                        ),
                    requirementKey =
                        rs.getString(
                            "evidence_requirement_key",
                        ),
                    evidenceTypeCode =
                        rs.getString(
                            "evidence_type_code",
                        ),
                    contentType =
                        rs.getString(
                            "content_type",
                        ),
                    storageState =
                        rs.getString(
                            "storage_state",
                        ),
                    capturedAt =
                        rs.getObject(
                            "captured_at",
                            OffsetDateTime::class.java,
                        ).toInstant(),
                    version =
                        rs.getLong("version"),
                )
            },
            workOrderId,
            workOrderId,
            evidencePolicyId,
            evidencePolicyRevision,
        )

    override fun safeDocumentRefs(
        workOrderId: UUID,
    ): List<FieldSafeDocumentRef> {
        // Phase 0-4 has Evidence metadata and document requirement
        // definitions, but no authoritative Project/Work document-revision
        // source. Slice 06 must not fabricate a current drawing/document.
        return emptyList()
    }
}
