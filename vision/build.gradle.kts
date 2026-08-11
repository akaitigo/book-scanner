plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)

    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.withType<Test>().configureEach {
    // Lets the real-photograph harness be pointed at a local directory; it
    // skips when unset, so CI is unaffected.
    System.getProperty("bookscanner.realPages")?.let { systemProperty("bookscanner.realPages", it) }
}

dependencies {
    api(project(":core-contracts"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
