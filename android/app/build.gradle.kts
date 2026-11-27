import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Optional local config (never committed): relay URL, Firebase Messaging ids, signing.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun prop(k: String, d: String = "") = localProps.getProperty(k) ?: d

android {
    namespace = "com.nextlayer.betweenly"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.nextlayer.betweenly"
        minSdk = 28
        targetSdk = 35
        versionCode = 7
        versionName = "0.0.7"
        ndk { abiFilters += listOf("arm64-v8a") }
        // DATA-FLOW: the only server the app talks to for messages/updates (besides map tiles + FCM).
        buildConfigField("String", "RELAY_URL", "\"${prop("relay.url", "https://loviedowpie-relay.example.workers.dev")}\"")
        buildConfigField("String", "FCM_PROJECT_ID", "\"${prop("fcm.projectId")}\"")
        buildConfigField("String", "FCM_APP_ID", "\"${prop("fcm.appId")}\"")
        buildConfigField("String", "FCM_API_KEY", "\"${prop("fcm.apiKey")}\"")
        buildConfigField("String", "FCM_SENDER_ID", "\"${prop("fcm.senderId")}\"")
        // GIF search. Empty until klipy.apiKey is set in local.properties.
        buildConfigField("String", "KLIPY_API_KEY", "\"${prop("klipy.apiKey").replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }
    signingConfigs {
        create("release") {
            val ks = prop("signing.keystore")
            if (ks.isNotEmpty()) {
                storeFile = file(ks)
                storePassword = prop("signing.password")
                keyAlias = prop("signing.alias", "betweenly")
                keyPassword = prop("signing.password")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (prop("signing.keystore").isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "DebugProbesKt.bin") }
}

// Build the Rust core for Android and drop the .so where Gradle packages it.
val buildRust by tasks.registering(Exec::class) {
    workingDir = rootProject.projectDir.parentFile
    val ndk = System.getenv("ANDROID_NDK_HOME") ?: ""
    environment("ANDROID_NDK_HOME", ndk)
    commandLine(
        "bash", "-lc",
        "source \$HOME/.cargo/env && cargo ndk -t arm64-v8a -o android/app/src/main/jniLibs build -p betweenly-core --release --features jni-api"
    )
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(buildRust) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.05.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    // Camera for the in-app camera (V2). CameraX = Google's thin wrapper over Camera2.
    val camerax = "1.4.2"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("com.google.firebase:firebase-messaging:24.1.1") // Messaging only. No Analytics.
}
