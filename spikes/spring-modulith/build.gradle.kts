plugins {
    id("org.springframework.boot") version "4.1.1"
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.spring") version "2.4.20"
}

group = "com.hiltech"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    implementation(platform("org.springframework.modulith:spring-modulith-bom:2.1.1"))

    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.modulith:spring-modulith-starter-jdbc")

    runtimeOnly("com.h2database:h2")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
