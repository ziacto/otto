import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Read the Gemini API key from local.properties (which is git-ignored). Fresh
// clones with no local.properties AI_KEY line still compile — the AI features
// just degrade to "AI unavailable" until the dev drops their key in.
val aiKey: String = run {
    val f = rootProject.file("local.properties")
    if (!f.exists()) return@run ""
    val props = Properties()
    f.inputStream().use { props.load(it) }
    props.getProperty("AI_KEY", "")
}

android {
    // Keep the Java package namespace as com.example.obd so we don't have to refactor
    // every .java file. applicationId is the user-facing ID Play Store sees — that's
    // what changes for the Otto rebrand.
    namespace = "com.example.obd"
    compileSdk = 36

    // Emit BuildConfig fields (needed to inject AI_KEY). AGP 8 disables this by default.
    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "app.otto.car"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.0-otto"

        buildConfigField("String", "AI_KEY", "\"$aiKey\"")

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
    kotlinOptions {
        jvmTarget = "11"
    }
    // GLBs are pre-compressed; disable AGP's asset compression so runtime
    // parsing avoids a decompress-then-parse hop.
    androidResources {
        noCompress += listOf("glb", "gltf")
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
    implementation ("com.github.PhilJay:MPAndroidChart:v3.1.0")

    // Room — local persistence for trips, PID samples, DTC events, VIN profiles, CBS ledger.
    implementation("androidx.room:room-runtime:2.6.1")
    annotationProcessor("androidx.room:room-compiler:2.6.1")

    // SplashScreen compat library — uses Android 12+ native splash on new devices,
    // back-ports the same API to API 23+. We install it before super.onCreate so the
    // splash window shows the launcher icon on a brand-coloured background while the
    // main Activity inflates.
    implementation("androidx.core:core-splashscreen:1.0.1")

    // SceneView + Filament — real-time PBR rendering of a GLB (BMW M4 F82).
    // Adds ~15-18 MB to the APK (Filament native libs) plus the ~23 MB
    // model itself. Kotlin required at the call site — see CarHeroSceneView.kt.
    implementation("io.github.sceneview:sceneview:2.2.1")

    // Kotlin standard library (implicit but pin it so version resolution
    // doesn't drift when other deps drag it in).
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
}