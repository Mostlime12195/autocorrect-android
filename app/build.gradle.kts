plugins {
    id("com.android.application")
}

val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "dev.mostlime.autocorrect"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.mostlime.autocorrect"
        minSdk = 26
        targetSdk = 37
        versionCode = runNumber
        versionName = "0.1.$runNumber"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // CI writes the release keystore from secrets (see README "Signing"); local builds fall back to the debug key
    val ksPath = System.getenv("AC_KEYSTORE_PATH")
    signingConfigs {
        if (ksPath != null) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = System.getenv("AC_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("AC_KEY_ALIAS")
                keyPassword = System.getenv("AC_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        // x86_64 only for the CI emulator (debug / instrumented builds); phones get arm64-v8a, half the APK size
        debug { ndk { abiFilters += listOf("arm64-v8a", "x86_64") } }
        release {
            ndk { abiFilters += listOf("arm64-v8a") }
            isMinifyEnabled = false
            signingConfig = if (ksPath != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { buildConfig = true }

    testOptions { unitTests.isReturnDefaultValues = true }

    packaging {
        resources.excludes += listOf("META-INF/*.kotlin_module", "META-INF/versions/**")
    }
}

dependencies {
    implementation(project(":engine"))
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.11.0")
    testImplementation("junit:junit:4.13.2")
    // instrumented tests on the CI emulator (real EditText / ImageDecoder)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
