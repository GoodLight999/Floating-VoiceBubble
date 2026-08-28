plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val sourceSha = providers.environmentVariable("FVB_CANDIDATE_SHA").orNull
    ?.trim()
    ?.takeIf { it.matches(Regex("[0-9a-fA-F]{40}")) }
    ?: "local-dev"

android {
    namespace = "com.goodlight.floatingvoicebubble"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.goodlight.floatingvoicebubble"
        minSdk = 33
        targetSdk = 36
        versionCode = 2026082801
        versionName = "0.1.0-rc.1"
        buildConfigField("String", "SOURCE_SHA", "\"$sourceSha\"")

        // LiteRT-LM 0.14.0 provides the Android runtime used by Gemma on these two
        // modern ABIs. Shipping sherpa-only 32-bit ABIs would advertise devices on
        // which the app's required on-device correction path cannot run.
        ndk {
            abiFilters += setOf("arm64-v8a", "x86_64")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES"
        )
    }

    // Stable development/release signing is deliberately opt-in through private
    // environment values. Never fall back to a repository-committed private key:
    // this app owns microphone + Accessibility privileges and its update identity matters.
    val stableStore = providers.environmentVariable("FVB_SIGNING_STORE_FILE").orNull
        ?.takeIf(String::isNotBlank)
        ?.let(::file)
    val stableStorePassword = providers.environmentVariable("FVB_SIGNING_STORE_PASSWORD").orNull
    val stableKeyAlias = providers.environmentVariable("FVB_SIGNING_KEY_ALIAS").orNull
    val stableKeyPassword = providers.environmentVariable("FVB_SIGNING_KEY_PASSWORD").orNull
    val stableSigningReady = stableStore?.isFile == true &&
        !stableStorePassword.isNullOrBlank() &&
        !stableKeyAlias.isNullOrBlank() &&
        !stableKeyPassword.isNullOrBlank()
    val stableSigning = if (stableSigningReady) {
        signingConfigs.create("stable") {
            storeFile = stableStore
            storePassword = stableStorePassword
            keyAlias = stableKeyAlias
            keyPassword = stableKeyPassword
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
    } else {
        null
    }

    buildTypes {
        debug {
            stableSigning?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            stableSigning?.let { signingConfig = it }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.documentfile:documentfile:1.1.0")

    implementation("com.google.ai.edge.litertlm:litertlm-android:0.14.0")
    // LiteRT-LM 0.14.0's published dependency metadata can resolve coroutines 1.9.0 even though
    // its Android binary was built against the 1.11.0 ABI. Pin both artifacts explicitly to avoid
    // the resulting NoSuchMethodError on the mandatory on-device Gemma correction path.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.5")
    implementation("org.apache.commons:commons-compress:1.28.0")
    // 5.5.0 raises its Android AAR minCompileSdk to 37. Floating VoiceBubble's
    // validated release matrix is API 33..36, so keep the newest line known to
    // remain consumable from compileSdk 36 until that matrix is deliberately revised.
    implementation("com.squareup.okhttp3:okhttp:5.3.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.3.21")
    testImplementation("org.json:json:20250517")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
