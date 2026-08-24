plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cl.felipebrunet.musica"
    compileSdk = 34

    defaultConfig {
        applicationId = "cl.felipebrunet.musica"
        minSdk = 28
        targetSdk = 34
        versionCode = 3
        versionName = "0.1.2"

        // Galaxy A10 (Exynos 7884) needs 32-bit; also ship 64-bit.
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
        // UI is Spanish-only; drop unused AppCompat locales to keep the APK small.
        resourceConfigurations += listOf("es")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            // Unsigned on purpose: Felipe signs locally with his .jks.
            // Do not attach debug or any repo keystore.
            signingConfig = null
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
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        jniLibs {
            excludes += setOf("**/x86/**", "**/x86_64/**", "**/mips/**", "**/armeabi/**")
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.media:media:1.7.0")

    testImplementation("junit:junit:4.13.2")
}
