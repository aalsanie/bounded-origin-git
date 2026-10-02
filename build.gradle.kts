import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test

plugins {
    java
}

group = "io.github.aalsanie"
version = "0.1.0-SNAPSHOT"

val boundedOriginVersion = providers.gradleProperty("boundedOriginVersion").get()
val junitVersion = providers.gradleProperty("junitVersion").get()

dependencies {
    implementation("io.github.aalsanie:bounded-origin-api:$boundedOriginVersion")
    implementation("io.github.aalsanie:bounded-origin-core:$boundedOriginVersion")
    implementation("io.github.aalsanie:bounded-origin-store-fs:$boundedOriginVersion")
    implementation("io.github.aalsanie:bounded-origin-proxy:$boundedOriginVersion")

    testImplementation(platform("org.junit:junit-bom:$junitVersion"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

configurations.configureEach {
    resolutionStrategy.failOnVersionConflict()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror", "-parameters"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

val clientTest by tasks.registering(Exec::class) {
    commandLine("node", "--test", "src/test/js/cgit-compare-v1.test.mjs")
    inputs.file(
        "src/main/resources/io/github/aalsanie/boundedorigingit/client/cgit-compare-v1.mjs")
    inputs.file("src/test/js/cgit-compare-v1.test.mjs")
}

tasks.named("check") {
    dependsOn(clientTest)
}
