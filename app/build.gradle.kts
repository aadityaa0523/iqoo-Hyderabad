import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.nadaka"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.nadaka"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += "arm64-v8a" } // iQOO is arm64; keeps the APK small for phone downloads
        // Cloud key from local.properties (git-ignored): never in the source or on GitHub.
        val props = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
        buildConfigField("String", "OPENROUTER_KEY", "\"" + props.getProperty("openrouter.key", "") + "\"")
    }
    buildFeatures { buildConfig = true }

    // Shared debug key so laptop (adb) and GitHub Actions builds install over each other.
    signingConfigs {
        getByName("debug") { storeFile = rootProject.file("debug.keystore") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources { noCompress += "tflite" }

    // QNN needs its Hexagon skel libs extracted to nativeLibraryDir.
    // Drop QNN pieces we don't use: DSP/GPU backends and pre-8-Gen-3 Hexagon versions.
    // ponytail: once the loaner's chip is known (logcat "NPU"), keep only its HtpV*Skel/Stub.
    packaging {
        jniLibs {
            useLegacyPackaging = true
            excludes += listOf("QnnDsp*", "QnnGpu*", "QnnHtpV68*", "QnnHtpV69*", "QnnHtpV73*").map { "lib/arm64-v8a/lib$it.so" }
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.13.0")
    val camerax = "1.6.2"
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("com.google.ai.edge.litert:litert:1.4.2")
    implementation("com.google.ai.edge.litert:litert-gpu:1.4.2")
    implementation("com.google.mlkit:text-recognition:16.0.1") // bundled Latin OCR, works offline
    implementation("com.google.mlkit:translate:17.0.3") // English -> Hindi / Telugu answers, on-device after one download
    val qnn = "2.50.0" // Qualcomm Hexagon NPU delegate for LiteRT
    implementation("com.qualcomm.qti:qnn-litert-delegate:$qnn")
    implementation("com.qualcomm.qti:qnn-runtime:$qnn")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.17.1") // local Gemma (.litertlm) runtime
    testImplementation("junit:junit:4.13.2")
}
