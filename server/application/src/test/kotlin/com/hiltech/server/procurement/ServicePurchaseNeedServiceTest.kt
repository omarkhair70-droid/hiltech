package com.hiltech.server.procurement

import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.ProductApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ServicePurchaseNeedServiceTest {
    private val jdbc = Mockito.mock(JdbcTemplate::class.java)
    private val approvals = Mockito.mock(ApprovalPersistencePort::class.java)
    private val commands = Mockito.mock(IdempotentCommandExecutor::class.java)
    private val audit = Mockito.mock(AuditEventWriter::class.java)
    private val clock = Clock.fixed(
        Instant.parse("2026-11-23T00:00:00Z"),ZoneOffset.UTC,
    )
    private val actor = UUID.randomUUID()
    private val org = UUID.randomUUID()
    private val team = UUID.randomUUID()
    private val operation = UUID.randomUUID()
    private val ctx = ProcurementDraftContext(operation,actor,"service-need-test")

    private fun service(enabled: Boolean = false, requestKey: String = "",
                        reviewKey: String = "") =
        ServicePurchaseNeedService(
            jdbc,approvals,commands,audit,clock,enabled,requestKey,reviewKey,
        )

    private fun input() = CreateServicePurchaseNeedRequest(
        operation,1,
        "External calibration of the assigned testing equipment.",
        "Complete approved calibration report and functional result.",
        "Current planned maintenance requires outside service.",
    )

    @Test fun featureIsFailClosedBeforeReadingAnyCompanySource() {
        assertEquals("SERVICE_NEED_UNAVAILABLE",
            assertThrows<ProductApiException> {
                service().teams(actor,org)
            }.code)
        Mockito.verifyNoInteractions(jdbc,approvals,commands,audit)
    }

    @Test fun unauthorizedOrganizationMembershipCannotDiscoverTeams() {
        val available = service(true,"SERVICE_REQUEST","SERVICE_REVIEW").teams(actor,org)
        assertTrue(available.isEmpty())
        Mockito.verify(approvals).isActiveOrganizationMember(org,actor,clock.instant())
        Mockito.verifyNoInteractions(jdbc,commands,audit)
    }

    @Test fun mismatchedCommandOperationCannotCreateDemand() {
        val input = input().copy(operationId=UUID.randomUUID())
        assertEquals("SERVICE_NEED_INVALID",
            assertThrows<ProductApiException> {
                service(true).create(ctx,team,input)
            }.code)
        Mockito.verifyNoInteractions(jdbc,approvals,commands,audit)
    }

    @Test fun blankAcceptanceCriteriaCannotBecomeAnUnverifiableService() {
        val input = input().copy(acceptanceCriteria="  ")
        assertEquals("SERVICE_NEED_INVALID",
            assertThrows<ProductApiException> {
                service(true).create(ctx,team,input)
            }.code)
        Mockito.verifyNoInteractions(jdbc,approvals,commands,audit)
    }

    @Test fun controlCharactersCannotEnterImmutableServiceSpecification() {
        val input = input().copy(specification="Calibration requested.\u0001 Invalid evidence")
        assertEquals("SERVICE_NEED_INVALID",
            assertThrows<ProductApiException> {
                service(true).create(ctx,team,input)
            }.code)
        Mockito.verifyNoInteractions(jdbc,approvals,commands,audit)
    }

    @Test fun reviewersCannotInventUnsupportedDecisions() {
        assertEquals("SERVICE_NEED_INVALID",
            assertThrows<ProductApiException> {
                service(true).review(ctx,UUID.randomUUID(),
                    ServicePurchaseNeedReview(operation,1,"APPROVED_TO_PAY"))
            }.code)
        Mockito.verifyNoInteractions(jdbc,approvals,commands,audit)
    }

    @Test fun requiringClarificationMustIncludeAnActualReason() {
        assertEquals("SERVICE_NEED_INVALID",
            assertThrows<ProductApiException> {
                service(true).review(ctx,UUID.randomUUID(),
                    ServicePurchaseNeedReview(operation,1,"NEEDS_INFO",""))
            }.code)
        Mockito.verifyNoInteractions(jdbc,approvals,commands,audit)
    }

    @Test fun revisionsRequireUsefulServiceAndAcceptanceDefinition() {
        assertEquals("SERVICE_NEED_INVALID",
            assertThrows<ProductApiException> {
                service(true).revise(ctx,UUID.randomUUID(),
                    ReviseServicePurchaseNeedRequest(operation,1,
                        "Service without test criteria",
                        "not specified",
                        "The equipment requires repair and inspection."))
            }.code)
        Mockito.verifyNoInteractions(jdbc,approvals,commands,audit)
    }
}
