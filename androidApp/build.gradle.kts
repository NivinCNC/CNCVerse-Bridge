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
        }
    }

    defaultConfig {
        applicationId = "com.cncverse.stremiobridge"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.14"
    }

    signingConfigs {
        create("release") {
            storeFile = file("../key/release.jks")
            storePassword = "android"
            keyAlias = "release"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
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
    }
}
