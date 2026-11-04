# ScreenGuard — Content Guard

ScreenGuard is an on-device content guard for Android. An adult installs
and configures it, and it checks what is actually on the screen — in any
app and any browser. When its on-device detection finds adult content,
it covers the screen and steps in.

Published by Columbus Celeritas LLC. Free software under the
GNU General Public License v3.0 (see `LICENSE`).

## How it works

- With the user's explicit consent — given in a prominent in-app
  disclosure before the feature can be enabled — ScreenGuard uses
  Android's Accessibility Service to capture a screenshot of the screen
  at intervals while protection is on.
- Each screenshot is analysed **on the device, in memory only**, by two
  on-device models: a primary judge (Falconsai NSFW image detection)
  and a second tripwire classifier (NudeNet v0). Their verdicts
  accumulate as full-screen strikes and motion-region crop points, so
  only a sustained conviction blocks.
- On a block, the screen is covered, the foreground app is sent Home,
  and that app is quarantined on a fixed ladder: 10, 20, 40, 80, 160,
  320 seconds, capped at 600. Changing protection settings requires a
  deliberate unlock ritual — a 60-second reflection, a 10-minute wait,
  then a 10-minute window to make changes — so a moment of impulse does
  not switch protection off.
- Judging pauses while a voice or video call is on screen, and the
  device's launcher is never blocked.
- Check frequency is adjustable by app tier, with a built-in phone
  benchmark and a capture-health governor that keep the rate at one
  the phone can sustain.

## Privacy by construction

- The manifest does **not** request the `INTERNET` permission. The app
  cannot send anything anywhere.
- Screenshots are never saved, never stored, and never leave the
  device. No accounts, no advertising, no analytics, no crash
  reporting, no third-party tracking.
- The only record kept is a local event log on the device: the time of
  each event, the foreground app's package name, and the detector's
  verdict and score — never an image or screen text. You can read it in
  the app and erase it by clearing the app's data.
- Privacy policy: https://columbusceleritas.com/privacy-policy/

## Models

The two ONNX model files are **not in git** — one of them exceeds
GitHub's 100 MB per-file limit. They are attached as assets to the
**v0.18.0 release** of this repository, together with a `SHA256SUMS`
file:

- `falconsai.onnx` (343,401,688 bytes) — SHA-256
  `a4316a4fb750169ac4fcabaabee1fcbd982b0ee8c0cc63fe3e944954bb9a7d9c` —
  the `onnx-community/nsfw_image_detection-ONNX` export of
  `Falconsai/nsfw_image_detection` (Apache-2.0).
- `classifier.onnx` (83,591,087 bytes) — SHA-256
  `e0746121167fd94c9b5327831472221dca88fb3746c2a5ffd6b6f301fc1ff04a` —
  `classifier_model.onnx` from the NudeNet v0 release (GPL-3.0).

Download both files, verify their hashes, and place them where the
build expects them — the path depends on the branch:

- `play` (default branch): `assetpack-models/src/main/assets/falconsai.onnx`
  and `assetpack-models/src/main/assets/classifier.onnx`
- `master`: `app/src/main/assets/falconsai.onnx` and
  `app/src/main/assets/classifier.onnx`

Both paths are covered by `.gitignore`, so the files stay untracked.
Full provenance and licence details for every component are in
`THIRD-PARTY-NOTICES.md`.

## Branches

- `play` (default) — the Google Play line: compile/target SDK 36,
  ONNX Runtime 1.21.1, models delivered in an install-time asset pack,
  prominent-disclosure consent flow, in-app privacy policy and
  open-source licences screens. Current build: versionCode 78,
  version 0.18.0.
- `master` — the sideload line: compile/target SDK 34, models bundled
  in the app assets. This line's APK (debug-signed) is the
  `sg-v0180.apk` asset on the v0.18.0 release.

## Build

Requirements: JDK 17 and the Android SDK (platform 36 for `play`,
platform 34 for `master`). Place the two model files as described
above, then open the project in Android Studio or use the Gradle
wrapper:

```bash
# play branch — the Play bundle
./gradlew :app:bundleRelease

# master branch — a sideload APK
./gradlew :app:assembleDebug
```

The first build needs `local.properties` with your SDK path (Android
Studio creates it automatically when you open the project). Play
uploads are signed with the publisher's upload key, which is not in
this repository; without the key environment, the release build stays
unsigned by design.

## Install

Install the APK, open ScreenGuard, and follow the in-app setup steps:
enable the Accessibility service in Android Settings and exclude the
app from battery optimisation so Android does not kill it. On the
`play` line, the prominent disclosure and consent come first, before
the Accessibility step can be completed.

## Licence

ScreenGuard is free software: you can redistribute it and/or modify it
under the terms of the GNU General Public License v3.0 as published by
the Free Software Foundation. See `LICENSE` for the full text and
`THIRD-PARTY-NOTICES.md` for the component attributions.

Copyright (C) 2026 Columbus Celeritas LLC

## Contact

Columbus Celeritas LLC — screenguard@columbusceleritas.com
