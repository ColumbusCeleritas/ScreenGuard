# ScreenGuard PoC v0.1

On-device Android screen guard: watches the screen locally and closes the
foreground app when adult content is detected. Built for Alejandro's
adult-content blocking project (Phase: single Android device, on-screen layer).

## How it works

1. **Capture** — an Accessibility Service uses `takeScreenshot()` (Android 11+,
   no MediaProjection consent popup every session) on a timer:
   ~1.5 s when a "risky" app (browser, social, video, chat) is in front,
   ~5 s otherwise. Static frames are skipped via a cheap thumbnail diff.
2. **Detect** — each changed frame is run, *in memory only*, through
   **NudeNet v3 320n** (YOLOv8n, ONNX, ~12 MB, bundled in `assets/320n.onnx`).
   Only the `*_EXPOSED` classes can trigger: `FEMALE_GENITALIA_EXPOSED`,
   `FEMALE_BREAST_EXPOSED`, `MALE_GENITALIA_EXPOSED`, `ANUS_EXPOSED`,
   `BUTTOCKS_EXPOSED`. Covered body parts, bellies, faces, swimwear etc.
   deliberately never trigger — this is the false-positive guard for medical,
   breastfeeding and family-photo content.
3. **Act** — on N consecutive hits (default 2) above the threshold
   (default 0.55): an opaque accessibility overlay covers the screen instantly,
   then `GLOBAL_ACTION_HOME` throws the app off screen. Warn-only mode exists
   for tuning.

## Privacy by construction

- The manifest does **not** request `INTERNET` permission. The app physically
  cannot send anything anywhere.
- Screenshots are never written to disk. The only log kept on the phone is:
  timestamp + foreground package + detected class + score (in the app UI).
- All inference is on-device (ONNX Runtime, CPU).

## Known PoC limits

- Apps with `FLAG_SECURE` (banking, DRM video) return black frames — accepted.
- A normal app cannot force-stop another app; overlay + Home is the action.
  Hardened v1 would install as Device Owner to also suspend apps and prevent
  uninstall/disable.
- Illustrated/anime content is a known weak spot of photo-trained detectors;
  a cartoon-NSFW classifier and/or a small VLM judge (SmolVLM) are planned
  as stage 2.

## Build

Requirements: JDK 17, Android SDK platform 34 + build-tools 34.

```powershell
# Windows (from this folder)
.\gradlew.bat assembleDebug
# APK -> app\build\outputs\apk\debug\app-debug.apk
```

```bash
# Linux/macOS
./gradlew assembleDebug
```

First build needs `local.properties` with your SDK path, e.g.
`sdk.dir=C:\\Users\\<you>\\AppData\\Local\\Android\\Sdk`
(Android Studio creates this automatically when you open the project.)

## Install & test

1. `adb install -r app-debug.apk` (or copy the APK to the phone and open it —
   allow "install unknown apps" for your file manager once).
2. Open **ScreenGuard PoC** → button 1 enables it in
   *Settings → Accessibility → Installed apps → ScreenGuard*.
3. Button 2: exclude it from battery optimisation so Android doesn't kill it.
4. Run the in-app **self-test** (sanity-checks the ONNX pipeline).
5. Tune: start in *warn-only* mode with threshold 0.55 and 2 consecutive hits,
   browse normally (social feeds, swimwear, medical/breastfeeding pages,
   family photos) and watch the event log for false alarms before switching
   Enforce on.

## Layout

- `GuardService.kt` — capture loop, frame diff, decision, overlay + Home
- `NudeNetDetector.kt` — ONNX preprocessing (320×320 letterbox) + postprocess
- `MainActivity.kt` — status, threshold/mode settings, self-test, event log
- `Prefs.kt` — settings/status store
