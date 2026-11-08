plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "de.luca.mitfahrt.wear"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "de.luca.mitfahrt"
        minSdk = 30
        targetSdk = 36
        versionCode = 15
        versionName = "0.15.0"
    }
    val releaseStore = System.getenv("MITFAHRT_KEYSTORE")?.takeIf { it.isNotBlank() }
    signingConfigs {
        if (releaseStore != null) create("releaseKey") {
            storeFile = file(releaseStore)
            storePassword = requireNotNull(System.getenv("MITFAHRT_STORE_PASSWORD"))
            keyAlias = requireNotNull(System.getenv("MITFAHRT_KEY_ALIAS"))
            keyPassword = requireNotNull(System.getenv("MITFAHRT_KEY_PASSWORD"))
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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
    implementation(project(":shared"))
    implementation("androidx.concurrent:concurrent-futures:1.2.0")
    implementation("androidx.wear.tiles:tiles:1.6.2")
    implementation("androidx.wear.protolayout:protolayout:1.4.2")
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.wear.compose:compose-material3:1.5.0")
    implementation("androidx.wear.compose:compose-foundation:1.5.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    testImplementation("junit:junit:4.13.2")
}
