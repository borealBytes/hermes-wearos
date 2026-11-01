plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Single source of truth: repo-root VERSION (standard semver, see scripts/version.sh).
// versionCode derives arithmetically: major*10000 + minor*100 + patch.
val appVersion: String = rootProject.file("VERSION").readText().trim()
val appVersionCode: Int = appVersion.split(".").map { it.toInt() }
    .let { (maj, min, pat) -> maj * 10000 + min * 100 + pat }

android {
    namespace = "dev.hermes.wearos"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.hermes.wearos"
        minSdk = 30
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersion
        // Galaxy Watch Ultra ships a 32-bit userspace (zygote32, abilist=armeabi-v7a only!)
        // despite the 64-bit chip. x86_64 serves the emulator. Do NOT "modernize" this away.
        ndk { abiFilters += listOf("armeabi-v7a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.wear)
    implementation(libs.androidx.wear.compose.material)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.wear.tooling.preview)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.vosk.android)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    // M5: watch-native notifications, ask tile, attention complication.
    implementation(libs.androidx.wear.tiles)
    implementation(libs.androidx.wear.tiles.material)
    debugImplementation(libs.androidx.wear.tiles.tooling)
    implementation(libs.androidx.wear.watchface.complications.data.source)
    implementation(libs.androidx.wear.watchface.complications.data.source.ktx)
    // W1-W4: Health Services body tools (fail-soft: every call degrades to "unavailable").
    implementation(libs.androidx.health.services.client)
    testImplementation(libs.junit)
    // Pure state-machine tests drive suspend probe loops on the JVM (FailoverTest).
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}
