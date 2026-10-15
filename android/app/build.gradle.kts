plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "com.moneybook"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.moneybook"
        minSdk = 26
        targetSdk = 36
        versionCode = System.getenv("MB_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("MB_VERSION_NAME") ?: "0.1.0"
    }
    // Release signing only when MB_KEYSTORE_FILE is set (CI); otherwise release builds stay unsigned.
    val keystore = System.getenv("MB_KEYSTORE_FILE")
    if (keystore != null) {
        signingConfigs.create("release") {
            storeFile = file(keystore)
            storePassword = System.getenv("MB_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("MB_KEY_ALIAS")
            keyPassword = System.getenv("MB_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

room { schemaDirectory("$projectDir/schemas") }

dependencies {
    implementation("com.moneybook:shared")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    // BiometricPrompt needs a FragmentActivity; biometric 1.1.0 pulls fragment 1.2.5, so the newer one is pinned.
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.glance.appwidget)
    implementation(libs.androidx.work.runtime.ktx)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.glance.appwidget.testing)
    testImplementation(libs.androidx.work.testing)
}
