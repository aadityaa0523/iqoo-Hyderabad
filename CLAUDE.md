# Nadaka: offline companion for blind users (iQOO Hackathon Hyderabad, 26-27 Sep 2026)

- Android, Kotlin, plain Views (no Compose), CameraX, LiteRT. minSdk 31, compileSdk 36.
- Runs fully offline. No network calls at runtime.
- Modes: WALK (obstacles, drop-offs, head height), PAY (currency notes), MEDS (medicine strips).
- Detector: `assets/detect.tflite` = EfficientDet-Lite0 (uint8 320x320 input; outputs boxes [ymin,xmin,ymax,xmax], classes, scores, count). Labels in `assets/labels.txt` (COCO, "???" = unused id).
- Inference order: QNN (NPU) delegate, then GPU, then CPU. Always show which one loaded.
- Decisions are deterministic rules. Never guess a medicine or a note: low confidence -> "Can't read clearly".
- All thresholds live in the `Settings` object (MainActivity.kt).
- Log with tag `NADAKA`.
- Build + install: `./gradlew installDebug` (JAVA_HOME = `C:\Program Files\Android\Android Studio\jbr`). Logs: `adb logcat -s NADAKA` (adb is in `%LOCALAPPDATA%\Android\Sdk\platform-tools`).
- Push to `main` -> GitHub Actions builds the APK -> release `latest` (install from the phone browser during Red Light).
- Small changes, commit after each working milestone, never leave the build broken.
