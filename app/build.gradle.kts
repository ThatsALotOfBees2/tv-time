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

    // GeckoView carries a whole browser engine per CPU type, so build one APK per type
    // (app-<abi>-release.apk) instead of one huge APK. The updater picks the right one.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    // Keep folders starting with "_" in assets: uBlock Origin needs its _locales folder
    // (Android's default pattern silently drops them).
    androidResources {
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:!CVS:!thumbs.db:!picasa.ini:!*~"
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
    // Firefox's engine, with uBlock Origin built in (see tools/fetch-ublock.sh).
    implementation("org.mozilla.geckoview:geckoview:${property("geckoviewVersion")}")
}

// uBlock Origin is downloaded at build time into src/main/assets/ublock (not kept in git).
val checkUblock by tasks.registering {
    doLast {
        if (!file("src/main/assets/ublock/manifest.json").exists()) {
            throw GradleException("uBlock Origin is missing: run tools/fetch-ublock.sh first")
        }
    }
}
tasks.named("preBuild") { dependsOn(checkUblock) }
