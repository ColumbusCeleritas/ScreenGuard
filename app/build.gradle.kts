plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Play upload signing (play branch only). The upload keystore lives ONLY in
// the build machine's secrets store (VM: ~/.secrets, 600 perms; laptop: the
// build tree's .tools folder) — never in this repo. Signing is wired ONLY
// when all four SG_UPLOAD_* environment variables are present (sourced from
// a 600-perm env file at build time); without them the release build type
// stays unsigned-by-config exactly as before, so the debug/sideload flow
// and any machine without the key are never broken by this block.
val uploadStoreFile = System.getenv("SG_UPLOAD_STORE_FILE")
val uploadStorePassword = System.getenv("SG_UPLOAD_STORE_PASSWORD")
val uploadKeyAlias = System.getenv("SG_UPLOAD_KEY_ALIAS")
val uploadKeyPassword = System.getenv("SG_UPLOAD_KEY_PASSWORD")
val hasUploadSigning = !uploadStoreFile.isNullOrBlank() &&
    !uploadStorePassword.isNullOrBlank() &&
    !uploadKeyAlias.isNullOrBlank() &&
    !uploadKeyPassword.isNullOrBlank()

android {
    namespace = "com.rem.screenguard"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.columbusceleritas.screenguard"
        minSdk = 30          // Android 11: first version with AccessibilityService.takeScreenshot()
        targetSdk = 36
        versionCode = 85
        versionName = "0.19.6"
        // PoC fleet is arm64 only (OnePlus 13, Galaxy): drops the x86/x86_64/armeabi
        // ONNX Runtime libraries and shrinks the APK from ~87 MB to ~36 MB.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        if (hasUploadSigning) {
            create("release") {
                storeFile = file(uploadStoreFile!!)
                storePassword = uploadStorePassword
                keyAlias = uploadKeyAlias
                keyPassword = uploadKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasUploadSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    // Play Asset Delivery (play branch only): the two ONNX models live in
    // the install-time "models" asset pack, not in the base module.
    assetPacks += ":assetpack-models"
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
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.21.1")
    // v0.19.0 (play-iap): Play Billing for the Supporter Pack (themes
    // only — protection is never sold). App module only; the models
    // asset pack is untouched. Version = the latest stable on Google
    // Maven at build time (metadata <release> 9.1.0, 2026-06-18).
    implementation("com.android.billingclient:billing:9.1.0")
}
