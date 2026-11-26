package com.hiltech.server.commercial

import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.HiltechRequestContextSnapshot
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.platform.IdempotencyKeyContract
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Internal commercial surface. All HTTP results are permission-scoped in the service;
 * only released quotes have a separate sell-only projection.
 */
@RestController
@RequestMapping("/v1/commercial")
class CommercialController(private val service: CommercialService, private val email: CommercialQuoteEmail, private val connections: CommercialMailConnection, private val whatsapp: CommercialQuoteWhatsapp, private val whatsappConnections: CommercialWhatsappConnection, private val oauth: CommercialMailOAuth, private val metaSignup: CommercialMetaSignup, private val whatsappWebhook: CommercialWhatsappWebhook) {
    private fun operation(key: String, id: String): UUID {
        val parsed = id.commercialUuid()
        IdempotencyKeyContract.requireMatches(key, parsed)
        return parsed
    }

    @PostMapping("/opportunities")
    fun create(
        http: HttpServletRequest,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: CreateCommercialOpportunityRequest,
    ) = service.createOpportunity(
        body, operation(key, body.operationId),
        HiltechRequestContext.current(http).requireIdentityId(),
        HiltechRequestContext.current(http).correlationId,
    )

    @GetMapping("/meta/webhook",produces=["text/plain"])
    fun verifyWebhook(@RequestParam("hub.mode",required=false) mode:String?,@RequestParam("hub.verify_token",required=false) token:String?,@RequestParam("hub.challenge",required=false) challenge:String?):org.springframework.http.ResponseEntity<String> {
        val verified=whatsappWebhook.verify(mode,token,challenge)
        return if(verified!=null) org.springframework.http.ResponseEntity.ok(verified) else org.springframework.http.ResponseEntity.status(403).build()
    }
    @PostMapping("/meta/webhook")
    fun whatsappReceipt(@RequestHeader("X-Hub-Signature-256",required=false) signature:String?,@RequestBody raw:ByteArray):org.springframework.http.ResponseEntity<Void> =
        if(whatsappWebhook.receive(raw,signature)) org.springframework.http.ResponseEntity.ok().build() else org.springframework.http.ResponseEntity.status(403).build()

    @GetMapping("/meta/status")
    fun metaStatus(http: HttpServletRequest,@RequestParam organizationId: String):Map<String,Boolean> {
        whatsappConnections.status(organizationId.commercialUuid(),HiltechRequestContext.current(http).requireIdentityId())
        return mapOf("available" to metaSignup.available())
    }
    @PostMapping("/meta/start")
    fun metaStart(http: HttpServletRequest,@RequestBody body:MetaSignupStartRequest) =
        metaSignup.start(body,HiltechRequestContext.current(http).requireIdentityId())
    @GetMapping("/meta/launch",produces=["text/html"])
    fun metaLaunch()=org.springframework.http.ResponseEntity.ok().header("Cache-Control","no-store").header("Referrer-Policy","no-referrer").body(metaSignup.browserPage())
    @PostMapping("/meta/complete")
    fun metaComplete(@RequestBody body:MetaSignupBrowserResult):org.springframework.http.ResponseEntity<Void> =
        if(metaSignup.complete(body)) org.springframework.http.ResponseEntity.noContent().build() else org.springframework.http.ResponseEntity.badRequest().build()
    @GetMapping("/meta/pending")
    fun metaPending(http:HttpServletRequest,@RequestParam organizationId:String,@RequestParam pendingId:String) =
        metaSignup.pendingStatus(organizationId.commercialUuid(),pendingId.commercialUuid(),HiltechRequestContext.current(http).requireIdentityId())
    @PostMapping("/meta/finish")
    fun metaFinish(http:HttpServletRequest,@RequestBody body:MetaSignupFinishRequest) =
        metaSignup.finish(body,HiltechRequestContext.current(http).requireIdentityId())

    @GetMapping("/whatsapp-connection")
    fun whatsappConnection(http: HttpServletRequest,@RequestParam organizationId: String) =
        whatsappConnections.status(organizationId.commercialUuid(),HiltechRequestContext.current(http).requireIdentityId())

    @PostMapping("/whatsapp-connection/connect")
    fun connectWhatsapp(http: HttpServletRequest,@RequestBody body: ConnectCommercialWhatsappRequest) =
        whatsappConnections.connect(body,HiltechRequestContext.current(http).requireIdentityId())

    @PostMapping("/whatsapp-connection/disconnect")
    fun disconnectWhatsapp(http: HttpServletRequest,@RequestBody body: DisconnectCommercialWhatsappRequest) =
        whatsappConnections.disconnect(body,HiltechRequestContext.current(http).requireIdentityId())

    @GetMapping("/oauth/status")
    fun oauthStatus(http: HttpServletRequest,@RequestParam organizationId: String) =
        oauth.status(organizationId.commercialUuid(),HiltechRequestContext.current(http).requireIdentityId())
    @PostMapping("/oauth/start")
    fun oauthStart(http: HttpServletRequest,@RequestBody body: CommercialOAuthStartRequest) =
        oauth.start(body,HiltechRequestContext.current(http).requireIdentityId())
    @GetMapping("/oauth/pending")
    fun oauthPending(http: HttpServletRequest,@RequestParam organizationId: String,@RequestParam pendingId: String) =
        oauth.pendingStatus(organizationId.commercialUuid(),pendingId.commercialUuid(),HiltechRequestContext.current(http).requireIdentityId())
    @PostMapping("/oauth/finish")
    fun oauthFinish(http: HttpServletRequest,@RequestBody body: CommercialOAuthFinishRequest) =
        oauth.finish(body,HiltechRequestContext.current(http).requireIdentityId())
    @PostMapping("/oauth/disconnect")
    fun oauthDisconnect(http: HttpServletRequest,@RequestBody body: CommercialOAuthDisconnectRequest) =
        oauth.disconnect(body,HiltechRequestContext.current(http).requireIdentityId())
    @GetMapping("/oauth/callback",produces=["text/plain"])
    fun oauthCallback(@RequestParam(required=false) state: String?,@RequestParam(required=false) code: String?,@RequestParam(required=false) error: String?): org.springframework.http.ResponseEntity<String> {
        val ok = oauth.callback(state,code,error)
        return org.springframework.http.ResponseEntity.ok().header("Cache-Control","no-store").header("Referrer-Policy","no-referrer")
            .body(if(ok) "Return to HILTECH and confirm the account. / ارجع إلى التطبيق وأكد الحساب." else "Authorization not completed. Return to HILTECH. / لم يكتمل الاتصال.")
    }

    @GetMapping("/mail-connection")
    fun mailConnection(http: HttpServletRequest, @RequestParam organizationId: String) =
        connections.status(organizationId.commercialUuid(),HiltechRequestContext.current(http).requireIdentityId())

    @PostMapping("/mail-connection/connect")
    fun connectMail(http: HttpServletRequest,@RequestBody body: ConnectCommercialMailRequest) =
        connections.connect(body,HiltechRequestContext.current(http).requireIdentityId())

    @PostMapping("/mail-connection/disconnect")
    fun disconnectMail(http: HttpServletRequest,@RequestBody body: DisconnectCommercialMailRequest) =
        connections.disconnect(body,HiltechRequestContext.current(http).requireIdentityId())

    @GetMapping("/document-settings")
    fun documentSettings(http: HttpServletRequest, @RequestParam organizationId: String) =
        service.documentSettings(organizationId.commercialUuid(), HiltechRequestContext.current(http).requireIdentityId())

    @PostMapping("/document-settings")
    fun saveDocumentSettings(http: HttpServletRequest, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: SaveCommercialDocumentSettingsRequest) =
        service.saveDocumentSettings(body,operation(key,body.operationId),HiltechRequestContext.current(http).requireIdentityId(),HiltechRequestContext.current(http).correlationId)

    @GetMapping("/capabilities")
    fun capabilities(http: HttpServletRequest, @RequestParam organizationId: String) =
        service.capabilities(organizationId.commercialUuid(), HiltechRequestContext.current(http).requireIdentityId())

    @PostMapping("/clients")
    fun createClient(http: HttpServletRequest, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: CreateCommercialClientRequest) =
        service.createClient(body, operation(key, body.operationId), HiltechRequestContext.current(http).requireIdentityId(), HiltechRequestContext.current(http).correlationId)

    @GetMapping("/clients")
    fun clients(http: HttpServletRequest, @RequestParam organizationId: String) =
        service.clients(organizationId.commercialUuid(), HiltechRequestContext.current(http).requireIdentityId())

    @GetMapping("/opportunities")
    fun list(http: HttpServletRequest, @RequestParam organizationId: String,
             @RequestParam(defaultValue = "100") limit: Int) =
        service.listOpportunities(
            organizationId.commercialUuid(),
            HiltechRequestContext.current(http).requireIdentityId(), limit,
        )

    @GetMapping("/opportunities/{opportunityId}")
    fun detail(http: HttpServletRequest, @PathVariable opportunityId: String) =
        service.opportunityDetail(
            opportunityId.commercialUuid(),
            HiltechRequestContext.current(http).requireIdentityId(),
        )

    @PostMapping("/opportunities/{opportunityId}/qualify")
    fun qualify(http: HttpServletRequest, @PathVariable opportunityId: String,
                @RequestHeader("Idempotency-Key") key: String,
                @RequestBody body: CommercialQualifyRequest) =
        service.qualifyOpportunity(
            opportunityId.commercialUuid(),body,operation(key,body.operationId),
            HiltechRequestContext.current(http).requireIdentityId(),
            HiltechRequestContext.current(http).correlationId,
        )

    @PostMapping("/opportunities/{opportunityId}/tenders")
    fun tender(http: HttpServletRequest, @PathVariable opportunityId: String,
               @RequestHeader("Idempotency-Key") key: String,
               @RequestBody body: CommercialTenderRequest) =
        service.addTender(
            opportunityId.commercialUuid(), body, operation(key, body.operationId),
            HiltechRequestContext.current(http).requireIdentityId(),
            HiltechRequestContext.current(http).correlationId,
        )

    @PostMapping("/opportunities/{opportunityId}/visits")
    fun visit(http: HttpServletRequest, @PathVariable opportunityId: String,
              @RequestHeader("Idempotency-Key") key: String,
              @RequestBody body: CommercialVisitRequest) =
        service.addVisit(
            opportunityId.commercialUuid(), body, operation(key, body.operationId),
            HiltechRequestContext.current(http).requireIdentityId(),
            HiltechRequestContext.current(http).correlationId,
        )

    @PostMapping("/visits/{visitId}/complete")
    fun completeVisit(http: HttpServletRequest, @PathVariable visitId: String,
                      @RequestHeader("Idempotency-Key") key: String,
                      @RequestBody body: CommercialVisitCompletionRequest) =
        service.completeVisit(
            visitId.commercialUuid(), body, operation(key, body.operationId),
            HiltechRequestContext.current(http).requireIdentityId(),
            HiltechRequestContext.current(http).correlationId,
        )

    @PostMapping("/opportunities/{opportunityId}/close")
    fun close(http: HttpServletRequest, @PathVariable opportunityId: String,
              @RequestHeader("Idempotency-Key") key: String,
              @RequestBody body: CommercialCloseRequest) =
        service.closeOpportunity(
            opportunityId.commercialUuid(), body, operation(key, body.operationId),
            HiltechRequestContext.current(http).requireIdentityId(),
            HiltechRequestContext.current(http).correlationId,
        )

    @PostMapping("/opportunities/{opportunityId}/scopes")
    fun scope(http: HttpServletRequest, @PathVariable opportunityId: String,
              @RequestHeader("Idempotency-Key") key: String,
              @RequestBody body: CommercialScopeRequest) =
        service.addScope(
            opportunityId.commercialUuid(), body, operation(key, body.operationId),
            HiltechRequestContext.current(http).requireIdentityId(),
            HiltechRequestContext.current(http).correlationId,
        )

    @PostMapping("/opportunities/{opportunityId}/boqs")
    fun boq(http: HttpServletRequest, @PathVariable opportunityId: String,
            @RequestHeader("Idempotency-Key") key: String,
            @RequestBody body: CommercialBoqRequest) =
        service.addBoq(
            opportunityId.commercialUuid(), body, operation(key, body.operationId),
            HiltechRequestContext.current(http).requireIdentityId(),
            HiltechRequestContext.current(http).correlationId,
        )

    @PostMapping("/opportunities/{opportunityId}/quotes")
    fun quote(http: HttpServletRequest, @PathVariable opportunityId: String,
              @RequestHeader("Idempotency-Key") key: String,
              @RequestBody body: CommercialQuoteRequest) =
        service.draftQuote(
            opportunityId.commercialUuid(), body, operation(key, body.operationId),
            HiltechRequestContext.current(http).requireIdentityId(),
            HiltechRequestContext.current(http).correlationId,
        )

    @PostMapping("/quotes/{quoteId}/request-approval")
    fun requestApproval(http: HttpServletRequest, @PathVariable quoteId: String,
                        @RequestHeader("Idempotency-Key") key: String,
                        @RequestBody body: CommercialApprovalRequest) =
        service.requestQuoteApproval(
            quoteId.commercialUuid(), operation(key, body.operationId),
            HiltechRequestContext.current(http).requireIdentityId(),
            HiltechRequestContext.current(http).correlationId,
        )

    @PostMapping("/quotes/{quoteId}/release")
    fun release(http: HttpServletRequest, @PathVariable quoteId: String,
                @RequestHeader("Idempotency-Key") key: String,
                @RequestBody body: CommercialApprovalRequest) =
        service.releaseQuote(
            quoteId.commercialUuid(), operation(key, body.operationId),
            HiltechRequestContext.current(http).requireIdentityId(),
            HiltechRequestContext.current(http).correlationId,
        )

    @GetMapping("/quotes/{quoteId}/customer-preview")
    fun quotePreview(http: HttpServletRequest, @PathVariable quoteId: String) =
        service.customerQuote(
            quoteId.commercialUuid(),
            HiltechRequestContext.current(http).requireIdentityId(),
        )

    @PostMapping("/quotes/{quoteId}/customer-pdf")
    fun quotePdf(http: HttpServletRequest, @PathVariable quoteId: String) =
        service.customerPdf(quoteId.commercialUuid(), HiltechRequestContext.current(http).requireIdentityId())

    @GetMapping("/quotes/{quoteId}/delivery-capabilities")
    fun deliveryCapabilities(http: HttpServletRequest,@PathVariable quoteId: String): Map<String,Boolean> {
        val id = quoteId.commercialUuid()
        val actor = HiltechRequestContext.current(http).requireIdentityId()
        service.customerQuote(id,actor)
        return mapOf("email" to runCatching { email.available(id,actor) }.getOrDefault(false),
            "whatsapp" to runCatching { whatsapp.available(id,actor) }.getOrDefault(false))
    }

    @GetMapping("/quotes/{quoteId}/whatsapp-history")
    fun whatsappHistory(http: HttpServletRequest,@PathVariable quoteId: String) =
        whatsapp.history(quoteId.commercialUuid(),HiltechRequestContext.current(http).requireIdentityId())

    @PostMapping("/quotes/{quoteId}/whatsapp")
    fun sendWhatsapp(http: HttpServletRequest,@PathVariable quoteId: String,@RequestHeader("Idempotency-Key") key: String,@RequestBody body: CommercialWhatsappRequest) =
        whatsapp.send(quoteId.commercialUuid(),HiltechRequestContext.current(http).requireIdentityId(),operation(key,body.operationId),body)

    @GetMapping("/quotes/{quoteId}/email-history")
    fun emailHistory(http: HttpServletRequest, @PathVariable quoteId: String) =
        email.history(quoteId.commercialUuid(), HiltechRequestContext.current(http).requireIdentityId())

    @PostMapping("/quotes/{quoteId}/email")
    fun sendEmail(http: HttpServletRequest, @PathVariable quoteId: String,
                  @RequestHeader("Idempotency-Key") key: String, @RequestBody body: CommercialQuoteEmailRequest) =
        email.send(quoteId.commercialUuid(), HiltechRequestContext.current(http).requireIdentityId(),
            operation(key, body.operationId), body.recipient)

    @GetMapping("/quotes/{quoteId}/dispatch-history")
    fun quoteDispatchHistory(http: HttpServletRequest, @PathVariable quoteId: String) =
        service.quoteDispatchHistory(quoteId.commercialUuid(), HiltechRequestContext.current(http).requireIdentityId())

    @PostMapping("/quotes/{quoteId}/dispatch-history")
    fun recordQuoteDispatch(http: HttpServletRequest, @PathVariable quoteId: String,
                            @RequestHeader("Idempotency-Key") key: String, @RequestBody request: RecordCommercialQuoteDispatchRequest) =
        service.recordQuoteDispatch(quoteId.commercialUuid(), request, operation(key, request.operationId),
            HiltechRequestContext.current(http).requireIdentityId(), HiltechRequestContext.current(http).correlationId)

    @GetMapping("/opportunities/{opportunityId}/costing")
    fun costing(http: HttpServletRequest, @PathVariable opportunityId: String) =
        service.privateCosting(
            opportunityId.commercialUuid(),
            HiltechRequestContext.current(http).requireIdentityId(),
        )

    @GetMapping("/opportunities/{opportunityId}/cost-summary")
    fun costSummary(http: HttpServletRequest, @PathVariable opportunityId: String) =
        service.privateCostSummary(
            opportunityId.commercialUuid(),
            HiltechRequestContext.current(http).requireIdentityId(),
        )

    @PostMapping("/opportunities/{opportunityId}/contracts")
    fun contract(http: HttpServletRequest, @PathVariable opportunityId: String,
                 @RequestHeader("Idempotency-Key") key: String,
                 @RequestBody body: CommercialContractDraftRequest) =
        service.draftContract(
            opportunityId.commercialUuid(), body, operation(key, body.operationId),
            HiltechRequestContext.current(http).requireIdentityId(),
            HiltechRequestContext.current(http).correlationId,
        )

    @PostMapping("/opportunities/{opportunityId}/client-pos")
    fun clientPo(http: HttpServletRequest, @PathVariable opportunityId: String,
                 @RequestHeader("Idempotency-Key") key: String,
                 @RequestBody body: CommercialClientPoDraftRequest) =
        service.draftClientPo(
            opportunityId.commercialUuid(), body, operation(key, body.operationId),
            HiltechRequestContext.current(http).requireIdentityId(),
            HiltechRequestContext.current(http).correlationId,
        )

    @PostMapping("/contracts/{contractId}/confirm-signed")
    fun confirmSigned(http: HttpServletRequest, @PathVariable contractId: String,
                      @RequestHeader("Idempotency-Key") key: String,
                      @RequestBody body: CommercialEvidenceConfirmationRequest) =
        service.confirmSignedContract(
            contractId.commercialUuid(),body,operation(key,body.operationId),
            HiltechRequestContext.current(http).requireIdentityId(),
            HiltechRequestContext.current(http).correlationId,
        )

    @PostMapping("/client-pos/{clientPoId}/verify")
    fun verifyPo(http: HttpServletRequest, @PathVariable clientPoId: String,
                 @RequestHeader("Idempotency-Key") key: String,
                 @RequestBody body: CommercialEvidenceConfirmationRequest) =
        service.verifyClientPo(
            clientPoId.commercialUuid(),body,operation(key,body.operationId),
            HiltechRequestContext.current(http).requireIdentityId(),
            HiltechRequestContext.current(http).correlationId,
        )

    @PostMapping("/opportunities/{opportunityId}/convert")
    fun convert(http: HttpServletRequest, @PathVariable opportunityId: String,
                @RequestHeader("Idempotency-Key") key: String,
                @RequestBody body: CommercialConversionRequest) =
        service.convertToProject(
            opportunityId.commercialUuid(), body, operation(key, body.operationId),
            HiltechRequestContext.current(http).requireIdentityId(),
            HiltechRequestContext.current(http).correlationId,
        )
}


private fun HiltechRequestContextSnapshot.requireIdentityId(): UUID =
    identityId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: throw ProductApiException(
            code = "UNAUTHENTICATED",
            message = "Authenticated HILTECH identity required.",
            status = HttpStatus.UNAUTHORIZED,
        )
