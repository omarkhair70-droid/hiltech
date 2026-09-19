import org.gradle.api.tasks.JavaExec

plugins {
    id("com.hiltech.base")
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSpring)
    alias(libs.plugins.springBoot)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

val jooqCodegen by configurations.creating
val generatedJooqDir = rootProject.layout.projectDirectory.dir("server/build/generated-src/jooq/main")

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}"))
    implementation(platform("org.springframework.modulith:spring-modulith-bom:${libs.versions.springModulith.get()}"))
    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}"))
    testImplementation(platform("org.springframework.modulith:spring-modulith-bom:${libs.versions.springModulith.get()}"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-jooq")
    implementation("org.springframework.modulith:spring-modulith-starter-jdbc")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation(libs.jooq)
    implementation(libs.kotlinx.serialization.json)
    runtimeOnly(libs.postgresql)

    add(jooqCodegen.name, libs.jooq.codegen)
    add(jooqCodegen.name, libs.postgresql)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }

    sourceSets.named("main") {
        kotlin.srcDir(generatedJooqDir)
    }
}

fun String.xmlEscaped(): String =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

val generateJooq by tasks.registering(JavaExec::class) {
    group = "code generation"
    description = "Generate first-slice jOOQ Kotlin sources from the migrated PostgreSQL schema."
    classpath = jooqCodegen
    mainClass.set("org.jooq.codegen.GenerationTool")

    outputs.dir(generatedJooqDir)
    outputs.upToDateWhen { false }
    notCompatibleWithConfigurationCache("jOOQ code generation reads a live database schema and environment-backed connection settings.")

    doFirst {
        val dbUrl = System.getenv("HILTECH_DB_URL") ?: "jdbc:postgresql://localhost:5432/hiltech"
        val dbUser = System.getenv("HILTECH_DB_USER") ?: "hiltech"
        val dbPassword = System.getenv("HILTECH_DB_PASSWORD") ?: "hiltech"

        val configFile = layout.buildDirectory.file("tmp/jooq/codegen.xml").get().asFile
        configFile.parentFile.mkdirs()

        generatedJooqDir.asFile.deleteRecursively()
        generatedJooqDir.asFile.mkdirs()

        configFile.writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <configuration>
              <jdbc>
                <driver>org.postgresql.Driver</driver>
                <url>${dbUrl.xmlEscaped()}</url>
                <user>${dbUser.xmlEscaped()}</user>
                <password>${dbPassword.xmlEscaped()}</password>
              </jdbc>
              <generator>
                <name>org.jooq.codegen.KotlinGenerator</name>
                <database>
                  <name>org.jooq.meta.postgres.PostgresDatabase</name>
                  <inputSchema>public</inputSchema>
                  <excludes>flyway_schema_history</excludes>
                </database>
                <generate>
                  <deprecated>false</deprecated>
                  <records>true</records>
                  <pojos>false</pojos>
                  <javaTimeTypes>true</javaTimeTypes>
                </generate>
                <target>
                  <packageName>com.hiltech.server.generated.jooq</packageName>
                  <directory>${generatedJooqDir.asFile.absolutePath.xmlEscaped()}</directory>
                </target>
              </generator>
            </configuration>
            """.trimIndent(),
        )

        args(configFile.absolutePath)
    }
}

tasks.register("verifyJooqGeneration") {
    group = "verification"
    description = "Generate jOOQ and assert the frozen first-slice tables are represented."
    dependsOn(generateJooq)
    notCompatibleWithConfigurationCache("Verification inspects files generated from the live PostgreSQL schema.")

    doLast {
        val generatedFiles = generatedJooqDir.asFile
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

        check(generatedFiles.isNotEmpty()) {
            "jOOQ KotlinGenerator produced no Kotlin sources at the frozen output path."
        }

        val generatedFileNames = generatedFiles.map { it.name }.toSet()
        listOf(
            "Organization.kt",
            "UserIdentity.kt",
            "ConfigRevision.kt",
            "Project.kt",
            "WorkOrder.kt",
            "Asset.kt",
            "StockBalance.kt",
            "Evidence.kt",
            "AuthorizationRelationProjection.kt",
            "AuthorizationProjectionOutbox.kt",
        ).forEach { expected ->
            check(expected in generatedFileNames) {
                "Generated jOOQ schema is missing required first-slice table source: $expected"
            }
        }
    }
}
