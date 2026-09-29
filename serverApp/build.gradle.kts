plugins {
    kotlin("jvm")
    application
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 11
}

dependencies {
    // Consumes the shared module's desktop JVM variant (plugin loader,
    // dex2jar conversion, Stremio server, web admin panel)
    implementation(project(":shared"))

    // Implementation-scoped deps of :shared don't land on our compile
    // classpath — declare the ones this module's sources touch directly.
    implementation(libs.kotlinx.coroutines.core)

    // dex2jar / ASM are runtime requirements of the desktop plugin loader —
    // keep them on the server classpath explicitly so installDist picks them
    // up even if variant metadata ever drops them.
    implementation(libs.dex2jar)
    implementation(libs.dex.tools)
    implementation(libs.asm)
    implementation(libs.asm.commons)
    implementation(libs.asm.tree)
    implementation(libs.asm.analysis)
    implementation(libs.asm.util)
    implementation(libs.okhttp)
    implementation(libs.jsoup)
}

application {
    mainClass.set("com.cncverse.stremiobridge.server.HeadlessMainKt")
    applicationName = "cncverse-bridge-server"
    // Exit on OOM so the service manager restarts a clean process instead of
    // leaving a half-dead JVM that answers nothing.
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8", "-XX:+ExitOnOutOfMemoryError")

    applicationDistribution.from("${projectDir}/packaging") {
        include("cncverse-bridge-server.service")
        include("cncverse-bridge-healthcheck.*")
        include("README-server.md")
        into("packaging")
    }
}

tasks.withType<org.gradle.jvm.tasks.Jar>().configureEach {
    manifest {
        attributes("Implementation-Title" to "CNCVerse Bridge Server")
    }
}
