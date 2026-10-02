import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.gravitycode.app"
    compileSdk = 36

    signingConfigs {
        create("gravityDebug") {
            storeFile = rootProject.file("keystore/gravitycode-debug.jks")
            storePassword = "gravitycode"
            storeType = "JKS"
            keyAlias = "gravitycode"
            keyPassword = "gravitycode"
        }
    }

    defaultConfig {
        applicationId = "dev.gravitycode.app"
        minSdk = 28
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("gravityDebug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
        jniLibs.useLegacyPackaging = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.compose.ui:ui:1.8.3")
    implementation("androidx.compose.ui:ui-tooling-preview:1.8.3")
    implementation("androidx.compose.foundation:foundation:1.8.3")
    implementation("androidx.compose.material3:material3:1.3.2")
    implementation("org.apache.commons:commons-compress:1.27.1")
    debugImplementation("androidx.compose.ui:ui-tooling:1.8.3")
}
