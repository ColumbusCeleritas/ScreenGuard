plugins {
    id("com.android.asset-pack")
}

// Play Asset Delivery — install-time pack carrying the two ONNX models
// (~396 MB compressed total). Install-time packs are installed together
// with the base module and their assets are merged into the app's
// AssetManager, so FalconModel / SceneClassifier keep loading via
// assets.open(...) unchanged. Verified by bundletool install + the
// FAL/CLS healthy-state emulator gate (play-readiness programme,
// priority 3, 2026-10-31).
assetPack {
    packName.set("models")
    dynamicDelivery {
        deliveryType.set("install-time")
    }
}
