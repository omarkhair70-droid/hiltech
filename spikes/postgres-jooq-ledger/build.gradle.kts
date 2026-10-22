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
    implementation("org.jooq:jooq:3.21.8")
    runtimeOnly("org.postgresql:postgresql:42.7.13")

    testImplementation(kotlin("test-junit5"))
}

tasks.test {
    useJUnitPlatform()
}
