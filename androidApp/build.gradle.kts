import java.util.Properties

val versionProps = Properties().apply {
    file("${rootDir}/version.properties").inputStream().use { load(it) }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    androidTarget {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
                    // Same as :shared / :desktopApp — CloudStream pulls a newer kotlin-stdlib
                    freeCompilerArgs.add("-Xskip-metadata-version-check")
                }
            }
        }
    }
    sourceSets {
        val androidMain by getting {
            dependencies {
                implementation(project(":shared"))
                implementation(libs.androidx.activity.compose)
                implementation(libs.androidx.core.ktx)
                implementation(libs.androidx.lifecycle.runtime)
                implementation(libs.androidx.appcompat)
                implementation(libs.androidx.material)
                implementation(libs.androidx.compose.material3.window.size)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(compose.foundation)
            }
        }
    }
}

android {
    namespace = "com.cncverse.stremiobridge.android"
    compileSdk = 35

    sourceSets {
        getByName("main") {
            manifest.srcFile("src/androidMain/AndroidManifest.xml")
            kotlin.srcDirs("src/androidMain/kotlin")
            res.srcDirs("src/androidMain/res")
            jniLibs.srcDirs("src/androidMain/jniLibs")
        }
    }

    defaultConfig {
        applicationId = "com.cncverse.stremiobridge"
        minSdk = 21
        targetSdk = 35
        versionCode = versionProps.getProperty("android.versionCode", "1").toInt()
        versionName = versionProps.getProperty("android.versionName", "0.0.22")
    }

    // Release signing: CI passes the keystore through environment variables (GitHub
    // secrets, see .github/workflows/release.yml); locally a key/release.jks is used
    // when present. Without either, release builds stay unsigned.
    val keystorePath = System.getenv("ANDROID_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
        ?: rootProject.file("key/release.jks").takeIf { it.exists() }?.absolutePath
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD") ?: "android"
                keyAlias = System.getenv("ANDROID_KEY_ALIAS") ?: "release"
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD") ?: "android"
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }



    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }

    dependencies {
        coreLibraryDesugaring(libs.desugar.jdk.libs)
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources.excludes += setOf(
            "META-INF/INDEX.LIST",
            "META-INF/io.netty.versions.properties",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE",
            "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            "META-INF/versions/**",
            "META-INF/LICENSE.txt",
            "META-INF/NOTICE",
        )
        resources.pickFirsts += setOf("META-INF/library.kotlin_module")
    }
}
