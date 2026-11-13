package com.hiltech.server.warehouse

import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.ProductApiErrorWriter
import com.hiltech.server.platform.ProductApiExceptionHandler
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.UUID

class AssetPassportApiTest {
    private val service = mock(AssetPassportService::class.java)
    private val mvc = MockMvcBuilders.standaloneSetup(AssetPassportController(service))
        .setControllerAdvice(ProductApiExceptionHandler(ProductApiErrorWriter())).build()

    @Test
    fun registrationDecodesAnAuthenticatedSharedClientRequestWithOptionalFieldsOmitted() {
        val operation = UUID.randomUUID()
        val facility = UUID.randomUUID()
        val type = UUID.randomUUID()
        val actor = UUID.randomUUID()
        val session = UUID.randomUUID()
        mvc.perform(post("/v1/assets").contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", operation.toString()).header("X-Correlation-Id", "synthetic-register")
            .with { request ->
                HiltechRequestContext.bindAuthenticatedIdentity(request, actor, session)
                request
            }
            .content("""
                {"operationId":"$operation","warehouseId":"$facility","assetTypeId":"$type",
                "assetCode":"SYNTH-PASSPORT","name":"Synthetic test tool","ownershipType":"COMPANY","condition":"GOOD"}
            """.trimIndent()))
            .andExpect(status().isOk())
        verify(service).register(
            AssetCommandContext(operation, actor, "synthetic-register"),
            AssetRegistrationRequest(operation, facility, type, "SYNTH-PASSPORT", "Synthetic test tool",
                ownershipType = "COMPANY", condition = AssetCondition.GOOD),
        )
    }
}
