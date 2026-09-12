import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.Properties

val versionProps = Properties().apply {
    file("${rootDir}/version.properties").inputStream().use { load(it) }
}

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
            freeCompilerArgs.add("-Xskip-metadata-version-check")
        }
    }

    sourceSets {
        val desktopMain by getting {
            kotlin.srcDirs("src/main/kotlin")
            resources.srcDirs("src/main/resources")
            dependencies {
                implementation(project(":shared"))
                implementation(compose.desktop.currentOs)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.foundation)
                implementation("org.jetbrains.compose.material3:material3-window-size-class:1.7.3")
                implementation(libs.kotlinx.coroutines.swing)

                // dex2jar for converting .cs3 DEX → .jar at runtime
                implementation(libs.dex2jar)
                implementation(libs.dex.tools)

                // ASM — dex2jar needs it at runtime (ClassRemapper); also used by
                // PluginBytecodeTransformer to neuter Android UI calls
                implementation(libs.asm)
                implementation(libs.asm.commons)
                implementation(libs.asm.tree)
                implementation(libs.asm.analysis)
                implementation(libs.asm.util)

                // Plugin runtime dependencies (on the classpath when loading converted plugins)
                implementation(libs.okhttp)
                implementation(libs.jsoup)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.cncverse.stremiobridge.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb)
            // jdk.zipfs — dex2jar writes converted jars via the zip filesystem provider
            // jdk.unsupported / java.scripting — commonly needed by plugin libraries
            // (sun.misc.Unsafe, Rhino JS eval)
            modules("jdk.zipfs", "jdk.unsupported", "java.scripting", "java.naming")
            packageName = "CNCVerse Bridge"
            packageVersion = versionProps.getProperty("desktop.versionName", "0.0.22")
            description = "Stremio addon server powered by CNCVerse CS3 plugins"
            vendor = "CNCVerse"

            windows {
                menuGroup = "CNCVerse"
                upgradeUuid = "b3f5e2a1-4c8d-4f3e-b7c2-9d1e5f6a8b0c"
                iconFile.set(project.file("src/main/resources/icon.ico"))
                shortcut = true
            }

            linux {
                // debMaintainer is required by the .deb target
                debMaintainer = "NivinCNC <nivincnc@users.noreply.github.com>"
                iconFile.set(project.file("src/main/resources/logo.png"))
                shortcut = true
                menuGroup = "CNCVerse"
                // rpm also supported: uncomment to ship both
                // packageName stays "CNCVerse Bridge"; the .deb installs to /opt
            }
        }

        // Disable ProGuard to avoid missing class errors during release packaging
        buildTypes.release.proguard {
            isEnabled.set(false)
        }

        // JVM args for the desktop app
        jvmArgs("-Dfile.encoding=UTF-8")
    }
}
