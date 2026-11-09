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
        versionCode = 16
        versionName = "0.16.0"
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
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.maxHeapSize = "1g"
            it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED")
            val roboHome = layout.buildDirectory.dir("robolectric-home").get().asFile
            it.systemProperty("user.home", roboHome.absolutePath)
            it.doFirst { roboHome.mkdirs() }
            it.systemProperty("maven.repo.local", layout.buildDirectory.dir("robolectric-repository").get().asFile.absolutePath)
            it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
        }
    }
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
    implementation(project(":shared"))
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
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
    testImplementation("org.robolectric:robolectric:4.17")
}
