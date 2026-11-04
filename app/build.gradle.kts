plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rem.screenguard"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rem.screenguard"
        minSdk = 30          // Android 11: first version with AccessibilityService.takeScreenshot()
        targetSdk = 34
        versionCode = 77
        versionName = "0.18.0"
        // PoC fleet is arm64 only (OnePlus 13, Galaxy): drops the x86/x86_64/armeabi
        // ONNX Runtime libraries and shrinks the APK from ~87 MB to ~36 MB.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    // .onnx assets are deliberately COMPRESSED (no noCompress entry).
    // AssetManager inflates them transparently on read: Falconsai
    // (v0.11.0, the judge) stream-copies its asset to internal
    // storage and creates its session from the file path (ORT mmap);
    // the incumbent classifier (restored v0.11.6 as the SUS
    // tripwire) loads from bytes via assets.open, its original
    // route, equally compression-agnostic. The fp32 weights deflate
    // substantially, which keeps the APK under the Gitea
    // release-asset truncation limit (~384 MB) that silently cut
    // v0.10.1.
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    // ONNX Runtime for Android (CPU by default; NNAPI optional in code)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")
}
