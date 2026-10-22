package com.hiltech.spike

import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules

class ModularityVerificationTest {
    @Test
    fun module_boundaries_are_valid() {
        ApplicationModules
            .of(HiltechBackendSpikeApplication::class.java)
            .verify()
    }
}
