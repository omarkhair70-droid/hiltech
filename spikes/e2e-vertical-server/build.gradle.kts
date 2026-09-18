plugins {
    kotlin("jvm") version "2.4.20"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.4.20")

    implementation("org.springframework.boot:spring-boot-starter-web:4.1.1")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server:4.1.1")
    implementation("org.springframework.modulith:spring-modulith-starter-core:2.1.1")

    implementation("org.jooq:jooq:3.21.8")
    runtimeOnly("org.postgresql:postgresql:42.7.13")

    implementation("software.amazon.awssdk:s3:2.55.0")
    implementation("software.amazon.awssdk:url-connection-client:2.55.0")

    implementation("io.opentelemetry:opentelemetry-api:1.66.0")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.hiltech.spike15.Spike15ServerKt")
}

tasks.test {
    useJUnitPlatform()
}
