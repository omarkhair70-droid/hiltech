package com.hiltech.server.documents

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.documents.storage.EvidenceFinalizeVerification
import com.hiltech.server.documents.storage.EvidenceObjectStoragePort
import com.hiltech.server.documents.storage.EvidenceStorageProperties
import com.hiltech.server.documents.storage.EvidenceUploadSpec
import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.security.AuthorizationProjectionIntentWriter
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class ReserveEvidenceUploadRequest(
    val operationId: String,
    val targetType: String,
    val targetId: String,
    val workOrderId: String? = null,
    val evidenceRequirementKey: String,
    val evidenceTypeCode: String,
    val contentType: String,
    val originalFileName: String? = null,
    val sizeBytes: Long,
    val expectedSha256: String,
    val capturedAt: String,
    val clientOccurredAt: String? = null,
    val supersedesEvidenceId: String? = null,
)

data class FinalizeEvidenceRequest(
    val operationId: String,
    val uploadSessionId: String,
    val expectedSha256: String,
    val expectedSizeBytes: Long,
)

data class EvidenceUploadTargetResponse(
    val uploadUrl: String,
    val requiredHeaders: Map<String, List<String>>,
    val expiresAt: String,
    val expectedSizeBytes: Long,
)

data class EvidenceMetadataResponse(
    val evidenceId: String,
    val uploadSessionId: String,
    val targetType: String,
    val targetId: String,
    val workOrderId: String?,
    val evidenceRequirementKey: String?,
    val evidencePolicyId: String?,
    val evidencePolicyRevision: Int?,
    val evidenceTypeCode: String,
    val contentType: String,
    val sizeBytes: Long,
    val sha256: String,
    val storageState: String,
    val classificationCode: String,
    val clientVisibilityMode: String,
    val evidenceVersion: Long,
)

data class ReserveEvidenceUploadResponse(
    val evidence: EvidenceMetadataResponse,
    val upload: EvidenceUploadTargetResponse,
    val correlationId: String,
    val replayed: Boolean,
)

data class FinalizeEvidenceResponse(
    val evidence: EvidenceMetadataResponse,
    val correlationId: String,
    val replayed: Boolean,
)

data class EvidenceDownloadTargetResponse(
    val downloadUrl: String,
    val expiresAt: String,
    val correlationId: String,
)

fun interface EvidenceStorageAccessPort {
    fun current(): EvidenceObjectStoragePort?

    /** Bounded server-only private bytes; consumers must verify actor/scope/hash. */
    fun readRestrictedObject(objectKey: String, maxBytes: Long): ByteArray? =
        current()?.readRestrictedObject(objectKey, maxBytes)
}

@Component
class SpringEvidenceStorageAccess(
    private val provider: ObjectProvider<EvidenceObjectStoragePort>,
) : EvidenceStorageAccessPort {
    override fun current(): EvidenceObjectStoragePort? =
        provider.ifAvailable
}

@Component
class EvidenceLifecycleService @org.springframework.beans.factory.annotation.Autowired constructor(
    private val persistence: EvidencePersistencePort,
    private val targetAuthorization: EvidenceTargetAuthorizationPort,
    private val employeeDocumentTarget:
        EmployeeDocumentEvidenceTargetPort,
    private val supplierQuoteDocumentTarget:
        SupplierQuoteDocumentEvidenceTargetPort,
    private val idempotency: IdempotentCommandExecutor,
    private val projectionWriter: AuthorizationProjectionIntentWriter,
    private val audit: AuditEventWriter,
    private val storageAccess: EvidenceStorageAccessPort,
    private val storageProperties: EvidenceStorageProperties,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
    private val invoiceDocumentTargetProvider: ObjectProvider<SupplierInvoiceDocumentEvidenceTargetPort>? = null,
    private val poDeliveryNoteTargetProvider: ObjectProvider<PoReceivingDeliveryNoteEvidenceTargetPort>? = null,
    private val serviceQuoteDocumentTargetProvider: ObjectProvider<ServiceQuoteDocumentEvidenceTargetPort>? = null,
    private val servicePurchaseDocumentTargetProvider: ObjectProvider<ServicePurchaseDocumentEvidenceTargetPort>? = null,
    private val financeExpenseReceiptTargetProvider: ObjectProvider<FinanceExpenseReceiptEvidenceTargetPort>? = null,
    private val commercialEvidenceTarget: CommercialEvidenceTargetPort =
        DisabledCommercialEvidenceTargetPort,
) {
    fun reserve(
        actorIdentityId: UUID,
        idempotencyHeader: String?,
        correlationId: String,
        request: ReserveEvidenceUploadRequest,
    ): ReserveEvidenceUploadResponse {
        val command = request.toReserveCommand()

        IdempotencyKeyContract.requireMatches(
            rawHeader = idempotencyHeader,
            operationId = command.operationId,
        )

        if (
            command.targetType ==
            "EMPLOYEE_DOCUMENT"
        ) {
            return reserveEmployeeDocument(
                actorIdentityId =
                    actorIdentityId,
                correlationId =
                    correlationId,
                command = command,
            )
        }

        if (command.targetType == "SUPPLIER_QUOTE_DOCUMENT") {
            return reserveSupplierQuoteDocument(actorIdentityId, correlationId, command)
        }
        if (command.targetType == "SERVICE_QUOTE_DOCUMENT") {
            return reserveServiceQuoteDocument(actorIdentityId, correlationId, command)
        }
        if (command.targetType == "SERVICE_PURCHASE_DOCUMENT") {
            return reserveServicePurchaseDocument(actorIdentityId, correlationId, command)
        }

        if (command.targetType == "SUPPLIER_INVOICE_DOCUMENT") {
            return reserveSupplierInvoiceDocument(actorIdentityId, correlationId, command)
        }
        if (command.targetType == "PO_RECEIVING_DELIVERY_NOTE") {
            return reservePoReceivingDeliveryNote(actorIdentityId, correlationId, command)
        }
        if (command.targetType == "FINANCE_EXPENSE_RECEIPT") {
            return reserveFinanceExpenseReceipt(actorIdentityId, correlationId, command)
        }

        if (command.targetType in setOf("COMMERCIAL_CONTRACT", "COMMERCIAL_CLIENT_PO")) {
            return reserveCommercialDocument(actorIdentityId, correlationId, command)
        }

        if (command.targetType != "WORK_ORDER") {
            throw ProductApiException(
                code = "EVIDENCE_TARGET_UNSUPPORTED",
                message = "This Evidence target type is not supported yet.",
                status = HttpStatus.UNPROCESSABLE_ENTITY,
            )
        }

        if (
            command.workOrderId != null &&
            command.workOrderId != command.targetId
        ) {
            throw ProductApiException(
                code = "EVIDENCE_WORK_ORDER_TARGET_MISMATCH",
                message = "workOrderId must match the WorkOrder target.",
                status = HttpStatus.UNPROCESSABLE_ENTITY,
            )
        }

        val workOrderId = command.targetId

        if (
            !targetAuthorization.canReserveForWorkOrder(
                identityId = actorIdentityId,
                workOrderId = workOrderId,
            )
        ) {
            throw ProductApiException(
                code = "OBJECT_NOT_VISIBLE",
                message = "The requested WorkOrder is not available.",
                status = HttpStatus.NOT_FOUND,
            )
        }

        val policy =
            persistence.loadWorkOrderPolicy(
                workOrderId = workOrderId,
                requirementKey = command.requirementKey,
            ) ?: throw ProductApiException(
                code = "EVIDENCE_REQUIREMENT_NOT_BOUND",
                message = "The WorkOrder does not contain this Evidence requirement.",
                status = HttpStatus.UNPROCESSABLE_ENTITY,
            )

        validateReserveAgainstPolicy(
            command = command,
            policy = policy,
        )

        command.supersedesEvidenceId?.let { superseded ->
            if (
                !persistence.isValidSupersededEvidence(
                    supersedesEvidenceId = superseded,
                    organizationId = policy.organizationId,
                    workOrderId = workOrderId,
                )
            ) {
                throw ProductApiException(
                    code = "EVIDENCE_SUPERSEDES_INVALID",
                    message = "Superseded Evidence must belong to the same WorkOrder.",
                    status = HttpStatus.UNPROCESSABLE_ENTITY,
                )
            }
        }

        val storage = requireStorage()
        val now = clock.instant()
        val evidenceId = UUID.randomUUID()
        val uploadSessionId = UUID.randomUUID()
        val objectVersionId = UUID.randomUUID()
        val spec =
            EvidenceUploadSpec(
                evidenceId = evidenceId,
                organizationId = policy.organizationId,
                objectVersionId = objectVersionId,
                contentType = command.contentType,
                expectedSizeBytes = command.sizeBytes,
                expectedSha256Hex = command.sha256,
            )
        val expiresAt =
            now.plusSeconds(storageProperties.uploadExpirySeconds)

        val execution =
            idempotency.execute(
                spec =
                    IdempotentCommandSpec(
                        operationId = command.operationId,
                        actorUserId = actorIdentityId,
                        commandType = "ReserveEvidenceUpload",
                        targetType = "WorkOrder",
                        targetId = workOrderId,
                        requestFingerprint = command.fingerprint(),
                        correlationId = correlationId,
                    ),
            ) {
                persistence.insertReservation(
                    EvidenceReservationInsert(
                        evidenceId = evidenceId,
                        uploadSessionId = uploadSessionId,
                        operationId = command.operationId,
                        objectKey = spec.objectKey,
                        policy = policy,
                        contentType = command.contentType,
                        originalFileName = command.originalFileName,
                        sizeBytes = command.sizeBytes,
                        sha256 = command.sha256,
                        capturedAt = command.capturedAt,
                        clientOccurredAt = command.clientOccurredAt,
                        capturedByUserId = actorIdentityId,
                        supersedesEvidenceId = command.supersedesEvidenceId,
                        uploadExpiresAt = expiresAt,
                        createdAt = now,
                    ),
                )

                projectionWriter.write(
                    EvidenceAuthorizationProjectionFactory.workOrder(
                        evidenceId = evidenceId,
                        workOrderId = workOrderId,
                        evidenceVersion = 1,
                        occurredAt = now,
                    ),
                )
                projectionWriter.write(
                    EvidenceAuthorizationProjectionFactory.creator(
                        evidenceId = evidenceId,
                        identityId = actorIdentityId,
                        evidenceVersion = 1,
                        occurredAt = now,
                    ),
                )

                audit.append(
                    AuditEventRecord(
                        actorUserId = actorIdentityId,
                        action = "EVIDENCE_UPLOAD_RESERVED",
                        targetType = "Evidence",
                        targetId = evidenceId,
                        newStateRef = "evidence:RESERVED",
                        occurredAt = now,
                        correlationId = correlationId,
                        reason = "WORK_ORDER_EVIDENCE_RESERVE",
                        configRevisionRefsJson =
                            """{"evidencePolicyId":"${policy.evidencePolicyId}","revision":${policy.evidencePolicyRevision}}""",
                    ),
                )

                IdempotentCommandOutcome(
                    resultCode = "EVIDENCE_RESERVED",
                )
            }

        val reservation =
            persistence.findReservationByOperationId(
                command.operationId,
            ) ?: throw ProductApiException(
                code = "EVIDENCE_RESERVATION_UNAVAILABLE",
                message = "The Evidence reservation is unavailable.",
                status = HttpStatus.SERVICE_UNAVAILABLE,
                retryable = true,
            )

        if (
            !clock.instant().isBefore(
                reservation.uploadExpiresAt,
            )
        ) {
            throw ProductApiException(
                code = "EVIDENCE_UPLOAD_SESSION_EXPIRED",
                message = "The Evidence upload session has expired.",
                status = HttpStatus.CONFLICT,
            )
        }

        val signed =
            runCatching {
                storage.createUploadTarget(
                    reservation.toStorageSpec(),
                )
            }.getOrElse {
                throw ProductApiException(
                    code = "EVIDENCE_STORAGE_UNAVAILABLE",
                    message = "Evidence storage is temporarily unavailable.",
                    status = HttpStatus.SERVICE_UNAVAILABLE,
                    retryable = true,
                )
            }

        val effectiveExpiry =
            if (
                signed.expiresAt.isBefore(
                    reservation.uploadExpiresAt,
                )
            ) {
                signed.expiresAt
            } else {
                reservation.uploadExpiresAt
            }

        return ReserveEvidenceUploadResponse(
            evidence = reservation.toMetadata(),
            upload =
                EvidenceUploadTargetResponse(
                    uploadUrl = signed.uploadUrl,
                    requiredHeaders = signed.requiredHeaders,
                    expiresAt = effectiveExpiry.toString(),
                    expectedSizeBytes = reservation.sizeBytes,
                ),
            correlationId = correlationId,
            replayed = execution.replayed,
        )
    }

    /**
     * Reuse the canonical private S3 Evidence lifecycle with a supplier/RFQ-
     * scoped target. No arbitrary source URI or cross-organization HR/work
     * Evidence can be used to satisfy a quote document requirement.
     */
    private fun reserveSupplierQuoteDocument(
        actorIdentityId: UUID,
        correlationId: String,
        command: ReserveCommand,
    ): ReserveEvidenceUploadResponse {
        if (command.workOrderId != null || command.supersedesEvidenceId != null ||
            command.requirementKey != "SUPPLIER_QUOTE_BINARY" ||
            command.assertedEvidenceTypeCode != "SUPPLIER_QUOTE"
        ) {
            throw ProductApiException(
                code = "EVIDENCE_SUPPLIER_QUOTE_CONTRACT_MISMATCH",
                message = "Supplier quotation Evidence must match its restricted slot contract.",
                status = HttpStatus.UNPROCESSABLE_ENTITY,
            )
        }
        val scanClass = employeeDocumentScanClass(command.contentType)
            ?: throw ProductApiException(
                code = "EVIDENCE_CONTENT_TYPE_NOT_ALLOWED",
                message = "Supplier quotation uploads must be PDF or supported scanned image.",
                status = HttpStatus.UNPROCESSABLE_ENTITY,
            )
        if (command.sizeBytes !in 1..EvidenceUploadSpec.MAX_EVIDENCE_OBJECT_BYTES) {
            throw ProductApiException(
                code = "EVIDENCE_SIZE_NOT_ALLOWED",
                message = "Supplier quotation exceeds the Evidence byte limit.",
                status = HttpStatus.UNPROCESSABLE_ENTITY,
            )
        }
        val target = supplierQuoteDocumentTarget.loadTarget(command.targetId)
            ?: throw ProductApiException(
                "OBJECT_NOT_VISIBLE", "Quotation document slot is not available.",
                HttpStatus.NOT_FOUND,
            )
        if (!supplierQuoteDocumentTarget.canReserve(actorIdentityId, target)) {
            throw ProductApiException(
                "OBJECT_NOT_VISIBLE", "Quotation document slot is not available.",
                HttpStatus.NOT_FOUND,
            )
        }
        val storage = requireStorage()
        val now = clock.instant()
        val evidenceId = UUID.randomUUID()
        val uploadSessionId = UUID.randomUUID()
        val spec = EvidenceUploadSpec(
            evidenceId = evidenceId,
            organizationId = target.purchasingOrganizationId,
            objectVersionId = UUID.randomUUID(),
            contentType = command.contentType,
            expectedSizeBytes = command.sizeBytes,
            expectedSha256Hex = command.sha256,
        )
        val expiresAt = now.plusSeconds(storageProperties.uploadExpirySeconds)
        val execution = idempotency.execute(
            spec = IdempotentCommandSpec(
                operationId = command.operationId,
                actorUserId = actorIdentityId,
                commandType = "ReserveEvidenceUpload",
                targetType = "SupplierQuoteDocument",
                targetId = target.slotId,
                requestFingerprint = command.fingerprint(),
                correlationId = correlationId,
            ),
        ) {
            val current = supplierQuoteDocumentTarget.loadTarget(command.targetId)
            if (current != target ||
                !supplierQuoteDocumentTarget.canReserve(actorIdentityId, target)
            ) {
                throw ProductApiException(
                    "OBJECT_NOT_VISIBLE", "Quotation document slot is not available.",
                    HttpStatus.NOT_FOUND,
                )
            }
            persistence.insertSupplierQuoteDocumentReservation(
                SupplierQuoteDocumentEvidenceReservationInsert(
                    evidenceId = evidenceId,
                    uploadSessionId = uploadSessionId,
                    operationId = command.operationId,
                    objectKey = spec.objectKey,
                    organizationId = current.purchasingOrganizationId,
                    documentSlotId = current.slotId,
                    contentType = command.contentType,
                    originalFileName = command.originalFileName,
                    sizeBytes = command.sizeBytes,
                    sha256 = command.sha256,
                    capturedAt = command.capturedAt,
                    clientOccurredAt = command.clientOccurredAt,
                    capturedByUserId = actorIdentityId,
                    securityScanClass = scanClass,
                    uploadExpiresAt = expiresAt,
                    createdAt = now,
                ),
            )
            audit.append(
                AuditEventRecord(
                    actorUserId = actorIdentityId,
                    action = "EVIDENCE_UPLOAD_RESERVED",
                    targetType = "Evidence",
                    targetId = evidenceId,
                    newStateRef = "evidence:RESERVED",
                    occurredAt = now,
                    correlationId = correlationId,
                    reason = "SUPPLIER_QUOTE_DOCUMENT_RESERVE",
                    configRevisionRefsJson = """{"targetType":"SUPPLIER_QUOTE_DOCUMENT"}""",
                ),
            )
            IdempotentCommandOutcome(resultCode = "EVIDENCE_RESERVED")
        }
        val reservation = persistence.findReservationByOperationId(command.operationId)
            ?: throw ProductApiException(
                "EVIDENCE_RESERVATION_UNAVAILABLE", "Quotation Evidence reservation unavailable.",
                HttpStatus.SERVICE_UNAVAILABLE, retryable = true,
            )
        if (reservation.targetType != "SUPPLIER_QUOTE_DOCUMENT" ||
            reservation.targetId != target.slotId ||
            reservation.organizationId != target.purchasingOrganizationId
        ) {
            throw ProductApiException(
                "OBJECT_NOT_VISIBLE", "Quotation Evidence is not available.",
                HttpStatus.NOT_FOUND,
            )
        }
        if (!clock.instant().isBefore(reservation.uploadExpiresAt)) {
            throw ProductApiException(
                "EVIDENCE_UPLOAD_SESSION_EXPIRED", "Evidence upload session expired.",
                HttpStatus.CONFLICT,
            )
        }
        val signed = runCatching {
            storage.createUploadTarget(reservation.toStorageSpec())
        }.getOrElse {
            throw ProductApiException(
                "EVIDENCE_STORAGE_UNAVAILABLE", "Evidence storage temporarily unavailable.",
                HttpStatus.SERVICE_UNAVAILABLE, retryable = true,
            )
        }
        val expiry = if (signed.expiresAt.isBefore(reservation.uploadExpiresAt))
            signed.expiresAt else reservation.uploadExpiresAt
        return ReserveEvidenceUploadResponse(
            evidence = reservation.toMetadata(),
            upload = EvidenceUploadTargetResponse(
                uploadUrl = signed.uploadUrl,
                requiredHeaders = signed.requiredHeaders,
                expiresAt = expiry.toString(),
                expectedSizeBytes = reservation.sizeBytes,
            ),
            correlationId = correlationId,
            replayed = execution.replayed,
        )
    }

    private fun reserveServiceQuoteDocument(
        actorIdentityId: UUID,
        correlationId: String,
        command: ReserveCommand,
    ): ReserveEvidenceUploadResponse {
        val serviceTarget = serviceQuoteDocumentTargetProvider?.ifAvailable
            ?: throw ProductApiException(
                "EVIDENCE_TARGET_UNSUPPORTED", "Service quotation Evidence integration unavailable.",
                HttpStatus.NOT_FOUND,
            )
        if (command.workOrderId != null || command.supersedesEvidenceId != null ||
            command.requirementKey != "SERVICE_QUOTE_BINARY" ||
            command.assertedEvidenceTypeCode != "SERVICE_QUOTE"
        ) {
            throw ProductApiException(
                code = "EVIDENCE_SERVICE_QUOTE_CONTRACT_MISMATCH",
                message = "Supplier quotation Evidence must match its restricted slot contract.",
                status = HttpStatus.UNPROCESSABLE_ENTITY,
            )
        }
        val scanClass = employeeDocumentScanClass(command.contentType)
            ?: throw ProductApiException(
                code = "EVIDENCE_CONTENT_TYPE_NOT_ALLOWED",
                message = "Supplier quotation uploads must be PDF or supported scanned image.",
                status = HttpStatus.UNPROCESSABLE_ENTITY,
            )
        if (command.sizeBytes !in 1..EvidenceUploadSpec.MAX_EVIDENCE_OBJECT_BYTES) {
            throw ProductApiException(
                code = "EVIDENCE_SIZE_NOT_ALLOWED",
                message = "Supplier quotation exceeds the Evidence byte limit.",
                status = HttpStatus.UNPROCESSABLE_ENTITY,
            )
        }
        val target = serviceTarget.loadTarget(command.targetId)
            ?: throw ProductApiException(
                "OBJECT_NOT_VISIBLE", "Quotation document slot is not available.",
                HttpStatus.NOT_FOUND,
            )
        if (!serviceTarget.canReserve(actorIdentityId, target)) {
            throw ProductApiException(
                "OBJECT_NOT_VISIBLE", "Quotation document slot is not available.",
                HttpStatus.NOT_FOUND,
            )
        }
        val storage = requireStorage()
        val now = clock.instant()
        val evidenceId = UUID.randomUUID()
        val uploadSessionId = UUID.randomUUID()
        val spec = EvidenceUploadSpec(
            evidenceId = evidenceId,
            organizationId = target.purchasingOrganizationId,
            objectVersionId = UUID.randomUUID(),
            contentType = command.contentType,
            expectedSizeBytes = command.sizeBytes,
            expectedSha256Hex = command.sha256,
        )
        val expiresAt = now.plusSeconds(storageProperties.uploadExpirySeconds)
        val execution = idempotency.execute(
            spec = IdempotentCommandSpec(
                operationId = command.operationId,
                actorUserId = actorIdentityId,
                commandType = "ReserveEvidenceUpload",
                targetType = "ServiceQuoteDocument",
                targetId = target.slotId,
                requestFingerprint = command.fingerprint(),
                correlationId = correlationId,
            ),
        ) {
            val current = serviceTarget.loadTarget(command.targetId)
            if (current != target ||
                !serviceTarget.canReserve(actorIdentityId, target)
            ) {
                throw ProductApiException(
                    "OBJECT_NOT_VISIBLE", "Quotation document slot is not available.",
                    HttpStatus.NOT_FOUND,
                )
            }
            persistence.insertServiceQuoteDocumentReservation(
                SupplierQuoteDocumentEvidenceReservationInsert(
                    evidenceId = evidenceId,
                    uploadSessionId = uploadSessionId,
                    operationId = command.operationId,
                    objectKey = spec.objectKey,
                    organizationId = current.purchasingOrganizationId,
                    documentSlotId = current.slotId,
                    contentType = command.contentType,
                    originalFileName = command.originalFileName,
                    sizeBytes = command.sizeBytes,
                    sha256 = command.sha256,
                    capturedAt = command.capturedAt,
                    clientOccurredAt = command.clientOccurredAt,
                    capturedByUserId = actorIdentityId,
                    securityScanClass = scanClass,
                    uploadExpiresAt = expiresAt,
                    createdAt = now,
                ),
            )
            audit.append(
                AuditEventRecord(
                    actorUserId = actorIdentityId,
                    action = "EVIDENCE_UPLOAD_RESERVED",
                    targetType = "Evidence",
                    targetId = evidenceId,
                    newStateRef = "evidence:RESERVED",
                    occurredAt = now,
                    correlationId = correlationId,
                    reason = "SERVICE_QUOTE_DOCUMENT_RESERVE",
                    configRevisionRefsJson = """{"targetType":"SERVICE_QUOTE_DOCUMENT"}""",
                ),
            )
            IdempotentCommandOutcome(resultCode = "EVIDENCE_RESERVED")
        }
        val reservation = persistence.findReservationByOperationId(command.operationId)
            ?: throw ProductApiException(
                "EVIDENCE_RESERVATION_UNAVAILABLE", "Quotation Evidence reservation unavailable.",
                HttpStatus.SERVICE_UNAVAILABLE, retryable = true,
            )
        if (reservation.targetType != "SERVICE_QUOTE_DOCUMENT" ||
            reservation.targetId != target.slotId ||
            reservation.organizationId != target.purchasingOrganizationId
        ) {
            throw ProductApiException(
                "OBJECT_NOT_VISIBLE", "Quotation Evidence is not available.",
                HttpStatus.NOT_FOUND,
            )
        }
        if (!clock.instant().isBefore(reservation.uploadExpiresAt)) {
            throw ProductApiException(
                "EVIDENCE_UPLOAD_SESSION_EXPIRED", "Evidence upload session expired.",
                HttpStatus.CONFLICT,
            )
        }
        val signed = runCatching {
            storage.createUploadTarget(reservation.toStorageSpec())
        }.getOrElse {
            throw ProductApiException(
                "EVIDENCE_STORAGE_UNAVAILABLE", "Evidence storage temporarily unavailable.",
                HttpStatus.SERVICE_UNAVAILABLE, retryable = true,
            )
        }
        val expiry = if (signed.expiresAt.isBefore(reservation.uploadExpiresAt))
            signed.expiresAt else reservation.uploadExpiresAt
        return ReserveEvidenceUploadResponse(
            evidence = reservation.toMetadata(),
            upload = EvidenceUploadTargetResponse(
                uploadUrl = signed.uploadUrl,
                requiredHeaders = signed.requiredHeaders,
                expiresAt = expiry.toString(),
                expectedSizeBytes = reservation.sizeBytes,
            ),
            correlationId = correlationId,
            replayed = execution.replayed,
        )
    }

    private fun reserveServicePurchaseDocument(
        actorIdentityId: UUID,
        correlationId: String,
        command: ReserveCommand,
    ): ReserveEvidenceUploadResponse {
        val serviceTarget = servicePurchaseDocumentTargetProvider?.ifAvailable
            ?: throw ProductApiException(
                "EVIDENCE_TARGET_UNSUPPORTED", "Service quotation Evidence integration unavailable.",
                HttpStatus.NOT_FOUND,
            )
        if (command.workOrderId != null || command.supersedesEvidenceId != null ||
            command.requirementKey !in setOf("SERVICE_DELIVERY_BINARY","SERVICE_INVOICE_BINARY") ||
            command.assertedEvidenceTypeCode !in setOf("SERVICE_DELIVERY","SERVICE_INVOICE")
        ) {
            throw ProductApiException(
                code = "EVIDENCE_SERVICE_QUOTE_CONTRACT_MISMATCH",
                message = "Supplier quotation Evidence must match its restricted slot contract.",
                status = HttpStatus.UNPROCESSABLE_ENTITY,
            )
        }
        val scanClass = employeeDocumentScanClass(command.contentType)
            ?: throw ProductApiException(
                code = "EVIDENCE_CONTENT_TYPE_NOT_ALLOWED",
                message = "Supplier quotation uploads must be PDF or supported scanned image.",
                status = HttpStatus.UNPROCESSABLE_ENTITY,
            )
        if (command.sizeBytes !in 1..EvidenceUploadSpec.MAX_EVIDENCE_OBJECT_BYTES) {
            throw ProductApiException(
                code = "EVIDENCE_SIZE_NOT_ALLOWED",
                message = "Supplier quotation exceeds the Evidence byte limit.",
                status = HttpStatus.UNPROCESSABLE_ENTITY,
            )
        }
        val target = serviceTarget.loadTarget(command.targetId)
            ?: throw ProductApiException(
                "OBJECT_NOT_VISIBLE", "Quotation document slot is not available.",
                HttpStatus.NOT_FOUND,
            )
        if (!serviceTarget.canReserve(actorIdentityId, target)) {
            throw ProductApiException(
                "OBJECT_NOT_VISIBLE", "Quotation document slot is not available.",
                HttpStatus.NOT_FOUND,
            )
        }
        val expected = when (target.documentKind) {
            "DELIVERY" -> "SERVICE_DELIVERY"
            "INVOICE" -> "SERVICE_INVOICE"
            else -> throw ProductApiException("EVIDENCE_TARGET_UNSUPPORTED",
                "Unknown service purchase document type.", HttpStatus.UNPROCESSABLE_ENTITY)
        }
        if (command.requirementKey != expected + "_BINARY" ||
            command.assertedEvidenceTypeCode != expected) {
            throw ProductApiException("EVIDENCE_SERVICE_DOCUMENT_MISMATCH",
                "Service document content kind does not match the target slot.",
                HttpStatus.UNPROCESSABLE_ENTITY)
        }
        val storage = requireStorage()
        val now = clock.instant()
        val evidenceId = UUID.randomUUID()
        val uploadSessionId = UUID.randomUUID()
        val spec = EvidenceUploadSpec(
            evidenceId = evidenceId,
            organizationId = target.organizationId,
            objectVersionId = UUID.randomUUID(),
            contentType = command.contentType,
            expectedSizeBytes = command.sizeBytes,
            expectedSha256Hex = command.sha256,
        )
        val expiresAt = now.plusSeconds(storageProperties.uploadExpirySeconds)
        val execution = idempotency.execute(
            spec = IdempotentCommandSpec(
                operationId = command.operationId,
                actorUserId = actorIdentityId,
                commandType = "ReserveEvidenceUpload",
                targetType = "ServicePurchaseDocument",
                targetId = target.slotId,
                requestFingerprint = command.fingerprint(),
                correlationId = correlationId,
            ),
        ) {
            val current = serviceTarget.loadTarget(command.targetId)
            if (current != target ||
                !serviceTarget.canReserve(actorIdentityId, target)
            ) {
                throw ProductApiException(
                    "OBJECT_NOT_VISIBLE", "Quotation document slot is not available.",
                    HttpStatus.NOT_FOUND,
                )
            }
            persistence.insertServicePurchaseDocumentReservation(
                SupplierQuoteDocumentEvidenceReservationInsert(
                    evidenceId = evidenceId,
                    uploadSessionId = uploadSessionId,
                    operationId = command.operationId,
                    objectKey = spec.objectKey,
                    organizationId = current.organizationId,
                    documentSlotId = current.slotId,
                    contentType = command.contentType,
                    originalFileName = command.originalFileName,
                    sizeBytes = command.sizeBytes,
                    sha256 = command.sha256,
                    capturedAt = command.capturedAt,
                    clientOccurredAt = command.clientOccurredAt,
                    capturedByUserId = actorIdentityId,
                    securityScanClass = scanClass,
                    uploadExpiresAt = expiresAt,
                    createdAt = now,
                ), target.documentKind,
            )
            audit.append(
                AuditEventRecord(
                    actorUserId = actorIdentityId,
                    action = "EVIDENCE_UPLOAD_RESERVED",
                    targetType = "Evidence",
                    targetId = evidenceId,
                    newStateRef = "evidence:RESERVED",
                    occurredAt = now,
                    correlationId = correlationId,
                    reason = "SERVICE_PURCHASE_DOCUMENT_RESERVE",
                    configRevisionRefsJson = """{"targetType":"SERVICE_PURCHASE_DOCUMENT"}""",
                ),
            )
            IdempotentCommandOutcome(resultCode = "EVIDENCE_RESERVED")
        }
        val reservation = persistence.findReservationByOperationId(command.operationId)
            ?: throw ProductApiException(
                "EVIDENCE_RESERVATION_UNAVAILABLE", "Quotation Evidence reservation unavailable.",
                HttpStatus.SERVICE_UNAVAILABLE, retryable = true,
            )
        if (reservation.targetType != "SERVICE_PURCHASE_DOCUMENT" ||
            reservation.targetId != target.slotId ||
            reservation.organizationId != target.organizationId
        ) {
            throw ProductApiException(
                "OBJECT_NOT_VISIBLE", "Quotation Evidence is not available.",
                HttpStatus.NOT_FOUND,
            )
        }
        if (!clock.instant().isBefore(reservation.uploadExpiresAt)) {
            throw ProductApiException(
                "EVIDENCE_UPLOAD_SESSION_EXPIRED", "Evidence upload session expired.",
                HttpStatus.CONFLICT,
            )
        }
        val signed = runCatching {
            storage.createUploadTarget(reservation.toStorageSpec())
        }.getOrElse {
            throw ProductApiException(
                "EVIDENCE_STORAGE_UNAVAILABLE", "Evidence storage temporarily unavailable.",
                HttpStatus.SERVICE_UNAVAILABLE, retryable = true,
            )
        }
        val expiry = if (signed.expiresAt.isBefore(reservation.uploadExpiresAt))
            signed.expiresAt else reservation.uploadExpiresAt
        return ReserveEvidenceUploadResponse(
            evidence = reservation.toMetadata(),
            upload = EvidenceUploadTargetResponse(
                uploadUrl = signed.uploadUrl,
                requiredHeaders = signed.requiredHeaders,
                expiresAt = expiry.toString(),
                expectedSizeBytes = reservation.sizeBytes,
            ),
            correlationId = correlationId,
            replayed = execution.replayed,
        )
    }

    private fun reserveSupplierInvoiceDocument(
        actorIdentityId: UUID,
        correlationId: String,
        command: ReserveCommand,
    ): ReserveEvidenceUploadResponse {
        val invoiceDocumentTarget = invoiceDocumentTargetProvider?.ifAvailable
            ?: throw ProductApiException(
                "EVIDENCE_TARGET_UNSUPPORTED", "Invoice Evidence integration is unavailable.",
                HttpStatus.NOT_FOUND,
            )
        if (command.workOrderId != null || command.supersedesEvidenceId != null ||
            command.requirementKey != "SUPPLIER_INVOICE_BINARY" ||
            command.assertedEvidenceTypeCode != "SUPPLIER_INVOICE"
        ) {
            throw ProductApiException(
                code = "EVIDENCE_SUPPLIER_INVOICE_CONTRACT_MISMATCH",
                message = "Supplier invoice Evidence must match its restricted slot contract.",
                status = HttpStatus.UNPROCESSABLE_ENTITY,
            )
        }
        val scanClass = employeeDocumentScanClass(command.contentType)
            ?: throw ProductApiException(
                code = "EVIDENCE_CONTENT_TYPE_NOT_ALLOWED",
                message = "Supplier invoice uploads must be PDF or supported scanned image.",
                status = HttpStatus.UNPROCESSABLE_ENTITY,
            )
        if (command.sizeBytes !in 1..EvidenceUploadSpec.MAX_EVIDENCE_OBJECT_BYTES) {
            throw ProductApiException(
                code = "EVIDENCE_SIZE_NOT_ALLOWED",
                message = "Supplier invoice exceeds the Evidence byte limit.",
                status = HttpStatus.UNPROCESSABLE_ENTITY,
            )
        }
        val target = invoiceDocumentTarget.loadTarget(command.targetId)
            ?: throw ProductApiException(
                "OBJECT_NOT_VISIBLE", "Invoice document slot is not available.",
                HttpStatus.NOT_FOUND,
            )
        if (!invoiceDocumentTarget.canReserve(actorIdentityId, target)) {
            throw ProductApiException(
                "OBJECT_NOT_VISIBLE", "Invoice document slot is not available.",
                HttpStatus.NOT_FOUND,
            )
        }
        val storage = requireStorage()
        val now = clock.instant()
        val evidenceId = UUID.randomUUID()
        val uploadSessionId = UUID.randomUUID()
        val spec = EvidenceUploadSpec(
            evidenceId = evidenceId,
            organizationId = target.purchasingOrganizationId,
            objectVersionId = UUID.randomUUID(),
            contentType = command.contentType,
            expectedSizeBytes = command.sizeBytes,
            expectedSha256Hex = command.sha256,
        )
        val expiresAt = now.plusSeconds(storageProperties.uploadExpirySeconds)
        val execution = idempotency.execute(
            spec = IdempotentCommandSpec(
                operationId = command.operationId,
                actorUserId = actorIdentityId,
                commandType = "ReserveEvidenceUpload",
                targetType = "SupplierInvoiceDocument",
                targetId = target.slotId,
                requestFingerprint = command.fingerprint(),
                correlationId = correlationId,
            ),
        ) {
            val current = invoiceDocumentTarget.loadTarget(command.targetId)
            if (current != target ||
                !invoiceDocumentTarget.canReserve(actorIdentityId, target)
            ) {
                throw ProductApiException(
                    "OBJECT_NOT_VISIBLE", "Invoice document slot is not available.",
                    HttpStatus.NOT_FOUND,
                )
            }
            persistence.insertSupplierInvoiceDocumentReservation(
                SupplierInvoiceDocumentEvidenceReservationInsert(
                    evidenceId = evidenceId,
                    uploadSessionId = uploadSessionId,
                    operationId = command.operationId,
                    objectKey = spec.objectKey,
                    organizationId = current.purchasingOrganizationId,
                    documentSlotId = current.slotId,
                    contentType = command.contentType,
                    originalFileName = command.originalFileName,
                    sizeBytes = command.sizeBytes,
                    sha256 = command.sha256,
                    capturedAt = command.capturedAt,
                    clientOccurredAt = command.clientOccurredAt,
                    capturedByUserId = actorIdentityId,
                    securityScanClass = scanClass,
                    uploadExpiresAt = expiresAt,
                    createdAt = now,
                ),
            )
            audit.append(
                AuditEventRecord(
                    actorUserId = actorIdentityId,
                    action = "EVIDENCE_UPLOAD_RESERVED",
                    targetType = "Evidence",
                    targetId = evidenceId,
                    newStateRef = "evidence:RESERVED",
                    occurredAt = now,
                    correlationId = correlationId,
                    reason = "SUPPLIER_INVOICE_DOCUMENT_RESERVE",
                    configRevisionRefsJson = """{"targetType":"SUPPLIER_INVOICE_DOCUMENT"}""",
                ),
            )
            IdempotentCommandOutcome(resultCode = "EVIDENCE_RESERVED")
        }
        val reservation = persistence.findReservationByOperationId(command.operationId)
            ?: throw ProductApiException(
                "EVIDENCE_RESERVATION_UNAVAILABLE", "Invoice Evidence reservation unavailable.",
                HttpStatus.SERVICE_UNAVAILABLE, retryable = true,
            )
        if (reservation.targetType != "SUPPLIER_INVOICE_DOCUMENT" ||
            reservation.targetId != target.slotId ||
            reservation.organizationId != target.purchasingOrganizationId
        ) {
            throw ProductApiException(
                "OBJECT_NOT_VISIBLE", "Invoice Evidence is not available.",
                HttpStatus.NOT_FOUND,
            )
        }
        if (!clock.instant().isBefore(reservation.uploadExpiresAt)) {
            throw ProductApiException(
                "EVIDENCE_UPLOAD_SESSION_EXPIRED", "Evidence upload session expired.",
                HttpStatus.CONFLICT,
            )
        }
        val signed = runCatching {
            storage.createUploadTarget(reservation.toStorageSpec())
        }.getOrElse {
            throw ProductApiException(
                "EVIDENCE_STORAGE_UNAVAILABLE", "Evidence storage temporarily unavailable.",
                HttpStatus.SERVICE_UNAVAILABLE, retryable = true,
            )
        }
        val expiry = if (signed.expiresAt.isBefore(reservation.uploadExpiresAt))
            signed.expiresAt else reservation.uploadExpiresAt
        return ReserveEvidenceUploadResponse(
            evidence = reservation.toMetadata(),
            upload = EvidenceUploadTargetResponse(
                uploadUrl = signed.uploadUrl,
                requiredHeaders = signed.requiredHeaders,
                expiresAt = expiry.toString(),
                expectedSizeBytes = reservation.sizeBytes,
            ),
            correlationId = correlationId,
            replayed = execution.replayed,
        )
    }

    private fun reserveFinanceExpenseReceipt(
        actorIdentityId: UUID,
        correlationId: String,
        command: ReserveCommand,
    ): ReserveEvidenceUploadResponse {
        val port = financeExpenseReceiptTargetProvider?.ifAvailable
            ?: throw ProductApiException(
                "EVIDENCE_TARGET_UNSUPPORTED", "Expense Evidence target is unavailable.",
                HttpStatus.NOT_FOUND,
            )
        if (command.workOrderId != null || command.supersedesEvidenceId != null ||
            command.requirementKey != "FINANCE_EXPENSE_RECEIPT_BINARY" ||
            command.assertedEvidenceTypeCode != "FINANCE_EXPENSE_RECEIPT"
        ) throw ProductApiException(
            "EVIDENCE_FINANCE_EXPENSE_CONTRACT_MISMATCH",
            "Expense receipts require the exact Finance Evidence contract.",
            HttpStatus.UNPROCESSABLE_ENTITY,
        )
        val scanClass = employeeDocumentScanClass(command.contentType)
            ?: throw ProductApiException(
                "EVIDENCE_CONTENT_TYPE_NOT_ALLOWED", "Receipt must be PDF, PNG or JPEG.",
                HttpStatus.UNPROCESSABLE_ENTITY,
            )
        if (command.sizeBytes !in 1..EvidenceUploadSpec.MAX_EVIDENCE_OBJECT_BYTES)
            throw ProductApiException(
                "EVIDENCE_SIZE_NOT_ALLOWED", "Receipt exceeds Evidence storage limit.",
                HttpStatus.UNPROCESSABLE_ENTITY,
            )
        val target = port.loadTarget(command.targetId)
            ?: throw ProductApiException(
                "OBJECT_NOT_VISIBLE", "Expense draft unavailable.", HttpStatus.NOT_FOUND,
            )
        if (!port.canReserve(actorIdentityId, target))
            throw ProductApiException(
                "OBJECT_NOT_VISIBLE", "Expense draft unavailable.", HttpStatus.NOT_FOUND,
            )
        val storage = requireStorage()
        val now = clock.instant()
        val evidenceId = UUID.randomUUID()
        val spec = EvidenceUploadSpec(
            evidenceId = evidenceId, organizationId = target.organizationId,
            objectVersionId = UUID.randomUUID(), contentType = command.contentType,
            expectedSizeBytes = command.sizeBytes, expectedSha256Hex = command.sha256,
        )
        val expires = now.plusSeconds(storageProperties.uploadExpirySeconds)
        val result = idempotency.execute(
            spec = IdempotentCommandSpec(
                operationId = command.operationId, actorUserId = actorIdentityId,
                commandType = "ReserveEvidenceUpload",
                targetType = "FinanceExpenseReceipt",
                targetId = target.expenseDraftId,
                requestFingerprint = command.fingerprint(), correlationId = correlationId,
            ),
        ) {
            val current = port.loadTarget(command.targetId)
            if (current != target || !port.canReserve(actorIdentityId, target))
                throw ProductApiException(
                    "OBJECT_NOT_VISIBLE", "Expense draft unavailable.", HttpStatus.NOT_FOUND,
                )
            persistence.insertFinanceExpenseReceiptReservation(
                FinanceExpenseReceiptEvidenceReservationInsert(
                    evidenceId = evidenceId, uploadSessionId = UUID.randomUUID(),
                    operationId = command.operationId, objectKey = spec.objectKey,
                    organizationId = current.organizationId,
                    expenseDraftId = current.expenseDraftId,
                    contentType = command.contentType,
                    originalFileName = command.originalFileName,
                    sizeBytes = command.sizeBytes, sha256 = command.sha256,
                    capturedAt = command.capturedAt,
                    clientOccurredAt = command.clientOccurredAt,
                    capturedByUserId = actorIdentityId,
                    securityScanClass = scanClass, uploadExpiresAt = expires,
                    createdAt = now,
                ),
            )
            audit.append(
                AuditEventRecord(
                    actorUserId = actorIdentityId, action = "EVIDENCE_UPLOAD_RESERVED",
                    targetType = "Evidence", targetId = evidenceId,
                    newStateRef = "evidence:RESERVED", occurredAt = now,
                    correlationId = correlationId,
                    reason = "FINANCE_EXPENSE_RECEIPT_RESERVE_NOT_APPROVAL",
                    configRevisionRefsJson = """{"targetType":"FINANCE_EXPENSE_RECEIPT"}""",
                ),
            )
            IdempotentCommandOutcome(resultCode = "EVIDENCE_RESERVED")
        }
        val reservation = persistence.findReservationByOperationId(command.operationId)
            ?: throw ProductApiException(
                "EVIDENCE_RESERVATION_UNAVAILABLE", "Receipt reservation unavailable.",
                HttpStatus.SERVICE_UNAVAILABLE, retryable = true,
            )
        if (reservation.targetType != "FINANCE_EXPENSE_RECEIPT" ||
            reservation.targetId != target.expenseDraftId ||
            reservation.organizationId != target.organizationId ||
            reservation.capturedByUserId != actorIdentityId)
            throw ProductApiException(
                "OBJECT_NOT_VISIBLE", "Receipt unavailable.", HttpStatus.NOT_FOUND,
            )
        if (!clock.instant().isBefore(reservation.uploadExpiresAt))
            throw ProductApiException(
                "EVIDENCE_UPLOAD_SESSION_EXPIRED", "Receipt upload session expired.",
                HttpStatus.CONFLICT,
            )
        val signed = runCatching {
            storage.createUploadTarget(reservation.toStorageSpec())
        }.getOrElse {
            throw ProductApiException(
                "EVIDENCE_STORAGE_UNAVAILABLE", "Receipt storage unavailable.",
                HttpStatus.SERVICE_UNAVAILABLE, retryable = true,
            )
        }
        val expiry = if (signed.expiresAt.isBefore(reservation.uploadExpiresAt))
            signed.expiresAt else reservation.uploadExpiresAt
        return ReserveEvidenceUploadResponse(
            evidence = reservation.toMetadata(),
            upload = EvidenceUploadTargetResponse(
                uploadUrl = signed.uploadUrl, requiredHeaders = signed.requiredHeaders,
                expiresAt = expiry.toString(), expectedSizeBytes = reservation.sizeBytes,
            ),
            correlationId = correlationId, replayed = result.replayed,
        )
    }

    private fun reservePoReceivingDeliveryNote(
        actorIdentityId: UUID,
        correlationId: String,
        command: ReserveCommand,
    ): ReserveEvidenceUploadResponse {
        val poDeliveryNoteTarget = poDeliveryNoteTargetProvider?.ifAvailable
            ?: throw ProductApiException(
                "EVIDENCE_TARGET_UNSUPPORTED", "Delivery-note Evidence integration is unavailable.",
                HttpStatus.NOT_FOUND,
            )
        if (command.workOrderId != null || command.supersedesEvidenceId != null ||
            command.requirementKey != "PO_DELIVERY_NOTE_BINARY" ||
            command.assertedEvidenceTypeCode != "PO_DELIVERY_NOTE"
        ) {
            throw ProductApiException(
                code = "EVIDENCE_PO_DELIVERY_NOTE_CONTRACT_MISMATCH",
                message = "Supplier delivery-note Evidence must match its restricted slot contract.",
                status = HttpStatus.UNPROCESSABLE_ENTITY,
            )
        }
        val scanClass = employeeDocumentScanClass(command.contentType)
            ?: throw ProductApiException(
                code = "EVIDENCE_CONTENT_TYPE_NOT_ALLOWED",
                message = "Supplier delivery-note uploads must be PDF or supported scanned image.",
                status = HttpStatus.UNPROCESSABLE_ENTITY,
            )
        if (command.sizeBytes !in 1..EvidenceUploadSpec.MAX_EVIDENCE_OBJECT_BYTES) {
            throw ProductApiException(
                code = "EVIDENCE_SIZE_NOT_ALLOWED",
                message = "PO delivery note exceeds the Evidence byte limit.",
                status = HttpStatus.UNPROCESSABLE_ENTITY,
            )
        }
        val target = poDeliveryNoteTarget.loadTarget(command.targetId)
            ?: throw ProductApiException(
                "OBJECT_NOT_VISIBLE", "PO receiving delivery note is not available.",
                HttpStatus.NOT_FOUND,
            )
        if (!poDeliveryNoteTarget.canReserve(actorIdentityId, target)) {
            throw ProductApiException(
                "OBJECT_NOT_VISIBLE", "PO receiving delivery note is not available.",
                HttpStatus.NOT_FOUND,
            )
        }
        val storage = requireStorage()
        val now = clock.instant()
        val evidenceId = UUID.randomUUID()
        val uploadSessionId = UUID.randomUUID()
        val spec = EvidenceUploadSpec(
            evidenceId = evidenceId,
            organizationId = target.organizationId,
            objectVersionId = UUID.randomUUID(),
            contentType = command.contentType,
            expectedSizeBytes = command.sizeBytes,
            expectedSha256Hex = command.sha256,
        )
        val expiresAt = now.plusSeconds(storageProperties.uploadExpirySeconds)
        val execution = idempotency.execute(
            spec = IdempotentCommandSpec(
                operationId = command.operationId,
                actorUserId = actorIdentityId,
                commandType = "ReserveEvidenceUpload",
                targetType = "PoReceivingDeliveryNote",
                targetId = target.receivingRecordId,
                requestFingerprint = command.fingerprint(),
                correlationId = correlationId,
            ),
        ) {
            val current = poDeliveryNoteTarget.loadTarget(command.targetId)
            if (current != target ||
                !poDeliveryNoteTarget.canReserve(actorIdentityId, target)
            ) {
                throw ProductApiException(
                    "OBJECT_NOT_VISIBLE", "PO receiving delivery note is not available.",
                    HttpStatus.NOT_FOUND,
                )
            }
            persistence.insertPoReceivingDeliveryNoteReservation(
                PoReceivingDeliveryNoteEvidenceReservationInsert(
                    evidenceId = evidenceId,
                    uploadSessionId = uploadSessionId,
                    operationId = command.operationId,
                    objectKey = spec.objectKey,
                    organizationId = current.organizationId,
                    receivingRecordId = current.receivingRecordId,
                    contentType = command.contentType,
                    originalFileName = command.originalFileName,
                    sizeBytes = command.sizeBytes,
                    sha256 = command.sha256,
                    capturedAt = command.capturedAt,
                    clientOccurredAt = command.clientOccurredAt,
                    capturedByUserId = actorIdentityId,
                    securityScanClass = scanClass,
                    uploadExpiresAt = expiresAt,
                    createdAt = now,
                ),
            )
            audit.append(
                AuditEventRecord(
                    actorUserId = actorIdentityId,
                    action = "EVIDENCE_UPLOAD_RESERVED",
                    targetType = "Evidence",
                    targetId = evidenceId,
                    newStateRef = "evidence:RESERVED",
                    occurredAt = now,
                    correlationId = correlationId,
                    reason = "PO_RECEIVING_DELIVERY_NOTE_RESERVE",
                    configRevisionRefsJson = """{"targetType":"PO_RECEIVING_DELIVERY_NOTE"}""",
                ),
            )
            IdempotentCommandOutcome(resultCode = "EVIDENCE_RESERVED")
        }
        val reservation = persistence.findReservationByOperationId(command.operationId)
            ?: throw ProductApiException(
                "EVIDENCE_RESERVATION_UNAVAILABLE", "Delivery-note Evidence reservation unavailable.",
                HttpStatus.SERVICE_UNAVAILABLE, retryable = true,
            )
        if (reservation.targetType != "PO_RECEIVING_DELIVERY_NOTE" ||
            reservation.targetId != target.receivingRecordId ||
            reservation.organizationId != target.organizationId
        ) {
            throw ProductApiException(
                "OBJECT_NOT_VISIBLE", "Delivery-note Evidence is not available.",
                HttpStatus.NOT_FOUND,
            )
        }
        if (!clock.instant().isBefore(reservation.uploadExpiresAt)) {
            throw ProductApiException(
                "EVIDENCE_UPLOAD_SESSION_EXPIRED", "Evidence upload session expired.",
                HttpStatus.CONFLICT,
            )
        }
        val signed = runCatching {
            storage.createUploadTarget(reservation.toStorageSpec())
        }.getOrElse {
            throw ProductApiException(
                "EVIDENCE_STORAGE_UNAVAILABLE", "Evidence storage temporarily unavailable.",
                HttpStatus.SERVICE_UNAVAILABLE, retryable = true,
            )
        }
        val expiry = if (signed.expiresAt.isBefore(reservation.uploadExpiresAt))
            signed.expiresAt else reservation.uploadExpiresAt
        return ReserveEvidenceUploadResponse(
            evidence = reservation.toMetadata(),
            upload = EvidenceUploadTargetResponse(
                uploadUrl = signed.uploadUrl,
                requiredHeaders = signed.requiredHeaders,
                expiresAt = expiry.toString(),
                expectedSizeBytes = reservation.sizeBytes,
            ),
            correlationId = correlationId,
            replayed = execution.replayed,
        )
    }

    /** Canonical Evidence upload/finalization, with Commercial-owned authorization. */
    private fun reserveCommercialDocument(
        actorIdentityId: UUID,
        correlationId: String,
        command: ReserveCommand,
    ): ReserveEvidenceUploadResponse {
        val targetEvidenceType = when (command.targetType) {
            "COMMERCIAL_CONTRACT" -> "COMMERCIAL_SIGNED_CONTRACT"
            "COMMERCIAL_CLIENT_PO" -> "COMMERCIAL_CLIENT_PO"
            else -> throw ProductApiException(
                code = "EVIDENCE_TARGET_UNSUPPORTED",
                message = "Unsupported document target.",
                status = HttpStatus.UNPROCESSABLE_ENTITY,
            )
        }
        if (
            command.workOrderId != null || command.supersedesEvidenceId != null ||
            command.requirementKey != targetEvidenceType ||
            command.assertedEvidenceTypeCode != targetEvidenceType
        ) {
            throw ProductApiException(
                code = "COMMERCIAL_EVIDENCE_CONTRACT_MISMATCH",
                message = "Commercial Evidence must use its exact target and document type.",
                status = HttpStatus.UNPROCESSABLE_ENTITY,
            )
        }
        val scanClass = when (command.contentType) {
            "application/pdf" -> "SIGNED_DOCUMENT"
            "image/jpeg", "image/png" -> "NATIVE_MEDIA"
            else -> throw ProductApiException(
                code = "EVIDENCE_CONTENT_TYPE_NOT_ALLOWED",
                message = "Signed contracts and client POs must use a PDF or supported image.",
                status = HttpStatus.UNPROCESSABLE_ENTITY,
            )
        }
        if (command.sizeBytes !in 1..EvidenceUploadSpec.MAX_EVIDENCE_OBJECT_BYTES) {
            throw ProductApiException(
                code = "EVIDENCE_SIZE_NOT_ALLOWED",
                message = "Commercial document exceeds the allowed Evidence limit.",
                status = HttpStatus.UNPROCESSABLE_ENTITY,
            )
        }
        val target = commercialEvidenceTarget.loadTarget(command.targetType, command.targetId)
            ?: throw ProductApiException(
                code = "OBJECT_NOT_VISIBLE",
                message = "The requested commercial document is unavailable.",
                status = HttpStatus.NOT_FOUND,
            )
        if (!target.mayReserve || !commercialEvidenceTarget.canAccess(actorIdentityId, target)) {
            throw ProductApiException(
                code = "OBJECT_NOT_VISIBLE",
                message = "Commercial document upload is not authorized.",
                status = HttpStatus.NOT_FOUND,
            )
        }

        val storage = requireStorage()
        val now = clock.instant()
        val evidenceId = UUID.randomUUID()
        val uploadSessionId = UUID.randomUUID()
        val spec = EvidenceUploadSpec(
            evidenceId = evidenceId,
            organizationId = target.organizationId,
            objectVersionId = UUID.randomUUID(),
            contentType = command.contentType,
            expectedSizeBytes = command.sizeBytes,
            expectedSha256Hex = command.sha256,
        )
        val expiry = now.plusSeconds(storageProperties.uploadExpirySeconds)

        val execution = idempotency.execute(
            spec = IdempotentCommandSpec(
                operationId = command.operationId,
                actorUserId = actorIdentityId,
                commandType = "ReserveEvidenceUpload",
                targetType = "CommercialDocument",
                targetId = command.targetId,
                requestFingerprint = command.fingerprint(),
                correlationId = correlationId,
            ),
        ) {
            val current = commercialEvidenceTarget.loadTarget(command.targetType, command.targetId)
            if (
                current == null || current.organizationId != target.organizationId ||
                !current.mayReserve || !commercialEvidenceTarget.canAccess(actorIdentityId, current)
            ) {
                throw ProductApiException(
                    code = "OBJECT_NOT_VISIBLE",
                    message = "Commercial document upload is no longer authorized.",
                    status = HttpStatus.NOT_FOUND,
                )
            }
            persistence.insertCommercialReservation(
                CommercialEvidenceReservationInsert(
                    evidenceId = evidenceId,
                    uploadSessionId = uploadSessionId,
                    operationId = command.operationId,
                    organizationId = current.organizationId,
                    targetType = command.targetType,
                    targetId = command.targetId,
                    evidenceTypeCode = targetEvidenceType,
                    objectKey = spec.objectKey,
                    contentType = command.contentType,
                    originalFileName = command.originalFileName,
                    sizeBytes = command.sizeBytes,
                    sha256 = command.sha256,
                    capturedAt = command.capturedAt,
                    clientOccurredAt = command.clientOccurredAt,
                    capturedByUserId = actorIdentityId,
                    securityScanClass = scanClass,
                    uploadExpiresAt = expiry,
                    createdAt = now,
                ),
            )
            audit.append(
                AuditEventRecord(
                    actorUserId = actorIdentityId,
                    action = "EVIDENCE_UPLOAD_RESERVED",
                    targetType = "Evidence",
                    targetId = evidenceId,
                    newStateRef = "evidence:RESERVED",
                    occurredAt = now,
                    correlationId = correlationId,
                    reason = "COMMERCIAL_AWARD_EVIDENCE_RESERVE",
                    configRevisionRefsJson = """{"targetType":"COMMERCIAL_AWARD"}""",
                ),
            )
            IdempotentCommandOutcome(resultCode = "EVIDENCE_RESERVED")
        }

        val reservation = persistence.findReservationByOperationId(command.operationId)
            ?: throw ProductApiException(
                code = "EVIDENCE_RESERVATION_UNAVAILABLE",
                message = "Commercial Evidence upload reservation unavailable.",
                status = HttpStatus.SERVICE_UNAVAILABLE,
                retryable = true,
            )
        if (!clock.instant().isBefore(reservation.uploadExpiresAt)) {
            throw ProductApiException(
                code = "EVIDENCE_UPLOAD_SESSION_EXPIRED",
                message = "Commercial Evidence upload session expired.",
                status = HttpStatus.CONFLICT,
            )
        }
        val signed = runCatching {
            storage.createUploadTarget(reservation.toStorageSpec())
        }.getOrElse {
            throw ProductApiException(
                code = "EVIDENCE_STORAGE_UNAVAILABLE",
                message = "Commercial Evidence upload storage is unavailable.",
                status = HttpStatus.SERVICE_UNAVAILABLE,
                retryable = true,
            )
        }
        val expiresAt = if (signed.expiresAt.isBefore(reservation.uploadExpiresAt)) {
            signed.expiresAt
        } else {
            reservation.uploadExpiresAt
        }
        return ReserveEvidenceUploadResponse(
            evidence = reservation.toMetadata(),
            upload = EvidenceUploadTargetResponse(
                uploadUrl = signed.uploadUrl,
                requiredHeaders = signed.requiredHeaders,
                expiresAt = expiresAt.toString(),
                expectedSizeBytes = reservation.sizeBytes,
            ),
            correlationId = correlationId,
            replayed = execution.replayed,
        )
    }

    private fun reserveEmployeeDocument(
        actorIdentityId: UUID,
        correlationId: String,
        command: ReserveCommand,
    ): ReserveEvidenceUploadResponse {
        if (command.workOrderId != null) {
            throw ProductApiException(
                code =
                    "EVIDENCE_WORK_ORDER_TARGET_MISMATCH",
                message =
                    "workOrderId must be absent for EmployeeDocument Evidence.",
                status =
                    HttpStatus.UNPROCESSABLE_ENTITY,
            )
        }

        if (
            command.requirementKey !=
            EMPLOYEE_DOCUMENT_REQUIREMENT_KEY ||
            command.assertedEvidenceTypeCode !=
            EMPLOYEE_DOCUMENT_EVIDENCE_TYPE
        ) {
            throw ProductApiException(
                code =
                    "EVIDENCE_EMPLOYEE_DOCUMENT_CONTRACT_MISMATCH",
                message =
                    "EmployeeDocument Evidence must use the fixed HR document requirement/type.",
                status =
                    HttpStatus.UNPROCESSABLE_ENTITY,
            )
        }

        if (command.supersedesEvidenceId != null) {
            throw ProductApiException(
                code =
                    "EVIDENCE_SUPERSEDES_UNSUPPORTED",
                message =
                    "EmployeeDocument Evidence superseding is not supported in this slice.",
                status =
                    HttpStatus.UNPROCESSABLE_ENTITY,
            )
        }

        val securityScanClass =
            employeeDocumentScanClass(
                command.contentType,
            )
                ?: throw ProductApiException(
                    code =
                        "EVIDENCE_CONTENT_TYPE_NOT_ALLOWED",
                    message =
                        "This content type is not allowed for EmployeeDocument Evidence.",
                    status =
                        HttpStatus.UNPROCESSABLE_ENTITY,
                )

        if (
            command.sizeBytes !in
            1..EvidenceUploadSpec.MAX_EVIDENCE_OBJECT_BYTES
        ) {
            throw ProductApiException(
                code =
                    "EVIDENCE_SIZE_NOT_ALLOWED",
                message =
                    "Evidence size exceeds the EmployeeDocument limit.",
                status =
                    HttpStatus.UNPROCESSABLE_ENTITY,
            )
        }

        val target =
            employeeDocumentTarget
                .loadTarget(
                    command.targetId,
                )
                ?: throw ProductApiException(
                    code = "OBJECT_NOT_VISIBLE",
                    message =
                        "The requested EmployeeDocument is not available.",
                    status =
                        HttpStatus.NOT_FOUND,
                )

        if (
            target.verificationState ==
            "REJECTED" ||
            !employeeDocumentTarget
                .canReserve(
                    identityId =
                        actorIdentityId,
                    target = target,
                )
        ) {
            throw ProductApiException(
                code = "OBJECT_NOT_VISIBLE",
                message =
                    "The requested EmployeeDocument is not available.",
                status =
                    HttpStatus.NOT_FOUND,
            )
        }

        val storage =
            requireStorage()
        val now =
            clock.instant()
        val evidenceId =
            UUID.randomUUID()
        val uploadSessionId =
            UUID.randomUUID()
        val spec =
            EvidenceUploadSpec(
                evidenceId = evidenceId,
                organizationId =
                    target.organizationId,
                objectVersionId =
                    UUID.randomUUID(),
                contentType =
                    command.contentType,
                expectedSizeBytes =
                    command.sizeBytes,
                expectedSha256Hex =
                    command.sha256,
            )
        val expiresAt =
            now.plusSeconds(
                storageProperties
                    .uploadExpirySeconds,
            )

        val execution =
            idempotency.execute(
                spec =
                    IdempotentCommandSpec(
                        operationId =
                            command.operationId,
                        actorUserId =
                            actorIdentityId,
                        commandType =
                            "ReserveEvidenceUpload",
                        targetType =
                            "EmployeeDocument",
                        targetId =
                            target.documentId,
                        requestFingerprint =
                            command.fingerprint(),
                        correlationId =
                            correlationId,
                    ),
            ) {
                val fresh =
                    employeeDocumentTarget
                        .loadTarget(
                            command.targetId,
                        )
                        ?: throw ProductApiException(
                            code =
                                "OBJECT_NOT_VISIBLE",
                            message =
                                "The requested EmployeeDocument is not available.",
                            status =
                                HttpStatus.NOT_FOUND,
                        )

                if (
                    fresh.verificationState ==
                    "REJECTED" ||
                    !employeeDocumentTarget
                        .canReserve(
                            identityId =
                                actorIdentityId,
                            target = fresh,
                        )
                ) {
                    throw ProductApiException(
                        code =
                            "OBJECT_NOT_VISIBLE",
                        message =
                            "The requested EmployeeDocument is not available.",
                        status =
                            HttpStatus.NOT_FOUND,
                    )
                }

                if (fresh.evidenceId != null) {
                    throw ProductApiException(
                        code =
                            "EMPLOYEE_DOCUMENT_EVIDENCE_EXISTS",
                        message =
                            "This EmployeeDocument already has Evidence attached.",
                        status =
                            HttpStatus.CONFLICT,
                    )
                }

                persistence
                    .insertEmployeeDocumentReservation(
                        EmployeeDocumentEvidenceReservationInsert(
                            evidenceId =
                                evidenceId,
                            uploadSessionId =
                                uploadSessionId,
                            operationId =
                                command.operationId,
                            objectKey =
                                spec.objectKey,
                            organizationId =
                                fresh.organizationId,
                            employeeDocumentId =
                                fresh.documentId,
                            evidenceTypeCode =
                                EMPLOYEE_DOCUMENT_EVIDENCE_TYPE,
                            contentType =
                                command.contentType,
                            originalFileName =
                                command.originalFileName,
                            sizeBytes =
                                command.sizeBytes,
                            sha256 =
                                command.sha256,
                            capturedAt =
                                command.capturedAt,
                            clientOccurredAt =
                                command.clientOccurredAt,
                            capturedByUserId =
                                actorIdentityId,
                            securityScanClass =
                                securityScanClass,
                            uploadExpiresAt =
                                expiresAt,
                            createdAt = now,
                        ),
                    )

                if (
                    !employeeDocumentTarget
                        .attachEvidence(
                            documentId =
                                fresh.documentId,
                            evidenceId =
                                evidenceId,
                            at = now,
                        )
                ) {
                    throw ProductApiException(
                        code =
                            "EMPLOYEE_DOCUMENT_EVIDENCE_CONFLICT",
                        message =
                            "EmployeeDocument Evidence attachment changed concurrently.",
                        status =
                            HttpStatus.CONFLICT,
                    )
                }

                audit.append(
                    AuditEventRecord(
                        actorUserId =
                            actorIdentityId,
                        action =
                            "EVIDENCE_UPLOAD_RESERVED",
                        targetType =
                            "Evidence",
                        targetId =
                            evidenceId,
                        newStateRef =
                            "evidence:RESERVED",
                        occurredAt = now,
                        correlationId =
                            correlationId,
                        reason =
                            "EMPLOYEE_DOCUMENT_EVIDENCE_RESERVE",
                        configRevisionRefsJson =
                            """{"targetType":"EMPLOYEE_DOCUMENT"}""",
                    ),
                )

                IdempotentCommandOutcome(
                    resultCode =
                        "EVIDENCE_RESERVED",
                )
            }

        val reservation =
            persistence
                .findReservationByOperationId(
                    command.operationId,
                )
                ?: throw ProductApiException(
                    code =
                        "EVIDENCE_RESERVATION_UNAVAILABLE",
                    message =
                        "The Evidence reservation is unavailable.",
                    status =
                        HttpStatus.SERVICE_UNAVAILABLE,
                    retryable = true,
                )

        if (
            !clock.instant().isBefore(
                reservation.uploadExpiresAt,
            )
        ) {
            throw ProductApiException(
                code =
                    "EVIDENCE_UPLOAD_SESSION_EXPIRED",
                message =
                    "The Evidence upload session has expired.",
                status =
                    HttpStatus.CONFLICT,
            )
        }

        val signed =
            runCatching {
                storage.createUploadTarget(
                    reservation.toStorageSpec(),
                )
            }.getOrElse {
                throw ProductApiException(
                    code =
                        "EVIDENCE_STORAGE_UNAVAILABLE",
                    message =
                        "Evidence storage is temporarily unavailable.",
                    status =
                        HttpStatus.SERVICE_UNAVAILABLE,
                    retryable = true,
                )
            }

        val effectiveExpiry =
            if (
                signed.expiresAt.isBefore(
                    reservation.uploadExpiresAt,
                )
            ) {
                signed.expiresAt
            } else {
                reservation.uploadExpiresAt
            }

        return ReserveEvidenceUploadResponse(
            evidence =
                reservation.toMetadata(),
            upload =
                EvidenceUploadTargetResponse(
                    uploadUrl =
                        signed.uploadUrl,
                    requiredHeaders =
                        signed.requiredHeaders,
                    expiresAt =
                        effectiveExpiry
                            .toString(),
                    expectedSizeBytes =
                        reservation.sizeBytes,
                ),
            correlationId =
                correlationId,
            replayed =
                execution.replayed,
        )
    }

    fun finalize(
        actorIdentityId: UUID,
        evidenceId: UUID,
        idempotencyHeader: String?,
        correlationId: String,
        request: FinalizeEvidenceRequest,
    ): FinalizeEvidenceResponse {
        val operationId =
            request.operationId.toUuid(
                "INVALID_OPERATION_ID",
            )
        val uploadSessionId =
            request.uploadSessionId.toUuid(
                "INVALID_UPLOAD_SESSION_ID",
            )
        val sha256 =
            normalizeSha256(
                request.expectedSha256,
            )

        IdempotencyKeyContract.requireMatches(
            rawHeader = idempotencyHeader,
            operationId = operationId,
        )

        val record =
            persistence.loadFinalizeRecord(
                evidenceId = evidenceId,
                uploadSessionId = uploadSessionId,
            ) ?: throw ProductApiException(
                code = "OBJECT_NOT_VISIBLE",
                message = "The requested Evidence is not available.",
                status = HttpStatus.NOT_FOUND,
            )

        if (
            request.expectedSizeBytes != record.expectedSizeBytes ||
            sha256 != record.expectedSha256
        ) {
            throw ProductApiException(
                code = "EVIDENCE_FINALIZE_EXPECTATION_MISMATCH",
                message = "Finalize expectations do not match the reservation.",
                status = HttpStatus.CONFLICT,
            )
        }

        if (
            record.targetType == "WORK_ORDER" &&
            record.workOrderLifecycleState in
                TERMINAL_WORK_STATES
        ) {
            throw ProductApiException(
                code = "WORK_ORDER_TERMINAL",
                message =
                    "Evidence cannot be finalized for a terminal WorkOrder.",
                status = HttpStatus.CONFLICT,
            )
        }

        if (
            !canAccessTarget(
                actorIdentityId =
                    actorIdentityId,
                record = record,
                operation =
                    EvidenceTargetOperation.FINALIZE,
            )
        ) {
            throw ProductApiException(
                code = "OBJECT_NOT_VISIBLE",
                message =
                    "The requested Evidence is not available.",
                status = HttpStatus.NOT_FOUND,
            )
        }

        if (
            record.storageState in FINAL_EVIDENCE_STATES
        ) {
            val execution =
                idempotency.execute(
                    spec =
                        finalizeSpec(
                            operationId = operationId,
                            actorIdentityId = actorIdentityId,
                            record = record,
                            sha256 = sha256,
                            sizeBytes = request.expectedSizeBytes,
                            correlationId = correlationId,
                        ),
                ) {
                    IdempotentCommandOutcome(
                        resultCode =
                            "EVIDENCE_" + record.storageState,
                    )
                }

            return FinalizeEvidenceResponse(
                evidence = record.toMetadata(),
                correlationId = correlationId,
                replayed = execution.replayed,
            )
        }

        if (
            !clock.instant().isBefore(
                record.uploadExpiresAt,
            )
        ) {
            throw ProductApiException(
                code = "EVIDENCE_UPLOAD_SESSION_EXPIRED",
                message = "The Evidence upload session has expired.",
                status = HttpStatus.CONFLICT,
            )
        }

        val storage = requireStorage()
        val verification =
            storage.verifyUploadedObject(
                record.toStorageSpec(),
            )

        val targetState =
            when (verification) {
                is EvidenceFinalizeVerification.Retryable ->
                    throw ProductApiException(
                        code = verification.code,
                        message = "Evidence storage verification should be retried.",
                        status = HttpStatus.SERVICE_UNAVAILABLE,
                        retryable = true,
                    )

                is EvidenceFinalizeVerification.Rejected ->
                    "REJECTED"

                is EvidenceFinalizeVerification.Verified ->
                    verifiedTargetState(
                        record = record,
                        verification = verification,
                    )
            }

        val now = clock.instant()
        val execution =
            idempotency.execute(
                spec =
                    finalizeSpec(
                        operationId = operationId,
                        actorIdentityId = actorIdentityId,
                        record = record,
                        sha256 = sha256,
                        sizeBytes = request.expectedSizeBytes,
                        correlationId = correlationId,
                    ),
            ) {
                val updated =
                    persistence.markFinalized(
                        evidenceId = record.evidenceId,
                        uploadSessionId = record.uploadSessionId,
                        expectedEvidenceVersion = record.evidenceVersion,
                        expectedUploadSessionVersion = record.uploadSessionVersion,
                        state = targetState,
                        finalizedAt = now,
                    )

                if (!updated) {
                    throw ProductApiException(
                        code = "VERSION_CONFLICT",
                        message = "Evidence changed before finalize completed.",
                        status = HttpStatus.CONFLICT,
                    )
                }

                if (
                    record.targetType ==
                    "WORK_ORDER"
                ) {
                    events.publishEvent(
                        EvidenceTerminalStateChanged(
                            eventId =
                                UUID.randomUUID(),
                            evidenceId =
                                record.evidenceId,
                            workOrderId =
                                requireNotNull(
                                    record.workOrderId,
                                ),
                            sourceVersion =
                                record.evidenceVersion +
                                    1,
                            storageState =
                                targetState,
                            evidenceTypeCode =
                                record.evidenceTypeCode,
                            evidenceRequirementKey =
                                requireNotNull(
                                    record
                                        .evidenceRequirementKey,
                                ),
                            classificationCode =
                                record.classificationCode,
                            actorIdentityId =
                                actorIdentityId,
                            occurredAt = now,
                            correlationId =
                                correlationId,
                        ),
                    )
                }

                audit.append(
                    AuditEventRecord(
                        actorUserId = actorIdentityId,
                        action = "EVIDENCE_UPLOAD_FINALIZED",
                        targetType = "Evidence",
                        targetId = record.evidenceId,
                        previousStateRef =
                            "evidence:" + record.storageState,
                        newStateRef =
                            "evidence:$targetState",
                        occurredAt = now,
                        correlationId = correlationId,
                        reason =
                            "BINARY_VERIFICATION_" + targetState,
                        configRevisionRefsJson =
                            record.auditContextJson(),
                    ),
                )

                IdempotentCommandOutcome(
                    resultCode =
                        "EVIDENCE_$targetState",
                )
            }

        val updated =
            persistence.loadFinalizeRecord(
                evidenceId = record.evidenceId,
                uploadSessionId = record.uploadSessionId,
            ) ?: throw ProductApiException(
                code = "EVIDENCE_FINALIZE_RESULT_UNAVAILABLE",
                message = "The finalized Evidence is unavailable.",
                status = HttpStatus.SERVICE_UNAVAILABLE,
                retryable = true,
            )

        return FinalizeEvidenceResponse(
            evidence = updated.toMetadata(),
            correlationId = correlationId,
            replayed = execution.replayed,
        )
    }

    fun metadata(
        actorIdentityId: UUID,
        evidenceId: UUID,
    ): EvidenceMetadataResponse {
        val record =
            loadVisibleEvidence(
                actorIdentityId =
                    actorIdentityId,
                evidenceId =
                    evidenceId,
                download = false,
            )

        return record.toMetadata()
    }

    fun downloadTarget(
        actorIdentityId: UUID,
        evidenceId: UUID,
        correlationId: String,
    ): EvidenceDownloadTargetResponse {
        val record =
            loadVisibleEvidence(
                actorIdentityId =
                    actorIdentityId,
                evidenceId =
                    evidenceId,
                download = true,
            )

        if (
            record.storageState != "READY"
        ) {
            throw ProductApiException(
                code =
                    "EVIDENCE_NOT_DOWNLOADABLE",
                message =
                    "Evidence is not ready for download.",
                status =
                    HttpStatus.CONFLICT,
            )
        }

        if (
            record.classificationCode
                .equals(
                    "HIGHLY_RESTRICTED",
                    ignoreCase = true,
                )
        ) {
            throw ProductApiException(
                code =
                    "EVIDENCE_DIRECT_DOWNLOAD_FORBIDDEN",
                message =
                    "This Evidence classification requires protected delivery.",
                status =
                    HttpStatus.valueOf(422),
            )
        }

        val storage =
            requireStorage()
        val signed =
            runCatching {
                storage.createDownloadTarget(
                    record.objectKey,
                )
            }.getOrElse {
                throw ProductApiException(
                    code =
                        "EVIDENCE_STORAGE_UNAVAILABLE",
                    message =
                        "Evidence storage is temporarily unavailable.",
                    status =
                        HttpStatus.SERVICE_UNAVAILABLE,
                    retryable = true,
                )
            }

        audit.append(
            AuditEventRecord(
                actorUserId =
                    actorIdentityId,
                action =
                    "EVIDENCE_DOWNLOAD_TARGET_ISSUED",
                targetType =
                    "Evidence",
                targetId =
                    record.evidenceId,
                newStateRef =
                    "evidence:" +
                        record.storageState,
                occurredAt =
                    clock.instant(),
                correlationId =
                    correlationId,
                reason =
                    "PRIVATE_SIGNED_DOWNLOAD",
                configRevisionRefsJson =
                    """{"evidencePolicyId":"${record.evidencePolicyId}","revision":${record.evidencePolicyRevision}}""",
            ),
        )

        return EvidenceDownloadTargetResponse(
            downloadUrl =
                signed.downloadUrl,
            expiresAt =
                signed.expiresAt.toString(),
            correlationId =
                correlationId,
        )
    }

    private fun loadVisibleEvidence(
        actorIdentityId: UUID,
        evidenceId: UUID,
        download: Boolean,
    ): EvidenceFinalizeRecord {
        val record =
            persistence.loadEvidenceRecord(
                evidenceId,
            ) ?: throw ProductApiException(
                code = "OBJECT_NOT_VISIBLE",
                message =
                    "The requested Evidence is not available.",
                status = HttpStatus.NOT_FOUND,
            )

        val allowed =
            canAccessTarget(
                actorIdentityId =
                    actorIdentityId,
                record = record,
                operation =
                    if (download) {
                        EvidenceTargetOperation.DOWNLOAD
                    } else {
                        EvidenceTargetOperation.VIEW
                    },
            )

        if (!allowed) {
            throw ProductApiException(
                code = "OBJECT_NOT_VISIBLE",
                message =
                    "The requested Evidence is not available.",
                status = HttpStatus.NOT_FOUND,
            )
        }

        return record
    }

    private fun canAccessTarget(
        actorIdentityId: UUID,
        record: EvidenceFinalizeRecord,
        operation: EvidenceTargetOperation,
    ): Boolean =
        when (record.targetType) {
            "WORK_ORDER" -> {
                val workOrderId =
                    record.workOrderId
                        ?: return false

                when (operation) {
                    EvidenceTargetOperation.FINALIZE ->
                        targetAuthorization
                            .canFinalizeEvidence(
                                identityId =
                                    actorIdentityId,
                                evidenceId =
                                    record.evidenceId,
                                workOrderId =
                                    workOrderId,
                                creatorIdentityId =
                                    record.capturedByUserId,
                            )

                    EvidenceTargetOperation.VIEW ->
                        targetAuthorization
                            .canViewEvidence(
                                identityId =
                                    actorIdentityId,
                                evidenceId =
                                    record.evidenceId,
                                workOrderId =
                                    workOrderId,
                                creatorIdentityId =
                                    record.capturedByUserId,
                            )

                    EvidenceTargetOperation.DOWNLOAD ->
                        targetAuthorization
                            .canDownloadEvidence(
                                identityId =
                                    actorIdentityId,
                                evidenceId =
                                    record.evidenceId,
                                workOrderId =
                                    workOrderId,
                                creatorIdentityId =
                                    record.capturedByUserId,
                            )
                }
            }

            "EMPLOYEE_DOCUMENT" -> {
                val target =
                    employeeDocumentTarget
                        .loadTarget(
                            record.targetId,
                        )
                        ?: return false

                target.organizationId ==
                    record.organizationId &&
                    target.evidenceId ==
                    record.evidenceId &&
                    target.verificationState !=
                    "REJECTED" &&
                    employeeDocumentTarget
                        .canAccess(
                            identityId =
                                actorIdentityId,
                            target = target,
                        )
            }

            "SUPPLIER_QUOTE_DOCUMENT" -> {
                val target = supplierQuoteDocumentTarget.loadTarget(record.targetId)
                    ?: return false
                target.purchasingOrganizationId == record.organizationId &&
                    supplierQuoteDocumentTarget.canAccess(actorIdentityId, target)
            }

            "SERVICE_QUOTE_DOCUMENT" -> {
                val serviceTarget = serviceQuoteDocumentTargetProvider?.ifAvailable ?: return false
                val target = serviceTarget.loadTarget(record.targetId) ?: return false
                target.purchasingOrganizationId == record.organizationId &&
                    serviceTarget.canAccess(actorIdentityId, target)
            }

            "SERVICE_PURCHASE_DOCUMENT" -> {
                val serviceTarget = servicePurchaseDocumentTargetProvider?.ifAvailable ?: return false
                val target = serviceTarget.loadTarget(record.targetId) ?: return false
                target.organizationId == record.organizationId &&
                    serviceTarget.canAccess(actorIdentityId, target)
            }

            "SUPPLIER_INVOICE_DOCUMENT" -> {
                val financeTarget = invoiceDocumentTargetProvider?.ifAvailable ?: return false
                val target = financeTarget.loadTarget(record.targetId) ?: return false
                target.purchasingOrganizationId == record.organizationId &&
                    financeTarget.canAccess(actorIdentityId, target)
            }

            "FINANCE_EXPENSE_RECEIPT" -> {
                val port = financeExpenseReceiptTargetProvider?.ifAvailable ?: return false
                val target = port.loadTarget(record.targetId) ?: return false
                target.organizationId == record.organizationId &&
                    target.createdByUserId == record.capturedByUserId &&
                    when (operation) {
                        EvidenceTargetOperation.FINALIZE ->
                            port.canReserve(actorIdentityId, target)
                        EvidenceTargetOperation.VIEW,
                        EvidenceTargetOperation.DOWNLOAD ->
                            port.canAccess(actorIdentityId, target)
                    }
            }

            "PO_RECEIVING_DELIVERY_NOTE" -> {
                val deliveryTarget = poDeliveryNoteTargetProvider?.ifAvailable ?: return false
                val target = deliveryTarget.loadTarget(record.targetId) ?: return false
                target.organizationId == record.organizationId &&
                    deliveryTarget.canAccess(actorIdentityId, target)
            }

            "COMMERCIAL_CONTRACT", "COMMERCIAL_CLIENT_PO" -> {
                val target = commercialEvidenceTarget.loadTarget(record.targetType, record.targetId)
                target != null && target.organizationId == record.organizationId &&
                    record.classificationCode == "HIGHLY_RESTRICTED" &&
                    record.clientVisibilityMode == "INTERNAL_ONLY" &&
                    record.evidenceTypeCode == (
                        if (record.targetType == "COMMERCIAL_CONTRACT")
                            "COMMERCIAL_SIGNED_CONTRACT" else "COMMERCIAL_CLIENT_PO"
                    ) &&
                    commercialEvidenceTarget.canAccess(actorIdentityId, target)
            }

            else -> false
        }

    private fun EvidenceFinalizeRecord
        .auditContextJson(): String =
        if (
            targetType == "WORK_ORDER" &&
            evidencePolicyId != null &&
            evidencePolicyRevision != null
        ) {
            """{"evidencePolicyId":"$evidencePolicyId","revision":$evidencePolicyRevision}"""
        } else {
            """{"targetType":"$targetType"}"""
        }

    private enum class EvidenceTargetOperation {
        FINALIZE,
        VIEW,
        DOWNLOAD,
    }

    private fun validateReserveAgainstPolicy(
        command: ReserveCommand,
        policy: WorkOrderEvidencePolicySnapshot,
    ) {
        if (
            policy.lifecycleState in TERMINAL_WORK_STATES
        ) {
            throw ProductApiException(
                code = "WORK_ORDER_TERMINAL",
                message = "Evidence cannot be reserved for a terminal WorkOrder.",
                status = HttpStatus.CONFLICT,
            )
        }

        if (
            command.assertedEvidenceTypeCode !=
                policy.evidenceTypeCode
        ) {
            throw ProductApiException(
                code = "EVIDENCE_TYPE_POLICY_MISMATCH",
                message = "Evidence type does not match the bound WorkOrder policy.",
                status = HttpStatus.UNPROCESSABLE_ENTITY,
            )
        }

        if (
            command.contentType !in
                policy.allowedContentTypes
        ) {
            throw ProductApiException(
                code = "EVIDENCE_CONTENT_TYPE_NOT_ALLOWED",
                message = "This content type is not allowed by the bound Evidence policy.",
                status = HttpStatus.UNPROCESSABLE_ENTITY,
            )
        }

        val maxBytes =
            minOf(
                policy.systemMaxSizeBytes,
                EvidenceUploadSpec.MAX_EVIDENCE_OBJECT_BYTES,
            )
        if (
            command.sizeBytes !in 1..maxBytes
        ) {
            throw ProductApiException(
                code = "EVIDENCE_SIZE_NOT_ALLOWED",
                message = "Evidence size exceeds the bound policy limit.",
                status = HttpStatus.UNPROCESSABLE_ENTITY,
            )
        }
    }

    private fun verifiedTargetState(
        record: EvidenceFinalizeRecord,
        verification: EvidenceFinalizeVerification.Verified,
    ): String =
        when (record.securityScanClass) {
            "ARBITRARY_FILE" ->
                "QUARANTINED"

            "GENERATED_TRUSTED_FORMAT" ->
                "READY"

            "NATIVE_MEDIA",
            "SIGNED_DOCUMENT" -> {
                val detected =
                    verification.detectedContentType
                        ?.lowercase()

                if (
                    detected !=
                    record.contentType.lowercase()
                ) {
                    "REJECTED"
                } else {
                    "READY"
                }
            }

            else ->
                "REJECTED"
        }

    private fun finalizeSpec(
        operationId: UUID,
        actorIdentityId: UUID,
        record: EvidenceFinalizeRecord,
        sha256: String,
        sizeBytes: Long,
        correlationId: String,
    ): IdempotentCommandSpec =
        IdempotentCommandSpec(
            operationId = operationId,
            actorUserId = actorIdentityId,
            commandType = "FinalizeEvidence",
            targetType = "Evidence",
            targetId = record.evidenceId,
            requestFingerprint =
                IdempotencyKeyContract.fingerprint(
                    listOf(
                        record.evidenceId,
                        record.uploadSessionId,
                        sha256,
                        sizeBytes,
                    ).joinToString("|"),
                ),
            correlationId = correlationId,
        )

    private fun requireStorage(): EvidenceObjectStoragePort =
        storageAccess.current()
            ?: throw ProductApiException(
                code = "EVIDENCE_STORAGE_UNAVAILABLE",
                message = "Evidence storage is not configured.",
                status = HttpStatus.SERVICE_UNAVAILABLE,
                retryable = true,
            )

    private fun ReserveEvidenceUploadRequest.toReserveCommand(): ReserveCommand {
        val normalizedTarget =
            targetType.trim().uppercase()

        return ReserveCommand(
            operationId =
                operationId.toUuid(
                    "INVALID_OPERATION_ID",
                ),
            targetType =
                normalizedTarget,
            targetId =
                targetId.toUuid(
                    "INVALID_TARGET_ID",
                ),
            workOrderId =
                workOrderId
                    ?.takeIf { it.isNotBlank() }
                    ?.toUuid(
                        "INVALID_WORK_ORDER_ID",
                    ),
            requirementKey =
                evidenceRequirementKey
                    .trim()
                    .takeIf {
                        it.isNotEmpty() &&
                            it.length <= 64
                    }
                    ?: malformed(
                        "INVALID_EVIDENCE_REQUIREMENT_KEY",
                    ),
            assertedEvidenceTypeCode =
                evidenceTypeCode
                    .trim()
                    .takeIf {
                        it.isNotEmpty() &&
                            it.length <= 64
                    }
                    ?: malformed(
                        "INVALID_EVIDENCE_TYPE",
                    ),
            contentType =
                normalizeContentType(
                    contentType,
                ),
            originalFileName =
                sanitizeFileName(
                    originalFileName,
                ),
            sizeBytes = sizeBytes,
            sha256 =
                normalizeSha256(
                    expectedSha256,
                ),
            capturedAt =
                capturedAt.toInstant(
                    "INVALID_CAPTURED_AT",
                ),
            clientOccurredAt =
                clientOccurredAt
                    ?.takeIf { it.isNotBlank() }
                    ?.toInstant(
                        "INVALID_CLIENT_OCCURRED_AT",
                    ),
            supersedesEvidenceId =
                supersedesEvidenceId
                    ?.takeIf { it.isNotBlank() }
                    ?.toUuid(
                        "INVALID_SUPERSEDES_EVIDENCE_ID",
                    ),
        )
    }

    private fun ReserveCommand.fingerprint(): String =
        IdempotencyKeyContract.fingerprint(
            listOf(
                targetType,
                targetId,
                workOrderId,
                requirementKey,
                assertedEvidenceTypeCode,
                contentType,
                originalFileName,
                sizeBytes,
                sha256,
                capturedAt,
                clientOccurredAt,
                supersedesEvidenceId,
            ).joinToString("|") {
                it?.toString() ?: ""
            },
        )

    private fun EvidenceReservationRecord.toStorageSpec(): EvidenceUploadSpec =
        EvidenceUploadSpec(
            evidenceId = evidenceId,
            organizationId = organizationId,
            objectVersionId =
                objectKey
                    .substringAfterLast("/")
                    .toUuid(
                        "EVIDENCE_OBJECT_KEY_INVALID",
                    ),
            contentType = contentType,
            expectedSizeBytes = sizeBytes,
            expectedSha256Hex = sha256,
        ).also {
            if (
                it.objectKey != objectKey
            ) {
                throw ProductApiException(
                    code = "EVIDENCE_OBJECT_KEY_INVALID",
                    message = "Evidence storage identity is invalid.",
                    status = HttpStatus.INTERNAL_SERVER_ERROR,
                )
            }
        }

    private fun EvidenceFinalizeRecord.toStorageSpec(): EvidenceUploadSpec =
        EvidenceUploadSpec(
            evidenceId = evidenceId,
            organizationId = organizationId,
            objectVersionId =
                objectKey
                    .substringAfterLast("/")
                    .toUuid(
                        "EVIDENCE_OBJECT_KEY_INVALID",
                    ),
            contentType = contentType,
            expectedSizeBytes = expectedSizeBytes,
            expectedSha256Hex = expectedSha256,
        ).also {
            if (
                it.objectKey != objectKey
            ) {
                throw ProductApiException(
                    code = "EVIDENCE_OBJECT_KEY_INVALID",
                    message = "Evidence storage identity is invalid.",
                    status = HttpStatus.INTERNAL_SERVER_ERROR,
                )
            }
        }

    private fun EvidenceReservationRecord.toMetadata(): EvidenceMetadataResponse =
        EvidenceMetadataResponse(
            evidenceId = evidenceId.toString(),
            uploadSessionId = uploadSessionId.toString(),
            targetType = targetType,
            targetId = targetId.toString(),
            workOrderId =
                workOrderId?.toString(),
            evidenceRequirementKey =
                evidenceRequirementKey,
            evidencePolicyId =
                evidencePolicyId?.toString(),
            evidencePolicyRevision =
                evidencePolicyRevision,
            evidenceTypeCode = evidenceTypeCode,
            contentType = contentType,
            sizeBytes = sizeBytes,
            sha256 = sha256,
            storageState = storageState,
            classificationCode = classificationCode,
            clientVisibilityMode = clientVisibilityMode,
            evidenceVersion = evidenceVersion,
        )

    private fun EvidenceFinalizeRecord.toMetadata(): EvidenceMetadataResponse =
        EvidenceMetadataResponse(
            evidenceId = evidenceId.toString(),
            uploadSessionId = uploadSessionId.toString(),
            targetType = targetType,
            targetId = targetId.toString(),
            workOrderId =
                workOrderId?.toString(),
            evidenceRequirementKey =
                evidenceRequirementKey,
            evidencePolicyId =
                evidencePolicyId?.toString(),
            evidencePolicyRevision =
                evidencePolicyRevision,
            evidenceTypeCode = evidenceTypeCode,
            contentType = contentType,
            sizeBytes = expectedSizeBytes,
            sha256 = expectedSha256,
            storageState = storageState,
            classificationCode = classificationCode,
            clientVisibilityMode = clientVisibilityMode,
            evidenceVersion = evidenceVersion,
        )

    private fun employeeDocumentScanClass(
        contentType: String,
    ): String? =
        when (contentType) {
            "image/jpeg",
            "image/png",
            "image/webp" ->
                "NATIVE_MEDIA"

            "application/pdf" ->
                "SIGNED_DOCUMENT"

            else -> null
        }

    private fun normalizeContentType(
        value: String,
    ): String =
        value
            .substringBefore(";")
            .trim()
            .lowercase()
            .takeIf {
                CONTENT_TYPE_PATTERN.matches(it)
            }
            ?: malformed(
                "INVALID_EVIDENCE_CONTENT_TYPE",
            )

    private fun normalizeSha256(
        value: String,
    ): String =
        value
            .trim()
            .lowercase()
            .takeIf {
                SHA256_PATTERN.matches(it)
            }
            ?: malformed(
                "INVALID_EVIDENCE_SHA256",
            )

    private fun sanitizeFileName(
        value: String?,
    ): String? =
        value
            ?.replace("\\", "/")
            ?.substringAfterLast("/")
            ?.filter {
                it.code >= 0x20 &&
                    it != 0x7f.toChar()
            }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.take(255)

    private fun String.toUuid(
        code: String,
    ): UUID =
        runCatching {
            UUID.fromString(this)
        }.getOrElse {
            malformed(code)
        }

    private fun String.toInstant(
        code: String,
    ): Instant =
        runCatching {
            Instant.parse(this)
        }.getOrElse {
            malformed(code)
        }

    private fun <T> malformed(
        code: String,
    ): T =
        throw ProductApiException(
            code = code,
            message = "Evidence request is invalid.",
            status = HttpStatus.BAD_REQUEST,
        )

    private data class ReserveCommand(
        val operationId: UUID,
        val targetType: String,
        val targetId: UUID,
        val workOrderId: UUID?,
        val requirementKey: String,
        val assertedEvidenceTypeCode: String,
        val contentType: String,
        val originalFileName: String?,
        val sizeBytes: Long,
        val sha256: String,
        val capturedAt: Instant,
        val clientOccurredAt: Instant?,
        val supersedesEvidenceId: UUID?,
    )

    companion object {
        private val TERMINAL_WORK_STATES =
            setOf(
                "ACCEPTED",
                "CLOSED",
                "CANCELLED",
            )

        private val FINAL_EVIDENCE_STATES =
            setOf(
                "READY",
                "QUARANTINED",
                "REJECTED",
            )

        private val SHA256_PATTERN =
            Regex("^[0-9a-f]{64}$")

        private val CONTENT_TYPE_PATTERN =
            Regex(
                "^[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+$",
            )

        private const val
            EMPLOYEE_DOCUMENT_REQUIREMENT_KEY =
            "EMPLOYEE_DOCUMENT_BINARY"

        private const val
            EMPLOYEE_DOCUMENT_EVIDENCE_TYPE =
            "HR_DOCUMENT"
    }
}

@RestController
@RequestMapping("/v1/evidence")
class EvidenceLifecycleController(
    private val service: EvidenceLifecycleService,
) {
    @PostMapping("/reservations")
    fun reserve(
        requestContext: HttpServletRequest,
        @RequestHeader(
            name = "Idempotency-Key",
            required = false,
        )
        idempotencyKey: String?,
        @RequestBody
        request: ReserveEvidenceUploadRequest,
    ): ReserveEvidenceUploadResponse {
        val context =
            HiltechRequestContext.current(
                requestContext,
            )

        return service.reserve(
            actorIdentityId =
                context.requireIdentityId(),
            idempotencyHeader =
                idempotencyKey,
            correlationId =
                context.correlationId,
            request = request,
        )
    }

    @GetMapping("/{evidenceId}")
    fun evidenceMetadata(
        requestContext: HttpServletRequest,
        @PathVariable
        evidenceId: String,
    ): EvidenceMetadataResponse {
        val context =
            HiltechRequestContext.current(
                requestContext,
            )

        return service.metadata(
            actorIdentityId =
                context.requireIdentityId(),
            evidenceId =
                evidenceId.toUuidOrBadRequest(
                    "INVALID_EVIDENCE_ID",
                ),
        )
    }

    @PostMapping("/{evidenceId}/download-target")
    fun downloadTarget(
        requestContext: HttpServletRequest,
        @PathVariable
        evidenceId: String,
    ): EvidenceDownloadTargetResponse {
        val context =
            HiltechRequestContext.current(
                requestContext,
            )

        return service.downloadTarget(
            actorIdentityId =
                context.requireIdentityId(),
            evidenceId =
                evidenceId.toUuidOrBadRequest(
                    "INVALID_EVIDENCE_ID",
                ),
            correlationId =
                context.correlationId,
        )
    }

    @PostMapping("/{evidenceId}/finalize")
    fun finalizeEvidence(
        requestContext: HttpServletRequest,
        @PathVariable
        evidenceId: String,
        @RequestHeader(
            name = "Idempotency-Key",
            required = false,
        )
        idempotencyKey: String?,
        @RequestBody
        request: FinalizeEvidenceRequest,
    ): FinalizeEvidenceResponse {
        val context =
            HiltechRequestContext.current(
                requestContext,
            )

        return service.finalize(
            actorIdentityId =
                context.requireIdentityId(),
            evidenceId =
                evidenceId.toUuidOrBadRequest(
                    "INVALID_EVIDENCE_ID",
                ),
            idempotencyHeader =
                idempotencyKey,
            correlationId =
                context.correlationId,
            request = request,
        )
    }

    private fun com.hiltech.server.platform.HiltechRequestContextSnapshot
        .requireIdentityId(): UUID =
        identityId
            ?.let {
                runCatching {
                    UUID.fromString(it)
                }.getOrNull()
            }
            ?: throw ProductApiException(
                code = "UNAUTHENTICATED",
                message = "Authentication is required.",
                status = HttpStatus.UNAUTHORIZED,
            )

    private fun String.toUuidOrBadRequest(
        code: String,
    ): UUID =
        runCatching {
            UUID.fromString(this)
        }.getOrElse {
            throw ProductApiException(
                code = code,
                message = "Evidence request is invalid.",
                status = HttpStatus.BAD_REQUEST,
            )
        }
}
