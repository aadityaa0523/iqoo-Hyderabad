# Nadaka: offline companion for blind users (iQOO Hackathon Hyderabad, 26-27 Sep 2026)

- Android, Kotlin, plain Views (no Compose), CameraX, LiteRT. minSdk 31, compileSdk 36.
- Runs fully offline. No network calls at runtime.
- Modes: WALK (obstacles, drop-offs, head height), PAY (currency notes), MEDS (medicine strips).
- Detector: `assets/detect.tflite` = YOLOX int8 from Qualcomm AI Hub (Apache-2.0), uint8 640x640 input; outputs boxes [x1,y1,x2,y2] px, scores, class_idx (uint8, dequantised in code), NMS in Detector.kt. Labels: `assets/labels.txt` (80 COCO).
- Inference order: QNN (NPU) delegate, then GPU, then CPU. Always show which one loaded.
- Decisions are deterministic rules. Never guess a medicine or a note: low confidence -> "Can't read clearly".
- All thresholds live in the `Settings` object (MainActivity.kt).
- Ego-motion: `EgoMotion.kt` (gyro yaw + step-detector speed, record-mode CSV logger), `Tracker.kt` (gyro-compensated tracking, looming TTC, speed toward user minus own walking, optional `assets/ego_model.json` classifier from `training/train_ego.py`). Design: `docs/ego-motion.md`.
- Log with tag `NADAKA`.
- Build + install: `./gradlew installDebug` (JAVA_HOME = `C:\Program Files\Android\Android Studio\jbr`). Logs: `adb logcat -s NADAKA` (adb is in `%LOCALAPPDATA%\Android\Sdk\platform-tools`).
- Push to `main` -> GitHub Actions builds the APK -> release `latest` (install from the phone browser during Red Light).
- Small changes, commit after each working milestone, never leave the build broken.
- Local Gemma: `Gemma.kt` runs Gemma 4 E2B (`.litertlm`, from Google AI Edge Gallery) with LiteRT-LM on the GPU, for "what's ahead?", sign reading and free questions. Never for safety (SafetyGate first; answers filtered by `SafetyGate.greenLight`). Put the model on a phone (app must have run once so it owns its folder):
  `adb shell am start -n app.nadaka/.MainActivity` then
  `adb shell cp /sdcard/Android/data/com.google.ai.edge.gallery/files/Gemma_4_E2B_it/*/gemma-4-E2B-it.litertlm /sdcard/Android/data/app.nadaka/files/`
