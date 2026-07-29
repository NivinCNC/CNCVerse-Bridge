import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    sourceSets {
        val desktopMain by getting {
            kotlin.srcDirs("src/main/kotlin")
            resources.srcDirs("src/main/resources")
            dependencies {
                implementation(project(":shared"))
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)

                // dex2jar for converting .cs3 DEX → .jar at runtime
                implementation(libs.dex2jar)
                implementation(libs.dex.tools)

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
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "CNCVerse Stremio Bridge"
            packageVersion = "1.0.0"
            description = "Stremio addon server powered by CNCVerse CS3 plugins"
            vendor = "CNCVerse"

            windows {
                menuGroup = "CNCVerse"
                upgradeUuid = "b3f5e2a1-4c8d-4f3e-b7c2-9d1e5f6a8b0c"
                iconFile.set(project.file("src/main/resources/icon.ico"))
                shortcut = true
            }
        }

        // JVM args for the desktop app
        jvmArgs("-Dfile.encoding=UTF-8")
    }
}
