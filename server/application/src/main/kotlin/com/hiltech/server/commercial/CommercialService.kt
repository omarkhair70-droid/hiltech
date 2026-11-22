package com.hiltech.server.commercial

import com.hiltech.server.approval.ApprovalEngineService
import com.hiltech.server.approval.ApprovalSubjectRef
import com.hiltech.server.approval.ApprovalSubjectVersionSource
import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.projects.CreateProjectCommand
import com.hiltech.server.projects.ProjectSourceType
import com.hiltech.server.projects.ProjectsService
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

internal fun String.commercialUuid(): UUID = runCatching { UUID.fromString(this) }.getOrElse {
    throw commercialError("COMMERCIAL_BAD_UUID", "Invalid identifier.", HttpStatus.BAD_REQUEST)
}
internal fun commercialError(code: String, message: String, status: HttpStatus) =
    ProductApiException(code = code, message = message, status = status)

private fun required(value: String, max: Int): String = value.trim().also {
    if (it.isEmpty() || it.length > max) throw commercialError(
        "COMMERCIAL_VALIDATION", "Required text missing or too long.", HttpStatus.BAD_REQUEST)
}
private fun nullable(value: String?, max: Int): String? = value?.trim()?.takeIf { it.isNotEmpty() }?.also {
    if (it.length > max) throw commercialError(
        "COMMERCIAL_VALIDATION", "Text too long.", HttpStatus.BAD_REQUEST)
}
private fun date(value: String): LocalDate = runCatching { LocalDate.parse(value) }.getOrElse {
    throw commercialError("COMMERCIAL_DATE_INVALID", "Invalid date.", HttpStatus.BAD_REQUEST)
}
private fun timestamp(value: String): Instant = runCatching { Instant.parse(value) }.getOrElse {
    throw commercialError("COMMERCIAL_TIME_INVALID", "ISO-8601 UTC timestamp required.", HttpStatus.BAD_REQUEST)
}
private fun currency(value: String) = value.also {
    if (!Regex("^[A-Z]{3}$").matches(it)) throw commercialError(
        "COMMERCIAL_CURRENCY_INVALID", "ISO currency required.", HttpStatus.BAD_REQUEST)
}
internal fun commercialExactLineTotal(quantity: BigDecimal, sellUnitPrice: BigDecimal): BigDecimal =
    try {
        quantity.multiply(sellUnitPrice).setScale(4, RoundingMode.UNNECESSARY)
    } catch (_: ArithmeticException) {
        throw commercialError(
            "COMMERCIAL_EXACT_PRICE_REQUIRED",
            "The quantity multiplied by unit price has more than four nonzero decimal places. An explicit pricing revision is required; no silent rounding.",
            HttpStatus.UNPROCESSABLE_ENTITY,
        )
    }

private fun amount(value: BigDecimal, positive: Boolean = false) {
    if ((if (positive) value.signum() <= 0 else value.signum() < 0) ||
        value.scale() > 4 || value.precision() > 20)
        throw commercialError("COMMERCIAL_AMOUNT_INVALID", "Invalid amount.", HttpStatus.BAD_REQUEST)
}

/** Only company-bound authority, no implicit employee, supplier, or client rights. */
@Component
class CommercialService(
    private val jdbc: JdbcTemplate,
    private val access: CommercialAccess,
    private val executor: IdempotentCommandExecutor,
    private val approval: ApprovalEngineService,
    private val projects: ProjectsService,
    private val audit: AuditEventWriter,
    private val awardEvidence: CommercialAwardEvidenceGate,
    private val clock: Clock,
) {
    private fun now() = clock.instant().atOffset(ZoneOffset.UTC)
    private fun row(sql: String, vararg args: Any): Map<String, Any?> =
        jdbc.queryForList(sql, *args).firstOrNull()
            ?: throw commercialError("COMMERCIAL_NOT_FOUND", "Record unavailable.", HttpStatus.NOT_FOUND)
    private fun op(id: UUID) =
        row("SELECT id,organization_id,client_organization_id,title,state,version FROM commercial_opportunity WHERE id=?", id)
    private fun op(id: UUID, actor: UUID, vararg authorities: String): Map<String, Any?> {
        val result = op(id)
        access.require(actor, result["organization_id"] as UUID, *authorities)
        return result
    }
    private fun open(record: Map<String, Any?>) {
        if (record["state"] !in setOf("OPEN", "QUALIFIED"))
            throw commercialError("COMMERCIAL_CLOSED", "Opportunity cannot be edited.", HttpStatus.CONFLICT)
    }
    private fun id(prefix: String, seed: UUID): UUID =
        UUID.nameUUIDFromBytes(("commercial:" + prefix + ":" + seed).toByteArray(Charsets.UTF_8))
    private fun audit(actor: UUID, action: String, target: UUID, state: String, correlation: String) {
        audit.append(AuditEventRecord(
            actorUserId = actor, action = action, targetType = "Commercial",
            targetId = target, newStateRef = state,
            occurredAt = clock.instant(), correlationId = correlation,
        ))
    }
    private fun execute(key: UUID, actor: UUID, command: String, target: UUID,
                        fingerprint: String, correlation: String, action: () -> Unit): Boolean =
        executor.execute(
            IdempotentCommandSpec(
                operationId = key, actorUserId = actor, commandType = command,
                targetType = "COMMERCIAL", targetId = target,
                requestFingerprint = IdempotencyKeyContract.fingerprint(fingerprint),
                correlationId = correlation,
            ),
        ) {
            action()
            IdempotentCommandOutcome("APPLIED", "{}")
        }.replayed
    private fun result(target: UUID, replay: Boolean, state: String, correlation: String) =
        CommercialCommandResult(target.toString(), replay, state, correlation)
    private fun lockOpportunity(id: UUID) {
        row("SELECT id FROM commercial_opportunity WHERE id=? FOR UPDATE", id)
    }
    private fun next(table: String, id: UUID, column: String = "version_number"): Int {
        // Table is a private fixed literal at callsites, not caller-controlled input.
        return jdbc.queryForObject(
            "SELECT coalesce(max(" + column + "),0)+1 FROM " + table + " WHERE opportunity_id=?",
            Int::class.java, id,
        ) ?: 1
    }

    fun createOpportunity(r: CreateCommercialOpportunityRequest, key: UUID,
                          actor: UUID, correlation: String): CommercialCommandResult {
        val company = r.organizationId.commercialUuid()
        val client = r.clientOrganizationId.commercialUuid()
        access.require(actor, company, "SALES_WRITE")
        val title = required(r.title, 240)
        val source = required(r.source, 32)
        if (source !in setOf("DIRECT","REFERRAL","RFQ","RFP","TENDER","OTHER"))
            throw commercialError("COMMERCIAL_SOURCE_INVALID", "Invalid source.", HttpStatus.BAD_REQUEST)
        val expected = r.expectedDecisionDate?.let(::date)
        val target = id("opportunity", key)
        val replay = execute(key, actor, "COMMERCIAL_OPPORTUNITY_CREATE", target, r.toString(), correlation) {
            access.require(actor, company, "SALES_WRITE")
            val exists = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM organization WHERE id=? AND organization_type='CLIENT' AND status='ACTIVE')",
                Boolean::class.java, client,
            ) == true
            if (!exists) throw commercialError("COMMERCIAL_CLIENT_INVALID", "Active Client required.", HttpStatus.BAD_REQUEST)
            jdbc.update(
                """INSERT INTO commercial_opportunity
                   (id,organization_id,client_organization_id,owner_user_id,source,opportunity_code,
                    title,description,state,expected_decision_date,created_by,created_at,updated_at,version)
                   VALUES (?,?,?,?,?,?,?,?,'OPEN',?,?,?,?,1)""",
                target,company,client,actor,source,"OPP-" + target.toString().uppercase(),
                title,nullable(r.description,10000),expected,actor,now(),now(),
            )
            audit(actor,"COMMERCIAL_OPPORTUNITY_CREATED",target,"OPEN",correlation)
        }
        return result(target,replay,"OPEN",correlation)
    }

    fun listOpportunities(company: UUID, actor: UUID, limit: Int): List<Map<String, Any?>> {
        access.require(actor,company,"SALES_VIEW","SALES_WRITE","SALES_RELEASE","SALES_AWARD")
        if (limit !in 1..200) throw commercialError("COMMERCIAL_LIMIT_INVALID","Limit 1..200.",HttpStatus.BAD_REQUEST)
        return jdbc.queryForList(
            """SELECT id,organization_id,client_organization_id,owner_user_id,opportunity_code,
                      title,source,state,expected_decision_date,created_at,updated_at,version
               FROM commercial_opportunity WHERE organization_id=?
               ORDER BY updated_at DESC,id LIMIT ?""",company,limit,
        )
    }

    fun opportunityDetail(id: UUID, actor: UUID): Map<String, Any> {
        op(id,actor,"SALES_VIEW","SALES_WRITE","SALES_RELEASE","SALES_AWARD")
        return mapOf(
            "opportunity" to row(
                """SELECT id,organization_id,client_organization_id,owner_user_id,opportunity_code,
                          title,description,source,state,closed_reason,expected_decision_date,version
                   FROM commercial_opportunity WHERE id=?""",id),
            "qualification" to jdbc.queryForList(
                """SELECT id,discovery_summary,open_questions,qualified_by,qualified_at
                   FROM commercial_qualification_record WHERE opportunity_id=?""",id),
            "tenders" to jdbc.queryForList(
                """SELECT id,tender_type,revision_number,reference_number,received_at,
                          submission_deadline,clarification_deadline,source_channel,submission_state
                   FROM commercial_tender_revision WHERE opportunity_id=? ORDER BY revision_number DESC""",id),
            "visits" to jdbc.queryForList(
                """SELECT id,site_id,scheduled_at,completed_at,purpose,notes,state,version
                   FROM commercial_site_visit WHERE opportunity_id=? ORDER BY scheduled_at DESC""",id),
            "scopeVersions" to jdbc.queryForList(
                """SELECT id,version_number,description,inclusions,exclusions,assumptions,
                          technical_constraints,client_responsibilities
                   FROM commercial_scope_version WHERE opportunity_id=? ORDER BY version_number DESC""",id),
            "quotes" to jdbc.queryForList(
                """SELECT id,quote_code,version_number,currency,total_price,state,validity_date
                   FROM commercial_quote_version WHERE opportunity_id=? ORDER BY version_number DESC""",id),
            "contracts" to jdbc.queryForList(
                """SELECT id,quote_version_id,contract_code,state,effective_date,end_date
                   FROM commercial_contract WHERE opportunity_id=? ORDER BY created_at DESC""",id),
            "clientPurchaseOrders" to jdbc.queryForList(
                """SELECT id,contract_id,client_po_number,issued_on,verified
                   FROM commercial_client_po WHERE opportunity_id=? ORDER BY created_at DESC""",id),
            "handoff" to jdbc.queryForList(
                """SELECT project_id,quote_version_id,contract_id,client_po_id
                   FROM commercial_project_handoff WHERE opportunity_id=?""",id),
        )
    }

    fun qualifyOpportunity(id: UUID,r: CommercialQualifyRequest,key: UUID,
                           actor: UUID,correlation: String): CommercialCommandResult {
        val company = op(id,actor,"SALES_WRITE")["organization_id"] as UUID
        val discovery = required(r.discoverySummary,10000)
        val questions = nullable(r.openQuestions,10000) ?: ""
        val target = this.id("qualification",key)
        val replay = execute(key,actor,"COMMERCIAL_OPPORTUNITY_QUALIFY",target,"$id|$r",correlation) {
            access.require(actor,company,"SALES_WRITE")
            lockOpportunity(id)
            if (jdbc.update(
                """UPDATE commercial_opportunity
                   SET state='QUALIFIED',version=version+1,updated_at=?
                   WHERE id=? AND version=? AND state='OPEN'""",
                now(),id,r.baseVersion,
            ) != 1) throw commercialError(
                "COMMERCIAL_QUALIFICATION_CONFLICT",
                "Opportunity is no longer open at this version.",HttpStatus.CONFLICT,
            )
            jdbc.update(
                """INSERT INTO commercial_qualification_record
                   (id,organization_id,opportunity_id,discovery_summary,open_questions,qualified_by,qualified_at)
                   VALUES (?,?,?,?,?,?,?)""",
                target,company,id,discovery,questions,actor,now(),
            )
            audit(actor,"COMMERCIAL_OPPORTUNITY_QUALIFIED",id,"QUALIFIED",correlation)
        }
        return result(id,replay,"QUALIFIED",correlation)
    }

    fun addTender(id: UUID,r: CommercialTenderRequest,key: UUID,actor: UUID,correlation: String): CommercialCommandResult {
        open(op(id,actor,"SALES_WRITE"))
        if (r.tenderType !in setOf("TENDER","RFQ","RFP","OTHER"))
            throw commercialError("COMMERCIAL_TENDER_TYPE","Invalid tender type.",HttpStatus.BAD_REQUEST)
        val received = timestamp(r.receivedAt).atOffset(ZoneOffset.UTC)
        val deadline = r.submissionDeadline?.let(::timestamp)?.atOffset(ZoneOffset.UTC)
        val clarification = r.clarificationDeadline?.let(::timestamp)?.atOffset(ZoneOffset.UTC)
        if (clarification != null && deadline != null && clarification > deadline)
            throw commercialError("COMMERCIAL_DEADLINE","Clarification after submission.",HttpStatus.BAD_REQUEST)
        val target = this.id("tender",key)
        val replay = execute(key,actor,"COMMERCIAL_TENDER_REVISION",target,"$id|$r",correlation) {
            val parent = op(id,actor,"SALES_WRITE");open(parent);lockOpportunity(id)
            jdbc.update(
                """INSERT INTO commercial_tender_revision
                   (id,organization_id,opportunity_id,revision_number,tender_type,reference_number,
                    received_at,submission_deadline,clarification_deadline,source_channel,created_by,created_at)
                   VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""",
                target,parent["organization_id"],id,next("commercial_tender_revision",id,"revision_number"),
                r.tenderType,nullable(r.referenceNumber,120),received,deadline,clarification,
                nullable(r.sourceChannel,160),actor,now(),
            )
            audit(actor,"COMMERCIAL_TENDER_REVISION_CREATED",target,"RECEIVED",correlation)
        }
        return result(target,replay,"RECEIVED",correlation)
    }

    fun addVisit(id: UUID,r: CommercialVisitRequest,key: UUID,actor: UUID,correlation: String): CommercialCommandResult {
        open(op(id,actor,"SALES_WRITE"))
        val siteId = r.siteId?.commercialUuid()
        val scheduled = timestamp(r.scheduledAt).atOffset(ZoneOffset.UTC)
        val purpose = required(r.purpose,240)
        val target = this.id("visit",key)
        val replay = execute(key,actor,"COMMERCIAL_VISIT_CREATE",target,"$id|$r",correlation) {
            lockOpportunity(id)
            val parent = op(id,actor,"SALES_WRITE");open(parent)
            if (siteId != null && jdbc.queryForObject(
                """SELECT EXISTS(SELECT 1 FROM site WHERE id=? AND organization_id=?
                   AND client_organization_id=? AND status='ACTIVE')""",Boolean::class.java,
                siteId,parent["organization_id"]!!,parent["client_organization_id"]!!,
            ) != true) throw commercialError("COMMERCIAL_SITE_MISMATCH","Site must belong to this client.",HttpStatus.CONFLICT)
            jdbc.update(
                """INSERT INTO commercial_site_visit
                   (id,organization_id,opportunity_id,site_id,scheduled_at,purpose,notes,
                    state,created_by,created_at)
                   VALUES (?,?,?,?,?,?,?,'SCHEDULED',?,?)""",
                target,parent["organization_id"],id,siteId,scheduled,purpose,nullable(r.notes,10000),actor,now(),
            )
            audit(actor,"COMMERCIAL_VISIT_CREATED",target,"SCHEDULED",correlation)
        }
        return result(target,replay,"SCHEDULED",correlation)
    }

    fun completeVisit(id: UUID,r: CommercialVisitCompletionRequest,key: UUID,
                      actor: UUID,correlation: String): CommercialCommandResult {
        val visit = row("SELECT organization_id,opportunity_id FROM commercial_site_visit WHERE id=?",id)
        access.require(actor,visit["organization_id"] as UUID,"SALES_WRITE")
        val notes = required(r.notes,10000)
        val replay = execute(key,actor,"COMMERCIAL_VISIT_COMPLETE",id,r.toString(),correlation) {
            access.require(actor,visit["organization_id"] as UUID,"SALES_WRITE")
            lockOpportunity(visit["opportunity_id"] as UUID)
            open(op(visit["opportunity_id"] as UUID,actor,"SALES_WRITE"))
            if (jdbc.update(
                """UPDATE commercial_site_visit SET state='COMPLETED',completed_at=?,
                   notes=?,version=version+1 WHERE id=? AND state='SCHEDULED' AND version=?""",
                now(),notes,id,r.baseVersion,
            ) != 1) throw commercialError("COMMERCIAL_VISIT_STALE","Visit changed.",HttpStatus.CONFLICT)
            audit(actor,"COMMERCIAL_VISIT_COMPLETED",id,"COMPLETED",correlation)
        }
        return result(id,replay,"COMPLETED",correlation)
    }

    fun closeOpportunity(id: UUID,r: CommercialCloseRequest,key: UUID,
                         actor: UUID,correlation: String): CommercialCommandResult {
        val company = op(id,actor,"SALES_WRITE")["organization_id"] as UUID
        if (r.state !in setOf("LOST","WITHDRAWN"))
            throw commercialError("COMMERCIAL_CLOSE_INVALID","Only lost/withdrawn allowed.",HttpStatus.BAD_REQUEST)
        val reason = required(r.reason,2000)
        val replay = execute(key,actor,"COMMERCIAL_OPPORTUNITY_CLOSE",id,r.toString(),correlation) {
            access.require(actor,company,"SALES_WRITE")
            if (jdbc.update(
                """UPDATE commercial_opportunity SET state=?,closed_reason=?,updated_at=?,version=version+1
                   WHERE id=? AND version=? AND state IN ('OPEN','QUALIFIED')""",
                r.state,reason,now(),id,r.baseVersion,
            ) != 1) throw commercialError("COMMERCIAL_OPPORTUNITY_STALE","Opportunity changed.",HttpStatus.CONFLICT)
            audit(actor,"COMMERCIAL_OPPORTUNITY_CLOSED",id,r.state,correlation)
        }
        return result(id,replay,r.state,correlation)
    }

    fun addScope(id: UUID,r: CommercialScopeRequest,key: UUID,
                 actor: UUID,correlation: String): CommercialCommandResult {
        open(op(id,actor,"SALES_WRITE"))
        val description = required(r.description,10000)
        val target = this.id("scope",key)
        val replay = execute(key,actor,"COMMERCIAL_SCOPE_CREATE",target,"$id|$r",correlation) {
            val parent = op(id,actor,"SALES_WRITE");open(parent);lockOpportunity(id)
            jdbc.update(
                """INSERT INTO commercial_scope_version
                   (id,organization_id,opportunity_id,version_number,description,inclusions,exclusions,
                    assumptions,technical_constraints,client_responsibilities,created_by,created_at)
                   VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""",
                target,parent["organization_id"],id,next("commercial_scope_version",id),description,
                r.inclusions.trim(),r.exclusions.trim(),r.assumptions.trim(),
                r.technicalConstraints.trim(),r.clientResponsibilities.trim(),actor,now(),
            )
            audit(actor,"COMMERCIAL_SCOPE_VERSION_CREATED",target,"IMMUTABLE",correlation)
        }
        return result(target,replay,"IMMUTABLE",correlation)
    }

    fun addBoq(id: UUID,r: CommercialBoqRequest,key: UUID,
               actor: UUID,correlation: String): CommercialCommandResult {
        val parent = op(id,actor,"SALES_WRITE");open(parent)
        val company = parent["organization_id"] as UUID
        access.require(actor,company,"SALES_COST_VIEW")
        val scopeId = r.scopeVersionId.commercialUuid()
        currency(r.currency)
        if (r.lines.isEmpty() || r.lines.size > 500 ||
            r.lines.map { it.lineCode.trim() }.toSet().size != r.lines.size)
            throw commercialError("COMMERCIAL_BOQ_LINES","1–500 distinct BOQ lines required.",HttpStatus.BAD_REQUEST)
        r.lines.forEach {
            required(it.lineCode,64);required(it.description,500);required(it.unit,48)
            if (it.category !in setOf("MATERIAL","LABOR","SUBCONTRACT","EQUIPMENT","OVERHEAD","OTHER"))
                throw commercialError("COMMERCIAL_BOQ_CATEGORY","Unknown category.",HttpStatus.BAD_REQUEST)
            amount(it.quantity,positive=true);amount(it.sellUnitPrice)
            it.internalUnitCost?.let { c -> amount(c) }
        }
        val target = this.id("boq",key)
        val replay = execute(key,actor,"COMMERCIAL_BOQ_CREATE",target,"$id|$r",correlation) {
            open(op(id,actor,"SALES_WRITE"));access.require(actor,company,"SALES_COST_VIEW");lockOpportunity(id)
            row("""SELECT id FROM commercial_scope_version
                   WHERE id=? AND opportunity_id=? AND organization_id=?""",scopeId,id,company)
            jdbc.update(
                """INSERT INTO commercial_boq_version
                   (id,organization_id,opportunity_id,scope_version_id,version_number,currency,created_by,created_at)
                   VALUES (?,?,?,?,?,?,?,?)""",
                target,company,id,scopeId,next("commercial_boq_version",id),r.currency,actor,now(),
            )
            r.lines.forEachIndexed { i,line ->
                jdbc.update(
                    """INSERT INTO commercial_boq_cost_line
                       (id,boq_version_id,line_code,description,category,quantity,unit,
                        internal_unit_cost,sell_unit_price,created_at)
                       VALUES (?,?,?,?,?,?,?,?,?,?)""",
                    UUID.nameUUIDFromBytes(("$target:$i").toByteArray()),
                    target,line.lineCode.trim(),line.description.trim(),line.category,line.quantity,
                    line.unit.trim(),line.internalUnitCost,line.sellUnitPrice,now(),
                )
            }
            val sealed=jdbc.update(
                "UPDATE commercial_boq_version SET sealed_at=? WHERE id=? AND sealed_at IS NULL",
                now(),target,
            )
            if(sealed!=1) throw commercialError(
                "COMMERCIAL_BOQ_NOT_SEALED","BOQ source was not sealed.",HttpStatus.CONFLICT)
            audit(actor,"COMMERCIAL_BOQ_PRIVATE_CREATED",target,"PRIVATE",correlation)
        }
        return result(target,replay,"PRIVATE",correlation)
    }

    fun draftQuote(id: UUID,r: CommercialQuoteRequest,key: UUID,
                   actor: UUID,correlation: String): CommercialCommandResult {
        open(op(id,actor,"SALES_WRITE"))
        val boqId = r.boqVersionId.commercialUuid()
        val validity = date(r.validityDate)
        val payment = required(r.paymentTerms,10000)
        val delivery = required(r.deliveryTerms,10000)
        val target = this.id("quote",key)
        val replay = execute(key,actor,"COMMERCIAL_QUOTE_DRAFT",target,"$id|$r",correlation) {
            val parent = op(id,actor,"SALES_WRITE");open(parent);lockOpportunity(id)
            val boq = row(
                """SELECT scope_version_id,currency FROM commercial_boq_version
                   WHERE id=? AND opportunity_id=? AND organization_id=?
                     AND sealed_at IS NOT NULL""",
                boqId,id,parent["organization_id"]!!,
            )
            val lines = jdbc.queryForList(
                """SELECT line_code,description,quantity,unit,sell_unit_price
                   FROM commercial_boq_cost_line WHERE boq_version_id=? ORDER BY line_code""",boqId,
            )
            if (lines.isEmpty()) throw commercialError("COMMERCIAL_QUOTE_EMPTY","BOQ has no lines.",HttpStatus.CONFLICT)
            val total = lines.fold(BigDecimal.ZERO) { sum,l ->
                sum + commercialExactLineTotal(l["quantity"] as BigDecimal,l["sell_unit_price"] as BigDecimal)
            }
            amount(total)
            jdbc.update(
                """INSERT INTO commercial_quote_version
                   (id,organization_id,opportunity_id,client_organization_id,scope_version_id,
                    boq_version_id,version_number,quote_code,currency,total_price,payment_terms,
                    delivery_terms,exclusions,validity_date,created_by,created_at)
                   VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                target,parent["organization_id"],id,parent["client_organization_id"],boq["scope_version_id"],
                boqId,next("commercial_quote_version",id),"Q-"+target.toString().uppercase(),
                boq["currency"],total,payment,delivery,r.exclusions.trim(),validity,actor,now(),
            )
            lines.forEachIndexed { i,l ->
                jdbc.update(
                    """INSERT INTO commercial_quote_line
                       (id,quote_version_id,line_code,description,quantity,unit,unit_price)
                       VALUES (?,?,?,?,?,?,?)""",
                    UUID.nameUUIDFromBytes(("$target:quote:$i").toByteArray()),
                    target,l["line_code"],l["description"],l["quantity"],l["unit"],l["sell_unit_price"],
                )
            }
            audit(actor,"COMMERCIAL_QUOTE_DRAFT_CREATED",target,"DRAFT",correlation)
        }
        return result(target,replay,"DRAFT",correlation)
    }

    private fun quote(id: UUID) = row(
        """SELECT id,organization_id,opportunity_id,client_organization_id,scope_version_id,
                  boq_version_id,version_number,state,version
           FROM commercial_quote_version WHERE id=?""",id,
    )

    fun requestQuoteApproval(id: UUID,key: UUID,actor: UUID,correlation: String): CommercialCommandResult {
        val company = quote(id)["organization_id"] as UUID
        access.require(actor,company,"SALES_WRITE")
        val replay = execute(key,actor,"COMMERCIAL_QUOTE_REQUEST_APPROVAL",id,id.toString(),correlation) {
            val q = quote(id);access.require(actor,company,"SALES_WRITE")
            lockOpportunity(q["opportunity_id"] as UUID)
            open(op(q["opportunity_id"] as UUID,actor,"SALES_WRITE"))
            if (q["state"] != "DRAFT") throw commercialError("COMMERCIAL_QUOTE_NOT_DRAFT","Quote already submitted.",HttpStatus.CONFLICT)
            if (jdbc.update(
                """UPDATE commercial_quote_version SET state='APPROVAL_PENDING',version=version+1
                   WHERE id=? AND state='DRAFT' AND version=?""",id,q["version"]!!,
            ) != 1) throw commercialError("COMMERCIAL_QUOTE_STALE","Quote changed.",HttpStatus.CONFLICT)
            val request = approval.requestException(
                actorUserId=actor,operationId=this.id("approval",key),
                subject=ApprovalSubjectRef(
                    organizationId=company,subjectType="COMMERCIAL_QUOTE",subjectId=id,
                    subjectVersion=(q["version"] as Number).toLong()+1L,
                ),
                policyKey="COMMERCIAL_QUOTE_RELEASE",reasonCode="COMMERCIAL_QUOTE_REVIEW",
                safeReasonSummary="Independent approval of exact commercial quote version.",
                correlationId=correlation,allowAuthorityBypass=false,
            )
            if (!request.approvalRequired || request.approvalRequestId == null)
                throw commercialError("COMMERCIAL_APPROVAL_NOT_CONFIGURED","Independent company approval is required.",HttpStatus.CONFLICT)
            audit(actor,"COMMERCIAL_QUOTE_APPROVAL_REQUESTED",id,"APPROVAL_PENDING",correlation)
        }
        return result(id,replay,"APPROVAL_PENDING",correlation)
    }

    fun releaseQuote(id: UUID,key: UUID,actor: UUID,correlation: String): CommercialCommandResult {
        val company = quote(id)["organization_id"] as UUID
        access.require(actor,company,"SALES_RELEASE")
        val replay = execute(key,actor,"COMMERCIAL_QUOTE_RELEASE",id,id.toString(),correlation) {
            val q = quote(id);access.require(actor,company,"SALES_RELEASE")
            lockOpportunity(q["opportunity_id"] as UUID)
            open(op(q["opportunity_id"] as UUID,actor,"SALES_RELEASE"))
            if (q["state"] != "APPROVAL_PENDING")
                throw commercialError("COMMERCIAL_QUOTE_NOT_PENDING","Quote not pending approved release.",HttpStatus.CONFLICT)
            if (jdbc.queryForObject(
                "SELECT validity_date < ? FROM commercial_quote_version WHERE id=?",
                Boolean::class.java,clock.instant().atOffset(ZoneOffset.UTC).toLocalDate(),id,
            ) == true) throw commercialError(
                "COMMERCIAL_QUOTE_EXPIRED",
                "The quote validity date passed; prepare a new version and approval.",HttpStatus.CONFLICT,
            )
            val latest = next("commercial_quote_version",q["opportunity_id"] as UUID)-1
            if ((q["version_number"] as Number).toInt() != latest)
                throw commercialError("COMMERCIAL_QUOTE_SUPERSEDED","A newer quote exists.",HttpStatus.CONFLICT)
            val currentSources=jdbc.queryForObject(
                """SELECT NOT EXISTS(
                     SELECT 1 FROM commercial_boq_version b
                     WHERE b.opportunity_id=q.opportunity_id
                       AND b.version_number>(
                         SELECT src.version_number FROM commercial_boq_version src WHERE src.id=q.boq_version_id)
                   ) AND NOT EXISTS(
                     SELECT 1 FROM commercial_scope_version s
                     WHERE s.opportunity_id=q.opportunity_id
                       AND s.version_number>(
                         SELECT src.version_number FROM commercial_scope_version src WHERE src.id=q.scope_version_id)
                   )
                   FROM commercial_quote_version q WHERE q.id=?""",
                Boolean::class.java,id,
            ) == true
            if (!currentSources) throw commercialError(
                "COMMERCIAL_QUOTE_SOURCE_STALE",
                "The scope or BOQ has a newer version; create a new quote and approval.",HttpStatus.CONFLICT)
            val approved = jdbc.queryForObject(
                """SELECT EXISTS(SELECT 1 FROM approval_request
                   WHERE organization_id=? AND subject_type='COMMERCIAL_QUOTE' AND subject_id=?
                     AND subject_version=? AND policy_key='COMMERCIAL_QUOTE_RELEASE'
                     AND state='APPROVED')""",Boolean::class.java,company,id,q["version"]!!,
            ) == true
            if (!approved) throw commercialError("COMMERCIAL_APPROVAL_MISSING","Exact quote version not approved.",HttpStatus.CONFLICT)
            if (jdbc.update(
                """UPDATE commercial_quote_version SET state='RELEASED',released_at=?,version=version+1
                   WHERE id=? AND version=? AND state='APPROVAL_PENDING'""",now(),id,q["version"]!!,
            ) != 1) throw commercialError("COMMERCIAL_QUOTE_STALE","Quote changed.",HttpStatus.CONFLICT)
            audit(actor,"COMMERCIAL_QUOTE_RELEASED",id,"RELEASED",correlation)
        }
        return result(id,replay,"RELEASED",correlation)
    }

    fun customerQuote(id: UUID,actor: UUID): ClientCommercialQuote {
        val q = quote(id)
        access.require(actor,q["organization_id"] as UUID,"SALES_VIEW","SALES_WRITE","SALES_RELEASE")
        if (q["state"] != "RELEASED")
            throw commercialError("COMMERCIAL_QUOTE_NOT_RELEASED","No released customer quote.",HttpStatus.NOT_FOUND)
        // Explicit projection: never SELECT * or join the private BOQ cost table.
        val v = row(
            """SELECT quote_code,version_number,client_organization_id,currency,total_price,
                      payment_terms,delivery_terms,exclusions,validity_date
               FROM commercial_quote_version WHERE id=? AND state='RELEASED'""",id,
        )
        val lines = jdbc.queryForList(
            """SELECT line_code,description,quantity,unit,unit_price
               FROM commercial_quote_line WHERE quote_version_id=? ORDER BY line_code""",id,
        ).map { l -> ClientCommercialQuoteLine(
            lineCode=l["line_code"] as String,description=l["description"] as String,
            quantity=l["quantity"] as BigDecimal,unit=l["unit"] as String,
            unitPrice=l["unit_price"] as BigDecimal,
        ) }
        return ClientCommercialQuote(
            quoteId=id.toString(),quoteCode=v["quote_code"] as String,
            versionNumber=(v["version_number"] as Number).toInt(),
            clientOrganizationId=v["client_organization_id"].toString(),
            currency=(v["currency"] as String).trim(),totalPrice=v["total_price"] as BigDecimal,
            paymentTerms=v["payment_terms"] as String,deliveryTerms=v["delivery_terms"] as String,
            exclusions=v["exclusions"] as String,validityDate=v["validity_date"].toString(),lines=lines,
        )
    }

    fun privateCosting(id: UUID,actor: UUID): List<Map<String, Any?>> {
        val parent = op(id,actor,"SALES_COST_VIEW")
        return jdbc.queryForList(
            """SELECT b.id AS boq_version_id,b.version_number,b.currency,l.line_code,l.description,
                      l.category,l.quantity,l.unit,l.internal_unit_cost,l.sell_unit_price
               FROM commercial_boq_version b JOIN commercial_boq_cost_line l ON l.boq_version_id=b.id
               WHERE b.organization_id=? AND b.opportunity_id=?
               ORDER BY b.version_number DESC,l.line_code""",parent["organization_id"]!!,id,
        )
    }

    fun privateCostSummary(id: UUID,actor: UUID): List<Map<String, Any?>> {
        val parent = op(id,actor,"SALES_COST_VIEW")
        return jdbc.queryForList(
            """SELECT boq_version_id,version_number,currency,total_lines,missing_cost_lines,
                      proposed_sell_total,known_cost_subtotal,complete_internal_cost_total
               FROM commercial_private_boq_cost_summary
               WHERE organization_id=? AND opportunity_id=?
               ORDER BY version_number DESC""",
            parent["organization_id"]!!,id,
        )
    }

    fun draftContract(id: UUID,r: CommercialContractDraftRequest,key: UUID,
                      actor: UUID,correlation: String): CommercialCommandResult {
        val parent=op(id,actor,"SALES_AWARD");open(parent)
        val quoteId=r.quoteVersionId.commercialUuid()
        val start=r.effectiveDate?.let(::date);val end=r.endDate?.let(::date)
        if (start != null && end != null && end < start)
            throw commercialError("COMMERCIAL_CONTRACT_DATES","Invalid dates.",HttpStatus.BAD_REQUEST)
        val target=this.id("contract",key)
        val replay=execute(key,actor,"COMMERCIAL_CONTRACT_DRAFT",target,r.toString(),correlation) {
            lockOpportunity(id)
            open(op(id,actor,"SALES_AWARD"))
            val q=quote(quoteId)
            if(q["organization_id"]!=parent["organization_id"]||q["opportunity_id"]!=id||q["state"]!="RELEASED")
                throw commercialError("COMMERCIAL_CONTRACT_SOURCE","Exact released quote required.",HttpStatus.CONFLICT)
            jdbc.update(
                """INSERT INTO commercial_contract
                   (id,organization_id,opportunity_id,quote_version_id,contract_code,
                    state,effective_date,end_date,warranty_terms,maintenance_terms,created_by,created_at)
                   VALUES (?,?,?,?,?,'DRAFT',?,?,?,?,?,?)""",
                target,parent["organization_id"],id,quoteId,required(r.contractCode,96),
                start,end,nullable(r.warrantyTerms,10000),nullable(r.maintenanceTerms,10000),actor,now(),
            )
            audit(actor,"COMMERCIAL_CONTRACT_DRAFTED",target,"DRAFT",correlation)
        }
        return result(target,replay,"DRAFT",correlation)
    }

    fun draftClientPo(id: UUID,r: CommercialClientPoDraftRequest,key: UUID,
                      actor: UUID,correlation: String): CommercialCommandResult {
        val parent=op(id,actor,"SALES_AWARD");open(parent)
        val contractId=r.contractId.commercialUuid()
        currency(r.currency);amount(r.amount)
        val issued=date(r.issuedOn)
        val target=this.id("client-po",key)
        val replay=execute(key,actor,"COMMERCIAL_CLIENT_PO_DRAFT",target,r.toString(),correlation) {
            lockOpportunity(id)
            open(op(id,actor,"SALES_AWARD"))
            val c=row(
                """SELECT q.currency FROM commercial_contract c
                   JOIN commercial_quote_version q ON q.id=c.quote_version_id
                   WHERE c.id=? AND c.opportunity_id=? AND c.organization_id=?""",
                contractId,id,parent["organization_id"]!!,
            )
            if ((c["currency"] as String).trim()!=r.currency)
                throw commercialError("COMMERCIAL_PO_CURRENCY","Currency differs from award.",HttpStatus.CONFLICT)
            jdbc.update(
                """INSERT INTO commercial_client_po
                   (id,organization_id,opportunity_id,contract_id,client_po_number,
                    issued_on,amount,currency,verified,created_by,created_at)
                   VALUES (?,?,?,?,?,?,?,?,false,?,?)""",
                target,parent["organization_id"],id,contractId,required(r.clientPoNumber,128),
                issued,r.amount,r.currency,actor,now(),
            )
            audit(actor,"COMMERCIAL_CLIENT_PO_DRAFTED",target,"UNVERIFIED",correlation)
        }
        return result(target,replay,"UNVERIFIED",correlation)
    }


    /**
     * This does not sign a contract. It acknowledges an already signed external
     * contract only after the canonical Evidence storage proves READY target-bound bytes.
     */
    fun confirmSignedContract(contractId: UUID,r: CommercialEvidenceConfirmationRequest,
                              key: UUID,actor: UUID,correlation: String): CommercialCommandResult {
        val c = row(
            "SELECT id,organization_id,opportunity_id,state FROM commercial_contract WHERE id=?",
            contractId,
        )
        val company = c["organization_id"] as UUID
        access.require(actor,company,"SALES_AWARD")
        val evidenceId = r.evidenceId.commercialUuid()
        val replay = execute(key,actor,"COMMERCIAL_CONTRACT_CONFIRM_SIGNED",
            contractId,"$contractId|$evidenceId",correlation) {
            access.require(actor,company,"SALES_AWARD")
            lockOpportunity(c["opportunity_id"] as UUID)
            open(op(c["opportunity_id"] as UUID,actor,"SALES_AWARD"))
            val current = row(
                "SELECT id,state,opportunity_id FROM commercial_contract WHERE id=? AND organization_id=? FOR UPDATE",
                contractId,company,
            )
            if (current["state"] != "DRAFT")
                throw commercialError("COMMERCIAL_CONTRACT_ALREADY_HANDLED",
                    "Contract is no longer a draft.",HttpStatus.CONFLICT)
            if (!awardEvidence.verifiedEvidence(evidenceId,"COMMERCIAL_CONTRACT",contractId,company))
                throw commercialError("COMMERCIAL_CONTRACT_EVIDENCE_NOT_READY",
                    "Verified restricted signed contract evidence is required.",HttpStatus.CONFLICT)
            if (jdbc.update(
                """UPDATE commercial_contract SET state='SIGNED',signed_evidence_id=?
                   WHERE id=? AND organization_id=? AND state='DRAFT'""",
                evidenceId,contractId,company,
            ) != 1) throw commercialError(
                "COMMERCIAL_CONTRACT_STALE","Contract changed.",HttpStatus.CONFLICT,
            )
            audit(actor,"COMMERCIAL_CONTRACT_SIGNED_EVIDENCE_CONFIRMED",contractId,"SIGNED",correlation)
        }
        return result(contractId,replay,"SIGNED",correlation)
    }

    fun verifyClientPo(clientPoId: UUID,r: CommercialEvidenceConfirmationRequest,
                       key: UUID,actor: UUID,correlation: String): CommercialCommandResult {
        val po = row(
            "SELECT id,organization_id,opportunity_id,contract_id,verified FROM commercial_client_po WHERE id=?",
            clientPoId,
        )
        val company = po["organization_id"] as UUID
        access.require(actor,company,"SALES_AWARD")
        val evidenceId = r.evidenceId.commercialUuid()
        val replay = execute(key,actor,"COMMERCIAL_CLIENT_PO_VERIFY",clientPoId,
            "$clientPoId|$evidenceId",correlation) {
            access.require(actor,company,"SALES_AWARD")
            lockOpportunity(po["opportunity_id"] as UUID)
            open(op(po["opportunity_id"] as UUID,actor,"SALES_AWARD"))
            val current = row(
                """SELECT p.id,p.verified,c.state AS contract_state
                   FROM commercial_client_po p
                   JOIN commercial_contract c ON c.id=p.contract_id
                   WHERE p.id=? AND p.organization_id=? FOR UPDATE OF p""",
                clientPoId,company,
            )
            if (current["verified"] == true || current["contract_state"] != "SIGNED")
                throw commercialError("COMMERCIAL_PO_NOT_ELIGIBLE",
                    "PO must be unverified and the contract signed.",HttpStatus.CONFLICT)
            if (!awardEvidence.verifiedEvidence(evidenceId,"COMMERCIAL_CLIENT_PO",clientPoId,company))
                throw commercialError("COMMERCIAL_PO_EVIDENCE_NOT_READY",
                    "Verified restricted client PO evidence is required.",HttpStatus.CONFLICT)
            if (jdbc.update(
                """UPDATE commercial_client_po SET verified=true,evidence_id=?
                   WHERE id=? AND organization_id=? AND verified=false""",
                evidenceId,clientPoId,company,
            ) != 1) throw commercialError(
                "COMMERCIAL_PO_STALE","Client PO changed.",HttpStatus.CONFLICT,
            )
            audit(actor,"COMMERCIAL_CLIENT_PO_EVIDENCE_VERIFIED",clientPoId,"VERIFIED",correlation)
        }
        return result(clientPoId,replay,"VERIFIED",correlation)
    }

    fun convertToProject(id: UUID,r: CommercialConversionRequest,key: UUID,
                         actor: UUID,correlation: String): CommercialCommandResult {
        val parent=op(id,actor,"SALES_AWARD")
        val contractId=r.contractId.commercialUuid()
        val poId=r.clientPoId.commercialUuid()
        val replay=execute(key,actor,"COMMERCIAL_WON_TO_PROJECT",id,"$id|$contractId|$poId",correlation) {
            access.require(actor,parent["organization_id"] as UUID,"SALES_AWARD")
            val locked=row(
                """SELECT id,organization_id,client_organization_id,title,state
                   FROM commercial_opportunity WHERE id=? FOR UPDATE""",id,
            );open(locked)
            val c=row(
                """SELECT c.id,c.quote_version_id,c.state,q.state AS quote_state
                   FROM commercial_contract c JOIN commercial_quote_version q ON q.id=c.quote_version_id
                   WHERE c.id=? AND c.organization_id=? AND c.opportunity_id=?""",
                contractId,locked["organization_id"]!!,id,
            )
            val po=row(
                """SELECT id,contract_id,verified FROM commercial_client_po
                   WHERE id=? AND opportunity_id=? AND organization_id=?""",
                poId,id,locked["organization_id"]!!,
            )
            if(c["state"]!="SIGNED"||c["quote_state"]!="RELEASED"||
               po["verified"]!=true||po["contract_id"]!=contractId)
                throw commercialError("COMMERCIAL_AWARD_INVALID","Signed contract and verified client PO required.",HttpStatus.CONFLICT)
            // The default gate is FAIL CLOSED until actual restricted READY evidence is linked.
            if(!awardEvidence.verifiedAward(contractId,poId,locked["organization_id"] as UUID))
                throw commercialError("COMMERCIAL_EVIDENCE_MISSING","Verified signed evidence required.",HttpStatus.CONFLICT)
            val creation=projects.createProject(CreateProjectCommand(
                operationId=this.id("canonical-project",id),
                organizationId=locked["organization_id"] as UUID,
                sourceType=ProjectSourceType.IMPORT,
                sourceExternalReference="COMMERCIAL:"+id+":QUOTE:"+c["quote_version_id"],
                explicitProjectCode=null,name=locked["title"] as String,
                clientOrganizationId=locked["client_organization_id"] as UUID,
                initialResponsibility=null,startDatePlanned=null,endDatePlanned=null,
                clientOccurredAt=clock.instant(),actorUserId=actor,correlationId=correlation,
            ))
            jdbc.update(
                """INSERT INTO commercial_project_handoff
                   (opportunity_id,organization_id,quote_version_id,contract_id,client_po_id,
                    project_id,created_by,created_at)
                   VALUES (?,?,?,?,?,?,?,?)""",
                id,locked["organization_id"],c["quote_version_id"],contractId,poId,
                creation.project.projectId,actor,now(),
            )
            jdbc.update(
                "UPDATE commercial_opportunity SET state='AWARDED',version=version+1,updated_at=? WHERE id=?",
                now(),id,
            )
            audit(actor,"COMMERCIAL_CONVERTED_TO_PROJECT",id,"AWARDED",correlation)
        }
        return result(id,replay,"AWARDED",correlation)
    }
}

/** A real signed-contract/Client-PO Evidence target adapter must replace this before activation. */
interface CommercialAwardEvidenceGate {
    fun verifiedEvidence(evidenceId: UUID, targetType: String, targetId: UUID, organizationId: UUID): Boolean
    fun verifiedAward(contractId: UUID, clientPoId: UUID, organizationId: UUID): Boolean
}

/** Test-only/no-integration sentinel, deliberately not registered as Spring bean. */
class FailClosedCommercialAwardEvidenceGate : CommercialAwardEvidenceGate {
    override fun verifiedEvidence(evidenceId: UUID, targetType: String,
                                  targetId: UUID, organizationId: UUID) = false
    override fun verifiedAward(contractId: UUID, clientPoId: UUID, organizationId: UUID) = false
}
@Component
class CommercialQuoteVersionGuard(private val jdbc: JdbcTemplate): ApprovalSubjectVersionSource {
    override fun supports(subjectType: String) = subjectType == "COMMERCIAL_QUOTE"
    override fun isCurrent(subject: ApprovalSubjectRef): Boolean =
        jdbc.queryForObject(
            """SELECT EXISTS(SELECT 1 FROM commercial_quote_version
               WHERE id=? AND organization_id=? AND state='APPROVAL_PENDING' AND version=?)""",
            Boolean::class.java,subject.subjectId,subject.organizationId,subject.subjectVersion,
        ) == true
}
