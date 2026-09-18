plugins {
    kotlin("jvm") version "2.4.20"
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    implementation(platform("io.opentelemetry:opentelemetry-bom:1.66.0"))
    implementation("io.opentelemetry:opentelemetry-api")
    implementation("io.opentelemetry:opentelemetry-sdk")
    implementation("io.opentelemetry:opentelemetry-sdk-trace")
    implementation("io.opentelemetry:opentelemetry-context")

    testImplementation("io.opentelemetry:opentelemetry-sdk-testing")
    testImplementation(kotlin("test-junit5"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
    }
}
