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
    implementation("software.amazon.awssdk:s3:2.55.0")
    implementation("software.amazon.awssdk:url-connection-client:2.55.0")

    testImplementation(kotlin("test-junit5"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
    }
}
