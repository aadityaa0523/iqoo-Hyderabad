# Nadaka

**A walking safety companion for blind and low-vision people, running entirely on an iQOO phone.**
Built at the iQOO Hackathon 2026, Hyderabad (26–27 Sep 2026).

Nadaka is worn at chest height, camera facing forward. While you walk, it watches the path and warns about what a
white cane can't reach in time: **drop-offs and stairs, head- and waist-height obstacles, and things coming at you**.
It also reads money, medicine, bus numbers and signs, and answers questions about the scene.
It **complements the cane; it never replaces it.** It never says "safe to cross" or "the path is clear".

Everything safety-related runs on the phone's **Snapdragon NPU, fully offline**.

---

## Why it's different

Apps like Lookout, Seeing AI, Envision and Be My Eyes answer when you *ask* ("what is this?", "read this").
Nadaka **watches continuously while you walk** and warns on its own, offline, on one ordinary phone:

1. **Metric depth from a single camera, calibrated by the user in 30 s.** Depth Anything gives only relative depth;
   a voice-guided calibration (your height + two short walks) turns it into metres using the floor as a ruler.
2. **Drop-off detection that doesn't cry wolf.** A drop is confirmed only when four independent checks agree over
   several frames: a visual edge, depth falling away, the floor plane breaking, and not an object's outline.
   Shadows, rugs, tiles, painted lines and shiny floors are rejected (tested).
3. **"Is it coming at me, or am I walking into it?"** Your own walking (step sensor) and turning (gyroscope) are
   subtracted before an object is called "approaching".
4. **A vision-language model on the NPU, with safety rules.** Qwen3-VL answers in ~1 s offline; its answers are
   filtered so it can never green-light movement.

---

## Features

### Walking safety (always on, offline)
| Feature | What you get |
|---|---|
| Obstacles | People, vehicles, animals, furniture and other objects with distance and direction ("chair, 2 metres, 1 o'clock") |
| **Drop-offs** | Steps, kerbs, stairs down, platform edges: "Stop. Drop ahead" + **5 s of vibration** |
| **Stairs up** | "Stairs going up ahead, 2 metres. About 8 steps." (not mistaken for a wall) |
| Step counting | About N steps going up; at least N steps going down (lower steps hide behind the edge) |
| Head height | Branches, signboards: "Stop, head height" |
| Waist height | Table tops and counters with open space underneath |
| Approaching | Something coming toward you (your own motion removed) |
| Blocked path | "Blocked ahead" when something fills the view |
| Camera problems | "Can't see: covered / too dark / blurry. Use your cane." Never a fake warning |
| Torch | On after 1 s of real darkness; checks the room's own light every 8 s and turns itself off |
| Heat | Shown (WARM / PHONE HOT); features are never cut |

### Asking (press volume up, then speak)
"What is this?" · "Read the sign" · "Find the door / exit / stairs / lift" · "Find the chair / bottle / bag" ·
"Read the lift buttons" · "What's the room number?" · "Walk straight" · "Is it safe?" (never says yes) ·
"Teach me the vibrations" · "I'm sitting" / "I'm on a bus" · "Stop"

### Reading
Currency notes (denomination + running total) · medicine strips (name, strength, expiry) ·
**bus route numbers** read automatically ("Bus 218") · signs and text · lift panels and room numbers.

### Emergency
- **Fall detection** (real falls of 50 cm+): "Fall detected. Press a volume key if you're OK."
- No press in 7 s: a continuous **siren** + an **SMS with GPS location** to up to 2 emergency contacts (GPS works offline).
- Manual emergency: hold both volume keys for 2 s. The fall / emergency screen outranks everything.

### Feedback and languages
- **Vibration first**: distinct patterns for drop-off, head height, approaching, sounds, drifting, can't see.
- **Sound awareness**: horns, sirens, bells, reversing beeps, barking dogs.
- **English, Hindi, Telugu**: alerts from fixed, accurate sentences; AI answers translated on the phone, offline.
- Indian names: bike, cycle, lorry, traffic signal.
- Silent while the mic is open; a short quiet gap after answers.

### Setup and accessibility
- **One-button calibration** (Settings > Calibrate, voice-guided, ~30 s).
- **Quick launch**: volume up × 3 opens Nadaka from anywhere (one-time accessibility setup).
- Low-vision UI: 4 text sizes, 3 contrast levels, simplified view, reduced motion, high-contrast camera, full TalkBack.
- Audio / haptic switches; main view: camera, depth + camera, or depth map.

### AI routing
Questions are answered **on the phone (Qwen3-VL on the NPU)**, or, if chosen, by cloud models when online
(OpenRouter), falling back to the phone instantly when offline, busy or slow. **Safety never uses the network.**
Diagnostics shows which engine answered and why.

---

## Measured on the iQOO (Snapdragon, Hexagon NPU)

| Model | Backend | Time | Notes |
|---|---|---|---|
| YOLOX (int8, 80 classes) | Hexagon NPU (QNN) | ~4 ms total | 317/317 ops on the NPU |
| Depth Anything V2 (FP16) | Hexagon NPU (QNN) | ~30 ms inference | 598/598 ops on the NPU |
| Qwen3-VL-2B (Q4) image encoding | Hexagon NPU | 0.25 s | GPU 14–17 s, CPU 33 s |
| Qwen3-VL-2B answer | Hexagon NPU | ~0.5–1.5 s | ~37 tokens/s |

Full benchmark: [docs/npu-benchmark.md](docs/npu-benchmark.md) and `docs/Nadaka_NPU_Benchmark_Report.pdf`.
Live check in the app: **Menu > Diagnostics** (backends, timings, NPU delegation, live depth map, drop-off evidence).

---

## Architecture

```
Camera2 (logical camera, 640x480 frames, rate-limited by activity: 10 / 5 / 3 / 2 fps)
  │
  ├─ YOLOX on NPU ──► letterboxed input, per-class confidence ──► Tracker
  │                   (category matching + label vote, ego-motion: approaching vs merely near)
  │
  ├─ Depth Anything V2 on NPU ──► floor ruler (calibrated metres)
  │       ├─ drop-off pipeline: edge lattice + depth jump + ground plane + object suppression
  │       │   → evidence history → state machine (SAFE / POSSIBLE / CONFIRMED)
  │       ├─ stairs-up profile + step counting, reflection guard
  │       └─ head / waist / floor obstacles, object distances
  │
  ├─ IMU: steps, gyro, gravity, fall detector, walk-straight heading
  │
  └─► Alert policy (priority, repeat limits, never "safe") ──► vibration first ──► short speech
                                                                (English / Hindi / Telugu)
Voice questions ──► intent ──► on-device OCR / Qwen3-VL (NPU, llama.cpp) or cloud ──► safety filter ──► translate ──► speech
```

Key files: `MainActivity.kt` (loop), `Detector.kt` + `Perception.kt` + `Tracker.kt` (objects), `Depth.kt` +
`drop/` (depth, drop-offs, stairs), `Calibration.kt`, `Fall.kt`, `Qwen.kt` + `Cloud.kt` (AI), `Lang.kt`
(languages), `ui/` (screens).

---

## Build and run

- Android Studio JBR: `JAVA_HOME = C:\Program Files\Android\Android Studio\jbr`
- `./gradlew installDebug` (arm64, minSdk 31)
- Qwen model files go in the app's files folder, `qwen/` (Qwen3VL-2B-Instruct-Q4_K_M.gguf + mmproj Q8_0); without them
  the app falls back to Gemma, then to built-in answers.
- Optional cloud key: `openrouter.key=...` in `local.properties` (git-ignored, never committed).
- Tests: `./gradlew testDebugUnitTest` (162 unit tests: drop-offs and false alarms, stairs, falls, calibration,
  tracking, languages, alerts).

## Honest limits
- Glass doors and mirrors: depth sees through them; the cane stays essential.
- Stairs going down: steps are counted only within ~1.5 m (lower steps hide behind the edge).
- Distances are best after calibration; beyond 10 m they're reported as unknown, not guessed.
- Auto-rickshaws are detected as vehicles, not named "auto" (not a COCO class).
