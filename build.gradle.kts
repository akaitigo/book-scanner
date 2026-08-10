plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

/*
 * Formatting is enforced by running the ktlint CLI directly, at the exact
 * version developers' editors/hooks run, rather than through a Gradle plugin.
 *
 * Reason: the ktlint-gradle plugin derives its file list from the Kotlin
 * plugin's source sets, and with AGP 9 it silently contributes no sources for
 * Android modules — `ktlintCheck` passed on a file with deliberate style
 * violations. A lint task that silently checks nothing is worse than none.
 * Globbing the source tree is dumber and cannot go quiet.
 */
val ktlint: Configuration by configurations.creating {
    // ktlint-cli publishes both a plain and a shadowed variant; without an
    // explicit choice Gradle cannot tell them apart. The shadowed one is the
    // runnable CLI — the plain variant omits its bundled dependencies.
    attributes {
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.SHADOWED))
    }
}

dependencies {
    ktlint(libs.ktlint.cli)
}

private val ktlintTargets = listOf("**/src/**/*.kt", "**/src/**/*.kts", "*.kts")
private val ktlintExcludes = listOf("!**/build/**")

tasks.register<JavaExec>("ktlintCheck") {
    group = "verification"
    description = "Checks Kotlin formatting across every module."
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    args = ktlintTargets + ktlintExcludes + listOf("--relative")
    // ktlint 1.x needs these opens to read the Kotlin compiler's internals.
    jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED")
}

tasks.register<JavaExec>("ktlintFormat") {
    group = "formatting"
    description = "Applies Kotlin formatting fixes across every module."
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    args = ktlintTargets + ktlintExcludes + listOf("--relative", "--format")
    jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED")
}
