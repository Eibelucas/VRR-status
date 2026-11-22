plugins {
    id("com.android.application")
}

android {
    namespace = "de.luca.mitfahrt.watchface"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "de.luca.mitfahrt.watchface"
        minSdk = 33
        targetSdk = 36
        versionCode = rootProject.extra["mitfahrtVersionCode"] as Int
        versionName = rootProject.extra["mitfahrtVersionName"] as String
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

    buildFeatures {
        buildConfig = false
        resValues = false
    }

    buildTypes {
        debug {
            isMinifyEnabled = true
            isShrinkResources = false
        }
        release {
            // R8 strips generated R.jar so the WFF package has zero DEX files (required for minSdk >= 33).
            // Keep isShrinkResources = false so WFF XML / preview assets are never stripped.
            isMinifyEnabled = true
            isShrinkResources = false
            signingConfig = signingConfigs.getByName(if (releaseStore == null) "debug" else "releaseKey")
        }
    }
}
