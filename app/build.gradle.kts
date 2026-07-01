plugins {
    alias(libs.plugins.android.application)
}

android {
    // Keep the Java package namespace as com.example.obd so we don't have to refactor
    // every .java file. applicationId is the user-facing ID Play Store sees — that's
    // what changes for the Otto rebrand.
    namespace = "com.example.obd"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.otto.car"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.0-otto"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Signing — release builds sign with debug for now so we can produce APKs without
    // a real keystore in source. Before the first Play Console upload, replace this
    // with a real keystore loaded from gradle.properties (kept out of git).
    signingConfigs {
        // The debug signing config is auto-created by AGP; we reuse it for release
        // until the user generates a production keystore. Document this in CI.
    }

    buildTypes {
        release {
            // R8 with full shrinking enabled. Cuts APK size and obfuscates code so
            // reverse-engineering is harder. ProGuard rules in proguard-rules.pro
            // keep Room generated classes, Gemini JSON model, and reflection paths.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Sign with debug until a real keystore exists — produces installable
            // release APKs for testing without manual keystore setup.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    implementation("androidx.compose.ui:ui:1.5.0")
    implementation("androidx.compose.material3:material3:1.2.0")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation ("com.github.PhilJay:MPAndroidChart:v3.1.0")

    // Room — local persistence for trips, PID samples, DTC events, VIN profiles, CBS ledger.
    implementation("androidx.room:room-runtime:2.6.1")
    annotationProcessor("androidx.room:room-compiler:2.6.1")

    // SplashScreen compat library — uses Android 12+ native splash on new devices,
    // back-ports the same API to API 23+. We install it before super.onCreate so the
    // splash window shows the launcher icon on a brand-coloured background while the
    // main Activity inflates.
    implementation("androidx.core:core-splashscreen:1.0.1")

}