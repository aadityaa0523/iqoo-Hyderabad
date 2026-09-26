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
