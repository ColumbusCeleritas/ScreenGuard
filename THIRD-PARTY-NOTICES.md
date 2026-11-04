# ScreenGuard — licence and third-party notices

ScreenGuard is free software, released under the **GNU General Public
License v3.0** (full text in `LICENSE` at the repository root). The
combined work is licensed under GPL-3.0 because one shipped component —
the NudeNet v0 classifier — is itself GPL-3.0 (see below). This file is
the attribution record for every third-party component the app ships.
Licence texts live in `licenses/`; from the 2026-11-02 G3 cycle they
are also bundled unchanged inside the app (`app/src/main/assets/`,
byte-identical copies) and displayed by the in-app Open-source
licences screen, together with this notices file and the privacy
policy text.

Component identities below were byte-verified against upstream sources
on 2026-10-31 (model licence audit,
`goals/screenguard-android-poc/hidden_files/model-licence-audit-2026-10-31/`);
the library licence checks were re-verified from the publishers' own
package metadata on 2026-11-01.

## On-device models

### Primary judge — Falconsai NSFW image detection (Apache-2.0)

- Upstream model: `Falconsai/nsfw_image_detection` (Hugging Face),
  licensed **Apache-2.0** (base model card, `license: apache-2.0`).
- Shipped form: the ONNX export `onnx-community/nsfw_image_detection-ONNX`
  (`onnx/model.onnx`) — an automatic, mechanical format conversion that
  states no licence of its own; the base model's Apache-2.0 governs.
  The file is used **unmodified**: its SHA-256 is
  `a4316a4fb750169ac4fcabaabee1fcbd982b0ee8c0cc63fe3e944954bb9a7d9c`,
  identical to the upstream blob and to the model inside every shipped
  build (in the Play line it is delivered in the `models` asset pack as
  `falconsai.onnx`).
- Apache-2.0 obligations met here: licence text in
  `licenses/apache-2.0.txt`; this attribution; no changes were made to
  the model. The upstream repo ships no NOTICE file.

### Tripwire classifier — NudeNet v0 (GPL-3.0)

- Upstream: `notAI-tech/NudeNet`, release **v0** (author: bedapudi6788),
  licensed **GPL-3.0** at that release (the AGPL-3.0 seen in secondary
  sources is the later v3 branch and does not apply to the v0 weights).
- Shipped form: `classifier_model.onnx` from the v0 release asset
  (83,591,087 bytes upstream), used **unmodified**: SHA-256
  `e0746121167fd94c9b5327831472221dca88fb3746c2a5ffd6b6f301fc1ff04a`,
  identical to the upstream asset and to the model inside every shipped
  build (Play line: `classifier.onnx` in the `models` asset pack).
- The v0 README states that commercial use without open-sourcing
  requires the author's explicit written permission. ScreenGuard is
  open-sourced under GPL-3.0, so no separate permission is relied on.

## Libraries

### ONNX Runtime for Android 1.21.1 (MIT)

- `com.microsoft.onnxruntime:onnxruntime-android:1.21.1`
- Copyright (c) Microsoft Corporation. MIT License — full text in
  `licenses/onnxruntime-mit.txt` (taken from the `v1.21.1` tag of
  `microsoft/onnxruntime`).

### AndroidX Core KTX 1.13.1 (Apache-2.0)

- `androidx.core:core-ktx:1.13.1`
- "The Apache Software License, Version 2.0" per the component's own
  POM on Google Maven (verified 2026-11-01). Text in
  `licenses/apache-2.0.txt`.

### Kotlin standard library 2.0.20 (Apache-2.0)

- `org.jetbrains.kotlin:kotlin-stdlib:2.0.20`
- "The Apache License, Version 2.0" per the component's own POM on
  Maven Central (verified 2026-11-01). Text in
  `licenses/apache-2.0.txt`.

## Copyright line

The copyright-holder line for ScreenGuard itself is added when the
public repository is created under the project owner's account (the
public host is GitHub; the account name was still to be provided when
this file was written). Nothing in this file invents one.
