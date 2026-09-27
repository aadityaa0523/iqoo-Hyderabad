# Nadaka

**An on-device walking safety system for blind and low-vision people, running entirely on one iQOO phone.**

Nadaka is worn at chest height with the camera facing forward. While the user walks, it continuously measures the
path ahead and turns what it finds into vibration first and short speech second: drop-offs and stairs, head- and
waist-height obstacles, people and vehicles coming closer, and anything a white cane cannot reach in time. On request
it reads money, medicine, bus numbers, signs and lift panels, finds doors and exits, and answers questions about the
scene. If the user falls, it sounds a siren, texts their family their location and keeps a record of what happened.

Every safety function runs **on the phone's Snapdragon Hexagon NPU, fully offline**. The system never tells the user
that it is safe to move: it reports what it measured, and the cane stays in charge.

---

## Table of contents

**Part I — System**
1. [Design principles](#1-design-principles)
2. [System architecture](#2-system-architecture)
3. [Data flow of one frame](#3-data-flow-of-one-frame)
4. [Threads, timing budget and scheduling](#4-threads-timing-budget-and-scheduling)

**Part II — Mathematical foundations**

5. [Camera model and coordinate systems](#5-camera-model-and-coordinate-systems)
6. [Monocular metric depth: the floor ruler](#6-monocular-metric-depth-the-floor-ruler)
7. [Motion from images: looming, time-to-contact, ego-motion](#7-motion-from-images-looming-time-to-contact-ego-motion)
8. [Detection geometry: letterboxing, IoU, suppression](#8-detection-geometry-letterboxing-iou-suppression)
9. [Robust statistics, filtering and fitting](#9-robust-statistics-filtering-and-fitting)
10. [Quantisation arithmetic on the NPU](#10-quantisation-arithmetic-on-the-npu)

**Part III — AI / ML models**

11. [YOLOX object detector](#11-yolox-object-detector)
12. [Depth Anything V2 depth estimator](#12-depth-anything-v2-depth-estimator)
13. [Qwen3-VL vision-language model](#13-qwen3-vl-vision-language-model)
14. [Gemma fallback model](#14-gemma-fallback-model)
15. [YAMNet sound classifier](#15-yamnet-sound-classifier)
16. [On-device OCR, translation and speech recognition](#16-on-device-ocr-translation-and-speech-recognition)
17. [Learned ego-motion classifier](#17-learned-ego-motion-classifier)
18. [Runtime: LiteRT, Qualcomm QNN and llama.cpp](#18-runtime-litert-qualcomm-qnn-and-llamacpp)

**Part IV — Layers**

19. [Layer 0: Sensing](#19-layer-0-sensing)
20. [Layer 1: Perception](#20-layer-1-perception)
21. [Layer 2: Tracking and ego-motion](#21-layer-2-tracking-and-ego-motion)
22. [Layer 3: Metric geometry and calibration](#22-layer-3-metric-geometry-and-calibration)
23. [Layer 4: Hazard detection](#23-layer-4-hazard-detection)
24. [Layer 5: Decision and alert policy](#24-layer-5-decision-and-alert-policy)
25. [Layer 6: Feedback: haptics, speech, languages, sound](#25-layer-6-feedback-haptics-speech-languages-sound)
26. [Layer 7: Assistant: voice, vision-language, reading](#26-layer-7-assistant-voice-vision-language-reading)
27. [Layer 8: Emergency: falls, siren, SMS, black box](#27-layer-8-emergency-falls-siren-sms-black-box)
28. [Layer 9: Mobility aids](#28-layer-9-mobility-aids)
29. [Layer 10: Interface and accessibility](#29-layer-10-interface-and-accessibility)
30. [Layer 11: Platform, performance and heat](#30-layer-11-platform-performance-and-heat)

**Part V — Performance** · **Part VI — Verification** · **Part VII — Safety, privacy, limits** · **Part VIII — Build**

---

# Part I — System

## 1. Design principles

| Principle | What it means in the code |
|---|---|
| **Warn without being asked** | A continuous analysis loop produces alerts on its own; questions are an addition, not the core. |
| **On-device, offline** | All perception, hazard and emergency logic runs on the phone. The network is optional and only ever used for free-form questions. |
| **Vibration first** | Every hazard has a distinct haptic rhythm; speech is limited to the few words that matter, so the user's ears stay on the street. |
| **Evidence before alerts** | A hazard must be supported by independent measurements and persist across frames before it is announced. |
| **Honest uncertainty** | Missing, stale or inconsistent data produces silence or "can't tell", never a guessed distance or a fake warning. |
| **Never a green light** | No component, including language models, is allowed to tell the user it is safe to walk, cross or go. |
| **Deterministic safety, generative extras** | Alerts come from fixed, testable logic; language models only describe and read. |

## 2. System architecture

```
┌──────────────────────────────────────────────────────────────────────────────────────────────┐
│ LAYER 0  SENSING                                                                              │
│  Camera2 logical camera ─ 640x480 YUV + full-res preview     IMU: accel · gyro · gravity ·    │
│  frame gate (rate chosen BEFORE any pixel work)              step detector · rotation vector  │
│  microphone (voice, sounds)                                  GPS (emergency only)              │
└───────────────┬──────────────────────────────────────────────────────────────┬───────────────┘
                │ upright ARGB frame                                             │ motion samples
┌───────────────▼──────────────────────┐   ┌────────────────────────────────┐  │
│ LAYER 1  PERCEPTION (Hexagon NPU)     │   │ Depth Anything V2 (FP16, NPU)  │  │
│  YOLOX int8 640x640, letterboxed      │   │ 518x518 → disparity map        │  │
│  per-class confidence, NMS            │   │ raw map + 48x32 pooled grid    │  │
└───────────────┬──────────────────────┘   └───────────────┬────────────────┘  │
┌───────────────▼──────────────────────────────────────────▼───────────────────▼───────────────┐
│ LAYER 2  TRACKING      IoU association within category · label votes · yaw compensation      │
│                        looming · time-to-contact · ego-motion subtraction · approaching       │
│ LAYER 3  GEOMETRY      floor ruler (relative → metres) · calibration · lens FOV · distances  │
│ LAYER 4  HAZARDS       drop-off evidence fusion + state machine · stairs up/down · step count │
│                        head / waist / floor obstacles · reflection guard · blocked · health   │
│ LAYER 5  DECISION      priority · ranges · repeat limits · quiet windows · never-green-light │
└───────────────┬──────────────────────────────────────────────────────────────────────────────┘
┌───────────────▼──────────────────────┐ ┌─────────────────────────────┐ ┌─────────────────────┐
│ LAYER 6  FEEDBACK                     │ │ LAYER 7  ASSISTANT           │ │ LAYER 8  EMERGENCY  │
│ haptic vocabulary · short speech      │ │ voice intents · Qwen3-VL on  │ │ fall detector ·     │
│ EN / HI / TE · sound awareness        │ │ NPU · OCR · finders · cloud  │ │ siren · SMS · black │
└──────────────────────────────────────┘ └─────────────────────────────┘ │ box                 │
 LAYER 9 MOBILITY: walk straight · torch · quick launch                  └─────────────────────┘
 LAYER 10 INTERFACE: safety states · depth view · low-vision UI · diagnostics
 LAYER 11 PLATFORM: NPU delegation · graph cache · frame gating · heat indicator · watchdogs
```

## 3. Data flow of one frame

1. **Gate.** The camera callback asks `frameDue()` whether a frame is wanted at the current activity rate. If not,
   the image buffer is released untouched.
2. **Convert.** YUV_420_888 → upright ARGB in one loop (BT.601 integer coefficients, rotation folded into the write
   index).
3. **Black box.** Every 0.5 s a 320 px JPEG copy is pushed into a 10 s in-memory ring (Layer 8).
4. **Detect.** Letterbox to 640×640 → YOLOX on the NPU → dequantise → per-class filter → NMS (Layer 1).
5. **Track.** Associate to tracks, vote labels, measure looming, distance and own-motion-corrected speed (Layer 2).
6. **Health.** 64×48 thumbnail → mean luminance and Laplacian variance → OK / dark / blurry / blocked (Layer 4).
7. **Depth.** 518×518 → Depth Anything V2 on the NPU → disparity; floor ruler update; obstacles (Layers 1, 3, 4).
8. **Drop-off pipeline.** Edges + depth verdict + ground plane + object suppression → evidence → state machine;
   stairs profile (Layer 4).
9. **Decide.** Alert policy picks at most a few alerts; drop-off, stairs, walk-straight and bus cues are added
   (Layer 5).
10. **Output.** Haptics and speech (Layer 6); one immutable `HudState` is posted to the interface (Layer 10).

## 4. Threads, timing budget and scheduling

| Thread | Work |
|---|---|
| Camera handler | Gate check, YUV→RGB of taken frames |
| Analysis executor (single) | Steps 3–10 above, strictly in order, one frame at a time (frames arriving while busy are dropped, never queued) |
| Sensor callbacks | IMU fusion, fall detector, black-box motion ring, barometer if present |
| Sound thread | YAMNet on 0.975 s windows with 0.5 s hop |
| Qwen worker + server process | Vision-language requests over localhost |
| Main (UI) thread | Rendering, voice callbacks, TTS, settings |

**Budget at 10 fps walking (100 ms per frame):** YOLOX ≈ 4 ms, depth ≈ 30 ms inference (+ pre/post-processing),
drop pipeline a few ms, tracking and policy < 1 ms. Frame rate by activity: walking 10, standing 5, sitting 3,
vehicle 2 fps. Depth runs on every analysed frame; the drop pipeline evaluates at up to ~15 Hz on the latest depth.

---

# Part II — Mathematical foundations

## 5. Camera model and coordinate systems

**Image coordinates.** All boxes and sample points are normalised: `x, y ∈ [0, 1]` from the top-left, independent of
resolution.

**Field of view from the lens.** From the camera's focal length `f` and physical sensor size `(W, H)` (both in mm):

```
FOV_h = 2 · atan( W / (2f) )        FOV_v = 2 · atan( H / (2f) )
```

In portrait orientation the frame's width uses the sensor's height and vice versa. Calibration reads these values
from the camera characteristics (57.8° × 72.8° on the test phone).

**Angles of a pixel.** A row `y` and column `x` map to angles relative to the optical axis:

```
ρ(y) = (y − 0.5) · FOV_v          (positive = below the axis)
φ(x) = (x − 0.5) · FOV_h          (positive = to the right)
```

**Camera pose.** Gravity from the IMU gives the pitch `θ` (how far the camera looks below the horizon) and roll:

```
θ = atan2(g_z, g_y)          roll = atan2(g_x, g_y)
```

**Direction for the user.** Bearing `φ` is spoken as a clock position, 12 o'clock straight ahead:
`hour = round(deg(φ) / 30)`, mapped into 1…12; on screen as LEFT / AHEAD / RIGHT with a ±0.14 rad dead zone.

## 6. Monocular metric depth: the floor ruler

Depth Anything V2 outputs relative inverse depth `d`: larger means nearer, but with no metric scale. Nadaka recovers
metres with one scale factor `s`:

```
z = s / d            (z = depth along the optical axis, in metres)
```

**Where a flat floor should be.** A camera at height `h` above a flat floor, pitched down by `θ`: the ray through
row angle `ρ` makes angle `α = θ + ρ` with the horizontal. It meets the floor at range `r = h / sin α`, so its
optical-axis depth is

```
z_floor(ρ) = r · cos ρ = h · cos ρ / sin(θ + ρ)          (defined for θ + ρ > 3°)
```

**Learning the scale.** On floor rows 0.8–2 m ahead, `s = d · z_floor`. The median over rows is accepted only if:
- the rows look like one flat surface: `(max − min) / median < 0.3`;
- 5 consecutive frames agree within `[0.8, 1.25]` of their median.

Afterwards the ruler adapts slowly (`s ← 0.9 s + 0.1 s_new`) while the ground under the feet keeps agreeing within
a factor of 1.6. A table top is far nearer than the floor, fails this check, and can never become the ruler.
If a flat floor keeps disagreeing for 20 frames, the ruler is relearned (the phone was re-mounted).

**From a pixel to a 3D point.** Given `z = s / d` at `(x, y)`:

```
range   = z / cos ρ
ahead   = range · cos(θ + ρ)            (horizontal distance)
height  = h − range · sin(θ + ρ)        (above the user's floor)
lateral = z · tan φ
```

This is how obstacles are placed at head height, waist height or below the floor.

**Expected floor disparity.** For hazard tests, observed disparity is compared with what a flat floor would give:

```
d_expected(y) = s / z_floor(ρ(y))          ratio = d_observed / d_expected
```

On the floor the ratio is ≈ 1; beyond a drop the surface is lower and farther, so the ratio falls below 1; an
obstacle is nearer, so it rises above 1.

**Distance by object size (fallback).** For an object of known real height `H_c` (person 1.7 m, car 1.5 m, bus 3 m,
chair 0.9 m, …) and normalised box height `b_h`, under the small-angle approximation:

```
distance ≈ H_c / (b_h · FOV_v)
```

Used only when the whole object is visible and, for people, only when the box is tall enough to be a standing person
(`b_h ≥ 1.2 · b_w` in normalised units); capped at 10 m.

## 7. Motion from images: looming, time-to-contact, ego-motion

**Looming.** An object's image height is inversely proportional to its distance, `b_h ∝ 1/D`. Over a sliding window
of 1 s (minimum span 0.25 s):

```
growth g = ln( b_h(t1) / b_h(t0) ) / (t1 − t0)
```

**Time-to-contact.** Since `ln b_h = const − ln D`, `g = −Ḋ / D`, so the time until contact at the current closing
rate is

```
τ = D / (−Ḋ) = 1 / g            (defined for g > 0.01)
```

τ needs no metric distance at all: it comes purely from how fast the object grows.

**Closing speed.** Using the size model at both ends of the window (unbiased even when the object closes fast):

```
closing = (H_c / FOV_v) · (1/b_h(t0) − 1/b_h(t1)) / (t1 − t0)
```

**Ego-motion subtraction.** The user's own walking speed `v` (steps per second × stride) explains part of the closing
speed along the object's bearing `φ`:

```
v_object = closing − v · cos φ
```

An object is **approaching** when `v_object > 0.7 m/s` and `τ < 3 s` (or when the learned classifier in §17 says so).
Things that cannot move by themselves (furniture, poles, plants) are never approaching.

**Turn compensation.** Between frames the camera turns by `ω · Δt` (gyroscope yaw rate `ω`), shifting every box by

```
Δx = ω · Δt / FOV_h
```

Old boxes are shifted before matching, so turning the body does not break tracks.

**Lateral speed.** `v_lateral = Δx_box · FOV_h · D / Δt`, smoothed with an EMA (α = 0.3).

**Own walking speed.** `v = n_steps / window · stride`, with a 3 s window; no step for 1.5 s means standing.

## 8. Detection geometry: letterboxing, IoU, suppression

**Letterbox.** A frame `w × h` is fitted into the square model input of side `S` without distortion:

```
k = S / max(w, h)       pad_x = (S − k·w) / 2       pad_y = (S − k·h) / 2
```

The scaled frame is drawn at `(pad_x, pad_y)` on a grey (114) canvas. A predicted input-pixel coordinate `u` maps
back to the frame as

```
x = (u − pad_x) / (k · w)          y = (v − pad_y) / (k · h)
```

For the 480×640 portrait frame into 640×640: `k = 1`, `pad_x = 80`, `pad_y = 0`. Stretching instead would make every
object 33 % wider than the network ever saw in training.

**Intersection over union.**

```
IoU(A, B) = area(A ∩ B) / ( area(A) + area(B) − area(A ∩ B) )
```

**Non-maximum suppression.** Sort by `score × priority(class)`; keep a box unless a kept box overlaps it with
IoU > 0.45 (same class) or IoU > 0.7 (different class = the same object with a second label). At most 25 boxes.

## 9. Robust statistics, filtering and fitting

**Median and MAD.** Depth samples are summarised with the median and the median absolute deviation
`MAD = median(|x_i − median(x)|)`, so a few wrong pixels (reflections, holes) cannot move the result.

**Exponential moving average.** `x ← (1 − α) x + α x_new`, used for detector confidence (α = 0.3), lateral speed
(α = 0.3), the scale ruler (α = 0.1), the depth-view range (α = 0.15) and label votes (decay 0.9 per frame).

**Plane fit (least squares).** Floor points `(a_i, l_i, z_i)` (ahead, lateral, height) are fitted to
`z = c₀ + c₁ a + c₂ l` by solving the 3×3 normal equations

```
| n     Σa    Σl  | |c₀|   | Σz  |
| Σa    Σa²   Σal | |c₁| = | Σaz |
| Σl    Σal   Σl² | |c₂|   | Σlz |
```

with Cramer's rule (`c_k = det(M_k) / det(M)`, rejected if `|det M| < 10⁻⁹`). The fit is trusted only when the RMS
residual is ≤ 6 cm, the slope `|c₁| ≤ 0.35` and the offset `|c₀| ≤ 0.3 m`.

**Line fit.** Stair slopes use the least-squares slope `m = (nΣxy − ΣxΣy) / (nΣx² − (Σx)²)`.

**Voting windows.** Temporal decisions use "k of the last n" counts over timestamped histories, so frames that arrive
irregularly still count correctly, and stale entries (older than 1.5 s) expire.

## 10. Quantisation arithmetic on the NPU

Integer models store each tensor as 8-bit integers with an affine mapping to real values:

```
real = scale · (q − zero_point)
```

YOLOX runs with int8 weights and int8 activations (w8a8): the Hexagon tensor processor multiplies and accumulates in
integers, and the three output tensors (boxes, scores, class indices) are dequantised in Kotlin with their own
`(scale, zero_point)`. Depth Anything V2 runs in FP16: half the memory of FP32 and native to the NPU's floating-point
path, keeping fine depth gradients that int8 would crush. The vision-language model uses llama.cpp block
quantisation (Q4_K_M ≈ 4–5 bits per weight with per-block scales for the language model, Q8_0 for the vision
projector).

---

# Part III — AI / ML models

## 11. YOLOX object detector

**What it does.** One forward pass turns an image into a set of boxes, each with a class and a confidence.

**How it works.**
- **Backbone** (CSPDarknet): stacked convolution blocks with cross-stage partial connections extract features at
  decreasing resolutions.
- **Neck** (PAFPN): a feature pyramid combines coarse, semantic features with fine, spatial ones in both directions,
  so small objects (a bottle) and large ones (a bus) are both detected.
- **Decoupled, anchor-free head:** at each output cell, separate branches predict the class probabilities, the box
  (centre offsets and size directly, with no predefined anchor boxes) and an objectness score. At 640×640 with strides
  8, 16 and 32 there are 80² + 40² + 20² = **8,400 candidate predictions** per image.
- **Training** (done by the model's authors): SimOTA label assignment picks, for each ground-truth object, the
  predictions that best match it by a cost of classification and localisation error.

**In Nadaka.** Input 640×640 RGB uint8 (letterboxed), output three quantised tensors; score = class probability ×
objectness. Compiled for the Snapdragon NPU with int8 weights and activations; all 317 operations run on the NPU.
Post-processing (§8, §20) runs in Kotlin.

**Classes.** The 80 COCO classes, grouped by Nadaka into PERSON, ANIMAL, VEHICLE, SEAT, FURNITURE and OTHER.
It does not know doors, stairs or keys; those are handled by depth (Layer 4) and the vision-language model (Layer 7).

## 12. Depth Anything V2 depth estimator

**What it does.** From one image, predicts for every pixel how near it is (relative inverse depth).

**How it works.**
- **Encoder:** a Vision Transformer (ViT-S, from the DINOv2 family). The 518×518 image is cut into 14×14-pixel
  patches, giving **37 × 37 = 1,369 tokens**; self-attention lets every patch use context from the whole image
  (the floor continuing, the horizon, the size of known objects).
- **Decoder (DPT):** features from several transformer layers are reassembled into image-like maps at multiple
  resolutions and fused from coarse to fine, producing a dense depth map with sharp edges.
- **Training** (done by the model's authors): a large teacher model is trained on synthetic images with exact depth,
  then labels millions of real images; the small student is trained on those pseudo-labels. The output is
  **affine-invariant inverse depth**: correct in shape, unknown in absolute scale and offset.

**In Nadaka.** Input 518×518 RGB normalised to [0, 1], FP16 on the Hexagon NPU, all 598 operations delegated.
Outputs used two ways: the full-resolution disparity (sampled around candidate edges by the drop-off pipeline) and a
48 × 32 average-pooled grid (obstacles, distances, the live depth view). Metric scale comes from the floor ruler (§6),
and the drop-off test compares both sides of an edge within the same frame (§23), which cancels most of the unknown
scale.

## 13. Qwen3-VL vision-language model

**What it does.** Takes an image and a question and writes a short answer in natural language.

**How it works.**
- **Vision encoder:** a Vision Transformer splits the image into patches; neighbouring patch features are merged into
  a smaller set of visual tokens.
- **Projector (mmproj):** maps the visual tokens into the language model's embedding space, so the image becomes a
  sequence of "words" the language model can read.
- **Language model:** a decoder-only transformer (~2 billion parameters in total) reads the system prompt, the visual
  tokens and the question, and generates the answer one token at a time (autoregressive decoding), each step
  attending to everything before it through a key-value cache.

**In Nadaka.**
- Weights: GGUF, Q4_K_M language model (≈ 1.1 GB) + Q8_0 projector (≈ 0.4 GB), stored on the phone.
- Served by a llama.cpp build for Snapdragon (§18) with the **Hexagon backend**: image encoding 0.25 s on the NPU
  versus 14–17 s on the GPU and 33 s on the CPU; decoding ≈ 37 tokens/s; prompt ingestion ≈ 660 tokens/s.
- Limits chosen for a walking user: 1,024-token context, 1 request slot, image capped at 256 tokens, 48-token answers
  trimmed to two sentences.
- System prompt: name the one main object first; one or two sentences under 20 words; left / ahead / right only;
  never guess distances; money in rupees; never say it is safe to walk, cross or go.
- Sampling: temperature 0.7, top-p 0.8, top-k 20.
- Every answer passes the never-green-light filter (§24) before it is spoken.

## 14. Gemma fallback model

If the Qwen files are absent, **Gemma 4 E2B** runs through LiteRT-LM on the GPU, with the same interface, prompt
style and safety filter. Sampling: top-k 64, top-p 0.95, temperature 1.0 (the model's recommended settings).

## 15. YAMNet sound classifier

**What it does.** Recognises everyday sounds from the microphone.

**How it works.** Audio at 16 kHz is converted to a **log-mel spectrogram** (energy in perceptual frequency bands
over time). A MobileNet-style network of depthwise-separable convolutions classifies each 0.975 s window
(15,600 samples) into **521 AudioSet classes**.

**In Nadaka.** Windows slide by 0.5 s. Five danger groups are watched: horn (car, truck, train horns), siren,
vehicle reversing beep, bicycle bell, dog barking. A danger must be confirmed over consecutive windows and is
rate-limited per kind. It runs on the CPU (~6–10 ms per window) to keep the NPU free for vision, and pauses while
the microphone is used for a question.

## 16. On-device OCR, translation and speech recognition

- **Text recognition (ML Kit, Latin script):** returns text blocks, lines and words with bounding boxes. Nadaka uses
  the positions, not just the text (lift buttons ordered top-to-bottom, the largest number on a door sign).
- **Translation (ML Kit):** on-device neural machine translation with a downloadable pack per language (~30 MB);
  English → Hindi and English → Telugu, offline once the packs are on the phone.
- **Speech recognition (Android):** the on-device recogniser returns several alternative transcripts; Nadaka's
  intent grammar checks all of them (§26).
- **Speech output (Android TTS):** English, Hindi and Telugu voices, all offline.

## 17. Learned ego-motion classifier

An optional logistic-regression model (`ego_model.json`, trained by `training/train_ego.py` from labelled walks)
replaces the hand-set approaching rule:

```
p(approaching) = σ( b + Σ_i w_i · (x_i − μ_i) / σ_i )          σ(z) = 1 / (1 + e^(−z))
```

Features `x` (fixed order): growth, closing speed, own speed, |yaw rate|, |pitch rate|, box height, distance from the
centre line, object's own speed, time-to-contact (capped at 10 s). Inputs are standardised with the training mean `μ`
and standard deviation `σ`; the decision threshold is stored with the model. Features can be logged during walks
(long-press the camera view) to collect more training data.

## 18. Runtime: LiteRT, Qualcomm QNN and llama.cpp

- **LiteRT + Qualcomm QNN delegate** (YOLOX, depth): HTP backend, sustained-high-performance power mode, FP16 enabled
  for the depth model. A **graph cache token** per model stores the compiled NPU graph, so later launches skip
  compilation (depth model load 5.2 s → 0.17 s). Automatic fallback HTP → GPU → CPU.
- **Delegation check:** the number of operations placed on the NPU is read from the runtime's own log
  ("Replacing 317 out of 317 nodes…") and shown in Diagnostics, so NPU use is measured, not assumed.
- **llama.cpp for Snapdragon** (Qwen): shipped as native libraries inside the app; the server binary is started as a
  child process bound to `127.0.0.1` with the vendor OpenCL and FastRPC libraries on its library path. It exposes an
  OpenAI-compatible chat endpoint; a watchdog restarts it after a crash (up to 3 times in a row, back-off 2 s × n).

---

# Part IV — Layers

## 19. Layer 0: Sensing

### 19.1 Camera (`WideCamera.kt`)
- **Logical Camera2 camera:** one capture session feeding a full-resolution `TextureView` preview and a 640×480
  `YUV_420_888` `ImageReader` (3 buffers, latest image only).
- **Conversion:** integer BT.601 YUV→RGB (`Y' = 1192·(Y−16)`, `R = (Y' + 1634·V) >> 10`,
  `G = (Y' − 833·V − 400·U) >> 10`, `B = (Y' + 2066·U) >> 10`) with the sensor rotation applied through the output
  index, so there is no second pass and no per-pixel allocation.
- **Frame gate:** `take()` is evaluated before conversion; dropped frames cost only the buffer release. This took the
  app's CPU use from ~85 % to ~26 % of a core.
- **Rate by activity:** 10 / 5 / 3 / 2 fps (walking / standing / sitting / vehicle); reading always at full rate.
- **Lens:** the main 1× lens for analysis. Automatic switching to the ultra-wide is disabled: every switch changes the
  field of view, invalidates the depth ruler and resets tracks.
- **Preview frame for questions and bus reading:** the full-resolution preview bitmap is grabbed on the UI thread
  (with a 300 ms timeout when requested from the analysis thread).

### 19.2 Motion sensors (`EgoMotion.kt`)
| Sensor | Rate | Used for |
|---|---|---|
| Gyroscope | game | yaw / pitch rate (EMA 0.3): turn compensation, ego features |
| Gravity | UI | pitch and roll of the camera |
| Linear acceleration | game | vibration energy (vehicle detection) |
| Step detector | fastest | walking speed, activity, calibration step counts |
| Accelerometer | game (~50 Hz) | fall detection, black-box motion trace |
| Game rotation vector | UI | heading for walk-straight (remapped so azimuth = camera direction) |
| Barometer (if present) | normal | descent evidence for drop-offs |

### 19.3 Activity (`Activity.kt`)
Walking if a step occurred in the last 2 s; vehicle if vibration energy ≥ 0.25 without steps; otherwise standing.
Resuming walking switches immediately; calming down needs a 10 s grace period. "I'm sitting" / "I'm on a bus" by voice
override the guess for a while. Activity sets the frame rate and which alerts are active; it is never announced.

## 20. Layer 1: Perception

### 20.1 Detector pipeline (`Detector.kt`, `Perception.kt`)
1. Letterbox the frame into a reused 640×640 canvas (§8).
2. Pack RGB bytes in one bulk copy (per-element buffer writes were ~1.2 M JNI calls per frame).
3. Run YOLOX on the NPU.
4. For each of the 8,400 predictions: dequantise the score; skip if below 0.45.
5. **Per-class confidence:** safety classes pass at 0.45; bed, toilet, refrigerator, oven, microwave, sink need 0.6;
   couch and TV 0.55; rare-in-India animals and aircraft/boats 0.65–0.7. Twenty-seven classes irrelevant to walking
   (cutlery, fruit, sports gear, toothbrush, hair drier…) are dropped.
6. Dequantise the box and map it back to frame coordinates (§8).
7. Suppression (§8).

### 20.2 Categories
| Category | Classes |
|---|---|
| PERSON | person |
| ANIMAL | dog, cat, cow, horse, sheep, elephant, bear, zebra, giraffe, bird |
| VEHICLE | car, truck, bus, motorcycle, bicycle, train, boat, airplane |
| SEAT | chair, couch, bench, bed, toilet |
| FURNITURE | dining table, tv, laptop, refrigerator, oven, microwave, sink |
| OTHER | everything else (spoken by its own name) |

Categories drive tracking (§21) and safety priority (§24). Speech uses locally common names: motorcycle → bike,
bicycle → cycle, dining table → table, traffic light → traffic signal, potted plant → plant.

### 20.3 Depth pipeline (`Depth.kt`)
1. Scale the frame to 518×518, normalise to [0, 1] floats.
2. Run Depth Anything V2 (FP16, NPU).
3. Keep the raw 518×518 disparity (for edge sampling) and average-pool into a 48 × 32 grid.
4. Update the floor ruler (§6), compute obstacles (§23), expose `floorTrusted` and the time of the run.

## 21. Layer 2: Tracking and ego-motion (`Tracker.kt`)

### 21.1 Association
Detections are processed in descending score order. Each is matched to the unmatched track **of the same category**
with the highest IoU after turn compensation, if that IoU ≥ 0.3; otherwise a new track starts. Tracks missed for up
to 0.5 s survive (shifted by the turn), so a flicker does not reset history.

### 21.2 Label stabiliser
Each track keeps a vote per label. Every frame all votes decay by 0.9 and the matched detection adds its confidence
to its label; the track's label is the arg-max. The detector saying "chair, couch, chair, chair, bench, chair" for one
object yields one track called "chair" that keeps accumulating frames. Matching on exact labels instead restarted the
track on every flip, so flickering objects never reached the frames needed to be spoken.

### 21.3 Per-track measurements
| Quantity | Formula / rule |
|---|---|
| Confidence | EMA of detector score, α = 0.3 |
| Hits | frames matched; ≥ 5 before speech |
| Size distance | §6, only when fully visible |
| Depth distance | §22.4 |
| `metres` | depth distance if available, else size distance |
| Growth, τ, closing | §7 over a 1 s window |
| Own speed | closing − own speed · cos(bearing) |
| Lateral speed | §7, EMA 0.3 |
| Moving | not fixed furniture, and \|own speed\| or \|lateral speed\| > 0.6 m/s |
| Approaching | §7 rule or §17 classifier |
| Edge | box touches the left/right 2 % of the frame |
| Consistent | depth and size distances within a factor of 1.6 |
| **Sure** | hits ≥ 5, confidence ≥ 0.45, consistent, and fully in view unless depth measured it |

## 22. Layer 3: Metric geometry and calibration

### 22.1 Floor ruler
§6. The ruler is learned only while walking, checked every frame against the nearest floor rows, adapted slowly,
relearned when persistently wrong, and restored from calibration at launch. Standing still, the floor is checked
against the trusted ruler but never used to learn it.

### 22.2 Voice-guided calibration (`Calibration.kt`) — one button, about 30 seconds
**Step 1: height.** The microphone opens by itself after the prompt. The spoken answer is parsed:
- feet and inches: `5 foot 8` → (5·12 + 8) · 0.0254 m;
- centimetres: 100–230 → value / 100;
- metres: 1.0–2.3.
Camera height for a chest-worn phone: `h = 0.72 · body height`. Volume down skips the step.

**Step 2: tilt.** The pitch must stay in 8°–32° for 2 s so the floor 0.8–2 m ahead is in view; outside it the voice
coaches "tilt down / up a little" every 3 s; after 25 s it moves on.

**Step 3: two walks, each bracketed by volume-down presses.** Press, walk 3 steps, press; press, walk 10 steps,
press. Counting and the 30 s per-walk clock start at the first press of each walk (waiting before pressing costs
nothing). The step count is read 0.7 s after the closing press because the step sensor reports late. The depth
ruler is relearned from scratch during the walks and must lock.

**Results:**
```
step factor  k = 13 / (steps sensed in both walks)          (clamped to 0.5–2)
stride       = 0.415 · body height · k                      (metres per sensed step)
cadence      = walking time / 13
```
plus the lens field of view (§5) and the locked depth scale. Everything is stored and applied at every launch.

### 22.3 Lens field of view
Read from the main back camera's focal length and physical sensor size (§5), accepted if within plausible bounds
(35°–85° × 45°–100°).

### 22.4 Object distances (`Depth.metresIn`)
- Sample the pooled disparity inside the box: the lower-middle half for most objects; the **top band** (5–35 % of the
  box height) for tables, desks, benches and beds, which is where the user would collide (the rest of such a box is
  empty space and the floor behind).
- Take the **75th percentile** of disparity (the nearest surface in the patch) and convert with `z = s / d`.
- Beyond 10 m the result is discarded: the ruler is fitted 0.8–2 m ahead and depth also carries an unknown offset,
  so extrapolation error grows fast with distance.

## 23. Layer 4: Hazard detection

### 23.1 Drop-off pipeline (`drop/`)
Evaluated at up to ~15 Hz on the latest frame and depth (depth older than 450 ms is unreliable).

**(a) Edge lattice — `EdgeAnalyzer.kt`.** On a 120×160 grey copy (`Y = 0.299R + 0.587G + 0.114B`) inside the lower
region of interest (42–97 % of the height, 18–82 % of the width):
1. 3×3 box blur.
2. Sobel gradients
   ```
   Gx = [-1 0 1; -2 0 2; -1 0 1] / 4        Gy = [-1 -2 -1; 0 0 0; 1 2 1] / 4
   ```
3. Keep pixels with `|Gy| ≥ 10` and `|Gy| ≥ 1.5 · |Gx|` (near-horizontal edges only, within ~34°).
4. Per row, over a 3-row band: the longest run of edge pixels allowing gaps of ≤ 3 px; reject runs shorter than 35 %
   of the width and rows within 3 px of the region border.
5. Score each row:
   ```
   strength   = mean |Gy| over the run / 40            (clamped to 0–1)
   continuity = run length / region width
   texture    = ½·|σ_above − σ_below| / max(σ_above, σ_below, 4) + ½·|μ_above − μ_below| / 60
   score      = 0.45·strength + 0.35·continuity + 0.20·texture
   ```
6. Non-maximum suppression over ±4 rows; up to 3 candidates with score ≥ 0.2.

**(b) Depth verdict — `DropDepthAnalyzer`.** For each candidate, 7 columns × 3 offsets (1.2 %, 2.2 %, 3.2 % of the
height) are sampled just **below** the edge (near side, where the user stands) and just **above** it (far side), each
as a ratio to the flat-floor expectation (§6). With medians `r_n`, `r_f` and MADs:

```
confidence = valid · (1 − clamp((MAD_n + MAD_f) / r_n / 0.25)) · (1 − clamp(|r_n − 1| / 1.2))
relative   = r_f / r_n                  (far side relative to the user's own floor, same frame)
jump       = 1 − relative
```

Verdict:
- **UNRELIABLE** if valid < 60 %, confidence < 0.45, or the near side is not the user's floor (`|r_n − 1| > 0.35`);
- **SUPPORTS** a drop if `jump ≥ 0.08`;
- **CONTRADICTS** if `|jump| < 0.05` (the floor continues) or `relative > 1.1` (the far side is nearer: an obstacle).

Judging the far side against the near side of the same frame cancels most of the depth model's scene-dependent scale;
missing or invalid depth is skipped, never read as "far".

**(c) Ground plane — `GroundPlaneAnalyzer`.** 8×8 floor samples in the lower-centre region are turned into 3D points
(§6) and fitted with a plane (§9). If reliable, points 2–8 % above the edge are compared with it; a median height
≥ 7 cm below the plane is a **break**, scored `min(1, depth_below / 0.25 m)`.

**(d) Object suppression.** An edge overlapping a tracked object's outline (especially at its base, and especially
furniture or bags) gets a suppression score; it lowers confidence but never removes the evidence (stairs with a
person on them are still stairs).

**(e) Fusion — `DropEvidenceFusion`.**
```
confidence = (0.35·edge + 0.40·depth_confidence·[SUPPORTS] + 0.25·ground_score·[break]) · (1 − 0.6·object_score)
```
- edge below 0.35, or no geometric support at all → NONE (an edge alone is never a drop);
- depth contradicts without a plane break → NONE;
- **STRONG**: edge ≥ 0.55, depth SUPPORTS, and (plane break or depth confidence ≥ 0.75), total ≥ 0.6;
- **PRESENT**: total ≥ 0.45; otherwise **WEAK_PRESENT**.

**(f) Reflection guard.** STRONG evidence whose far side measures more than 1.2 m below the floor, where the image
beyond the edge looks like the same floor (mean brightness within 20 grey levels) or like a bright mirror image
(brightness > 200), is capped at PRESENT: polished floors and puddles reflecting the ceiling never produce "Stop".

**(g) Temporal state machine — `DropStateMachine`.**
```
SAFE ──(2 of last 3 have evidence)──► POSSIBLE_DROP ──(3 of last 5 STRONG, or descending)──► CONFIRMED_DROP
  ▲                                         │                                                   │
  └────────────(8 consecutive clean evaluations)─────────────────────────────────────────────────┘
SENSOR_BLOCKED and PATH_NOT_TRAVERSABLE override everything and clear the history.
```
Path not traversable: severe blur; a wall (centre and bottom of the view at nearly the same disparity, ratio ≥ 0.85,
or the centre nearer than 0.6 m); or a featureless view with no trusted depth.

**(h) Output.** POSSIBLE: one soft pulse at most every 1.5 s. CONFIRMED (rising edge): "Stop. Drop ahead, 2 metres"
(or "Stop. Stairs down ahead … At least N steps") and **5 s of vibration**: ten 350 ms pulses 150 ms apart, the first
three rising 170 → 215 → 255, then full strength; if a barometer shows descent, ten full-strength 400 ms pulses.

### 23.2 Stairs and step counting (`StairsUpAnalyzer`)
**Height profile.** From row 96 % up to 30 % of the frame in 1 % steps, the median of 5 central columns is turned into
`(ahead, height)` (§6), keeping points 0.3–6 m ahead.

**Stairs up.** The first point more than 8 cm above the floor (after at least three floor points) marks the first step.
Over the next 1.2 m:
```
slope m = least-squares slope of height over distance,   top = max height
stairs up  ⇔  m ∈ [0.35, 1.3]  (≈ 20°–52°)  and  top ≥ 0.3 m
```
A wall rises almost vertically (slope far above 1.3), a ramp too gently (below 0.35), a single kerb stops rising below
0.3 m. Needs 3 of the last 5 evaluations, and replaces the "blocked" warning that a rising surface would otherwise cause.

**Step counting.** Walking the profile, a new level is registered when the height differs from the current level by
more than 10 cm for at least 2 consecutive samples.
- Up: each level change counts one step; a jump larger than 30 cm between visible treads counts
  `round(jump / 0.17 m)` hidden steps. Spoken as "about N steps".
- Down: every level change counts one, including a large first jump (lower steps hide behind the top edge until the
  user is within ~1.5 m). Spoken as "at least N steps". A platform (one jump, then flat) stays at one level and is
  called a drop, never stairs.

### 23.3 Other depth hazards (`Depth.kt`)
| Hazard | Rule |
|---|---|
| **Head height** | per column: nearest point at 1.2–2.1 m height within 2 m, with nothing at body height (0.3–1.0 m) closer than 0.8 m behind it; at least 2 columns; 4 consecutive depth frames |
| **Waist height** | nearest point at 0.45–1.2 m height within 1.5 m ahead in the corridor (35–65 % of the width), in at least 2 columns; also while standing once the ruler is trusted |
| **Floor obstacle** | walking up the corridor median from near to far (0.7–3.5 m), 3 consecutive rows at least 25 % nearer than a flat floor would be |
| **Blocked ahead** | see path not traversable (§23.1g) |
| **Legacy drop cue** | corridor columns where the floor is > 45 % farther (big drop) or > 8 % farther with a sharp lip (step), agreeing across the corridor and tracked at the user's walking speed; kept as evidence, the state machine decides |

### 23.4 Camera health (`Alerts.kt → assess`)
On a 64×48 thumbnail: mean luminance and the variance of the Laplacian
`L = 4·g(x,y) − g(x−1,y) − g(x+1,y) − g(x,y−1) − g(x,y+1)`.
- blocked: luminance < 20 and variance < 15 (a hand or pocket over the lens);
- dark: luminance < 35;
- blurry: variance < 15;
- tilted: pitch or roll outside the usable range.
A state must persist 1 s before it is announced; hazards from an unusable frame are not reported.

## 24. Layer 5: Decision and alert policy (`Alerts.kt`)

### 24.1 Order
1. Camera health (after persistence).
2. In a vehicle: silence except camera health.
3. Drop-offs (from the state machine, on the rising edge) and head height.
4. **Approaching** objects, within 8 m, the one with the smallest `τ / priority`.
5. **Close** objects in the walking path (within 1.5 m walking, 0.75 m otherwise), the most urgent one.
6. Waist-height and unnamed floor obstacles.
7. Awareness (one message at a time, with a 2.5 s gap between messages):
   - a **crowd** (4+ people within 10 m) instead of repeated "person";
   - **moving** objects only when coming closer (own speed > 0.6 m/s toward the user) and within 6 m;
   - **static** objects within 5 m in the walking path, once, and again only when they loom 50 % larger or after 15 s
     for the same label in the same direction.

### 24.2 Priority and urgency
```
priority: vehicle 1.8 · person 1.5 · animal 1.4 · seat / furniture / street furniture 1.2 · other 1.0
urgency  = distance / priority          (smaller = announce first)
```
Example: a car at 3 m (3 / 1.8 = 1.67) is announced before a bag at 2 m (2 / 1.0 = 2.0) and a person at 2.8 m
(2.8 / 1.5 = 1.87).

### 24.3 Never a green light (`Voice.kt → SafetyGate`)
- Questions about safety ("can I cross", "is the path clear", "सुरक्षित", "దాట"…) are recognised first and answered only
  with hazards seen in the last 1.5 s, never with "yes".
- Any spoken text matching phrases such as "safe to cross", "you can go", "path is clear", "no obstacles" or
  "it's safe" is discarded and replaced; this applies to every language-model answer.

### 24.4 Timing rules
Repeat limits per hazard (3.5 s), per close object (4 s), per moving object (12 s), per static label and direction
(15 s). Nothing is spoken while the microphone is open (vibration still works). After an answer, routine speech waits
3 s; danger still interrupts. During a fall countdown or siren, routine alerts are held back.

## 25. Layer 6: Feedback: haptics, speech, languages, sound

### 25.1 Haptic vocabulary (`Haptics.kt`)
| Pattern | Meaning |
|---|---|
| Ten pulses, strength rising, 5 s | Stop: drop-off confirmed |
| One soft 90 ms pulse | Possible edge ahead |
| Two rising swells | Head height |
| Four taps getting faster | Something coming at you |
| Long–short–long | Horn, siren or dog nearby |
| Two soft pulses | The camera can't see |
| One long / two short | Walk straight: drifted left / right |
| Ticks, faster when closer | Something in the path |

**Proximity ticks (parking-sensor style).** For the nearest object in the path within 2.5 m, the tick interval is
```
interval = 300 + 700 · clamp((d − 1) / (2.5 − 1), 0, 1)  ms          (150 ms when closer than 0.75 m)
```
with three strength levels. Ticks continue only while the gap is shrinking (by at least 0.3 m); after 1 s without
progress they stop, except at touching range, so standing near a wall does not buzz forever.

Drop-off vibrations use the alarm usage class, so they are felt even when media sound is off. A spoken lesson plays
every pattern with its meaning.

### 25.2 Speech
Haptics-first mode speaks only short words ("Stop. Drop.", "Head.", "Bus 218."); speech mode speaks full sentences.
Answers to questions are protected: routine alerts queue behind them, only danger interrupts.

### 25.3 Languages (`Lang.kt`)
- **App sentences** (alerts, stairs, fall messages, walk-straight cues, bus numbers, door finder results, camera
  problems) are translated with **fixed templates** per language, including distance ("2 मीटर पर", "2 మీటర్ల
  దూరంలో"), clock direction ("9 बजे की दिशा में") and Hindi grammatical gender (feminine: car, bike, bus, cow… →
  "आ रही है"; masculine → "आ रहा है"). Several alerts spoken together are matched as the longest runs of sentences each
  covered by a template; if any part has no template, the whole text stays in English (never a mix).
- **Free-form answers** are generated and safety-checked in English, then translated on the phone (§16).
- About 30 object names per language (e.g. truck → लारी / లారీ, cow → गाय / ఆవు, dog → कुत्ता / కుక్క).

### 25.4 Sound awareness (`Sounds.kt`)
§15: horn, siren, reversing, bell, dog → "Horn nearby." with the long–short–long pattern; paused in vehicles and while
listening.

## 26. Layer 7: Assistant: voice, vision-language, reading

### 26.1 Voice intents (`Voice.kt`)
Press volume up; a double-tap vibration marks the open microphone. Common recogniser slips are normalised ("what's a
head" → "what's ahead"); every alternative transcript is checked, and a safety question in **any** alternative wins.

| Intent | Examples | Action |
|---|---|---|
| Safety | is it safe, can I cross | recent hazards only, never "yes" |
| Walk straight | walk straight, keep me straight | §28.1 |
| Panel | read the lift buttons, room number | §26.5 |
| Emergency | emergency, help me, bachao | siren + SMS |
| Find | find the door / chair / bottle | §26.4 |
| Describe | what is this, what's ahead | vision-language model |
| Sign | read the sign / label / board | vision-language model, then OCR |
| Read | money, medicine, padh, dawai | reading mode (§26.6) |
| Modes | I'm sitting, I'm on a bus, let's go | activity override |
| Feedback | speak everything, vibration first, quiet | output style |
| Lesson | teach me the vibrations | haptic lesson |

### 26.2 Vision-language answers
Describe, sign and find requests go to Qwen3-VL (§13) with the full-resolution preview frame (downscaled to 1024 px).
The reply is filtered (§24.3), translated if needed (§25.3) and spoken as an answer.

### 26.3 Engine routing (`Cloud.kt`)
- **On-device** (default): Qwen on the NPU.
- **Cloud when online:** hosted vision models through an OpenAI-compatible API, several models tried in order in one
  request. The on-device model answers the same question instantly when the phone is offline, the API is busy (HTTP
  429) or slower than 8 s; two failures in a row pause the cloud for 60 s.
- The route and its reason ("Wi-Fi is available", "Offline: using the phone's NPU", "HTTP 429 (free models busy)")
  are shown in Diagnostics. The API key is injected at build time from `local.properties` and is never committed.
- Safety functions never use this path.

### 26.4 Finding things (`Find.kt`, `Panel.kt → SceneFinder`)
- **Detector classes** (chair, bottle, bag…): the nearest matching track is announced with clock direction and
  distance every 3 s; "right in front of you" within 0.75 m; "no … in view, turn slowly" when lost.
- **Anything else** (door, exit, stairs, lift, counter): the vision-language model is asked every 2.5 s "Is there a …
  in this photo? … left, ahead or right … or: not visible". A result is announced only when **two consecutive answers
  agree on the side** ("Door on your right, closed"); a change of side or "not visible" resets; the search ends after
  30 s.

### 26.5 Lift panels and room numbers (`Panel.kt`)
OCR words with bounding boxes. Lift labels match `-?\d{1,2} | G | LG | UG | B\d? | P\d? | M | L\d?`; rows are formed
by `top / median height`, ordered top-to-bottom then left-to-right ("Lift buttons, top to bottom: 4, 5, 2, 3, ground,
1"). Room numbers match `[A-Z]?-?\d{2,4}[A-Z]?` and the tallest one wins ("Number 204"). The vision-language model is
used only when OCR finds nothing.

### 26.6 Money and medicine (`Reader.kt`)
Reading mode coaches the user ("move closer" if text covers < 4 % of the frame, "turn it over", "hold still") and
speaks only when **the same answer is read on two frames**.
- **Money:** cue phrases (reserve bank, rupees, governor, ₹); the denomination is voted from printed numbers
  (10…2000) and denomination words (weighted double). A running total resets after 60 s.
- **Medicine** (checked before money, since strips print "M.R.P. ₹"): strength with units (mg, mcg, ml, g, IU); expiry
  from forms like `EXP 03/2027`, `Exp.Date: MAR.2027`, `EXP 03/27`, valid through the end of that month; expired
  medicine is flagged.

### 26.7 Bus route numbers (`Bus.kt`)
- Trigger: a bus track seen on ≥ 5 frames whose box is at least 12 % of the frame height; the nearest bus first; one
  attempt every 0.7 s, at most 6 per bus.
- The route board (top 45 % of the box) is cropped from the full-resolution preview and upscaled (up to 3×) to ~640 px.
- Route pattern: `\d{1,3}[A-Z]{0,2}(/\d{1,3}[A-Z]?)?`, not adjacent to other letters or digits, not starting with 0,
  with O→0 and I→1 corrections. Number plates ("TS 09 UB 1234") are rejected by construction.
- The same number must be read twice; if OCR gives up, the vision-language model gets one attempt. Announced once per
  bus: "Bus 218."

## 27. Layer 8: Emergency: falls, siren, SMS, black box

### 27.1 Fall detection (`Fall.kt`)
On the accelerometer magnitude `g = |a| / 9.81`, four stages must follow in order:

1. **Free fall.** `g < 0.45` for at least 40 ms starts integrating the height fallen from the missing gravity:
   ```
   v ← v + (1 − g) · 9.81 · Δt          h ← h + v · Δt
   ```
   In pure free fall `h = ½ · 9.81 · t²`, so **0.5 m takes ≈ 0.32 s**.
2. **Impact.** `g > 2.3` within 1.2 s; accepted only if `h ≥ 0.5 m` (a jolt or a short drop resets).
3. **Stillness.** After an 0.8 s settle window, `mean |g − 1| < 0.25` over 1.5 s.
4. **Orientation change.** The angle between the carried gravity vector (tracked while `0.9 < g < 1.1`) and the resting
   one, `acos(u·v / |u||v|)`, is at least 45°.

Rejected by design: walking, jumps landing upright, stumbles without impact, short drops onto a sofa.

### 27.2 Response
- Voice: "Fall detected. If you are OK, press a volume key. Otherwise in 7 seconds I will sound an alarm and call your
  emergency contacts." and "Alarm in 3 seconds." with vibration. No popup; any volume key cancels.
- No response in 7 s → **siren**, **SMS** and the emergency screen, which outranks every other state.
- Holding both volume keys for 2 s triggers the same emergency manually.

### 27.3 Siren synthesis
A wail is synthesised in the app: over a 1.2 s loop the frequency follows
```
f(u) = 650 + 850 · (1 − cos 2πu) / 2          u ∈ [0, 1)       (650 → 1500 → 650 Hz)
```
Phase is accumulated sample by sample, `φ_{n+1} = φ_n + 2π f_n k / 44100`, where
`k = round(C) / C` and `C = Σ f_n / 44100` is the number of cycles in the loop. Scaling by `k` makes the loop contain a
whole number of cycles, so the end joins the start with no click or gap. The 16-bit PCM buffer loops indefinitely on
the alarm stream; the alarm volume is raised to maximum while it sounds and restored afterwards; vibration is
continuous.

### 27.4 Emergency SMS (`Sms.kt`)
Up to two contacts picked from the phone's contact list (no contacts permission needed for a single pick). The message
"NADAKA ALERT: I may have fallen and need help. Please call or come now." includes a map link to the last known
position with its accuracy and age; a second message follows when a fresh GPS fix arrives. GPS works without internet.
A clearly marked test message can be sent from Settings.

### 27.5 Black box (`BlackBox.kt`)
- **Always (in memory only):** a 320 px JPEG (quality 55) every 0.5 s and every accelerometer magnitude sample, both
  trimmed to the last 10 s.
- **On a fall:** 3 more seconds are captured, then saved on the phone:
  - an **overview image in the Gallery** (Pictures/Nadaka): all frames in a 5-column grid labelled "−8.0 s … FALL …
    +3.0 s" (after-fall frames in red), with the motion trace underneath on a 0–4 g scale (1 g = still, ~0 = falling,
    spike = impact) and the fall moment marked;
  - every frame (`frame_−08000_ms.jpg` …) and `motion.csv` (`ms_from_fall, acceleration_g`) in the app's folder.
- Nothing is written unless a fall is detected; nothing is uploaded.

## 28. Layer 9: Mobility aids

### 28.1 Walk straight (`Straight.kt`)
The heading `ψ` (game rotation vector, remapped so the azimuth follows the camera) is locked. Each update:
```
Δ = wrap(ψ − ψ_locked) ∈ (−180°, 180°]
side = +1 if Δ > 10°,  −1 if Δ < −10°,  0 if |Δ| < 5°,  unchanged in between (hysteresis)
```
A side held for 1 s is cued (repeated at most every 4 s); a return to 0 is confirmed ("Straight."); |Δ| > 60° held for
2 s is a deliberate turn and re-locks the heading. Ends after 90 s or on "stop". Wrapping makes 3° → 349° a 14° drift
left, not 346° right.

### 28.2 Automatic torch (`Lens.kt → TorchPolicy`)
- Off → on after 1 s of mean luminance below 35 (one dark frame is not enough).
- While on, the camera sees the torch's light, not the room's. Every 8 s the torch goes off for 0.7 s (exposure
  settles), and the room is judged: luminance ≥ 55 → stay off; otherwise back on.
- Leaving the app turns it off and resets the policy, so it cannot come back on by itself in daylight.

### 28.3 Quick launch (`QuickLaunch.kt`)
An accessibility service that only receives key events. Three volume-up presses within 1.5 s open the app from
anywhere, including over the lock screen (the activity may show when locked and turn the screen on). Every press still
changes the volume; inside the app the service stays quiet so volume up keeps its meaning. No screen content is read.

## 29. Layer 10: Interface and accessibility (`ui/`)

### 29.1 Safety states (`Safety.kt`)
A pure function turns the pipeline state into one state, in priority order:

| State | Shape | Level |
|---|---|---|
| FALL DETECTED / EMERGENCY | octagon | danger |
| CAMERA OFF / SAFETY PAUSED / SENSOR OFF / CAN'T SEE | slashed circle | error |
| CALIBRATING / STARTING | ring of dots | info |
| READING / PAUSED / RESTING | pause | info |
| STOP (drop ahead, stairs down, head height) | octagon | danger |
| CAUTION (… coming), POSSIBLE DROP, STAIRS UP, BLOCKED AHEAD, obstacle | triangle | caution |
| PATH CLEAR | circle with check | calm |

Every state carries words, its own outline shape and a border style (solid fill, dashed heavy frame, quiet frame), so
it is readable without colour. TalkBack reads a composed sentence ("Warning. Stop. Drop ahead. Approximately 0.8
meters ahead."); when app audio is off, hazards are announced through an assertive live region instead.

### 29.2 Hero view (`HeroView.kt`)
Camera, depth + camera, or depth. The depth map is bilinearly upsampled 4× from the 48×32 grid; brightness follows a
range that adapts slowly (EMA 0.15) instead of rescaling per frame. Standard style: a smooth near-bright gradient.
High-contrast style: six brightness bands with contour lines. Overlays: floor outline (only when the floor was
measured), drop edge (thick line with a contrasting outline), hatched lower level with its distance, head-height line,
object brackets with name and distance.

### 29.3 Low vision
- Text: four sizes on top of the system font scale; titles shrink to fit rather than clip.
- Contrast: standard, high (white on black) and maximum (yellow on black); every text pair meets the WCAG ratio
  `(L₁ + 0.05) / (L₂ + 0.05) ≥ 7`, with `L` the relative luminance, and this is unit-tested.
- Simplified view, reduced motion (the state glyph's slow breathing and pulses switch off), high-contrast or inverted
  camera image, a rounded frame around the screen, controls at least 80 dp tall.

### 29.4 Settings and first run
Calibration, voice language, text size, contrast, detail, motion, main view, camera image, audio, haptic, alert style,
cloud mode, emergency contacts, quick launch, "test haptic", "test audio", "teach me the vibrations", "how fall alerts
work". The first run opens settings with a spoken welcome.

### 29.5 Diagnostics
Live depth map with overlays; detector and depth backend, latency and NPU delegation; frame rate, lens, activity,
heat; camera, barometer and motion-sensor status; the AI engine answering now, why, and the last answer's latency;
the complete drop-off evidence (edge score, depth verdict and confidence, ground plane, object suppression, evidence
class, history, possible / strong / recovery counts, path check, timing); the last spoken sentence; an in-app NPU
benchmark.

## 30. Layer 11: Platform, performance and heat

- **Heat levels** (`Thermal.kt`): the worst of four signals —
  Android thermal status (moderate → WARM, severe → HOT, critical → CRITICAL), thermal headroom (≥ 0.85 / 0.93 / 0.98),
  battery temperature (≥ 42 / 44 / 46 °C) and the app's own 90th-percentile frame time against its learned cool
  baseline (> 1.5× → WARM, > 2× → HOT). Escalation is immediate; stepping down needs sustained calm. The level is shown
  and "Phone is hot" is spoken once; features are not reduced unless `Settings.heatThrottle` is enabled.
- **Battery:** spoken warning at low charge.
- **Watchdogs:** a failed frame shows "safety paused" instead of silently stopping; no frames for 4 s shows "camera
  off"; the Qwen server is restarted after a crash; missing motion sensors are reported.
- **Logging:** a per-evaluation drop-off CSV in debug builds (edge, depth verdict and confidence, ground, object,
  class, confidence, state, path check, timings), used to diagnose field behaviour.

---

# Part V — Performance

Measured on the iQOO test phone (Snapdragon, Hexagon NPU):

| Model | Backend | Time | Detail |
|---|---|---|---|
| YOLOX int8, 640×640 | Hexagon NPU (QNN) | 1.8 ms inference, ~4 ms total | 317 / 317 operations on the NPU |
| YOLOX int8 | GPU / CPU | 47.8 ms / 78.8 ms total | for comparison |
| Depth Anything V2 FP16, 518×518 | Hexagon NPU (QNN) | 27.5 ms inference, ~46 ms total | 598 / 598 operations on the NPU |
| Depth Anything V2 | GPU / CPU | 250.6 ms / 560.5 ms total | for comparison |
| Qwen3-VL-2B image encoding | Hexagon NPU | 0.25 s | GPU 14–17 s, CPU 33 s |
| Qwen3-VL-2B answer | Hexagon NPU | 0.5–1.5 s | ~37 tokens/s decode, ~660 tokens/s prompt |
| Model load with graph cache | — | YOLOX 143 ms, depth 170 ms, Qwen ~5 s | depth 5.2 s without the cache |
| App CPU after frame gating | CPU | ~26 % of a core | ~85 % before |

Method, median / p90 / p95 per stage and raw data: [`docs/npu-benchmark.md`](docs/npu-benchmark.md) and
`docs/Nadaka_NPU_Benchmark_Report.pdf`. The in-app benchmark (Diagnostics → Run NPU benchmark) reproduces them on any
phone.

---

# Part VI — Verification

Unit tests (`app/src/test/`) run on the JVM against the same Kotlin logic that runs on the phone:

| Area | What is tested |
|---|---|
| Drop-offs (`DropTest`) | Synthetic scenes ray-cast through the floor model with real edges and depth. **Must stay silent:** shadows, painted lines, tile grids, rugs, doormats, door thresholds, puddles, marble, furniture edges, moving people, blank views, walls. **Must confirm:** steps, 15 cm kerbs, stairs, walking toward stairs, standing at stairs, a 10° pitch, a 1.5 m platform edge. **Rules:** one strong frame is not confirmed; 2 of 3 → possible; 3 of 5 → confirmed; recovery needs many clean frames; unreliable depth never supports; the barometer alone never confirms; a reflection never says stop; stairs up found and counted; walls, ramps and kerbs are not stairs; stairs down counted, platforms not. |
| Depth (`DepthTest`) | Floor ruler, table tops never becoming the floor, head height, obstacles, standing vs walking. |
| Tracking (`TrackerTest`, `PerceptionTest`) | Association, looming, approaching, letterbox mapping, categories, per-class confidence, label stabiliser. |
| Alerts (`AlertPolicyTest`, `MovingTest`, `SafetyUiTest`) | Priorities, ranges, repeat limits, crowd, safety states, never-green-light. |
| Emergency (`FallTest`) | Real fall detected once; jumps, walking, stumbles and short drops rejected. |
| Calibration, torch, walk straight, bus numbers, panels, quick launch, languages, haptics, accessibility contrast | Each has its own test file. |

Field verification uses the Diagnostics screen and the drop-off CSV log.

---

# Part VII — Safety, privacy and known limits

**Safety principles**
- Never tells the user it is safe to walk, cross or go; the cane stays primary.
- Silent or "can't tell" when unsure; never an invented distance.
- Missing or stale depth is never evidence of a hazard.
- Safety processing never depends on the network.

**Privacy**
- Camera images are processed on the phone. They leave it only if the user chooses cloud answers, and then only with a
  question.
- Black-box images are kept in memory and saved locally only after a fall; nothing is uploaded.
- The cloud API key lives in `local.properties` (git-ignored). Emergency messages go only to contacts the user picked.

**Known limits**
- Glass doors and mirrors: depth sees through or into them.
- Stairs going down are counted only within ~1.5 m.
- Distances are most accurate after calibration; beyond 10 m they are reported as unknown.
- Auto-rickshaws are detected as vehicles rather than named.
- Free cloud models may be busy; the on-device model then answers.
- Quick launch and the black box work while the app is running.

---

# Part VIII — Project structure and build

```
app/src/main/java/app/nadaka/
  MainActivity.kt        analysis loop, settings object, speech output, wiring of every layer
  WideCamera.kt          Camera2 logical camera, frame gate, YUV → RGB
  Detector.kt            LiteRT + QNN runtime, YOLOX inference, suppression
  Perception.kt          categories, per-class confidence, letterbox maths
  Tracker.kt             tracks, label votes, looming, ego-motion, learned classifier
  EgoMotion.kt           IMU fusion: steps, gyro, gravity, heading, fall and black-box input
  Activity.kt            walking / standing / vehicle detection
  Depth.kt               Depth Anything V2, floor ruler, obstacles, object distances
  drop/                  drop-off pipeline: edges, depth verdict, ground plane, fusion,
                         state machine, stairs and step counting, reflection guard
  Calibration.kt         voice-guided calibration
  Alerts.kt              decision and alert policy, priorities, camera health
  Haptics.kt             haptic vocabulary, proximity ticks, drop-off vibration
  Lang.kt                Hindi / Telugu templates, on-device answer translation
  Voice.kt               speech recognition, intents, never-green-light filter
  Qwen.kt / Gemma.kt     on-device vision-language models
  Cloud.kt               engine routing, cloud client
  Panel.kt               scene finder, lift panels, room numbers
  Reader.kt              currency and medicine reading
  Bus.kt                 bus route numbers
  Fall.kt / BlackBox.kt  fall detection, black-box recording
  Sms.kt                 emergency SMS with GPS
  Find.kt                object finder, siren
  Straight.kt            walk-straight aid
  Lens.kt                lens policy, torch policy
  QuickLaunch.kt         volume-up × 3 launcher service
  Sounds.kt              YAMNet sound awareness
  Thermal.kt             heat levels
  A11y.kt                saved preferences, palettes, camera filters
  ui/                    live screen, safety states, hero view, glyphs, settings, diagnostics
app/src/main/assets/     detect.tflite (YOLOX), depth.tflite (Depth Anything V2), yamnet.tflite, labels
app/src/main/jniLibs/    llama.cpp libraries for Snapdragon (Qwen server)
app/src/test/            unit tests
docs/                    NPU benchmark report and design notes
training/                data collection and training scripts
```

**Build and install**
- JDK: Android Studio's bundled JBR (`JAVA_HOME = C:\Program Files\Android\Android Studio\jbr`).
- `./gradlew installDebug` (arm64, Android 12+).
- Vision-language model files in the app's files folder under `qwen/`: `Qwen3VL-2B-Instruct-Q4_K_M.gguf` and
  `mmproj-Qwen3VL-2B-Instruct-Q8_0.gguf`.
- Optional cloud key: `openrouter.key=...` in `local.properties`.
- Tests: `./gradlew testDebugUnitTest`.
- First run: Settings → Calibrate (30 s); optionally enable quick launch in Android accessibility settings and add
  emergency contacts.
