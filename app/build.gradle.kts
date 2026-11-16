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
        // -PversionCodeOverride / -PupdateUrl are only used by the update test in CI.
        val build = (project.findProperty("versionCodeOverride") as String?)
            ?: System.getenv("GITHUB_RUN_NUMBER") ?: "1"
        versionCode = build.toInt()
        versionName = "1.0.$build"
        // Where the app looks for updates (published by GitHub Actions for every build of main).
        // An empty value switches the updater off.
        resValue(
            "string", "update_url",
            (project.findProperty("updateUrl") as String?)
                ?: "https://github.com/ThatsALotOfBees2/tv-time/releases/latest/download/version.json",
        )
    }

    buildFeatures {
        resValues = true
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

    // The start page and the YouTube ad remover are shared with the Raspberry Pi version.
    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets", "../web")
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
