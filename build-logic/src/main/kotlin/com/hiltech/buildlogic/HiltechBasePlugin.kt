package com.hiltech.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test

class HiltechBasePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.group = "com.hiltech"
        project.version = "0.1.0-SNAPSHOT"

        project.tasks.withType(Test::class.java).configureEach {
            useJUnitPlatform()
        }
    }
}
