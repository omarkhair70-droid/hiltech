package com.hiltech.server.service

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class ServiceDeliverySensitiveHttpFilter : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !request.requestURI.contains("/service-delivery") && !request.requestURI.matches(Regex("^/v1/service/organizations/[^/]+/delivery-reviews(?:/.*)?$"))

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        response.setHeader("Cache-Control", "no-store, private")
        response.setHeader("Pragma", "no-cache")
        response.setHeader("X-Content-Type-Options", "nosniff")
        filterChain.doFilter(request, response)
    }
}
