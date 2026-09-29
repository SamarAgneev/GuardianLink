// parent-app/build.gradle.kts
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
}

val cloudflareWorkerUrl = providers.gradleProperty("CLOUDFLARE_WORKER_URL")
    .orElse("https://guardianlink-api.workers.dev")
    .get()

android {
    namespace  = "com.guardianlink.parent"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.guardianlink.parent"
        minSdk        = 26
        targetSdk     = 34
        versionCode   = 1
        versionName   = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "CLOUDFLARE_WORKER_URL", "\"$cloudflareWorkerUrl\"")

        // Inject Maps API key from secrets.properties
        manifestPlaceholders["MAPS_API_KEY"] =
            (project.findProperty("MAPS_API_KEY") as? String) ?: ""
    }

    buildTypes {
        release {
            isMinifyEnabled   = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":common"))

    // Core AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)

    // Lifecycle
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.livedata)
    implementation(libs.lifecycle.runtime)

    // Navigation
    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)

    // Hilt DI
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.realtime.db)
    implementation(libs.firebase.storage)
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.analytics)

    // WorkManager (parent uses for periodic report generation)
    implementation(libs.workmanager)

    // Coroutines
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.play.services)

    // WebRTC (parent receives streams)
    implementation(libs.webrtc)

    // Maps + Location
    implementation(libs.maps)
    implementation(libs.location)

    // Security
    implementation(libs.security.crypto)
    implementation(libs.biometric)

    // Image loading (display camera frames)
    implementation(libs.coil)

    // Charts (screen time reports)
    implementation(libs.mpandroid.chart)

    // Animations (Lottie)
    implementation(libs.lottie)

    // Logging
    implementation("com.jakewharton.timber:timber:5.0.1")

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.espresso)
}

kapt { correctErrorTypes = true }
