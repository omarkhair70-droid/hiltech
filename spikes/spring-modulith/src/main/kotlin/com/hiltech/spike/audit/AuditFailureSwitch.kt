package com.hiltech.spike.audit

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean

@Component
class AuditFailureSwitch(
    @Value("${hiltech.spike.audit.fail:false}") failInitially: Boolean,
) {
    private val failing = AtomicBoolean(failInitially)

    fun shouldFail(): Boolean = failing.get()

    fun setFailing(value: Boolean) {
        failing.set(value)
    }
}
