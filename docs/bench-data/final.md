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
