package com.hiltech.server.work

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.projects.ProjectAuthorizationPort
import com.hiltech.server.projects.ProjectLifecycleState
import com.hiltech.server.projects.ProjectSnapshot
import com.hiltech.server.projects.ProjectsPersistencePort
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID

@Component
class WorkService(
    private val projects: ProjectsPersistencePort,
    private val work: WorkPersistencePort,
    private val authorization: ProjectAuthorizationPort,
    private val projection: WorkAuthorizationProjectionBridge,
    private val idempotency: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    fun workTypes(actor: UUID, projectId: UUID): List<WorkTypeChoice> {
        val project = project(projectId)
        requireView(actor, project)
        return work.workTypes(project.organizationId, clock.instant())
    }

    fun list(actor: UUID, projectId: UUID): List<WorkOrderSnapshot> {
        val project = project(projectId)
        requireView(actor, project)
        return work.workOrders(projectId)
    }

    fun get(actor: UUID, workOrderId: UUID): WorkOrderSnapshot {
        val order = order(workOrderId)
        requireView(actor, project(order.projectId))
        return order
    }

    @Transactional
    fun create(command: CreateWorkOrderCommand): WorkOrderMutationResult {
        validateDates(command.plannedStart, command.plannedEnd)
        positive(command.baseProjectVersion)
        if (command.expectedBaselineVersion < 1) validation("BASELINE_VERSION_INVALID", "expectedBaselineVersion must be positive.")
        val title = required(command.title, 240)
        val description = optional(command.description, 4000)
        val typeCode = code(command.workTypeCode)
        val id = deterministic("work-order", command.operationId)
        val replayed = execute(command.operationId, command.actorUserId, "WORK_ORDER_CREATE", id, command.correlationId,
            listOf(command.projectId, command.siteId, command.projectSiteId, command.areaId, command.workPackageId, command.explicitCode, typeCode, command.workTypeRevision, title, description, command.plannedStart, command.plannedEnd, command.priorityCode, command.baseProjectVersion, command.expectedBaselineVersion, command.clientOccurredAt)) {
            val now = clock.instant()
            val project = project(command.projectId)
            requireCreateWork(command.actorUserId, project)
            if (project.version != command.baseProjectVersion || project.baselineVersion != command.expectedBaselineVersion) version()
            if (project.lifecycleState !in setOf(ProjectLifecycleState.PLANNING, ProjectLifecycleState.READY)) conflict("REJECTED_STATE", "WorkOrders can be composed only for a PLANNING or READY Project.")
            if (!work.validContext(project, command.projectSiteId, command.siteId, command.areaId, command.workPackageId)) conflict("WORK_CONTEXT_INVALID", "The WorkOrder Project, Site, Area or WorkPackage context is invalid.")
            val definition = work.workType(project.organizationId, typeCode, command.workTypeRevision, now) ?: conflict("WORK_TYPE_UNAVAILABLE", "The selected active WorkType revision is unavailable.")
            val policy = work.codePolicies(project.organizationId, now).singleOrNull() ?: conflict("WORK_ORDER_CODE_POLICY_UNAVAILABLE", "Exactly one applicable active WORK_ORDER CodePolicy is required.")
            val allocated = allocateCode(command.explicitCode, project.projectId, policy, now)
            if (!projects.bumpProjectVersion(project.projectId, project.version, now)) version()
            work.insertWorkOrder(id, project, allocated, command.siteId, command.projectSiteId, command.areaId, command.workPackageId, title, description, command.plannedStart, command.plannedEnd, command.priorityCode?.let(::code) ?: definition.defaultPriorityCode ?: "NORMAL", command.actorUserId, now)
            val created = order(id)
            val event = changed(created, "created", command.actorUserId, now, command.correlationId)
            projection.workOrderCreated(created, event.eventId, now)
            record("WORK_ORDER_CREATED", created, null, WorkOrderLifecycle.DRAFT.name, command.actorUserId, now, command.correlationId, event)
            outcome(command.projectId, id)
        }
        return WorkOrderMutationResult(get(command.actorUserId, id), replayed)
    }

    @Transactional
    fun update(command: UpdateWorkOrderDetailsCommand): WorkOrderMutationResult {
        validateDates(command.plannedStart, command.plannedEnd); positive(command.baseVersion)
        val title=required(command.title,240); val description=optional(command.description,4000); val priority=code(command.priorityCode)
        val initial=order(command.workOrderId); requireCreateWork(command.actorUserId, project(initial.projectId))
        val replayed=execute(command.operationId,command.actorUserId,"WORK_ORDER_UPDATE",command.workOrderId,command.correlationId,listOf(command.workOrderId,command.areaId,command.workPackageId,title,description,command.plannedStart,command.plannedEnd,priority,command.baseVersion,command.clientOccurredAt)){
            val now=clock.instant(); val current=order(command.workOrderId); val project=project(current.projectId); requireCreateWork(command.actorUserId,project)
            if(current.version!=command.baseVersion)version(); if(current.lifecycleState !in setOf(WorkOrderLifecycle.DRAFT,WorkOrderLifecycle.PLANNED)) conflict("WORK_PLAN_FROZEN","WorkOrder details are frozen after assignment.")
            if(!work.validContext(project,current.projectSiteId,current.siteId,command.areaId,command.workPackageId)) conflict("WORK_CONTEXT_INVALID","The Area or WorkPackage context is invalid.")
            if(!work.updateDetails(current.workOrderId,current.version,command.areaId,command.workPackageId,title,description,command.plannedStart,command.plannedEnd,priority,now))version()
            val updated=order(current.workOrderId); val event=changed(updated,"details_updated",command.actorUserId,now,command.correlationId); record("WORK_ORDER_UPDATED",updated,current.lifecycleState.name,updated.lifecycleState.name,command.actorUserId,now,command.correlationId,event); outcome(updated.projectId,updated.workOrderId)
        }
        return WorkOrderMutationResult(get(command.actorUserId,command.workOrderId),replayed)
    }

    @Transactional
    fun plan(command: PlanWorkCommand): WorkOrderMutationResult {
        positive(command.baseVersion); if(command.expectedBaselineVersion<1)validation("BASELINE_VERSION_INVALID","expectedBaselineVersion must be positive."); if(command.payloadSchemaVersion<1)validation("INSTRUCTION_SCHEMA_INVALID","payloadSchemaVersion must be positive.")
        val typeCode=code(command.workTypeCode); val bindingId=deterministic("work-binding",command.operationId); val instructionId=deterministic("work-instruction",command.operationId)
        val initial=order(command.workOrderId); requireCreateWork(command.actorUserId,project(initial.projectId))
        val replayed=execute(command.operationId,command.actorUserId,"WORK_ORDER_PLAN",command.workOrderId,command.correlationId,listOf(command.workOrderId,typeCode,command.workTypeRevision,command.payloadSchemaVersion,command.structuredInstructionJson,command.instructionSummary,command.baseVersion,command.expectedBaselineVersion,command.clientOccurredAt)){
            val now=clock.instant(); val current=order(command.workOrderId); val project=project(current.projectId); requireCreateWork(command.actorUserId,project)
            if(current.version!=command.baseVersion||current.baselineVersion!=command.expectedBaselineVersion||project.baselineVersion!=command.expectedBaselineVersion)version()
            if(current.lifecycleState!=WorkOrderLifecycle.DRAFT)conflict("REJECTED_STATE","Only a DRAFT WorkOrder can be planned.")
            val definition=work.workType(project.organizationId,typeCode,command.workTypeRevision,now)?:conflict("WORK_TYPE_UNAVAILABLE","The selected active WorkType revision is unavailable.")
            val instructionJson=validatedJson(command.structuredInstructionJson?:definition.instructionTemplate?.json?:"{}")
            val schema=if(command.structuredInstructionJson==null) definition.instructionTemplate?.schemaVersion?:command.payloadSchemaVersion else command.payloadSchemaVersion
            work.insertBinding(bindingId,current.workOrderId,definition,command.actorUserId,now)
            work.insertInstruction(instructionId,current.workOrderId,1,definition.instructionTemplate,schema,instructionJson,optional(command.instructionSummary,1000),null,"Initial planning instruction",command.actorUserId,now,command.correlationId)
            work.materializeChecklist(current.workOrderId,definition.checklistTemplate,parseChecklist(definition.checklistTemplate))
            val requirements=buildList{
                work.readinessRequirements(definition.readiness.ref.id).forEach{add(MaterializedRequirement("READINESS",it,definition.readiness.ref))}
                work.evidenceRequirements(definition.evidence.ref.id).forEach{add(MaterializedRequirement("EVIDENCE",it,definition.evidence.ref))}
                addAll(parseTemplateRequirements("ASSET",definition.assetTemplate)); addAll(parseTemplateRequirements("MATERIAL",definition.materialTemplate))
            }
            work.materializeRequirements(current.workOrderId,requirements,now)
            if(!work.markPlanned(current.workOrderId,current.version,bindingId,instructionId,definition,now))version()
            val updated=order(current.workOrderId); val event=changed(updated,"planned",command.actorUserId,now,command.correlationId); record("WORK_ORDER_PLANNED",updated,current.lifecycleState.name,updated.lifecycleState.name,command.actorUserId,now,command.correlationId,event,listOf(definition.ref,definition.assignment.ref,definition.readiness.ref,definition.evidence.ref,definition.review.ref)); outcome(updated.projectId,updated.workOrderId)
        }
        return WorkOrderMutationResult(get(command.actorUserId,command.workOrderId),replayed)
    }

    @Transactional
    fun revise(command: ReviseWorkInstructionCommand): WorkOrderMutationResult {
        positive(command.baseVersion); if(command.payloadSchemaVersion<1)validation("INSTRUCTION_SCHEMA_INVALID","payloadSchemaVersion must be positive."); val json=validatedJson(command.structuredInstructionJson); val reason=required(command.changeReason,1000); val summary=optional(command.summary,1000); val instructionId=deterministic("work-instruction",command.operationId)
        val initial=order(command.workOrderId); requireCreateWork(command.actorUserId,project(initial.projectId))
        val replayed=execute(command.operationId,command.actorUserId,"WORK_INSTRUCTION_REVISE",command.workOrderId,command.correlationId,listOf(command.workOrderId,command.payloadSchemaVersion,json,summary,reason,command.baseVersion,command.clientOccurredAt)){
            val now=clock.instant(); val current=order(command.workOrderId); requireCreateWork(command.actorUserId,project(current.projectId)); if(current.version!=command.baseVersion)version(); if(current.lifecycleState !in setOf(WorkOrderLifecycle.DRAFT,WorkOrderLifecycle.PLANNED))conflict("INSTRUCTION_FROZEN","Instructions cannot be revised after assignment.")
            val next=(current.instruction?.revision?:0)+1; if(!work.reviseInstruction(current.workOrderId,current.version,instructionId,next,command.payloadSchemaVersion,json,summary,reason,command.actorUserId,now,command.correlationId))version()
            val updated=order(current.workOrderId); val event=changed(updated,"instruction_revised",command.actorUserId,now,command.correlationId); record("WORK_INSTRUCTION_REVISED",updated,current.instruction?.revision?.toString(),next.toString(),command.actorUserId,now,command.correlationId,event); outcome(updated.projectId,updated.workOrderId)
        }
        return WorkOrderMutationResult(get(command.actorUserId,command.workOrderId),replayed)
    }

    @Transactional
    fun createTask(command: CreateWorkTaskCommand): WorkTaskMutationResult {
        positive(command.baseVersion); validateTask(command.sortOrder,command.estimatedDurationMinutes); val title=required(command.title,240); val description=optional(command.description,4000); val taskCode=command.taskCode?.let(::code); val evidence=command.evidenceRequirementKey?.let(::code); val taskId=deterministic("work-task",command.operationId)
        val initial=order(command.workOrderId); requireCreateWork(command.actorUserId,project(initial.projectId))
        val replayed=execute(command.operationId,command.actorUserId,"WORK_TASK_CREATE",taskId,command.correlationId,listOf(command.workOrderId,taskCode,title,description,command.sortOrder,command.mandatory,command.estimatedDurationMinutes,evidence,command.baseVersion,command.clientOccurredAt)){
            val now=clock.instant(); val current=order(command.workOrderId); requireCreateWork(command.actorUserId,project(current.projectId)); if(current.version!=command.baseVersion)version(); requirePlanningState(current)
            work.insertTask(taskId,current,taskCode,title,description,command.sortOrder,command.mandatory,command.estimatedDurationMinutes,evidence,command.actorUserId,now); if(!work.bumpWorkOrder(current.workOrderId,current.version,now))version(); val updated=order(current.workOrderId); val event=changed(updated,"task_created",command.actorUserId,now,command.correlationId); record("WORK_TASK_CREATED",updated,null,WorkTaskState.PLANNED.name,command.actorUserId,now,command.correlationId,event); outcome(updated.projectId,taskId)
        }
        return WorkTaskMutationResult(get(command.actorUserId,command.workOrderId),taskId,replayed)
    }

    @Transactional
    fun updateTask(command: UpdateWorkTaskCommand): WorkTaskMutationResult {
        positive(command.baseTaskVersion);positive(command.baseWorkOrderVersion);validateTask(command.sortOrder,command.estimatedDurationMinutes);val title=required(command.title,240);val description=optional(command.description,4000);val taskCode=command.taskCode?.let(::code);val evidence=command.evidenceRequirementKey?.let(::code);val workOrderId=work.taskWorkOrderId(command.taskId)?:hidden();val initial=order(workOrderId);requireCreateWork(command.actorUserId,project(initial.projectId))
        val replayed=execute(command.operationId,command.actorUserId,"WORK_TASK_UPDATE",command.taskId,command.correlationId,listOf(command.taskId,taskCode,title,description,command.sortOrder,command.mandatory,command.estimatedDurationMinutes,evidence,command.state,command.baseTaskVersion,command.baseWorkOrderVersion,command.clientOccurredAt)){
            val now=clock.instant();val current=order(workOrderId);requireCreateWork(command.actorUserId,project(current.projectId));requirePlanningState(current);if(current.version!=command.baseWorkOrderVersion)version();val task=work.task(command.taskId)?:hidden();if(task.version!=command.baseTaskVersion)version();if(!work.updateTask(command.taskId,task.version,taskCode,title,description,command.sortOrder,command.mandatory,command.estimatedDurationMinutes,evidence,command.state,now))version();if(!work.bumpWorkOrder(current.workOrderId,current.version,now))version();val updated=order(current.workOrderId);val event=changed(updated,"task_updated",command.actorUserId,now,command.correlationId);record("WORK_TASK_UPDATED",updated,task.state.name,command.state.name,command.actorUserId,now,command.correlationId,event);outcome(updated.projectId,command.taskId)
        }
        return WorkTaskMutationResult(get(command.actorUserId,workOrderId),command.taskId,replayed)
    }

    @Transactional
    fun addDependency(command:AddWorkDependencyCommand):WorkDependencyMutationResult{
        positive(command.baseVersion);if(command.lagMinutes<0)validation("DEPENDENCY_LAG_INVALID","lagMinutes cannot be negative.");val id=deterministic("work-dependency",command.operationId);val initial=order(command.workOrderId);requireCreateWork(command.actorUserId,project(initial.projectId))
        val replayed=execute(command.operationId,command.actorUserId,"WORK_DEPENDENCY_ADD",id,command.correlationId,listOf(command.workOrderId,command.predecessorWorkOrderId,command.type,command.lagMinutes,command.baseVersion,command.clientOccurredAt)){
            val now=clock.instant();val successor=order(command.workOrderId);val predecessor=order(command.predecessorWorkOrderId);requireCreateWork(command.actorUserId,project(successor.projectId));requirePlanningState(successor);if(successor.version!=command.baseVersion)version();if(predecessor.projectId!=successor.projectId||predecessor.organizationId!=successor.organizationId||predecessor.workOrderId==successor.workOrderId)conflict("WORK_DEPENDENCY_CONTEXT_INVALID","Dependencies must connect distinct WorkOrders in the same Project.");if(predecessor.lifecycleState==WorkOrderLifecycle.CANCELLED||successor.lifecycleState==WorkOrderLifecycle.CANCELLED)conflict("WORK_DEPENDENCY_CANCELLED_NODE","Cancelled WorkOrders cannot receive dependencies.")
            work.insertDependency(id,successor,predecessor.workOrderId,command.type,command.lagMinutes,command.actorUserId,now);if(hasCycle(work.dependencyGraph(successor.projectId)))conflict("WORK_DEPENDENCY_CYCLE","The dependency would create a cycle.");if(!work.bumpWorkOrder(successor.workOrderId,successor.version,now))version();val updated=order(successor.workOrderId);val event=changed(updated,"dependency_added",command.actorUserId,now,command.correlationId);record("WORK_DEPENDENCY_ADDED",updated,null,command.type.name,command.actorUserId,now,command.correlationId,event);outcome(updated.projectId,id)
        }
        return WorkDependencyMutationResult(get(command.actorUserId,command.workOrderId),id,replayed)
    }

    @Transactional
    fun removeDependency(command:RemoveWorkDependencyCommand):WorkDependencyMutationResult{
        positive(command.baseVersion);positive(command.baseDependencyVersion);val initial=order(command.workOrderId);requireCreateWork(command.actorUserId,project(initial.projectId))
        val replayed=execute(command.operationId,command.actorUserId,"WORK_DEPENDENCY_REMOVE",command.dependencyId,command.correlationId,listOf(command.workOrderId,command.dependencyId,command.baseVersion,command.baseDependencyVersion,command.clientOccurredAt)){
            val now=clock.instant();val current=order(command.workOrderId);requireCreateWork(command.actorUserId,project(current.projectId));requirePlanningState(current);if(current.version!=command.baseVersion)version();val dep=work.dependency(command.dependencyId)?:hidden();if(dep.successorWorkOrderId!=current.workOrderId||dep.version!=command.baseDependencyVersion)version();if(!work.deleteDependency(dep.dependencyId,dep.version))version();if(!work.bumpWorkOrder(current.workOrderId,current.version,now))version();val updated=order(current.workOrderId);val event=changed(updated,"dependency_removed",command.actorUserId,now,command.correlationId);record("WORK_DEPENDENCY_REMOVED",updated,dep.type.name,null,command.actorUserId,now,command.correlationId,event);outcome(updated.projectId,dep.dependencyId)
        }
        return WorkDependencyMutationResult(get(command.actorUserId,command.workOrderId),command.dependencyId,replayed)
    }

    @Transactional
    fun activate(command:ActivateProjectCommand):ProjectActivationResult{
        positive(command.baseVersion);if(command.expectedBaselineVersion<1)validation("BASELINE_VERSION_INVALID","expectedBaselineVersion must be positive.");val replayed=execute(command.operationId,command.actorUserId,"PROJECT_ACTIVATE",command.projectId,command.correlationId,listOf(command.projectId,command.baseVersion,command.expectedBaselineVersion,command.clientOccurredAt)){
            val now=clock.instant();val project=project(command.projectId);requireCreateWork(command.actorUserId,project);if(project.version!=command.baseVersion||project.baselineVersion!=command.expectedBaselineVersion)version();val blockers=work.activationBlockers(project);if(blockers.isNotEmpty())throw ProductApiException("PROJECT_ACTIVATION_BLOCKED","The Project activation contract is not satisfied.",HttpStatus.UNPROCESSABLE_ENTITY,details=buildJsonObject{put("reasonCodes",blockers.joinToString(","))});if(!work.activateProject(project.projectId,project.version,now))version();val updated=project(project.projectId);audit.append(AuditEventRecord(actorUserId=command.actorUserId,action="PROJECT_ACTIVATED",targetType="PROJECT",targetId=project.projectId,previousStateRef=project.lifecycleState.name,newStateRef=updated.lifecycleState.name,safeDiffJson="""{"fields":["lifecycleState","startDateActual"]}""",occurredAt=now,correlationId=command.correlationId));events.publishEvent(ProjectActivated(projectId=project.projectId,organizationId=project.organizationId,sourceVersion=updated.version,actorUserId=command.actorUserId,occurredAt=now,correlationId=command.correlationId));outcome(project.projectId,project.projectId)
        };val updated=project(command.projectId);return ProjectActivationResult(updated.projectId,updated.lifecycleState.name,updated.version,replayed)
    }

    private fun execute(operationId:UUID,actor:UUID,type:String,target:UUID,correlation:String,fingerprint:List<Any?>,action:()->IdempotentCommandOutcome):Boolean=try{idempotency.execute(IdempotentCommandSpec(operationId,actor,type,"WORK_ORDER",target,IdempotencyKeyContract.fingerprint(fingerprint.joinToString("|")),correlation)){action()}.replayed}catch(e:DataIntegrityViolationException){conflict("WORK_CONSTRAINT_CONFLICT","The change conflicts with authoritative WorkOrder data.")}
    private fun outcome(projectId:UUID,targetId:UUID)=IdempotentCommandOutcome("APPLIED",buildJsonObject{put("projectId",projectId.toString());put("targetId",targetId.toString())}.toString())
    private fun record(action:String,order:WorkOrderSnapshot,previous:String?,next:String?,actor:UUID,at:Instant,correlation:String,event:WorkOrderChanged,refs:List<ConfigRef> = emptyList()){audit.append(AuditEventRecord(actorUserId=actor,action=action,targetType="WORK_ORDER",targetId=order.workOrderId,previousStateRef=previous,newStateRef=next,safeDiffJson="""{"fields":["workPlanning","version"]}""",occurredAt=at,correlationId=correlation,configRevisionRefsJson=if(refs.isEmpty())null else refs.joinToString(prefix="[\"",separator="\",\"",postfix="\"]"){it.id.toString()}));events.publishEvent(event)}
    private fun changed(order:WorkOrderSnapshot,type:String,actor:UUID,at:Instant,correlation:String)=WorkOrderChanged(workOrderId=order.workOrderId,organizationId=order.organizationId,projectId=order.projectId,siteId=order.siteId,changeType=type,sourceVersion=order.version,actorUserId=actor,occurredAt=at,correlationId=correlation)
    private fun parseChecklist(template:BoundConfig?):List<TemplateChecklistItem>{if(template==null)return emptyList();val root=parseObject(template.json?:"{}");return (root["items"] as? JsonArray).orEmpty().mapIndexed{index,e->val o=e.jsonObject;TemplateChecklistItem(requiredJson(o,"key",64),requiredJson(o,"label",240),o["required"]?.jsonPrimitive?.booleanOrNull?:true,o["sortOrder"]?.jsonPrimitive?.intOrNull?:index,o["evidenceRequirementKey"]?.jsonPrimitive?.contentOrNull)}}
    private fun parseTemplateRequirements(family:String,template:BoundConfig?):List<MaterializedRequirement>{if(template==null)return emptyList();val root=parseObject(template.json?:"{}");val items=(root["requirements"] as? JsonArray)?: (root["items"] as? JsonArray)?:JsonArray(emptyList());return items.mapIndexed{i,e->val o=e.jsonObject;MaterializedRequirement(family,RequirementDefinition(UUID.nameUUIDFromBytes("${template.ref.id}:$family:$i".toByteArray()),requiredJson(o,"key",64),o["type"]?.jsonPrimitive?.contentOrNull?:family,o["label"]?.jsonPrimitive?.contentOrNull,o["required"]?.jsonPrimitive?.booleanOrNull?:true,o["sortOrder"]?.jsonPrimitive?.intOrNull?:i),template.ref)}}
    private fun validatedJson(raw:String):String=parseObject(raw).toString()
    private fun parseObject(raw:String):JsonObject=try{Json.parseToJsonElement(raw).jsonObject}catch(_:Exception){validation("TEMPLATE_JSON_INVALID","Structured template or instruction JSON must be an object.")}
    private fun requiredJson(o:JsonObject,key:String,max:Int)=o[key]?.jsonPrimitive?.contentOrNull?.let{required(it,max)}?:validation("TEMPLATE_SCHEMA_INVALID","Template item $key is required.")
    private fun allocateCode(explicit:String?,projectId:UUID,policy:WorkCodePolicy,at:Instant):String{val value=if(explicit!=null){if(!policy.manualOverrideAllowed)conflict("MANUAL_CODE_NOT_ALLOWED","The active CodePolicy does not allow a manual WorkOrder code.");code(explicit)}else{val seq=work.allocateCode(projectId,policy,at);listOfNotNull(policy.prefix?.takeIf{it.isNotBlank()},if(policy.includeYear)at.atZone(ZoneOffset.UTC).year.toString()else null,seq.toString().padStart(policy.sequencePadding,'0')).joinToString(policy.separator)};if(work.codeExists(projectId,value))conflict("WORK_ORDER_CODE_CONFLICT","The WorkOrder code already exists in this Project.");return value}
    private fun hasCycle(edges:List<WorkDependencySnapshot>):Boolean{val a=edges.groupBy{it.predecessorWorkOrderId}.mapValues{e->e.value.map{it.successorWorkOrderId}};val visiting=mutableSetOf<UUID>();val visited=mutableSetOf<UUID>();fun visit(n:UUID):Boolean{if(n in visiting)return true;if(!visited.add(n))return false;visiting+=n;val c=a[n].orEmpty().any(::visit);visiting-=n;return c};return edges.any{visit(it.predecessorWorkOrderId)}}
    private fun requirePlanningState(order:WorkOrderSnapshot){if(order.lifecycleState !in setOf(WorkOrderLifecycle.DRAFT,WorkOrderLifecycle.PLANNED))conflict("WORK_PLAN_FROZEN","Planning changes are forbidden after assignment.")}
    private fun validateTask(order:Int,duration:Int?){if(order<0)validation("TASK_ORDER_INVALID","sortOrder cannot be negative.");if(duration!=null&&duration<1)validation("TASK_DURATION_INVALID","estimatedDurationMinutes must be positive.")}
    private fun validateDates(start:Instant?,end:Instant?){if(start!=null&&end!=null&&end<start)validation("PLANNED_DATE_RANGE_INVALID","plannedEnd must not precede plannedStart.")}
    private fun requireCreateWork(actor:UUID,project:ProjectSnapshot){if(!authorization.canCreateWork(actor,project))hidden()}
    private fun requireView(actor:UUID,project:ProjectSnapshot){if(!authorization.canViewProject(actor,project))hidden()}
    private fun project(id:UUID)=projects.project(id,clock.instant())?:hidden()
    private fun order(id:UUID)=work.workOrder(id)?:hidden()
    private fun deterministic(namespace:String,operationId:UUID)=UUID.nameUUIDFromBytes("hiltech:$namespace:$operationId".toByteArray(StandardCharsets.UTF_8))
    private fun required(value:String,max:Int)=value.trim().takeIf{it.isNotEmpty()&&it.length<=max}?:validation("REJECTED_VALIDATION","A required WorkOrder value is invalid.")
    private fun optional(value:String?,max:Int):String?{val v=value?.trim()?.takeIf{it.isNotEmpty()};if(v!=null&&v.length>max)validation("REJECTED_VALIDATION","A WorkOrder value exceeds its maximum length.");return v}
    private fun code(value:String)=required(value,64).uppercase(Locale.ROOT).also{if(!it.matches(Regex("[A-Z0-9][A-Z0-9._-]*")))validation("WORK_CODE_INVALID","Codes may contain letters, numbers, dot, underscore and hyphen.")}
    private fun positive(value:Long){if(value<1)validation("VERSION_INVALID","Version must be positive.")}
    private fun version():Nothing=throw ProductApiException("VERSION_CONFLICT","The WorkOrder or Project changed before this command was applied.",HttpStatus.CONFLICT)
    private fun conflict(code:String,message:String):Nothing=throw ProductApiException(code,message,HttpStatus.CONFLICT)
    private fun validation(code:String,message:String):Nothing=throw ProductApiException(code,message,HttpStatus.BAD_REQUEST)
    private fun hidden():Nothing=throw ProductApiException("OBJECT_NOT_VISIBLE","The requested work object is unavailable.",HttpStatus.NOT_FOUND)
}
