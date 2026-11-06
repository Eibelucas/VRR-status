plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "de.luca.mitfahrt"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "de.luca.mitfahrt"
        minSdk = 29
        targetSdk = 36
        versionCode = 4
        versionName = "0.4.0"
    }
    val releaseStore = System.getenv("MITFAHRT_KEYSTORE")?.takeIf { it.isNotBlank() }
    signingConfigs {
        if (releaseStore != null) create("releaseKey") {
            storeFile = file(releaseStore)
            storePassword = requireNotNull(System.getenv("MITFAHRT_STORE_PASSWORD")) { "MITFAHRT_STORE_PASSWORD fehlt" }
            keyAlias = requireNotNull(System.getenv("MITFAHRT_KEY_ALIAS")) { "MITFAHRT_KEY_ALIAS fehlt" }
            keyPassword = requireNotNull(System.getenv("MITFAHRT_KEY_PASSWORD")) { "MITFAHRT_KEY_PASSWORD fehlt" }
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Installable preview; replace with a private release key before store publication.
            signingConfig = signingConfigs.getByName(if (releaseStore == null) "debug" else "releaseKey")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
