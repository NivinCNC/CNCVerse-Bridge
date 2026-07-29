plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.android.library)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
            freeCompilerArgs.add("-Xskip-metadata-version-check")
            freeCompilerArgs.add("-Xexpect-actual-classes")
        }
    }

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
            freeCompilerArgs.add("-Xexpect-actual-classes")
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.ui)
                implementation(compose.components.resources)
                // Adaptive window size class for bottom nav vs left rail
                implementation("org.jetbrains.compose.material3:material3-window-size-class:1.7.3")

                // Ktor Server
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.cio)
                implementation(libs.ktor.server.content.negotiation)
                implementation(libs.ktor.server.cors)

                // Ktor Client (for downloading CNC.json and plugins)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.cio)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.json)

                // KotlinX
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.coroutines.core)

                // Cloudstream API interfaces to satisfy Dalvik ClassLoader
                implementation(files("libs/cloudstream-api.jar"))
                implementation("com.github.Blatzar:NiceHttp:0.4.16") {
                    exclude(group = "org.jetbrains.kotlinx")
                    exclude(group = "org.jetbrains.kotlin")
                    exclude(group = "com.squareup.okhttp3")
                }
                
                // Plugin runtime dependencies
                implementation(libs.okhttp)
                implementation(libs.jsoup)
                implementation(libs.jackson.module.kotlin)
                implementation("dev.whyoleg.cryptography:cryptography-core:0.4.0")
                implementation("dev.whyoleg.cryptography:cryptography-provider-jdk:0.4.0")
                implementation("com.uwetrottmann.tmdb2:tmdb-java:2.9.0")

                // Coil for image loading
                implementation(libs.coil.compose)
                implementation(libs.coil.network.ktor)
            }
        }

        val jvmCommon by creating {
            dependsOn(commonMain)
        }

        val androidMain by getting {
            dependsOn(jvmCommon)
            dependencies {
                implementation("androidx.appcompat:appcompat:1.6.1")
                implementation(libs.kotlinx.coroutines.android)
                implementation(libs.androidx.core.ktx)
                implementation(libs.androidx.lifecycle.runtime)
                implementation(libs.androidx.activity.compose)
                implementation(compose.preview)
            }
        }

        val desktopMain by getting {
            dependsOn(jvmCommon)
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
                // dex-tools submodule contains com.googlecode.dex2jar.tools.Dex2jarCmd
                implementation(libs.dex2jar)
                implementation(libs.dex.tools)
            }
        }
    }
}

android {
    namespace = "com.cncverse.stremiobridge"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }

    dependencies {
        coreLibraryDesugaring(libs.desugar.jdk.libs)
    }
}


