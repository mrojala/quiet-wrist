plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// CI passes the run number so every build is installable as an upgrade.
val buildVersionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()

// Experiment builds override the application id to borrow the identity of an app
// on Huawei Health's quick-reply whitelist (SMS, WhatsApp, Messenger, Telegram).
// Sideload-only: see the "Replying from the watch" section of the README.
val defaultApplicationId = "com.mrojala.quietwrist"
val buildApplicationId =
    System.getenv("APPLICATION_ID")?.takeIf { it.isNotBlank() } ?: defaultApplicationId
val isExperiment = buildApplicationId != defaultApplicationId

android {
    namespace = "com.mrojala.quietwrist"
    compileSdk = 35

    defaultConfig {
        applicationId = buildApplicationId
        // Ranking.getLastAudiblyAlertedMillis() is API 29.
        minSdk = 29
        targetSdk = 35
        versionCode = buildVersionCode
        versionName = "0.1.$buildVersionCode"
        // Named in code so an experiment build is obvious in the launcher and in
        // Huawei Health's app list, whatever package name it is wearing.
        resValue("string", "app_name", if (isExperiment) "QuietWrist EXP" else "QuietWrist")
    }

    // Present only when the CI secrets are decoded into app/release.p12.
    val keystoreFile = file("release.p12")
    signingConfigs {
        if (keystoreFile.exists()) {
            create("release") {
                storeFile = keystoreFile
                storeType = "PKCS12"
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS") ?: "quietwrist"
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
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
        viewBinding = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
}
