package com.hiltech.server.work

import com.hiltech.server.projects.ProjectSnapshot
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class WorkCodePolicy(
    val id: UUID, val code: String, val prefix: String?, val includeYear: Boolean, val separator: String,
    val sequencePadding: Int, val manualOverrideAllowed: Boolean, val resetRule: String,
)

data class BoundConfig(val ref: ConfigRef, val templateType: String? = null, val schemaVersion: Int? = null, val json: String? = null)

data class WorkTypeDefinitionSnapshot(
    val ref: ConfigRef, val description: String?, val assignment: BoundConfig, val readiness: BoundConfig,
    val evidence: BoundConfig, val review: BoundConfig, val tracking: BoundConfig?,
    val assetTemplate: BoundConfig?, val materialTemplate: BoundConfig?, val documentTemplate: BoundConfig?, val checklistTemplate: BoundConfig?,
    val instructionTemplate: BoundConfig?, val defaultPriorityCode: String?,
    val defaultProgressWeight: BigDecimal, val countsTowardProgress: Boolean,
)

data class RequirementDefinition(val id: UUID, val key: String, val type: String, val label: String?, val required: Boolean, val sortOrder: Int)

interface WorkPersistencePort {
    fun workType(organizationId: UUID, code: String, revision: Int?, at: Instant): WorkTypeDefinitionSnapshot?
    fun workTypes(organizationId: UUID, at: Instant): List<WorkTypeChoice>
    fun codePolicies(organizationId: UUID, at: Instant): List<WorkCodePolicy>
    fun allocateCode(projectId: UUID, policy: WorkCodePolicy, at: Instant): Long
    fun codeExists(projectId: UUID, code: String): Boolean
    fun validContext(project: ProjectSnapshot, projectSiteId: UUID, siteId: UUID, areaId: UUID?, packageId: UUID?): Boolean
    fun insertWorkOrder(id: UUID, project: ProjectSnapshot, code: String, siteId: UUID, projectSiteId: UUID, areaId: UUID?, packageId: UUID?, title: String, description: String?, start: Instant?, end: Instant?, priority: String, actor: UUID, at: Instant)
    fun workOrder(id: UUID): WorkOrderSnapshot?
    fun workOrders(projectId: UUID): List<WorkOrderSnapshot>
    fun updateDetails(id: UUID, version: Long, areaId: UUID?, packageId: UUID?, title: String, description: String?, start: Instant?, end: Instant?, priority: String, at: Instant): Boolean
    fun insertBinding(id: UUID, workOrderId: UUID, definition: WorkTypeDefinitionSnapshot, actor: UUID, at: Instant)
    fun insertInstruction(id: UUID, workOrderId: UUID, revision: Int, template: BoundConfig?, schemaVersion: Int, json: String, summary: String?, supersedes: UUID?, reason: String?, actor: UUID, at: Instant, correlationId: String)
    fun materializeChecklist(workOrderId: UUID, template: BoundConfig?, items: List<TemplateChecklistItem>)
    fun readinessRequirements(configId: UUID): List<RequirementDefinition>
    fun evidenceRequirements(configId: UUID): List<RequirementDefinition>
    fun materializeRequirements(workOrderId: UUID, definitions: List<MaterializedRequirement>, at: Instant)
    fun markPlanned(id: UUID, version: Long, bindingId: UUID, instructionId: UUID, definition: WorkTypeDefinitionSnapshot, at: Instant): Boolean
    fun reviseInstruction(id: UUID, version: Long, instructionId: UUID, instructionRevision: Int, schemaVersion: Int, json: String, summary: String?, reason: String, actor: UUID, at: Instant, correlationId: String): Boolean
    fun insertTask(id: UUID, workOrder: WorkOrderSnapshot, code: String?, title: String, description: String?, order: Int, mandatory: Boolean, duration: Int?, evidenceKey: String?, actor: UUID, at: Instant)
    fun task(id: UUID): WorkTaskSnapshot?
    fun taskWorkOrderId(id: UUID): UUID?
    fun updateTask(id: UUID, version: Long, code: String?, title: String, description: String?, order: Int, mandatory: Boolean, duration: Int?, evidenceKey: String?, state: WorkTaskState, at: Instant): Boolean
    fun bumpWorkOrder(id: UUID, version: Long, at: Instant): Boolean
    fun insertDependency(id: UUID, workOrder: WorkOrderSnapshot, predecessor: UUID, type: WorkDependencyType, lag: Long, actor: UUID, at: Instant)
    fun dependency(id: UUID): WorkDependencySnapshot?
    fun deleteDependency(id: UUID, version: Long): Boolean
    fun dependencyGraph(projectId: UUID): List<WorkDependencySnapshot>
    fun activationBlockers(project: ProjectSnapshot): List<String>
    fun activateProject(projectId: UUID, version: Long, at: Instant): Boolean
}

data class TemplateChecklistItem(val key: String, val label: String, val required: Boolean, val sortOrder: Int, val evidenceKey: String?)
data class MaterializedRequirement(val family: String, val definition: RequirementDefinition, val source: ConfigRef?)

@Component
class JdbcWorkPersistence(private val jdbc: JdbcTemplate) : WorkPersistencePort {
    override fun workType(organizationId: UUID, code: String, revision: Int?, at: Instant): WorkTypeDefinitionSnapshot? =
        workTypeRows(
            organizationId,
            at,
            "AND wt.code = ?" + if (revision == null) "" else " AND wt.revision_number = ?",
            if (revision == null) arrayOf<Any>(code) else arrayOf<Any>(code, revision),
        ).singleOrNull()

    override fun workTypes(organizationId: UUID, at: Instant): List<WorkTypeChoice> =
        workTypeRows(organizationId, at, "", emptyArray()).map { WorkTypeChoice(it.ref, it.description, it.defaultPriorityCode, it.defaultProgressWeight, it.countsTowardProgress) }

    private fun workTypeRows(organizationId: UUID, at: Instant, extra: String, extraArgs: Array<out Any>): List<WorkTypeDefinitionSnapshot> {
        val timestamp = at.atOffset(ZoneOffset.UTC)
        return jdbc.query(
            """
            SELECT wt.id wt_id, wt.code wt_code, wt.name wt_name, wt.description wt_description, wt.revision_number wt_revision,
                   wtd.default_priority_code, wtd.default_progress_weight, wtd.counts_toward_project_progress,
                   ap.id ap_id, ap.code ap_code, ap.name ap_name, ap.revision_number ap_revision,
                   rp.id rp_id, rp.code rp_code, rp.name rp_name, rp.revision_number rp_revision,
                   ep.id ep_id, ep.code ep_code, ep.name ep_name, ep.revision_number ep_revision,
                   vp.id vp_id, vp.code vp_code, vp.name vp_name, vp.revision_number vp_revision,
                   tp.id tp_id, tp.code tp_code, tp.name tp_name, tp.revision_number tp_revision,
                   asset.id asset_id, asset.code asset_code, asset.name asset_name, asset.revision_number asset_revision,
                   atd.template_type asset_type, atd.schema_version asset_schema, atd.structured_definition::text asset_json,
                   material.id material_id, material.code material_code, material.name material_name, material.revision_number material_revision,
                   mtd.template_type material_type, mtd.schema_version material_schema, mtd.structured_definition::text material_json,
                   document.id document_id, document.code document_code, document.name document_name, document.revision_number document_revision,
                   dtd.template_type document_type, dtd.schema_version document_schema, dtd.structured_definition::text document_json,
                   checklist.id checklist_id, checklist.code checklist_code, checklist.name checklist_name, checklist.revision_number checklist_revision,
                   ctd.template_type checklist_type, ctd.schema_version checklist_schema, ctd.structured_definition::text checklist_json,
                   instruction.id instruction_id, instruction.code instruction_code, instruction.name instruction_name, instruction.revision_number instruction_revision,
                   itd.template_type instruction_type, itd.schema_version instruction_schema, itd.structured_definition::text instruction_json
            FROM config_revision wt
            JOIN work_type_definition wtd ON wtd.config_revision_id = wt.id
            JOIN config_revision ap ON ap.id = wtd.assignment_policy_id
            JOIN assignment_policy apd ON apd.config_revision_id = ap.id
            JOIN config_revision rp ON rp.id = wtd.readiness_policy_id
            JOIN readiness_policy rpd ON rpd.config_revision_id = rp.id
            JOIN config_revision ep ON ep.id = wtd.evidence_policy_id
            JOIN evidence_policy epd ON epd.config_revision_id = ep.id
            JOIN config_revision vp ON vp.id = wtd.review_policy_id
            JOIN review_policy vpd ON vpd.config_revision_id = vp.id
            LEFT JOIN config_revision tp ON tp.id = wtd.tracking_policy_id
            LEFT JOIN field_tracking_policy tpd ON tpd.config_revision_id = tp.id
            LEFT JOIN config_revision asset ON asset.id = wtd.asset_requirement_template_id
            LEFT JOIN template_definition atd ON atd.config_revision_id = asset.id
            LEFT JOIN config_revision material ON material.id = wtd.material_requirement_template_id
            LEFT JOIN template_definition mtd ON mtd.config_revision_id = material.id
            LEFT JOIN config_revision document ON document.id = wtd.document_requirement_template_id
            LEFT JOIN template_definition dtd ON dtd.config_revision_id = document.id
            LEFT JOIN config_revision checklist ON checklist.id = wtd.checklist_template_id
            LEFT JOIN template_definition ctd ON ctd.config_revision_id = checklist.id
            LEFT JOIN config_revision instruction ON instruction.id = wtd.instruction_template_id
            LEFT JOIN template_definition itd ON itd.config_revision_id = instruction.id
            WHERE wt.family = 'work-types' AND wt.lifecycle_state = 'ACTIVE'
              AND (wt.effective_from IS NULL OR wt.effective_from <= ?) AND (wt.effective_to IS NULL OR wt.effective_to > ?)
              AND ((wt.scope_type = 'ORGANIZATION' AND wt.scope_organization_id = ?) OR wt.scope_type = 'SYSTEM')
              AND ap.lifecycle_state = 'ACTIVE' AND rp.lifecycle_state = 'ACTIVE' AND ep.lifecycle_state = 'ACTIVE' AND vp.lifecycle_state = 'ACTIVE'
              AND (ap.scope_type = 'SYSTEM' OR (wt.scope_type = 'ORGANIZATION' AND ap.scope_type = 'ORGANIZATION' AND ap.scope_organization_id = wt.scope_organization_id))
              AND (rp.scope_type = 'SYSTEM' OR (wt.scope_type = 'ORGANIZATION' AND rp.scope_type = 'ORGANIZATION' AND rp.scope_organization_id = wt.scope_organization_id))
              AND (ep.scope_type = 'SYSTEM' OR (wt.scope_type = 'ORGANIZATION' AND ep.scope_type = 'ORGANIZATION' AND ep.scope_organization_id = wt.scope_organization_id))
              AND (vp.scope_type = 'SYSTEM' OR (wt.scope_type = 'ORGANIZATION' AND vp.scope_type = 'ORGANIZATION' AND vp.scope_organization_id = wt.scope_organization_id))
              AND (tp.id IS NULL OR (tp.lifecycle_state = 'ACTIVE' AND tpd.config_revision_id IS NOT NULL
                   AND (tp.scope_type = 'SYSTEM' OR (wt.scope_type = 'ORGANIZATION' AND tp.scope_type = 'ORGANIZATION' AND tp.scope_organization_id = wt.scope_organization_id))))
              AND (asset.id IS NULL OR (asset.lifecycle_state = 'ACTIVE' AND atd.config_revision_id IS NOT NULL
                   AND (asset.scope_type = 'SYSTEM' OR (wt.scope_type = 'ORGANIZATION' AND asset.scope_type = 'ORGANIZATION' AND asset.scope_organization_id = wt.scope_organization_id))))
              AND (material.id IS NULL OR (material.lifecycle_state = 'ACTIVE' AND mtd.config_revision_id IS NOT NULL
                   AND (material.scope_type = 'SYSTEM' OR (wt.scope_type = 'ORGANIZATION' AND material.scope_type = 'ORGANIZATION' AND material.scope_organization_id = wt.scope_organization_id))))
              AND (document.id IS NULL OR (document.lifecycle_state = 'ACTIVE' AND dtd.config_revision_id IS NOT NULL
                   AND (document.scope_type = 'SYSTEM' OR (wt.scope_type = 'ORGANIZATION' AND document.scope_type = 'ORGANIZATION' AND document.scope_organization_id = wt.scope_organization_id))))
              AND (checklist.id IS NULL OR (checklist.lifecycle_state = 'ACTIVE' AND ctd.config_revision_id IS NOT NULL
                   AND (checklist.scope_type = 'SYSTEM' OR (wt.scope_type = 'ORGANIZATION' AND checklist.scope_type = 'ORGANIZATION' AND checklist.scope_organization_id = wt.scope_organization_id))))
              AND (instruction.id IS NULL OR (instruction.lifecycle_state = 'ACTIVE' AND itd.config_revision_id IS NOT NULL
                   AND (instruction.scope_type = 'SYSTEM' OR (wt.scope_type = 'ORGANIZATION' AND instruction.scope_type = 'ORGANIZATION' AND instruction.scope_organization_id = wt.scope_organization_id))))
              $extra
            ORDER BY CASE WHEN wt.scope_type = 'ORGANIZATION' THEN 0 ELSE 1 END, wt.code, wt.revision_number DESC
            """.trimIndent(),
            { rs, _ ->
                fun ref(prefix: String): ConfigRef = ConfigRef(rs.getObject("${prefix}_id", UUID::class.java), rs.getString("${prefix}_code"), rs.getString("${prefix}_name"), rs.getInt("${prefix}_revision"))
                fun optional(prefix: String): BoundConfig? = rs.getObject("${prefix}_id", UUID::class.java)?.let { BoundConfig(ref(prefix)) }
                fun template(prefix: String): BoundConfig? = rs.getObject("${prefix}_id", UUID::class.java)?.let { BoundConfig(ref(prefix), rs.getString("${prefix}_type"), rs.getObject("${prefix}_schema", Int::class.javaObjectType), rs.getString("${prefix}_json")) }
                WorkTypeDefinitionSnapshot(
                    ref("wt"), rs.getString("wt_description"), BoundConfig(ref("ap")), BoundConfig(ref("rp")), BoundConfig(ref("ep")), BoundConfig(ref("vp")), optional("tp"),
                    template("asset"), template("material"), template("document"), template("checklist"), template("instruction"), rs.getString("default_priority_code"),
                    rs.getBigDecimal("default_progress_weight"), rs.getBoolean("counts_toward_project_progress"),
                )
            }, timestamp, timestamp, organizationId, *extraArgs,
        ).distinctBy { it.ref.code }
    }

    override fun codePolicies(organizationId: UUID, at: Instant): List<WorkCodePolicy> {
        val timestamp = at.atOffset(ZoneOffset.UTC)
        return jdbc.query(
            """SELECT cr.id, cr.code, cp.prefix, cp.include_year, cp.separator, cp.sequence_padding, cp.manual_override_allowed, cp.reset_rule
               FROM config_revision cr JOIN code_policy cp ON cp.config_revision_id = cr.id
               WHERE cr.family='code-policies' AND cr.lifecycle_state='ACTIVE' AND cp.target_object_type='WORK_ORDER'
                 AND cp.sequence_scope='PROJECT' AND cp.uniqueness_scope='PROJECT'
                 AND ((cr.scope_type='ORGANIZATION' AND cr.scope_organization_id=?) OR cr.scope_type='SYSTEM')
                 AND (cr.effective_from IS NULL OR cr.effective_from<=?) AND (cr.effective_to IS NULL OR cr.effective_to>?)
               ORDER BY CASE WHEN cr.scope_type='ORGANIZATION' THEN 0 ELSE 1 END, cr.code""".trimIndent(),
            { rs, _ -> WorkCodePolicy(rs.getObject("id", UUID::class.java), rs.getString("code"), rs.getString("prefix"), rs.getBoolean("include_year"), rs.getString("separator"), rs.getInt("sequence_padding"), rs.getBoolean("manual_override_allowed"), rs.getString("reset_rule")) },
            organizationId, timestamp, timestamp,
        )
    }

    override fun allocateCode(projectId: UUID, policy: WorkCodePolicy, at: Instant): Long {
        val period = if (policy.resetRule == "YEARLY") at.atZone(ZoneOffset.UTC).year.toString() else "ALL"
        jdbc.update("""INSERT INTO code_sequence(scope_key,target_object_type,policy_code,sequence_period,next_value,version,updated_at) VALUES (?,'WORK_ORDER',?,?,1,1,?) ON CONFLICT DO NOTHING""", projectId.toString(), policy.code, period, at.atOffset(ZoneOffset.UTC))
        return requireNotNull(jdbc.queryForObject("""UPDATE code_sequence SET next_value=next_value+1,version=version+1,updated_at=? WHERE scope_key=? AND target_object_type='WORK_ORDER' AND policy_code=? AND sequence_period=? RETURNING next_value-1""", Long::class.java, at.atOffset(ZoneOffset.UTC), projectId.toString(), policy.code, period))
    }

    override fun codeExists(projectId: UUID, code: String): Boolean = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM work_order WHERE project_id=? AND work_order_code=?)", Boolean::class.java, projectId, code) == true

    override fun validContext(project: ProjectSnapshot, projectSiteId: UUID, siteId: UUID, areaId: UUID?, packageId: UUID?): Boolean =
        jdbc.queryForObject(
            """SELECT EXISTS(SELECT 1 FROM project_site ps WHERE ps.id=? AND ps.organization_id=? AND ps.project_id=? AND ps.site_id=? AND ps.lifecycle_state<>'CLOSED')
               AND (?::uuid IS NULL OR EXISTS(SELECT 1 FROM area a WHERE a.id=? AND a.organization_id=? AND a.site_id=?))
               AND (?::uuid IS NULL OR EXISTS(SELECT 1 FROM work_package wp WHERE wp.id=? AND wp.organization_id=? AND wp.project_id=? AND (wp.site_id IS NULL OR wp.site_id=?)))""".trimIndent(),
            Boolean::class.java, projectSiteId, project.organizationId, project.projectId, siteId, areaId, areaId, project.organizationId, siteId, packageId, packageId, project.organizationId, project.projectId, siteId,
        ) == true

    override fun insertWorkOrder(id: UUID, project: ProjectSnapshot, code: String, siteId: UUID, projectSiteId: UUID, areaId: UUID?, packageId: UUID?, title: String, description: String?, start: Instant?, end: Instant?, priority: String, actor: UUID, at: Instant) {
        jdbc.update(
            """INSERT INTO work_order(id,organization_id,work_order_code,project_id,site_id,project_site_id,area_id,work_package_id,title,description,lifecycle_state,readiness_state,planned_start,planned_end,priority_code,current_instruction_revision_id,current_policy_binding_id,progress_weight,counts_toward_project_progress,baseline_version,created_at,created_by,updated_at,version)
               VALUES (?,?,?,?,?,?,?,?,?,?,'DRAFT','NOT_EVALUATED',?,?,?,NULL,NULL,1.0,true,?,?,?,?,1)""".trimIndent(),
            id, project.organizationId, code, project.projectId, siteId, projectSiteId, areaId, packageId, title, description, start?.atOffset(ZoneOffset.UTC), end?.atOffset(ZoneOffset.UTC), priority, project.baselineVersion, at.atOffset(ZoneOffset.UTC), actor, at.atOffset(ZoneOffset.UTC),
        )
    }

    override fun workOrder(id: UUID): WorkOrderSnapshot? = loadOrders("wo.id=?", arrayOf(id)).singleOrNull()
    override fun workOrders(projectId: UUID): List<WorkOrderSnapshot> = loadOrders("wo.project_id=?", arrayOf(projectId))

    override fun updateDetails(id: UUID, version: Long, areaId: UUID?, packageId: UUID?, title: String, description: String?, start: Instant?, end: Instant?, priority: String, at: Instant): Boolean =
        jdbc.update("""UPDATE work_order SET area_id=?,work_package_id=?,title=?,description=?,planned_start=?,planned_end=?,priority_code=?,updated_at=?,version=version+1 WHERE id=? AND version=? AND lifecycle_state IN ('DRAFT','PLANNED')""", areaId, packageId, title, description, start?.atOffset(ZoneOffset.UTC), end?.atOffset(ZoneOffset.UTC), priority, at.atOffset(ZoneOffset.UTC), id, version) == 1

    override fun insertBinding(id: UUID, workOrderId: UUID, definition: WorkTypeDefinitionSnapshot, actor: UUID, at: Instant) {
        jdbc.update(
            """INSERT INTO work_policy_binding(id,work_order_id,binding_revision,work_type_definition_id,work_type_revision,assignment_policy_id,assignment_policy_revision,readiness_policy_id,readiness_policy_revision,evidence_policy_id,evidence_policy_revision,review_policy_id,review_policy_revision,tracking_policy_id,tracking_policy_revision,checklist_template_id,checklist_template_revision,instruction_template_id,instruction_template_revision,binding_created_at,binding_created_by)
               VALUES (?,?,1,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""".trimIndent(),
            id, workOrderId, definition.ref.id, definition.ref.revision, definition.assignment.ref.id, definition.assignment.ref.revision,
            definition.readiness.ref.id, definition.readiness.ref.revision, definition.evidence.ref.id, definition.evidence.ref.revision,
            definition.review.ref.id, definition.review.ref.revision, definition.tracking?.ref?.id, definition.tracking?.ref?.revision,
            definition.checklistTemplate?.ref?.id, definition.checklistTemplate?.ref?.revision, definition.instructionTemplate?.ref?.id,
            definition.instructionTemplate?.ref?.revision, at.atOffset(ZoneOffset.UTC), actor,
        )
    }

    override fun insertInstruction(id: UUID, workOrderId: UUID, revision: Int, template: BoundConfig?, schemaVersion: Int, json: String, summary: String?, supersedes: UUID?, reason: String?, actor: UUID, at: Instant, correlationId: String) {
        jdbc.update("""INSERT INTO work_instruction_revision(id,work_order_id,instruction_revision,source_instruction_template_id,source_instruction_template_revision,payload_schema_version,structured_payload_json,summary_text,created_at,created_by,supersedes_instruction_revision_id,change_reason,correlation_id) VALUES (?,?,?,?,?,?,?::jsonb,?,?,?,?,?,?)""", id, workOrderId, revision, template?.ref?.id, template?.ref?.revision, schemaVersion, json, summary, at.atOffset(ZoneOffset.UTC), actor, supersedes, reason, correlationId)
    }

    override fun materializeChecklist(workOrderId: UUID, template: BoundConfig?, items: List<TemplateChecklistItem>) {
        items.forEach { item -> jdbc.update("""INSERT INTO work_checklist_item_instance(id,work_order_id,source_template_id,source_template_revision,item_key,label,required,sort_order,completion_state,evidence_requirement_key,version) VALUES (?,?,?,?,?,?,?,?,'PENDING',?,1) ON CONFLICT (work_order_id,item_key) DO NOTHING""", UUID.nameUUIDFromBytes("checklist:$workOrderId:${item.key}".toByteArray()), workOrderId, template?.ref?.id, template?.ref?.revision, item.key, item.label, item.required, item.sortOrder, item.evidenceKey) }
    }

    override fun readinessRequirements(configId: UUID): List<RequirementDefinition> = jdbc.query("SELECT id,requirement_key,requirement_type_code,label,required,sort_order FROM readiness_policy_requirement WHERE config_revision_id=? ORDER BY sort_order,id", { rs, _ -> RequirementDefinition(rs.getObject("id", UUID::class.java), rs.getString("requirement_key"), rs.getString("requirement_type_code"), rs.getString("label"), rs.getBoolean("required"), rs.getInt("sort_order")) }, configId)
    override fun evidenceRequirements(configId: UUID): List<RequirementDefinition> = jdbc.query("SELECT id,requirement_key,evidence_type_code,NULL::text label,(min_count>0) required,0 sort_order FROM evidence_policy_requirement WHERE config_revision_id=? ORDER BY requirement_key", { rs, _ -> RequirementDefinition(rs.getObject("id", UUID::class.java), rs.getString("requirement_key"), rs.getString("evidence_type_code"), rs.getString("label"), rs.getBoolean("required"), rs.getInt("sort_order")) }, configId)

    override fun materializeRequirements(workOrderId: UUID, definitions: List<MaterializedRequirement>, at: Instant) {
        definitions.forEach { req -> jdbc.update("""INSERT INTO work_requirement_instance(id,work_order_id,requirement_family,requirement_key,source_config_id,source_config_revision,required,satisfaction_state,waived,updated_at,version) VALUES (?,?,?,?,?,?,?,'PENDING',false,?,1) ON CONFLICT (work_order_id,requirement_family,requirement_key) DO NOTHING""", UUID.nameUUIDFromBytes("requirement:$workOrderId:${req.family}:${req.definition.key}".toByteArray()), workOrderId, req.family, req.definition.key, req.source?.id, req.source?.revision, req.definition.required, at.atOffset(ZoneOffset.UTC)) }
    }

    override fun markPlanned(id: UUID, version: Long, bindingId: UUID, instructionId: UUID, definition: WorkTypeDefinitionSnapshot, at: Instant): Boolean =
        jdbc.update("""UPDATE work_order SET lifecycle_state='PLANNED',readiness_state='NOT_EVALUATED',current_policy_binding_id=?,current_instruction_revision_id=?,priority_code=COALESCE(NULLIF(priority_code,''),?),progress_weight=?,counts_toward_project_progress=?,updated_at=?,version=version+1 WHERE id=? AND version=? AND lifecycle_state='DRAFT'""", bindingId, instructionId, definition.defaultPriorityCode ?: "NORMAL", definition.defaultProgressWeight, definition.countsTowardProgress, at.atOffset(ZoneOffset.UTC), id, version) == 1

    override fun reviseInstruction(id: UUID, version: Long, instructionId: UUID, instructionRevision: Int, schemaVersion: Int, json: String, summary: String?, reason: String, actor: UUID, at: Instant, correlationId: String): Boolean {
        val current = workOrder(id) ?: return false
        insertInstruction(instructionId, id, instructionRevision, current.binding?.instructionTemplate?.let { BoundConfig(it) }, schemaVersion, json, summary, current.instruction?.instructionRevisionId, reason, actor, at, correlationId)
        return jdbc.update("""UPDATE work_order SET current_instruction_revision_id=?,updated_at=?,version=version+1 WHERE id=? AND version=? AND lifecycle_state IN ('DRAFT','PLANNED')""", instructionId, at.atOffset(ZoneOffset.UTC), id, version) == 1
    }

    override fun insertTask(id: UUID, workOrder: WorkOrderSnapshot, code: String?, title: String, description: String?, order: Int, mandatory: Boolean, duration: Int?, evidenceKey: String?, actor: UUID, at: Instant) {
        jdbc.update("""INSERT INTO work_task(id,organization_id,work_order_id,task_code,title,description,sort_order,mandatory,estimated_duration_minutes,evidence_requirement_key,state,created_at,created_by,updated_at,version) VALUES (?,?,?,?,?,?,?,?,?,?,'PLANNED',?,?,?,1)""", id, workOrder.organizationId, workOrder.workOrderId, code, title, description, order, mandatory, duration, evidenceKey, at.atOffset(ZoneOffset.UTC), actor, at.atOffset(ZoneOffset.UTC))
    }
    override fun task(id: UUID): WorkTaskSnapshot? = jdbc.query(taskSelect + " WHERE id=?", taskMapper, id).singleOrNull()
    override fun taskWorkOrderId(id: UUID): UUID? = jdbc.query("SELECT work_order_id FROM work_task WHERE id=?", { rs, _ -> rs.getObject(1, UUID::class.java) }, id).singleOrNull()
    override fun updateTask(id: UUID, version: Long, code: String?, title: String, description: String?, order: Int, mandatory: Boolean, duration: Int?, evidenceKey: String?, state: WorkTaskState, at: Instant): Boolean = jdbc.update("""UPDATE work_task SET task_code=?,title=?,description=?,sort_order=?,mandatory=?,estimated_duration_minutes=?,evidence_requirement_key=?,state=?,updated_at=?,version=version+1 WHERE id=? AND version=?""", code, title, description, order, mandatory, duration, evidenceKey, state.name, at.atOffset(ZoneOffset.UTC), id, version) == 1
    override fun bumpWorkOrder(id: UUID, version: Long, at: Instant): Boolean = jdbc.update("""UPDATE work_order SET updated_at=?,version=version+1 WHERE id=? AND version=? AND lifecycle_state IN ('DRAFT','PLANNED')""", at.atOffset(ZoneOffset.UTC), id, version) == 1

    override fun insertDependency(id: UUID, workOrder: WorkOrderSnapshot, predecessor: UUID, type: WorkDependencyType, lag: Long, actor: UUID, at: Instant) {
        jdbc.update("""INSERT INTO work_order_dependency(id,organization_id,project_id,predecessor_work_order_id,successor_work_order_id,dependency_type,lag_minutes,created_at,created_by,version) VALUES (?,?,?,?,?,?,?,?,?,1)""", id, workOrder.organizationId, workOrder.projectId, predecessor, workOrder.workOrderId, type.name, lag, at.atOffset(ZoneOffset.UTC), actor)
    }
    override fun dependency(id: UUID): WorkDependencySnapshot? = jdbc.query(dependencySelect + " WHERE id=?", dependencyMapper, id).singleOrNull()
    override fun deleteDependency(id: UUID, version: Long): Boolean = jdbc.update("DELETE FROM work_order_dependency WHERE id=? AND version=?", id, version) == 1
    override fun dependencyGraph(projectId: UUID): List<WorkDependencySnapshot> = jdbc.query(dependencySelect + " WHERE project_id=? ORDER BY created_at,id", dependencyMapper, projectId)

    override fun activationBlockers(project: ProjectSnapshot): List<String> = buildList {
        if (project.lifecycleState.name != "READY") add("PROJECT_NOT_READY")
        if (project.responsibility == null) add("PROJECT_RESPONSIBILITY_REQUIRED")
        val included = jdbc.queryForObject("SELECT count(*) FROM work_order WHERE project_id=? AND baseline_version=? AND counts_toward_project_progress=true AND lifecycle_state<>'CANCELLED'", Int::class.java, project.projectId, project.baselineVersion) ?: 0
        if (included == 0) add("INCLUDED_WORK_REQUIRED")
        val drafts = jdbc.queryForObject("SELECT count(*) FROM work_order WHERE project_id=? AND baseline_version=? AND counts_toward_project_progress=true AND lifecycle_state='DRAFT'", Int::class.java, project.projectId, project.baselineVersion) ?: 0
        if (drafts > 0) add("DRAFT_WORK_REMAINS")
        val unbound = jdbc.queryForObject("SELECT count(*) FROM work_order WHERE project_id=? AND baseline_version=? AND counts_toward_project_progress=true AND lifecycle_state<>'CANCELLED' AND (current_policy_binding_id IS NULL OR current_instruction_revision_id IS NULL)", Int::class.java, project.projectId, project.baselineVersion) ?: 0
        if (unbound > 0) add("UNBOUND_WORK_REMAINS")
        if (hasCycle(dependencyGraph(project.projectId))) add("WORK_DEPENDENCY_CYCLE")
    }
    override fun activateProject(projectId: UUID, version: Long, at: Instant): Boolean = jdbc.update("""UPDATE project SET lifecycle_state='ACTIVE',start_date_actual=COALESCE(start_date_actual,?),updated_at=?,version=version+1 WHERE id=? AND version=? AND lifecycle_state='READY'""", at.atOffset(ZoneOffset.UTC), at.atOffset(ZoneOffset.UTC), projectId, version) == 1

    private fun loadOrders(where: String, args: Array<out Any>): List<WorkOrderSnapshot> = jdbc.query(
        """SELECT wo.*, wpb.id binding_id, wpb.binding_revision,
                  wpb.work_type_definition_id, wt.code wt_code,wt.name wt_name,wpb.work_type_revision,
                  wpb.assignment_policy_id, ap.code ap_code,ap.name ap_name,wpb.assignment_policy_revision,
                  wpb.readiness_policy_id, rp.code rp_code,rp.name rp_name,wpb.readiness_policy_revision,
                  wpb.evidence_policy_id, ep.code ep_code,ep.name ep_name,wpb.evidence_policy_revision,
                  wpb.review_policy_id, vp.code vp_code,vp.name vp_name,wpb.review_policy_revision,
                  wpb.tracking_policy_id, tp.code tp_code,tp.name tp_name,wpb.tracking_policy_revision,
                  wpb.checklist_template_id, ct.code ct_code,ct.name ct_name,wpb.checklist_template_revision,
                  wpb.instruction_template_id, it.code it_code,it.name it_name,wpb.instruction_template_revision,wpb.binding_created_at,
                  wir.id instruction_id,wir.instruction_revision,wir.payload_schema_version,wir.structured_payload_json::text instruction_json,
                  wir.summary_text,wir.change_reason,wir.created_at instruction_created_at,wir.correlation_id
           FROM work_order wo LEFT JOIN work_policy_binding wpb ON wpb.id=wo.current_policy_binding_id
           LEFT JOIN config_revision wt ON wt.id=wpb.work_type_definition_id LEFT JOIN config_revision ap ON ap.id=wpb.assignment_policy_id
           LEFT JOIN config_revision rp ON rp.id=wpb.readiness_policy_id LEFT JOIN config_revision ep ON ep.id=wpb.evidence_policy_id
           LEFT JOIN config_revision vp ON vp.id=wpb.review_policy_id LEFT JOIN config_revision tp ON tp.id=wpb.tracking_policy_id
           LEFT JOIN config_revision ct ON ct.id=wpb.checklist_template_id LEFT JOIN config_revision it ON it.id=wpb.instruction_template_id
           LEFT JOIN work_instruction_revision wir ON wir.id=wo.current_instruction_revision_id WHERE $where ORDER BY wo.work_order_code,wo.id""".trimIndent(),
        { rs, _ ->
            val id=rs.getObject("id",UUID::class.java)
            fun cref(idColumn:String,codeColumn:String,nameColumn:String,revisionColumn:String):ConfigRef?=rs.getObject(idColumn,UUID::class.java)?.let{ConfigRef(it,rs.getString(codeColumn),rs.getString(nameColumn),rs.getInt(revisionColumn))}
            val wt=cref("work_type_definition_id","wt_code","wt_name","work_type_revision")
            val binding=rs.getObject("binding_id",UUID::class.java)?.let{WorkPolicyBindingSnapshot(it,rs.getInt("binding_revision"),requireNotNull(wt),requireNotNull(cref("assignment_policy_id","ap_code","ap_name","assignment_policy_revision")),requireNotNull(cref("readiness_policy_id","rp_code","rp_name","readiness_policy_revision")),requireNotNull(cref("evidence_policy_id","ep_code","ep_name","evidence_policy_revision")),requireNotNull(cref("review_policy_id","vp_code","vp_name","review_policy_revision")),cref("tracking_policy_id","tp_code","tp_name","tracking_policy_revision"),cref("checklist_template_id","ct_code","ct_name","checklist_template_revision"),cref("instruction_template_id","it_code","it_name","instruction_template_revision"),rs.getObject("binding_created_at",OffsetDateTime::class.java).toInstant())}
            val instruction=rs.getObject("instruction_id",UUID::class.java)?.let{WorkInstructionSnapshot(it,rs.getInt("instruction_revision"),binding?.instructionTemplate,rs.getInt("payload_schema_version"),rs.getString("instruction_json"),rs.getString("summary_text"),rs.getString("change_reason"),rs.getObject("instruction_created_at",OffsetDateTime::class.java).toInstant(),rs.getString("correlation_id"))}
            WorkOrderSnapshot(id,rs.getObject("organization_id",UUID::class.java),rs.getString("work_order_code"),rs.getObject("project_id",UUID::class.java),rs.getObject("site_id",UUID::class.java),rs.getObject("project_site_id",UUID::class.java),rs.getObject("area_id",UUID::class.java),rs.getObject("work_package_id",UUID::class.java),rs.getString("title"),rs.getString("description"),WorkOrderLifecycle.valueOf(rs.getString("lifecycle_state")),WorkReadiness.valueOf(rs.getString("readiness_state")),rs.getObject("planned_start",OffsetDateTime::class.java)?.toInstant(),rs.getObject("planned_end",OffsetDateTime::class.java)?.toInstant(),rs.getString("priority_code"),rs.getBoolean("counts_toward_project_progress"),rs.getBigDecimal("progress_weight"),rs.getInt("baseline_version"),rs.getObject("created_at",OffsetDateTime::class.java).toInstant(),rs.getObject("updated_at",OffsetDateTime::class.java).toInstant(),rs.getLong("version"),binding,instruction,checklist(id),requirements(id),tasks(id),dependencyGraph(rs.getObject("project_id",UUID::class.java)).filter{it.predecessorWorkOrderId==id||it.successorWorkOrderId==id})
        }, *args)

    private fun checklist(id:UUID)=jdbc.query("SELECT * FROM work_checklist_item_instance WHERE work_order_id=? ORDER BY sort_order,id",{rs,_->WorkChecklistItemSnapshot(rs.getObject("id",UUID::class.java),rs.getString("item_key"),rs.getString("label"),rs.getBoolean("required"),rs.getInt("sort_order"),rs.getString("completion_state"),rs.getString("evidence_requirement_key"),rs.getLong("version"))},id)
    private fun requirements(id:UUID)=jdbc.query("""SELECT wri.*,cr.code source_code,cr.name source_name FROM work_requirement_instance wri LEFT JOIN config_revision cr ON cr.id=wri.source_config_id WHERE work_order_id=? ORDER BY requirement_family,requirement_key""",{rs,_->WorkRequirementSnapshot(rs.getObject("id",UUID::class.java),rs.getString("requirement_family"),rs.getString("requirement_key"),rs.getObject("source_config_id",UUID::class.java)?.let{ConfigRef(it,rs.getString("source_code"),rs.getString("source_name"),rs.getInt("source_config_revision"))},rs.getBoolean("required"),rs.getString("satisfaction_state"),rs.getLong("version"))},id)
    private fun tasks(id:UUID)=jdbc.query(taskSelect+" WHERE work_order_id=? ORDER BY sort_order,id",taskMapper,id)
    private val taskSelect="SELECT id,task_code,title,description,sort_order,mandatory,estimated_duration_minutes,evidence_requirement_key,state,version FROM work_task"
    private val taskMapper={rs:java.sql.ResultSet,_:Int->WorkTaskSnapshot(rs.getObject("id",UUID::class.java),rs.getString("task_code"),rs.getString("title"),rs.getString("description"),rs.getInt("sort_order"),rs.getBoolean("mandatory"),rs.getObject("estimated_duration_minutes",Int::class.javaObjectType),rs.getString("evidence_requirement_key"),WorkTaskState.valueOf(rs.getString("state")),rs.getLong("version"))}
    private val dependencySelect="SELECT id,predecessor_work_order_id,successor_work_order_id,dependency_type,lag_minutes,version FROM work_order_dependency"
    private val dependencyMapper={rs:java.sql.ResultSet,_:Int->WorkDependencySnapshot(rs.getObject("id",UUID::class.java),rs.getObject("predecessor_work_order_id",UUID::class.java),rs.getObject("successor_work_order_id",UUID::class.java),WorkDependencyType.valueOf(rs.getString("dependency_type")),rs.getLong("lag_minutes"),rs.getLong("version"))}
    private fun hasCycle(edges:List<WorkDependencySnapshot>):Boolean{val a=edges.groupBy{it.predecessorWorkOrderId}.mapValues{e->e.value.map{it.successorWorkOrderId}};val visiting=mutableSetOf<UUID>();val visited=mutableSetOf<UUID>();fun visit(n:UUID):Boolean{if(n in visiting)return true;if(!visited.add(n))return false;visiting+=n;val c=a[n].orEmpty().any(::visit);visiting-=n;return c};return edges.any{visit(it.predecessorWorkOrderId)}}
}
