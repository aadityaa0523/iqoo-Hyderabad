# Nadaka

**An offline walking companion for blind and low-vision people, running entirely on an iQOO phone.**
Built at the iQOO Hackathon 2026, Hyderabad (26–27 Sep 2026).

Nadaka is worn at chest height, camera facing forward. It warns about what a white cane can't
reach in time (drop-offs, head-height obstacles, things coming at you), reads money, medicine and
signs, and answers questions about the scene. It **complements the cane; it never replaces it.**

Everything runs on the phone: no internet, no cloud, no images leave the device.

---

## Features

### Seeing (all on-device)

| Feature | How |
|---|---|
| Object detection (80 COCO classes: people, chairs, vehicles, dogs, bags…) | **YOLOX** int8 on the **Hexagon NPU** (Qualcomm QNN), ~22 ms/frame |
| Distance in metres | **Depth Anything V2** on the NPU (FP16); the floor is used as a ruler |
| **Drop-offs** (stairs down, drains) and **kerbs / single steps** | Floor looks farther than a flat floor would; needs corridor width, a sharp lip and consistent tracking (see below) |
| **Head-height obstacles** (branches, shelves, awnings) | Near depth at 1.2–2.1 m height with free space below |
| Unnamed obstacles (walls, poles) | Depth: floor looks nearer than it should |
| **Approaching vs merely near** | **Ego-motion**: your own walking (step detector) and turning (gyroscope) are subtracted |
| Moving vs static | Forward/sideways speed after removing your own motion; furniture can never "move" |
| Confidence | An object is used only when seen on 5+ frames, fully in view, with depth and size agreeing. **Unsure objects stay silent; it never says "maybe".** |

**Drop-off detection (v2)**: all of these must hold:
- walking (steps detected), chest-like camera angle (−5° to 35°);
- a floor ruler agreed over 5 frames, and the ground under the feet matching it (rejects table tops);
- ≥ 60 % of the walking corridor drops (rejects dark tiles, shadows);
- a big drop (> 45 % farther than flat floor) → **"Stop. Drop."**, or a small one (> 8 %) with a sudden lip → **"Step down."**;
- the edge distance follows the user's walking speed frame to frame (rejects noise);
- no table/bed/couch covering the bottom-centre of the view.

### Telling the user: vibration first

Blind pedestrians navigate by hearing, so Nadaka speaks as little as possible.
Research basis in [`docs/haptics.md`](docs/haptics.md) (Brewster & Brown 2004, van Erp 2002, Cassinelli et al. 2006).

| Meaning | You feel | You hear |
|---|---|---|
| Something in your path | Ticks, faster as it gets closer (from 2.5 m; near-continuous under 0.75 m); stop 1 s after you stop approaching | – |
| Something approaching | Four taps getting faster | – |
| Drop-off / step | Three long heavy pulses | "Stop. Drop." / "Step down." |
| Head-height obstacle | Two rising swells | "Head." |
| Camera can't see | Two soft pulses | "Camera blocked / Too dark. Use your cane." |

Awareness (spoken briefly, once per object):
- **static objects within 5 m** ahead: "Chair, 3 metres, 12 o'clock.";
- **moving objects within 10 m**: "Person moving, 8 metres, 1 o'clock.";
- crowds: "Crowd ahead." instead of repeating "person".

Directions use the **clock face** (12 o'clock = straight ahead), as taught in orientation & mobility training.
Spoken answers are never cut off by routine alerts; only danger interrupts.

### Voice questions (volume-up, wait for the double buzz, speak)

On-device speech recognition (offline English pack), keeps listening up to 8 s until you start talking.

| You say | Nadaka |
|---|---|
| "What's ahead?" | **Local Gemma 4 E2B** looks at the camera frame (plus detector facts) and describes the scene |
| "Read the sign" / "What does it say?" | Gemma reads the text and gives its meaning, translating Hindi/Telugu |
| Any other question ("Is the door open?") | Gemma answers from the camera |
| "Is it safe to cross?" / "Can I walk?" (English, Hindi, Telugu) | **Never "yes".** A fixed answer built from the last 1.5 s of sensor facts, handing the decision back to the cane |
| "Read this note / medicine" | Starts PAY / MEDS reading |
| "Use speech" / "Use vibration" | Full sentences vs vibration first |
| "Tell me more" / "Tell me less" | Chatty vs quiet |
| "Teach me the vibrations" | Plays each pattern with its meaning |

**Safety gate:** safety questions are caught before anything else and never reach Gemma; every Gemma
answer is scanned and discarded if it contains a movement green-light ("path is clear", "you can go",
"no obstacles"…).

### Reading (volume-down)

On-device ML Kit OCR with voice coaching ("Move closer", "Hold still"); answers only after two matching
frames, otherwise "Can't read clearly. I won't guess."
- **PAY:** note denomination + running total ("500 rupees. Total 700 rupees.").
- **MEDS:** name, strength, expiry; "Warning: expired … Do not take."

### Staying reliable

- **Activity modes (automatic):** walking (full guidance) / standing (quiet, only what is at you) /
  in a vehicle (depth and approach alerts paused: bus motion fakes them).
- **Camera health:** blocked lens, dark (turns on the torch first), blurry, tilted.
- **Thermal governor:** fuses Android thermal status, thermal headroom, battery temperature and its own
  frame time vs the phone's learned cool speed; sheds work, tells the user once, and never stops the
  safety loop (detection every 3rd frame, depth every 6th at worst). NPU runs in sustained (not burst) mode.
- Low-battery warning.

### Sighted view (judges, trainers, family via Office Kit screen mirroring)

Header with on-device NPU timings and mode, corner-bracket boxes with distance, a radar with 5 m / 10 m
rings, a depth thumbnail, a hazard banner, and a caption of exactly what the user feels and hears.

---

## Models (all on-device)

| Model | Runtime / hardware | Source & licence |
|---|---|---|
| YOLOX (int8, 640×640) | LiteRT + QNN delegate, Hexagon NPU | Qualcomm AI Hub, Apache-2.0 |
| Depth Anything V2 (FP16, 518×518) | LiteRT + QNN delegate, Hexagon NPU | Qualcomm AI Hub (listed as MIT) |
| Gemma 4 E2B (multimodal) | LiteRT-LM, GPU | Downloaded by Google AI Edge Gallery; Gemma terms |
| ML Kit Text Recognition (Latin, bundled) | On-device | Google ML Kit |
| Speech recognition | Android on-device recognizer | Google (system) |

Fallback order for the vision models: NPU → GPU → CPU (the screen shows which one loaded).

## Where to use it

Best in **flat, built environments**: college/office campuses, hospitals, station concourses, malls,
building interiors. **Not yet for:** busy road crossings (side traffic), hills and slopes, railway
platform edges, heavy rain / fog.

## Honest limits

- Glass doors, thin poles and wires are hard for any camera.
- Distances beyond ~5 m are rough (±30 %).
- One vibration motor: direction comes by voice (clock face), not vibration.
- Drop-off detection is verified on synthetic 3-D scenes; real-world stairs/kerb validation is ongoing.

---

## Build and install

Requirements: Android Studio's JDK, Android SDK, an iQOO / Snapdragon 8-series phone (tested on SM8850, Android 16).

```bash
./gradlew testDebugUnitTest      # 71 unit tests
./gradlew installDebug           # or push to main: GitHub Actions publishes the APK as release "latest"
```

**Gemma model** (once per phone, after the app has run once so it owns its folder):

```bash
adb shell cp /sdcard/Android/data/com.google.ai.edge.gallery/files/Gemma_4_E2B_it/*/gemma-4-E2B-it.litertlm /sdcard/Android/data/app.nadaka/files/
```

Offline speech: accept the "Download English (US)" prompt the first time you ask a question.

## Training

See [`training/README.md`](training/README.md).
- **Ego-motion approach classifier:** long-press the screen to record walks, volume-up marks "approaching";
  `python training/train_ego.py logs/*.csv` (numpy only, runs in Termux) → `assets/ego_model.json`.
  Method: [`docs/ego-motion.md`](docs/ego-motion.md).
- **Currency classifier:** MobileNetV3 fine-tuning in Termux on the phone (`training/train.py`).

## Code map

| File | What |
|---|---|
| `MainActivity.kt` | Camera loop, settings, wiring, speech/vibration output |
| `Detector.kt` | YOLOX + NMS; NPU/GPU/CPU opener |
| `Depth.kt` | Depth model, floor ruler, drop-off / head-height / obstacle analysis |
| `Tracker.kt`, `EgoMotion.kt` | Tracking, ego-motion, time-to-contact, record mode |
| `Alerts.kt` | What to say and when: priorities, ranges, habituation, camera health |
| `Haptics.kt` | Vibration vocabulary and proximity ticks |
| `Voice.kt`, `Gemma.kt` | Speech recognition, intents, safety gate, local Gemma |
| `Reader.kt` | PAY / MEDS OCR rules |
| `Activity.kt`, `Thermal.kt` | Activity modes, thermal governor |
| `Hud.kt` | Sighted view |

## Roadmap (written, not yet integrated)

- Horn / siren / bicycle bell / reversing / dog-bark alerts from the microphone (YAMNet on the NPU): 360° awareness.
- "Find a chair / my phone / a person" guidance.
- Emergency gesture (hold both volume keys) with alarm and spoken call for help.
- Voice-set modes: "I'm sitting", "I'm on the bus", "Let's go".
