package com.hiltech.spike.audit

import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean

@Component
class AuditFailureSwitch(
    environment: Environment,
) {
    private val failing = AtomicBoolean(
        environment.getProperty(
            "hiltech.spike.audit.fail",
            Boolean::class.java,
            false,
        ),
    )

    fun shouldFail(): Boolean = failing.get()

    fun setFailing(value: Boolean) {
        failing.set(value)
    }
}
