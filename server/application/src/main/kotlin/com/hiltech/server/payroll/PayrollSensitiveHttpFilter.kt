package com.hiltech.server.payroll

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/** Salary and employment data must not enter browser/proxy caches. */
@Component
class PayrollSensitiveHttpFilter : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !(
            request.requestURI.startsWith("/v1/payroll/") ||
                request.requestURI.startsWith("/v1/me/payroll/")
        )

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        response.setHeader("Cache-Control", "no-store, private, max-age=0")
        response.setHeader("Pragma", "no-cache")
        response.setHeader("X-Content-Type-Options", "nosniff")
        chain.doFilter(request, response)
    }
}
