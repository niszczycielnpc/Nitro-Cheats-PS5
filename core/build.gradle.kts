// Pure Kotlin/JDK module: the ps5debug-NG protocol client, scanner, tracer, cheat-file writers and FTP.
// No Android dependencies, so it is unit-tested on the JVM (./gradlew :core:test).
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("failed")
        showStandardStreams = false
    }
}
