# NPU verification and benchmark (iQOO I2501, SM8850 / Hexagon HTP V81, Android 16)

Reproduce: `adb push frame.jpg /sdcard/Android/data/app.nadaka/files/bench.jpg`, then
`adb shell am start -n app.nadaka/.BenchActivity` (optionally `--ei runs 100`), then
`adb pull /sdcard/Android/data/app.nadaka/files/bench.md`. No camera runs during the benchmark.
Each row: fresh interpreter, 10 warm-up + 100 timed runs on the same real 480x640 camera frame.
"Delegated ops" is read from the runtime's own log ("Replacing X out of Y node(s) with delegate").

## Verification (Phase 1)

| Check | Result |
|---|---|
| Delegate / backend | `QnnDelegate.Options`: `HTP_BACKEND`, skel dir = `nativeLibraryDir`, `HTP_PERFORMANCE_SUSTAINED_HIGH_PERFORMANCE`, FP16 precision for depth |
| HTP libraries packaged and installed | libQnnHtp, libQnnHtpPrepare, libQnnHtpV75/V79/**V81** Skel+Stub, libQnnSystem, libQnnTFLiteDelegate (checked in APK and in the installed lib dir) |
| Full delegation (runtime log) | YOLOX **317/317**, Depth Anything V2 **598/598** nodes to `TfLiteQnnDelegate` |
| Graph caching | **Was not working** (no model token: only 20 B-2 KB metadata files). Fixed with `setModelToken`: `qnn_binary_*` 9.6 MB (YOLOX) and 52.7 MB (depth) now written; HTP init YOLOX 624 -> 143-147 ms, depth 5,235 -> 161-170 ms |
| Debug field | HUD header: `YOLO <HTP/GPU/CPU> <ms> · Depth <HTP/GPU/CPU> <ms> · fps · lens` |

## Final results (after pre-processing fix)

# Nadaka inference benchmark
Device: I2501 (SM8850), Android 16; frame 480x640; 100 timed runs after 10 warm-up; ms

| Model | Backend | Delegated ops | Init ms | Stage | Median | P90 | P95 | Min | Max |
|---|---|---|---|---|---|---|---|---|---|
| YOLOX | HTP | 317/317 (TfLiteQnnDelegate) | 143 | pre | 2.08 | 2.10 | 2.13 | 1.94 | 2.21 |
| YOLOX | HTP | 317/317 (TfLiteQnnDelegate) | 143 | inference | 1.78 | 1.80 | 1.80 | 1.75 | 2.07 |
| YOLOX | HTP | 317/317 (TfLiteQnnDelegate) | 143 | post | 0.10 | 0.10 | 0.10 | 0.10 | 0.14 |
| YOLOX | HTP | 317/317 (TfLiteQnnDelegate) | 143 | total | 3.95 | 3.99 | 4.04 | 3.82 | 4.17 |
| YOLOX | GPU | 315/317 (TfLiteGpuDelegateV2) | 1222 | pre | 11.00 | 14.14 | 14.48 | 3.99 | 15.21 |
| YOLOX | GPU | 315/317 (TfLiteGpuDelegateV2) | 1222 | inference | 35.24 | 39.65 | 39.82 | 31.12 | 40.73 |
| YOLOX | GPU | 315/317 (TfLiteGpuDelegateV2) | 1222 | post | 1.00 | 1.20 | 1.25 | 0.26 | 1.33 |
| YOLOX | GPU | 315/317 (TfLiteGpuDelegateV2) | 1222 | total | 47.77 | 54.72 | 55.07 | 35.70 | 56.06 |
| YOLOX | CPU | XNNPACK (CPU) | 28 | pre | 2.27 | 2.58 | 2.64 | 2.03 | 2.93 |
| YOLOX | CPU | XNNPACK (CPU) | 28 | inference | 76.34 | 87.87 | 88.18 | 75.88 | 91.42 |
| YOLOX | CPU | XNNPACK (CPU) | 28 | post | 0.11 | 0.13 | 0.13 | 0.11 | 0.17 |
| YOLOX | CPU | XNNPACK (CPU) | 28 | total | 78.83 | 90.57 | 90.84 | 78.22 | 94.05 |
| Depth | HTP | 598/598 (TfLiteQnnDelegate) | 170 | pre | 10.96 | 13.88 | 13.91 | 2.13 | 13.97 |
| Depth | HTP | 598/598 (TfLiteQnnDelegate) | 170 | inference | 27.50 | 28.15 | 28.66 | 26.64 | 30.85 |
| Depth | HTP | 598/598 (TfLiteQnnDelegate) | 170 | post | 6.46 | 10.10 | 11.58 | 1.02 | 17.57 |
| Depth | HTP | 598/598 (TfLiteQnnDelegate) | 170 | total | 45.84 | 48.73 | 50.03 | 31.78 | 50.60 |
| Depth | GPU | 598/598 (TfLiteGpuDelegateV2) | 1476 | pre | 13.98 | 14.76 | 14.92 | 4.82 | 16.52 |
| Depth | GPU | 598/598 (TfLiteGpuDelegateV2) | 1476 | inference | 229.66 | 230.71 | 232.09 | 227.33 | 232.94 |
| Depth | GPU | 598/598 (TfLiteGpuDelegateV2) | 1476 | post | 8.73 | 10.18 | 10.84 | 2.45 | 12.14 |
| Depth | GPU | 598/598 (TfLiteGpuDelegateV2) | 1476 | total | 250.64 | 255.18 | 256.04 | 239.19 | 258.89 |
| Depth | CPU | XNNPACK (CPU) | 89 | pre | 2.09 | 2.26 | 2.30 | 1.91 | 3.92 |
| Depth | CPU | XNNPACK (CPU) | 89 | inference | 556.47 | 581.72 | 587.85 | 548.46 | 607.28 |
| Depth | CPU | XNNPACK (CPU) | 89 | post | 1.86 | 2.01 | 2.13 | 0.65 | 2.50 |
| Depth | CPU | XNNPACK (CPU) | 89 | total | 560.47 | 585.54 | 592.58 | 551.24 | 612.49 |


## Baseline before the pre-processing fix (cold graph cache)

# Nadaka inference benchmark
Device: I2501 (SM8850), Android 16; frame 480x640; 100 timed runs after 10 warm-up; ms

| Model | Backend | Delegated ops | Init ms | Stage | Median | P90 | P95 | Min | Max |
|---|---|---|---|---|---|---|---|---|---|
| YOLOX | HTP | 317/317 (TfLiteQnnDelegate) | 624 | pre | 15.04 | 22.35 | 22.60 | 14.76 | 25.04 |
| YOLOX | HTP | 317/317 (TfLiteQnnDelegate) | 624 | inference | 1.95 | 2.00 | 2.03 | 1.91 | 2.25 |
| YOLOX | HTP | 317/317 (TfLiteQnnDelegate) | 624 | post | 0.10 | 0.14 | 0.14 | 0.10 | 0.26 |
| YOLOX | HTP | 317/317 (TfLiteQnnDelegate) | 624 | total | 17.11 | 24.39 | 24.76 | 16.80 | 27.18 |
| YOLOX | GPU | 315/317 (TfLiteGpuDelegateV2) | 1626 | pre | 20.11 | 20.20 | 20.24 | 19.91 | 24.30 |
| YOLOX | GPU | 315/317 (TfLiteGpuDelegateV2) | 1626 | inference | 37.02 | 37.14 | 37.17 | 36.61 | 37.24 |
| YOLOX | GPU | 315/317 (TfLiteGpuDelegateV2) | 1626 | post | 0.14 | 0.15 | 0.16 | 0.14 | 0.18 |
| YOLOX | GPU | 315/317 (TfLiteGpuDelegateV2) | 1626 | total | 57.27 | 57.42 | 57.47 | 56.90 | 61.59 |
| YOLOX | CPU | XNNPACK (CPU) | 18 | pre | 20.09 | 22.48 | 22.65 | 19.87 | 24.81 |
| YOLOX | CPU | XNNPACK (CPU) | 18 | inference | 93.89 | 97.20 | 98.41 | 93.63 | 100.25 |
| YOLOX | CPU | XNNPACK (CPU) | 18 | post | 0.14 | 0.15 | 0.15 | 0.14 | 0.35 |
| YOLOX | CPU | XNNPACK (CPU) | 18 | total | 114.48 | 118.65 | 120.17 | 113.76 | 122.72 |
| Depth | HTP | 598/598 (TfLiteQnnDelegate) | 5235 | pre | 34.09 | 34.26 | 34.32 | 33.80 | 36.39 |
| Depth | HTP | 598/598 (TfLiteQnnDelegate) | 5235 | inference | 27.75 | 27.87 | 28.02 | 25.95 | 28.28 |
| Depth | HTP | 598/598 (TfLiteQnnDelegate) | 5235 | post | 2.07 | 3.01 | 3.31 | 0.96 | 4.67 |
| Depth | HTP | 598/598 (TfLiteQnnDelegate) | 5235 | total | 63.87 | 64.95 | 65.67 | 60.98 | 66.50 |
| Depth | GPU | 598/598 (TfLiteGpuDelegateV2) | 1574 | pre | 81.84 | 91.65 | 95.09 | 57.09 | 102.21 |
| Depth | GPU | 598/598 (TfLiteGpuDelegateV2) | 1574 | inference | 249.58 | 260.09 | 262.16 | 225.36 | 263.51 |
| Depth | GPU | 598/598 (TfLiteGpuDelegateV2) | 1574 | post | 8.72 | 9.92 | 10.76 | 0.99 | 13.12 |
| Depth | GPU | 598/598 (TfLiteGpuDelegateV2) | 1574 | total | 339.61 | 355.78 | 359.17 | 289.00 | 366.38 |
| Depth | CPU | XNNPACK (CPU) | 97 | pre | 34.13 | 35.80 | 36.41 | 33.84 | 42.41 |
| Depth | CPU | XNNPACK (CPU) | 97 | inference | 585.02 | 594.43 | 596.69 | 577.06 | 603.32 |
| Depth | CPU | XNNPACK (CPU) | 97 | post | 1.95 | 2.11 | 2.15 | 0.68 | 2.30 |
| Depth | CPU | XNNPACK (CPU) | 97 | total | 621.23 | 630.58 | 632.84 | 611.74 | 639.82 |


## Summary

| Model | Backend | Precision | Delegated ops | Inference median / P95 | Total median / P95 |
|---|---|---|---|---|---|
| YOLOX | CPU (XNNPACK, 4 threads) | w8a8 | n/a | 76.3 / 88.2 ms | 78.8 / 90.8 ms |
| YOLOX | GPU | w8a8 | 315/317 | 35.2 / 39.8 ms | 47.8 / 55.1 ms |
| YOLOX | **HTP** | **w8a8** | **317/317** | **1.78 / 1.80 ms** | **3.95 / 4.04 ms** |
| Depth Anything V2 | CPU (XNNPACK) | FP32 | n/a | 556.5 / 587.9 ms | 560.5 / 592.6 ms |
| Depth Anything V2 | GPU | FP32 | 598/598 | 229.7 / 232.1 ms | 250.6 / 256.0 ms |
| Depth Anything V2 | **HTP** | **FP16** | **598/598** | **27.5 / 28.7 ms** | **45.8 / 50.0 ms** |
| Depth Anything V2 | HTP | INT8 | - | not available (see below) | - |

Pre-processing fix (bulk array copy instead of per-element `ByteBuffer.put`): YOLOX HTP total 17.1 -> 3.95 ms,
depth HTP total 63.9 -> 45.8 ms, same tensor values.

## Depth INT8 (Phase 3)

Qualcomm AI Hub 0.63.0 publishes Depth Anything V2 quantized only as **w8a16 in ONNX / QNN-DLC**, not LiteRT;
V1 likewise; V3 is float-only. No INT8 LiteRT build exists to test. Producing one needs a calibration set of
real frames from the target scenes (not yet recorded) plus either an AI Hub account (quantize/compile job)
or a local ONNX -> TFLite PTQ toolchain, and full w8a8 on this ViT (DINOv2) backbone is where Qualcomm itself
stopped at w8a16. `depth_int8.tflite` was therefore **not created**; phases 4-6 (scene-by-scene FP16 vs INT8
and drop-off impact) have nothing to compare yet.

**Recommendation: keep FP16 (FP32 weights executed at FP16 on HTP) as production.** Depth HTP inference is
27.5 ms of a 45.8 ms depth step; even a perfect 2x INT8 speed-up would save ~14 ms, while the drop-off
detector depends on depth-edge sharpness that quantization tends to blur.
