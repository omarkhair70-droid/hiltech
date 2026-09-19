package com.hiltech.server.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

class ProductHttpRuntimeTest {
    private val errorWriter =
        ProductApiErrorWriter()

    private val mvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(
                ProbeController(),
            )
            .setControllerAdvice(
                ProductApiExceptionHandler(
                    errorWriter,
                ),
            )
            .build()

    @Test
    fun representativeProductErrorsUseOneSafeEnvelope() {
        val cases =
            listOf(
                Case("bad", 400, "INVALID_VALUE", false),
                Case("unauth", 401, "UNAUTHENTICATED", false),
                Case("deny", 403, "PERMISSION_DENIED", false),
                Case("hidden", 404, "OBJECT_NOT_VISIBLE", false),
                Case("conflict", 409, "VERSION_CONFLICT", false),
                Case("validation", 422, "REJECTED_VALIDATION", false),
                Case("rate", 429, "RATE_LIMITED", true),
                Case("service", 503, "TEMPORARY_UNAVAILABLE", true),
            )

        cases.forEach { item ->
            val correlation =
                "corr-" + item.kind

            mvc.perform(
                get(
                    "/probe/" + item.kind,
                ).header(
                    HiltechRequestHeaders.CORRELATION_ID,
                    correlation,
                ),
            )
                .andExpect(
                    status().`is`(
                        item.status,
                    ),
                )
                .andExpect(
                    header().string(
                        HiltechRequestHeaders.CORRELATION_ID,
                        correlation,
                    ),
                )
                .andExpect(
                    jsonPath("$.code")
                        .value(item.code),
                )
                .andExpect(
                    jsonPath("$.correlationId")
                        .value(correlation),
                )
                .andExpect(
                    jsonPath("$.retryable")
                        .value(item.retryable),
                )
        }
    }

    @Test
    fun unexpectedFailureReturnsSafe500WithoutInternalText() {
        val result =
            mvc.perform(
                get("/probe/boom")
                    .header(
                        HiltechRequestHeaders.CORRELATION_ID,
                        "corr-boom",
                    ),
            )
                .andExpect(
                    status().isInternalServerError,
                )
                .andExpect(
                    jsonPath("$.code")
                        .value("INTERNAL_ERROR"),
                )
                .andExpect(
                    jsonPath("$.correlationId")
                        .value("corr-boom"),
                )
                .andReturn()

        val body =
            result.response.contentAsString

        assertFalse(
            body.contains(
                "password",
                ignoreCase = true,
            ),
        )
        assertFalse(
            body.contains(
                "sql",
                ignoreCase = true,
            ),
        )
    }

    @Test
    fun unsafeCorrelationHeaderIsReplacedServerSide() {
        val request =
            MockHttpServletRequest().apply {
                addHeader(
                    HiltechRequestHeaders.CORRELATION_ID,
                    "unsafe correlation value",
                )
            }
        val response =
            MockHttpServletResponse()

        HiltechRequestContextFilter()
            .doFilter(
                request,
                response,
                MockFilterChain(),
            )

        val correlation =
            response.getHeader(
                HiltechRequestHeaders.CORRELATION_ID,
            )

        assertTrue(
            !correlation.isNullOrBlank(),
        )
        assertNotEquals(
            "unsafe correlation value",
            correlation,
        )
        assertTrue(
            runCatching {
                java.util.UUID.fromString(
                    correlation,
                )
            }.isSuccess,
        )
    }

    @Test
    fun requestContextNormalizesTraceParent() {
        assertEquals(
            "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01",
            HiltechRequestContext
                .normalizedTraceParent(
                    "00-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA-BBBBBBBBBBBBBBBB-01",
                ),
        )
        assertEquals(
            null,
            HiltechRequestContext
                .normalizedTraceParent(
                    "not-a-trace",
                ),
        )
    }

    private data class Case(
        val kind: String,
        val status: Int,
        val code: String,
        val retryable: Boolean,
    )

    @RestController
    @RequestMapping("/probe")
    private class ProbeController {
        @GetMapping("/{kind}")
        fun probe(
            @PathVariable kind: String,
        ): Map<String, Boolean> {
            when (kind) {
                "ok" ->
                    return mapOf(
                        "ok" to true,
                    )

                "bad" ->
                    throw failure(
                        HttpStatus.BAD_REQUEST,
                        "INVALID_VALUE",
                    )

                "unauth" ->
                    throw failure(
                        HttpStatus.UNAUTHORIZED,
                        "UNAUTHENTICATED",
                    )

                "deny" ->
                    throw failure(
                        HttpStatus.FORBIDDEN,
                        "PERMISSION_DENIED",
                    )

                "hidden" ->
                    throw failure(
                        HttpStatus.NOT_FOUND,
                        "OBJECT_NOT_VISIBLE",
                    )

                "conflict" ->
                    throw ProductApiException(
                        code = "VERSION_CONFLICT",
                        message = "The object changed.",
                        status = HttpStatus.CONFLICT,
                        currentVersion = 9,
                        conflict =
                            ProductConflictPayload(
                                conflictType = "STALE_VERSION",
                                attemptedBaseVersion = 8,
                                currentVersion = 9,
                                localWorkSafe = true,
                                allowedRecoveryActions =
                                    listOf(
                                        "REFRESH",
                                    ),
                            ),
                    )

                "validation" ->
                    throw failure(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "REJECTED_VALIDATION",
                    )

                "rate" ->
                    throw ProductApiException(
                        code = "RATE_LIMITED",
                        message = "Try again later.",
                        status = HttpStatus.TOO_MANY_REQUESTS,
                        retryable = true,
                    )

                "service" ->
                    throw ProductApiException(
                        code = "TEMPORARY_UNAVAILABLE",
                        message = "Try again later.",
                        status = HttpStatus.SERVICE_UNAVAILABLE,
                        retryable = true,
                    )

                "boom" ->
                    error(
                        "SQL password=do-not-leak",
                    )

                else ->
                    error("unexpected")
            }
        }

        private fun failure(
            status: HttpStatus,
            code: String,
        ) =
            ProductApiException(
                code = code,
                message = "Safe product failure.",
                status = status,
            )
    }
}
