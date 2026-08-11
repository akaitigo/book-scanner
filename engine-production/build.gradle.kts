plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.bookscanner.engine.production"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    testOptions {
        unitTests.all {
            // Robolectric loads a full Android runtime per test class; the
            // default fork heap is not enough once real bitmaps are decoded.
            it.maxHeapSize = "2g"
        }
        unitTests {
            // Robolectric substitutes for an emulator here: this project's
            // build hosts have no /dev/kvm, so the PDF size gate (ADR-0004)
            // has to be measurable as a JVM test to run in CI at all.
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    api(project(":core-contracts"))
    api(project(":pdf-writer"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.exifinterface)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
