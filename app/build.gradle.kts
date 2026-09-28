plugins {
    id("com.android.application")
}

android {
    namespace = "com.kai.terminal"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kai.terminal"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "3.2.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
