package com.hiltech.server.payroll

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/** One company setup key for new deployments; explicit installed keys remain authoritative. */
@Component
class PayrollRunPolicyConfiguration(
    @Value("\${hiltech.payroll.run-approval-policy-key:PAYROLL_RUN_REVIEW}") configuredKey: String,
) {
    val key: String = payrollRunPolicyKey(configuredKey)
}

internal fun payrollRunPolicyKey(configured: String): String = configured.trim().ifBlank { "PAYROLL_RUN_REVIEW" }
