package com.hiltech.server.work

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

enum class EngineeringMetricValueType { DECIMAL, INTEGER, TEXT, BOOLEAN, ENUM }
enum class EngineeringEvaluationStatus { PASS, FAIL, UNKNOWN }

data class FieldEngineeringMetricDefinition(
    val key: String,
    val label: String,
    val valueType: EngineeringMetricValueType,
    val required: Boolean,
    val dimension: String?,
    val unitCode: String?,
    val precision: Int?,
    val minValue: String?,
    val maxValue: String?,
    val options: List<String>,
    val acceptedValues: List<String>,
)

data class FieldEngineeringTestDefinition(
    val templateId: UUID,
    val templateCode: String,
    val templateName: String,
    val templateRevision: Int,
    val schemaVersion: Int,
    val label: String,
    val subjectLabel: String,
    val rawEvidenceRequired: Boolean,
    val instrumentAssetRequired: Boolean,
    val calibrationRequired: Boolean,
    val deviceReportedStatusAllowed: Boolean,
    val reviewerRelationshipCode: String?,
    val metrics: List<FieldEngineeringMetricDefinition>,
)

data class FieldEngineeringTestCaptureRequest(
    val operationId: UUID,
    val assignmentId: UUID,
    val assignmentVersion: Long,
    val workOrderVersion: Long,
    val evidencePolicyId: UUID,
    val evidencePolicyRevision: Int,
    val requirementKey: String,
    val schemaTemplateId: UUID,
    val schemaTemplateRevision: Int,
    val schemaVersion: Int,
    val testSubjectRef: String,
    val values: Map<String, String>,
    val instrumentAssetId: UUID? = null,
    val rawEvidenceId: UUID? = null,
    val deviceReportedStatus: String? = null,
    val capturedAt: Instant,
    val clientOccurredAt: Instant? = null,
    val supersedesResultId: UUID? = null,
)

data class FieldEngineeringMetricEvaluation(
    val key: String,
    val status: EngineeringEvaluationStatus,
    val normalizedValue: String?,
)

data class FieldEngineeringTestReceipt(
    val resultId: UUID,
    val workOrderId: UUID,
    val requirementKey: String,
    val testSubjectRef: String,
    val evaluatedStatus: EngineeringEvaluationStatus,
    val deviceReportedStatus: String?,
    val reviewerRelationshipCode: String?,
    val supersedesResultId: UUID?,
    val replayed: Boolean,
)

data class FieldEngineeringTestSource(
    val organizationId: UUID,
    val workOrderId: UUID,
    val workOrderVersion: Long,
    val lifecycleState: WorkOrderLifecycle,
    val evidencePolicyId: UUID,
    val evidencePolicyRevision: Int,
    val requirementKey: String,
    val evidenceTypeCode: String,
    val reviewerRelationshipCode: String?,
    val templateId: UUID,
    val templateCode: String,
    val templateName: String,
    val templateRevision: Int,
    val schemaVersion: Int,
    val schemaJson: String,
)

data class FieldEngineeringStoredResult(
    val operationId: UUID,
    val organizationId: UUID,
    val workOrderId: UUID,
    val requirementKey: String,
    val schemaTemplateId: UUID,
    val schemaTemplateRevision: Int,
    val testSubjectRef: String,
    val evaluatedStatus: EngineeringEvaluationStatus,
    val deviceReportedStatus: String?,
    val supersedesResultId: UUID?,
)

internal object FieldEngineeringTestSchema {
    private val keyPattern = Regex("[A-Za-z0-9_.-]{1,64}")

    fun parse(source: FieldEngineeringTestSource): FieldEngineeringTestDefinition =
        parseTemplate(
            source.templateId,
            source.templateCode,
            source.templateName,
            source.templateRevision,
            source.schemaVersion,
            source.schemaJson,
            source.reviewerRelationshipCode,
        )

    fun parseTemplate(
        templateId: UUID,
        templateCode: String,
        templateName: String,
        templateRevision: Int,
        schemaVersion: Int,
        schemaJson: String,
        reviewerRelationshipCode: String?,
    ): FieldEngineeringTestDefinition {
        val root = runCatching { Json.parseToJsonElement(schemaJson) as JsonObject }
            .getOrElse { invalidSchema("Template JSON is invalid.") }
        val label = text(root, "label", 160) ?: templateName
        val subjectLabel = text(root, "subjectLabel", 120) ?: "Test subject"
        val rawEvidenceRequired = bool(root, "rawEvidenceRequired") ?: false
        val instrumentAssetRequired = bool(root, "instrumentAssetRequired") ?: false
        val calibrationRequired = bool(root, "calibrationRequired") ?: false
        val deviceReportedStatusAllowed = bool(root, "deviceReportedStatusAllowed") ?: true
        if (calibrationRequired && !instrumentAssetRequired) {
            invalidSchema("Calibration-required schemas must require an instrument Asset.")
        }
        val metricArray = root["metrics"] as? JsonArray ?: invalidSchema("metrics must be an array.")
        if (metricArray.isEmpty() || metricArray.size > 100) {
            invalidSchema("Engineering test schemas require 1..100 metrics.")
        }
        val metrics = metricArray.map { element ->
            val metric = element as? JsonObject ?: invalidSchema("Each metric must be an object.")
            val key = text(metric, "key", 64) ?: invalidSchema("Metric key is required.")
            if (!keyPattern.matches(key)) invalidSchema("Metric key is invalid.")
            val valueType = text(metric, "valueType", 24)?.uppercase()
                ?.let { runCatching { EngineeringMetricValueType.valueOf(it) }.getOrNull() }
                ?: invalidSchema("Metric valueType is invalid.")
            val precision = metric["precision"]?.jsonPrimitive?.intOrNull
            if (precision != null &&
                (precision !in 0..9 || valueType !in setOf(EngineeringMetricValueType.DECIMAL, EngineeringMetricValueType.INTEGER))) {
                invalidSchema("Metric precision is invalid.")
            }
            val min = text(metric, "min", 64)?.let(::decimalOrSchema)
            val max = text(metric, "max", 64)?.let(::decimalOrSchema)
            if ((min != null || max != null) &&
                valueType !in setOf(EngineeringMetricValueType.DECIMAL, EngineeringMetricValueType.INTEGER)) {
                invalidSchema("Only numeric metrics may define min/max.")
            }
            if (min != null && max != null && min > max) invalidSchema("Metric min exceeds max.")
            fun stringArray(name: String): List<String> =
                (metric[name] as? JsonArray)
                    ?.map { it.jsonPrimitive.content }
                    ?.also {
                        if (it.size > 50 || it.distinct().size != it.size ||
                            it.any { value -> value.isBlank() || value.length > 120 }) {
                            invalidSchema("$name is invalid.")
                        }
                    }.orEmpty()
            val options = stringArray("options")
            val accepted = stringArray("acceptedValues")
            if (options.isNotEmpty() && valueType != EngineeringMetricValueType.ENUM) {
                invalidSchema("options are valid only for ENUM metrics.")
            }
            if (valueType == EngineeringMetricValueType.ENUM && options.isEmpty()) {
                invalidSchema("ENUM metrics require options.")
            }
            if (accepted.isNotEmpty() &&
                valueType !in setOf(EngineeringMetricValueType.TEXT, EngineeringMetricValueType.ENUM, EngineeringMetricValueType.BOOLEAN)) {
                invalidSchema("acceptedValues are not valid for this metric type.")
            }
            if (valueType == EngineeringMetricValueType.ENUM && accepted.any { it !in options }) {
                invalidSchema("ENUM acceptedValues must be present in options.")
            }
            if (valueType == EngineeringMetricValueType.BOOLEAN &&
                accepted.any { it !in setOf("true", "false") }) {
                invalidSchema("BOOLEAN acceptedValues must use true/false.")
            }
            FieldEngineeringMetricDefinition(
                key = key,
                label = text(metric, "label", 160) ?: key,
                valueType = valueType,
                required = bool(metric, "required") ?: true,
                dimension = text(metric, "dimension", 64),
                unitCode = text(metric, "unitCode", 32),
                precision = precision,
                minValue = min?.stripTrailingZeros()?.toPlainString(),
                maxValue = max?.stripTrailingZeros()?.toPlainString(),
                options = options,
                acceptedValues = accepted,
            )
        }
        if (metrics.map { it.key }.distinct().size != metrics.size) invalidSchema("Metric keys must be unique.")
        return FieldEngineeringTestDefinition(
            templateId, templateCode, templateName, templateRevision,
            schemaVersion, label, subjectLabel, rawEvidenceRequired, instrumentAssetRequired,
            calibrationRequired, deviceReportedStatusAllowed, reviewerRelationshipCode, metrics,
        )
    }

    private fun text(o: JsonObject, key: String, max: Int): String? =
        o[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.also {
            if (it.length > max) invalidSchema("$key is too long.")
        }

    private fun bool(o: JsonObject, key: String): Boolean? =
        o[key]?.jsonPrimitive?.booleanOrNull
            ?: if (o.containsKey(key)) invalidSchema("$key must be boolean.") else null

    private fun decimalOrSchema(value: String): BigDecimal =
        runCatching { BigDecimal(value) }.getOrElse { invalidSchema("Numeric bound is invalid.") }

    private fun invalidSchema(message: String): Nothing = throw ProductApiException(
        "ENGINEERING_TEST_SCHEMA_INVALID", message, HttpStatus.CONFLICT,
    )
}

interface FieldEngineeringTestPersistencePort {
    fun source(workOrderId: UUID, requirementKey: String): FieldEngineeringTestSource?
    fun readyEvidence(evidenceId: UUID, source: FieldEngineeringTestSource, actor: UUID): Boolean
    fun result(operationId: UUID): FieldEngineeringStoredResult?
    fun hasSuccessor(resultId: UUID): Boolean
    fun insert(
        request: FieldEngineeringTestCaptureRequest,
        source: FieldEngineeringTestSource,
        assignment: WorkAssignmentRecord,
        actor: UUID,
        deviceInstallationId: UUID,
        valuesJson: String,
        status: EngineeringEvaluationStatus,
        evaluationJson: String,
        deviceStatus: String?,
        at: Instant,
        correlationId: String,
    )
}

@Repository
class FieldEngineeringTestPersistence(
    private val jdbc: JdbcTemplate,
) : FieldEngineeringTestPersistencePort {
    override fun source(workOrderId: UUID, requirementKey: String): FieldEngineeringTestSource? =
        jdbc.query(
            """
            SELECT wo.organization_id, wo.id work_order_id, wo.version work_order_version, wo.lifecycle_state,
                   wpb.evidence_policy_id, wpb.evidence_policy_revision,
                   epr.requirement_key, epr.evidence_type_code, epr.reviewer_relationship_code,
                   tr.id template_id, tr.code template_code, tr.name template_name,
                   tr.revision_number template_revision, td.schema_version,
                   td.structured_definition::text schema_json
            FROM work_order wo
            JOIN work_policy_binding wpb
              ON wpb.id = wo.current_policy_binding_id
             AND wpb.work_order_id = wo.id
             AND wpb.superseded_at IS NULL
            JOIN evidence_policy_requirement epr
              ON epr.config_revision_id = wpb.evidence_policy_id
             AND epr.requirement_key = ?
            JOIN config_revision tr
              ON tr.id = epr.structured_test_template_id
             AND tr.family = 'templates'
            JOIN template_definition td
              ON td.config_revision_id = tr.id
             AND td.template_type = 'ENGINEERING_TEST_SCHEMA'
            WHERE wo.id = ?
            """.trimIndent(),
            { rs, _ ->
                FieldEngineeringTestSource(
                    rs.getObject("organization_id", UUID::class.java),
                    rs.getObject("work_order_id", UUID::class.java),
                    rs.getLong("work_order_version"),
                    WorkOrderLifecycle.valueOf(rs.getString("lifecycle_state")),
                    rs.getObject("evidence_policy_id", UUID::class.java),
                    rs.getInt("evidence_policy_revision"),
                    rs.getString("requirement_key"),
                    rs.getString("evidence_type_code"),
                    rs.getString("reviewer_relationship_code"),
                    rs.getObject("template_id", UUID::class.java),
                    rs.getString("template_code"),
                    rs.getString("template_name"),
                    rs.getInt("template_revision"),
                    rs.getInt("schema_version"),
                    rs.getString("schema_json"),
                )
            },
            requirementKey, workOrderId,
        ).singleOrNull()

    override fun readyEvidence(
        evidenceId: UUID,
        source: FieldEngineeringTestSource,
        actor: UUID,
    ): Boolean = jdbc.queryForObject(
        """
        SELECT EXISTS (
            SELECT 1 FROM evidence e
            WHERE e.id = ?
              AND e.organization_id = ?
              AND e.work_order_id = ?
              AND e.evidence_requirement_key = ?
              AND e.evidence_policy_id = ?
              AND e.evidence_policy_revision = ?
              AND e.captured_by_user_id = ?
              AND e.storage_state = 'READY'
        )
        """.trimIndent(),
        Boolean::class.java,
        evidenceId, source.organizationId, source.workOrderId, source.requirementKey,
        source.evidencePolicyId, source.evidencePolicyRevision, actor,
    ) == true

    override fun result(operationId: UUID): FieldEngineeringStoredResult? =
        jdbc.query(
            """
            SELECT operation_id, organization_id, work_order_id, requirement_key,
                   schema_template_id, schema_template_revision, test_subject_ref,
                   evaluated_status, device_reported_status, supersedes_result_id
            FROM field_engineering_test_result
            WHERE operation_id = ?
            """.trimIndent(),
            { rs, _ ->
                FieldEngineeringStoredResult(
                    rs.getObject("operation_id", UUID::class.java),
                    rs.getObject("organization_id", UUID::class.java),
                    rs.getObject("work_order_id", UUID::class.java),
                    rs.getString("requirement_key"),
                    rs.getObject("schema_template_id", UUID::class.java),
                    rs.getInt("schema_template_revision"),
                    rs.getString("test_subject_ref"),
                    EngineeringEvaluationStatus.valueOf(rs.getString("evaluated_status")),
                    rs.getString("device_reported_status"),
                    rs.getObject("supersedes_result_id", UUID::class.java),
                )
            },
            operationId,
        ).singleOrNull()

    override fun hasSuccessor(resultId: UUID): Boolean = jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM field_engineering_test_result WHERE supersedes_result_id = ?)",
        Boolean::class.java,
        resultId,
    ) == true

    override fun insert(
        request: FieldEngineeringTestCaptureRequest,
        source: FieldEngineeringTestSource,
        assignment: WorkAssignmentRecord,
        actor: UUID,
        deviceInstallationId: UUID,
        valuesJson: String,
        status: EngineeringEvaluationStatus,
        evaluationJson: String,
        deviceStatus: String?,
        at: Instant,
        correlationId: String,
    ) {
        val inserted = jdbc.update(
            """
            INSERT INTO field_engineering_test_result (
                operation_id, organization_id, work_order_id, assignment_id, assignment_version,
                captured_by_user_id, device_installation_id, work_order_version,
                evidence_policy_id, evidence_policy_revision, requirement_key,
                schema_template_id, schema_template_revision, schema_version,
                test_subject_ref, values_json, instrument_asset_id, raw_evidence_id,
                device_reported_status, evaluated_status, evaluation_json,
                captured_at, client_occurred_at, supersedes_result_id, created_at,
                correlation_id, version
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, 1)
            """.trimIndent(),
            request.operationId, source.organizationId, source.workOrderId,
            assignment.assignmentId, assignment.version, actor, deviceInstallationId,
            source.workOrderVersion, source.evidencePolicyId, source.evidencePolicyRevision,
            source.requirementKey, source.templateId, source.templateRevision, source.schemaVersion,
            request.testSubjectRef.trim(), valuesJson, request.instrumentAssetId, request.rawEvidenceId,
            deviceStatus, status.name, evaluationJson, request.capturedAt.atOffset(ZoneOffset.UTC),
            request.clientOccurredAt?.atOffset(ZoneOffset.UTC), request.supersedesResultId,
            at.atOffset(ZoneOffset.UTC), correlationId,
        )
        check(inserted == 1) { "Engineering test result must insert exactly once." }
    }
}

data class FieldEngineeringInstrumentState(
    val organizationId: UUID,
    val calibrationStatus: String?,
)

interface FieldEngineeringInstrumentSourcePort {
    fun state(assetId: UUID, at: Instant): FieldEngineeringInstrumentState?
}

@Service
class FieldEngineeringTestService(
    private val persistence: FieldEngineeringTestPersistencePort,
    private val work: WorkPersistencePort,
    private val readiness: ReadinessAssignmentPersistencePort,
    private val sourceAuthority: WorkAssignmentSourceAuthorityPort,
    private val authorization: WorkAssignmentAuthorizationPort,
    private val instruments: FieldEngineeringInstrumentSourcePort,
    private val commands: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val clock: Clock,
    @Value("\${hiltech.field.engineering-tests.enabled:false}")
    private val enabled: Boolean = false,
) {
    @Transactional
    fun capture(
        actor: UUID,
        deviceInstallationId: UUID,
        workOrderId: UUID,
        request: FieldEngineeringTestCaptureRequest,
        correlationId: String,
    ): FieldEngineeringTestReceipt {
        if (!enabled) unavailable()
        validateRequest(request, workOrderId)
        val now = clock.instant()
        val source = persistence.source(workOrderId, request.requirementKey) ?: unavailable()
        val schema = FieldEngineeringTestSchema.parse(source)
        requireCurrent(actor, workOrderId, request, source, now)
        val assignment = readiness.activeAssignment(workOrderId) ?: unavailable()
        if (assignment.assignmentId != request.assignmentId || assignment.version != request.assignmentVersion) {
            stale("ENGINEERING_TEST_ASSIGNMENT_CHANGED")
        }
        val evaluated = evaluate(schema, request.values)
        val deviceStatus = normalizeDeviceStatus(request.deviceReportedStatus, schema)
        validateEvidenceAndInstrument(actor, request, source, schema, now)
        validateSupersession(request, source)

        val canonicalValues = buildJsonObject {
            schema.metrics.forEach { metric ->
                evaluated.first[metric.key]?.let { put(metric.key, it) }
            }
        }.toString()
        val evaluationJson = buildJsonObject {
            put("schemaTemplateId", source.templateId.toString())
            put("schemaTemplateRevision", source.templateRevision)
            put("schemaVersion", source.schemaVersion)
            put("status", evaluated.second.name)
            putJsonArray("metrics") {
                evaluated.third.forEach { item ->
                    add(buildJsonObject {
                        put("key", item.key)
                        put("status", item.status.name)
                        item.normalizedValue?.let { put("value", it) }
                    })
                }
            }
        }.toString()

        val fingerprint = IdempotencyKeyContract.fingerprint(
            listOf(
                workOrderId, request.assignmentId, request.assignmentVersion, request.workOrderVersion,
                request.evidencePolicyId, request.evidencePolicyRevision, request.requirementKey,
                request.schemaTemplateId, request.schemaTemplateRevision, request.schemaVersion,
                request.testSubjectRef.trim(), canonicalValues, request.instrumentAssetId,
                request.rawEvidenceId, deviceStatus, request.capturedAt, request.clientOccurredAt,
                request.supersedesResultId,
            ).joinToString("|"),
        )

        val execution = commands.execute(
            IdempotentCommandSpec(
                operationId = request.operationId,
                actorUserId = actor,
                commandType = "FIELD_ENGINEERING_TEST_CAPTURE",
                targetType = "WORK_ORDER",
                targetId = workOrderId,
                requestFingerprint = fingerprint,
                correlationId = correlationId,
            ),
        ) {
            val currentSource = persistence.source(workOrderId, request.requirementKey) ?: unavailable()
            requireCurrent(actor, workOrderId, request, currentSource, clock.instant())
            val currentAssignment = readiness.activeAssignment(workOrderId) ?: unavailable()
            if (currentAssignment.assignmentId != request.assignmentId ||
                currentAssignment.version != request.assignmentVersion) {
                stale("ENGINEERING_TEST_ASSIGNMENT_CHANGED")
            }
            validateEvidenceAndInstrument(actor, request, currentSource, schema, clock.instant())
            validateSupersession(request, currentSource)
            persistence.insert(
                request, currentSource, currentAssignment, actor, deviceInstallationId,
                canonicalValues, evaluated.second, evaluationJson, deviceStatus,
                clock.instant(), correlationId,
            )
            audit.append(
                AuditEventRecord(
                    actorUserId = actor,
                    action = "FIELD_ENGINEERING_TEST_CAPTURED",
                    targetType = "WORK_ORDER",
                    targetId = workOrderId,
                    newStateRef = request.operationId.toString(),
                    safeDiffJson = """{"fields":["engineeringTest","${request.requirementKey}","${evaluated.second.name}"]}""",
                    occurredAt = clock.instant(),
                    correlationId = correlationId,
                ),
            )
            IdempotentCommandOutcome(
                "ENGINEERING_TEST_CAPTURED",
                """{"resultId":"${request.operationId}","status":"${evaluated.second.name}"}""",
            )
        }

        val stored = persistence.result(request.operationId)
            ?: stale("ENGINEERING_TEST_RESULT_MISSING")
        return FieldEngineeringTestReceipt(
            stored.operationId, stored.workOrderId, stored.requirementKey,
            stored.testSubjectRef, stored.evaluatedStatus, stored.deviceReportedStatus,
            schema.reviewerRelationshipCode, stored.supersedesResultId, execution.replayed,
        )
    }

    private fun validateRequest(request: FieldEngineeringTestCaptureRequest, workOrderId: UUID) {
        if (request.assignmentVersion < 1 || request.workOrderVersion < 1 ||
            request.evidencePolicyRevision < 1 || request.schemaTemplateRevision < 1 ||
            request.schemaVersion < 1 || request.requirementKey.isBlank() ||
            request.requirementKey.length > 64 || request.testSubjectRef.isBlank() ||
            request.testSubjectRef.length > 160 || request.values.size > 100) {
            invalid("ENGINEERING_TEST_REQUEST_INVALID")
        }
        if (request.supersedesResultId == request.operationId) invalid("ENGINEERING_TEST_SUPERSESSION_INVALID")
        if (workOrderId == UUID(0, 0)) invalid("ENGINEERING_TEST_REQUEST_INVALID")
    }

    private fun requireCurrent(
        actor: UUID,
        workOrderId: UUID,
        request: FieldEngineeringTestCaptureRequest,
        source: FieldEngineeringTestSource,
        now: Instant,
    ) {
        if (!sourceAuthority.isCurrentAssignedUser(actor, workOrderId, now) ||
            !authorization.canUseAssignedWork(actor, workOrderId, "can_submit_completion")) {
            unavailable()
        }
        val order = work.workOrder(workOrderId) ?: unavailable()
        if (order.version != request.workOrderVersion || source.workOrderVersion != request.workOrderVersion) {
            stale("ENGINEERING_TEST_WORK_CHANGED")
        }
        if (order.lifecycleState !in setOf(
                WorkOrderLifecycle.ASSIGNED, WorkOrderLifecycle.IN_PROGRESS,
                WorkOrderLifecycle.BLOCKED, WorkOrderLifecycle.REWORK_REQUIRED,
            )) {
            stale("ENGINEERING_TEST_STATE_CHANGED")
        }
        if (source.evidencePolicyId != request.evidencePolicyId ||
            source.evidencePolicyRevision != request.evidencePolicyRevision ||
            source.templateId != request.schemaTemplateId ||
            source.templateRevision != request.schemaTemplateRevision ||
            source.schemaVersion != request.schemaVersion) {
            stale("ENGINEERING_TEST_SCHEMA_CHANGED")
        }
    }

    private fun evaluate(
        schema: FieldEngineeringTestDefinition,
        supplied: Map<String, String>,
    ): Triple<Map<String, String>, EngineeringEvaluationStatus, List<FieldEngineeringMetricEvaluation>> {
        val allowedKeys = schema.metrics.map { it.key }.toSet()
        if (supplied.keys.any { it !in allowedKeys }) invalid("ENGINEERING_TEST_UNKNOWN_METRIC")
        val normalized = linkedMapOf<String, String>()
        val evaluations = schema.metrics.map { metric ->
            val raw = supplied[metric.key]?.trim()?.takeIf { it.isNotEmpty() }
            if (raw == null) {
                if (metric.required) invalid("ENGINEERING_TEST_REQUIRED_METRIC_MISSING")
                return@map FieldEngineeringMetricEvaluation(metric.key, EngineeringEvaluationStatus.UNKNOWN, null)
            }
            val value = normalize(metric, raw)
            normalized[metric.key] = value
            FieldEngineeringMetricEvaluation(metric.key, evaluateMetric(metric, value), value)
        }
        val overall = when {
            evaluations.any { it.status == EngineeringEvaluationStatus.FAIL } -> EngineeringEvaluationStatus.FAIL
            evaluations.any { it.status == EngineeringEvaluationStatus.PASS } -> EngineeringEvaluationStatus.PASS
            else -> EngineeringEvaluationStatus.UNKNOWN
        }
        return Triple(normalized, overall, evaluations)
    }

    private fun normalize(metric: FieldEngineeringMetricDefinition, raw: String): String {
        if (raw.length > 500) invalid("ENGINEERING_TEST_VALUE_INVALID")
        return when (metric.valueType) {
            EngineeringMetricValueType.DECIMAL -> {
                val value = runCatching { BigDecimal(raw) }.getOrElse { invalid("ENGINEERING_TEST_VALUE_INVALID") }
                val scale = value.stripTrailingZeros().scale().coerceAtLeast(0)
                if (metric.precision != null && scale > metric.precision) invalid("ENGINEERING_TEST_PRECISION_INVALID")
                value.stripTrailingZeros().toPlainString()
            }
            EngineeringMetricValueType.INTEGER -> {
                val value = runCatching { BigDecimal(raw) }.getOrElse { invalid("ENGINEERING_TEST_VALUE_INVALID") }
                if (value.stripTrailingZeros().scale() > 0) invalid("ENGINEERING_TEST_VALUE_INVALID")
                value.toBigIntegerExact().toString()
            }
            EngineeringMetricValueType.BOOLEAN -> when (raw.lowercase()) {
                "true" -> "true"
                "false" -> "false"
                else -> invalid("ENGINEERING_TEST_VALUE_INVALID")
            }
            EngineeringMetricValueType.ENUM -> {
                if (raw !in metric.options) invalid("ENGINEERING_TEST_VALUE_INVALID")
                raw
            }
            EngineeringMetricValueType.TEXT -> raw
        }
    }

    private fun evaluateMetric(metric: FieldEngineeringMetricDefinition, normalized: String): EngineeringEvaluationStatus =
        when (metric.valueType) {
            EngineeringMetricValueType.DECIMAL, EngineeringMetricValueType.INTEGER -> {
                val value = BigDecimal(normalized)
                val min = metric.minValue?.let(::BigDecimal)
                val max = metric.maxValue?.let(::BigDecimal)
                if (min == null && max == null) EngineeringEvaluationStatus.UNKNOWN
                else if ((min == null || value >= min) && (max == null || value <= max)) EngineeringEvaluationStatus.PASS
                else EngineeringEvaluationStatus.FAIL
            }
            EngineeringMetricValueType.TEXT, EngineeringMetricValueType.ENUM, EngineeringMetricValueType.BOOLEAN -> {
                if (metric.acceptedValues.isEmpty()) EngineeringEvaluationStatus.UNKNOWN
                else if (normalized in metric.acceptedValues) EngineeringEvaluationStatus.PASS
                else EngineeringEvaluationStatus.FAIL
            }
        }

    private fun normalizeDeviceStatus(raw: String?, schema: FieldEngineeringTestDefinition): String? {
        if (raw == null) return null
        if (!schema.deviceReportedStatusAllowed) invalid("ENGINEERING_TEST_DEVICE_STATUS_NOT_ALLOWED")
        return raw.trim().uppercase().takeIf { it in setOf("PASS", "FAIL", "UNKNOWN") }
            ?: invalid("ENGINEERING_TEST_DEVICE_STATUS_INVALID")
    }

    private fun validateEvidenceAndInstrument(
        actor: UUID,
        request: FieldEngineeringTestCaptureRequest,
        source: FieldEngineeringTestSource,
        schema: FieldEngineeringTestDefinition,
        now: Instant,
    ) {
        if (schema.rawEvidenceRequired && request.rawEvidenceId == null) {
            invalid("ENGINEERING_TEST_RAW_EVIDENCE_REQUIRED")
        }
        request.rawEvidenceId?.let {
            if (!persistence.readyEvidence(it, source, actor)) invalid("ENGINEERING_TEST_RAW_EVIDENCE_INVALID")
        }
        if (schema.instrumentAssetRequired && request.instrumentAssetId == null) {
            invalid("ENGINEERING_TEST_INSTRUMENT_REQUIRED")
        }
        request.instrumentAssetId?.let { assetId ->
            val instrument = instruments.state(assetId, now)
                ?.takeIf { it.organizationId == source.organizationId }
                ?: invalid("ENGINEERING_TEST_INSTRUMENT_INVALID")
            if (schema.calibrationRequired &&
                instrument.calibrationStatus !in setOf("VALID", "DUE")) {
                stale("ENGINEERING_TEST_CALIBRATION_INVALID")
            }
        }
    }

    private fun validateSupersession(
        request: FieldEngineeringTestCaptureRequest,
        source: FieldEngineeringTestSource,
    ) {
        request.supersedesResultId?.let { priorId ->
            val prior = persistence.result(priorId) ?: invalid("ENGINEERING_TEST_SUPERSESSION_INVALID")
            if (prior.organizationId != source.organizationId || prior.workOrderId != source.workOrderId ||
                prior.requirementKey != source.requirementKey || prior.schemaTemplateId != source.templateId ||
                prior.schemaTemplateRevision != source.templateRevision ||
                prior.testSubjectRef != request.testSubjectRef.trim() || persistence.hasSuccessor(priorId)) {
                invalid("ENGINEERING_TEST_SUPERSESSION_INVALID")
            }
        }
    }

    private fun unavailable(): Nothing = throw ProductApiException(
        "ENGINEERING_TEST_UNAVAILABLE", "The structured engineering test is not available.", HttpStatus.NOT_FOUND,
    )
    private fun invalid(code: String): Nothing = throw ProductApiException(
        code, "The engineering test input is invalid.", HttpStatus.BAD_REQUEST,
    )
    private fun stale(code: String): Nothing = throw ProductApiException(
        code, "The engineering test source changed; reload the WorkOrder before retrying.", HttpStatus.CONFLICT,
    )
}

@RestController
@RequestMapping("/v1/work-orders")
class FieldEngineeringTestController(
    private val service: FieldEngineeringTestService,
) {
    @PostMapping("/{workOrderId}/engineering-tests")
    fun capture(
        servletRequest: HttpServletRequest,
        @PathVariable workOrderId: UUID,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestBody request: FieldEngineeringTestCaptureRequest,
    ): FieldEngineeringTestReceipt {
        IdempotencyKeyContract.requireMatches(idempotencyKey, request.operationId)
        val context = HiltechRequestContext.current(servletRequest)
        val actor = context.identityId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw ProductApiException("AUTHENTICATION_REQUIRED", "A HILTECH identity is required.", HttpStatus.UNAUTHORIZED)
        val device = context.deviceInstallationId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw ProductApiException("DEVICE_CONTEXT_REQUIRED", "A valid device installation is required.", HttpStatus.BAD_REQUEST)
        return service.capture(actor, device, workOrderId, request, context.correlationId)
    }
}
