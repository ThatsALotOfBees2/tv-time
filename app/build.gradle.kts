plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tvtime.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tvtime.app"
        minSdk = 21
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    signingConfigs {
        // A fixed key checked into the repo so every build (local or CI) has the same
        // signature and new versions install over old ones. It protects nothing secret.
        create("tvtime") {
            storeFile = file("tvtime.keystore")
            storePassword = "tvtime"
            keyAlias = "tvtime"
            keyPassword = "tvtime"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("tvtime")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("tvtime")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
}
