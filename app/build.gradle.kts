plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.yvora.app"
    // Updated to API 35
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yvora.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 16
        versionName = "2.0.2-dev"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        // Upgraded to Java 17
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }

    aaptOptions {
        // Required for MediaPipe task files
        noCompress += "task"
    }
}

dependencies {
    // --- UI & Core ---
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // --- Lifecycle ---
    val lifecycle_version = "2.8.4"
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:$lifecycle_version")
    implementation("androidx.lifecycle:lifecycle-service:$lifecycle_version")

    // --- CameraX ---
    val camerax_version = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax_version")
    implementation("androidx.camera:camera-camera2:$camerax_version")
    implementation("androidx.camera:camera-lifecycle:$camerax_version")
    implementation("androidx.camera:camera-view:$camerax_version")

    // --- MediaPipe ---
    implementation("com.google.mediapipe:tasks-vision:0.10.14")

    // --- Testing ---
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

// Branded copy of the assembled single debug APK; original AGP output remains intact.
tasks.register<Copy>("packageYvoraDebug") {
    dependsOn("assembleDebug")
    from(layout.buildDirectory.dir("outputs/apk/debug")) {
        include("app-debug.apk")
        rename("app-debug.apk", "Yvora-debug.apk")
    }
    into(layout.buildDirectory.dir("outputs/yvora"))
}
