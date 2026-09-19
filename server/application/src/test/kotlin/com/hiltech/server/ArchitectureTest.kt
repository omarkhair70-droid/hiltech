package com.hiltech.server

import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules

class ArchitectureTest {
    @Test
    fun moduleBoundariesAreValid() {
        ApplicationModules.of(HiltechServerApplication::class.java).verify()
    }
}
