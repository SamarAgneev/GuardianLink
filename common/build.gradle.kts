// common/build.gradle.kts
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
}

android {
    namespace  = "com.guardianlink.common"
    compileSdk = 34

    defaultConfig {
        minSdk    = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Firebase models reference Timestamp
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.firestore)

    // Security
    implementation(libs.security.crypto)

    // Coroutines
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.play.services)

    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.okhttp.logging)

    testImplementation(libs.junit)
}
