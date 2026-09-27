# Nadaka

**An on-device walking safety system for blind and low-vision people, built for a single iQOO phone.**

Nadaka is worn at chest height with the camera facing forward. While the user walks, it continuously reads the path
ahead and turns what it sees into vibration first and short speech second: drop-offs and stairs, head- and
waist-height obstacles, people and vehicles coming closer, and anything the white cane cannot reach in time.
On request it reads money, medicine, bus numbers, signs and lift panels, and answers questions about the scene.

All safety processing runs **on the phone's Snapdragon Hexagon NPU, fully offline**. The system never tells the
user that it is safe to move; it reports what it measured, and the cane stays in charge.

---

## Contents

- [System overview](#system-overview)
- [Layer 0: Sensing](#layer-0-sensing)
- [Layer 1: Perception on the NPU](#layer-1-perception-on-the-npu)
- [Layer 2: Tracking and ego-motion](#layer-2-tracking-and-ego-motion)
- [Layer 3: Metric geometry and calibration](#layer-3-metric-geometry-and-calibration)
- [Layer 4: Hazard detection](#layer-4-hazard-detection)
- [Layer 5: Decision and alert policy](#layer-5-decision-and-alert-policy)
- [Layer 6: Feedback: haptics, speech, languages](#layer-6-feedback-haptics-speech-languages)
- [Layer 7: Assistant: voice, vision-language, reading](#layer-7-assistant-voice-vision-language-reading)
- [Layer 8: Emergency: fall detection, siren, SMS, black box](#layer-8-emergency-fall-detection-siren-sms-black-box)
- [Layer 9: Mobility aids](#layer-9-mobility-aids)
- [Layer 10: Interface and accessibility](#layer-10-interface-and-accessibility)
- [Layer 11: Platform, performance and heat](#layer-11-platform-performance-and-heat)
- [Measured performance](#measured-performance)
- [Project structure](#project-structure)
- [Build, install and test](#build-install-and-test)
- [Safety principles and known limits](#safety-principles-and-known-limits)

---

## System overview

```
                         ┌─────────────────────────────────────────────────────────────┐
 Layer 0  SENSING        │ Camera2 logical camera · accelerometer · gyroscope · gravity │
                         │ step detector · rotation vector · microphone · GPS          │
                         └──────────────┬──────────────────────────────────────────────┘
                                        │ frame gate (rate chosen before any pixel work)
                         ┌──────────────▼──────────────┐   ┌──────────────────────────┐
 Layer 1  PERCEPTION     │ YOLOX int8   (Hexagon NPU)  │   │ Depth Anything V2 FP16   │
                         │ letterbox · per-class conf  │   │ (Hexagon NPU)            │
                         └──────────────┬──────────────┘   └────────────┬─────────────┘
 Layer 2  TRACKING       category matching · label vote · ego-motion ·  │
                         approaching vs merely near · time-to-contact   │
 Layer 3  GEOMETRY       floor ruler · voice calibration · lens FOV ────┤
 Layer 4  HAZARDS        drop-off evidence fusion · stairs up/down · step counting ·
                         head / waist / floor obstacles · reflection guard · blocked path
 Layer 5  DECISION       health gate · safety priority · repeat limits · never-green-light filter
 Layer 6  FEEDBACK       haptic vocabulary · short speech · English / Hindi / Telugu
 Layer 7  ASSISTANT      voice intents · Qwen3-VL on the NPU (llama.cpp) · OCR · cloud routing
 Layer 8  EMERGENCY      fall detection · siren · SMS with GPS · black-box recording
 Layer 9  MOBILITY       walk-straight · auto torch · quick launch
 Layer 10 INTERFACE      low-vision UI · TalkBack · live depth view · diagnostics
 Layer 11 PLATFORM       NPU delegation · graph cache · frame gating · heat indicator
```

The camera loop runs on one analysis thread. Each analysed frame goes through detection, depth, tracking,
geometry and hazards in order; the result is one immutable state object that the decision layer turns into at
most a few alerts and the interface draws.

---

## Layer 0: Sensing

### Camera
- **Logical Camera2 camera** (`WideCamera.kt`): one session delivering a full-resolution `TextureView` preview and a
  640×480 `YUV_420_888` analysis stream. YUV is converted to upright ARGB in a single pass with the sensor rotation
  applied during the conversion (no second rotation pass, no per-pixel allocation).
- **Frame gate before conversion.** A `take()` predicate is consulted *before* YUV→RGB, so a skipped frame costs
  nothing. Earlier designs converted every frame and discarded it afterwards; gating first cut the app's CPU load
  from ~85 % to ~26 % of a core.
- **Activity-based rate.** Frames are analysed at 10 fps while walking, 5 standing, 3 sitting, 2 in a vehicle
  (`Settings.fps*`). Reading mode always runs at walking rate.
- The main 1× lens is used for analysis; automatic lens switching is disabled because every switch invalidates
  the depth calibration and object tracks.

### Motion and position (`EgoMotion.kt`)
- Accelerometer (fall detection, black box), gyroscope (turn compensation), gravity (camera pitch and roll),
  step detector (walking speed and step counting), game rotation vector (heading for walk-straight).
- GPS is read only for emergency messages.
- Microphone for voice questions and for sound awareness (Layer 6).

---

## Layer 1: Perception on the NPU

### Object detection: YOLOX (`Detector.kt`, `Perception.kt`)
- **Model:** YOLOX, int8 weights and activations (w8a8), 640×640 input, 80 COCO classes, compiled for the Hexagon
  NPU. Outputs are three quantised tensors (boxes, scores, class index), dequantised in Kotlin.
- **Letterboxed input.** The 480×640 portrait frame is scaled to fit 640×640 without distortion and padded with the
  model's training grey (114); boxes are mapped back with `unletterbox()`. Stretching the frame would make every
  object 33 % wider than the network was trained on.
- **Per-class confidence.** Safety classes (people, vehicles, animals, street objects) pass at 0.45. Classes that
  misfire indoors need more (bed, toilet, refrigerator, oven 0.6; couch, TV 0.55). About 25 classes irrelevant to
  walking (cutlery, fruit, sports gear, toothbrush…) are dropped at the source.
- **Suppression.** Class-aware non-maximum suppression (IoU 0.45), then a cross-class pass: two labels on almost the
  same box (IoU > 0.7) keep only the one with the higher `score × safety priority`.
- **Categories.** The 80 classes are grouped into PERSON, ANIMAL, VEHICLE, SEAT, FURNITURE and OTHER. Categories drive
  tracking and safety priority; speech keeps the specific, locally used name (bike, cycle, lorry, traffic signal).

### Depth: Depth Anything V2 (`Depth.kt`)
- **Model:** Depth Anything V2 (ViT-S), 518×518 input, run in FP16 on the Hexagon NPU. Output is relative inverse
  depth (disparity) per pixel.
- Two products per frame: the full-resolution disparity (used by the drop-off pipeline to sample around candidate
  edges) and a 48×32 pooled grid (used for obstacles, distances and the live depth view).
- Depth runs on every analysed frame (~30 ms); stale depth is treated as unreliable rather than reused.

### Runtime (`Detector.kt → openInterpreter`)
- LiteRT with the **Qualcomm QNN delegate** on the HTP backend, sustained-high-performance mode, and a per-model
  **graph cache token**: the first launch compiles the graph for the NPU, later launches load it in milliseconds.
- Automatic fallback HTP → GPU → CPU if a backend is unavailable; the active backend is shown in Diagnostics.

---

## Layer 2: Tracking and ego-motion

### Tracker (`Tracker.kt`)
- **Association:** greedy IoU matching (≥ threshold) between detections and existing tracks, **within the same
  category**. Camera yaw between frames is predicted from the gyroscope and applied to old boxes before matching,
  so turning the body does not break tracks.
- **Label stabiliser:** each track keeps confidence-weighted votes per label with exponential decay; its label is the
  majority. "Chair, couch, chair, chair" on one object stays a single track called "chair" instead of restarting.
- **Persistence:** a track must be matched on 5+ frames before it can be spoken; briefly missed tracks survive a short
  grace window so a one-frame dropout does not reset history.
- **Sure vs unsure:** a track is *sure* when it is steady, confident, and its depth and size-based distances agree.
  Unsure tracks are only spoken when practically touching.

### Ego-motion (`EgoMotion.kt`, `Tracker.measure`)
- **Own speed** = step cadence × stride (stride from calibration). **Own turn** = gyroscope yaw rate.
- **Looming:** relative growth of the box height over a sliding window; **time-to-contact** = 1 / growth.
- **Object's own speed** = closing speed − own speed × cos(bearing). An object is *approaching* only when it closes
  faster than the user's walking explains, so walking toward a parked car is not reported as the car coming.
- **Lateral speed** from box drift after turn compensation, scaled by distance.
- A small learned model (`ego_model.json`) can replace the hand-set approaching rule; features: growth, closing, own
  speed, yaw, pitch, box height, off-centre, object speed, time-to-contact.

---

## Layer 3: Metric geometry and calibration

### The floor ruler (`Depth.kt`)
Relative disparity `d` becomes optical-axis depth `z = s / d` with one unknown scale `s`. For a camera at height `h`,
pitched down by `θ`, the image row at angle `ρ` below the optical axis meets a flat floor at

```
z_floor(ρ) = h · cos ρ / sin(θ + ρ)
```

so `s = d · z_floor` on floor rows. The scale is learned from the floor 0.8–2 m ahead while walking, accepted only
after several frames agree, re-checked every frame against the ground under the feet, and adapted slowly.
A table top (much nearer than the floor) fails the check and can never become the ruler.

### Voice-guided calibration (`Calibration.kt`) — one button, ~30 s
1. **Height:** spoken ("170", "5 foot 8"); camera height ≈ 0.72 × body height.
2. **Tilt:** the user holds the phone as it will be worn; voice coaching ("tilt down a little") until the pitch
   holds steady inside the range where the floor ahead is visible.
3. **Two walks bracketed by volume-down presses** (press, 3 steps, press; press, 10 steps, press). The clock and the
   step count start at the first press of each walk. The depth ruler is learned fresh while walking; the known 13
   steps are compared with the step sensor's count to correct stride (factor clamped 0.5–2).
4. **Lens field of view** is read from the camera's focal length and sensor size (57.8° × 72.8° on the test phone,
   instead of an assumed 52° × 67°).
Everything is saved and restored at launch, so later sessions start calibrated.

### Distances to objects
- **Depth distance:** top quartile of disparity inside the object's box (tables and benches are measured at their
  top edge, where the user would collide). Beyond 10 m it is reported as unknown, never extrapolated.
- **Size prior** (class height ÷ angular box height) only when the whole object is visible and a person is standing;
  capped at 10 m. Cut-off or seated people get "unknown" instead of a wrong number.

---

## Layer 4: Hazard detection

### Drop-off pipeline (`drop/`)
A drop-off is confirmed only when **independent evidence agrees over time**. Per evaluation (~15 Hz):

1. **Edge lattice** (`EdgeAnalyzer.kt`): on a 120×160 grey copy of the lower view: 3×3 blur, Sobel gradients, keep
   pixels with strong vertical gradient that is ≥ 1.5× the horizontal one (near-horizontal edges only). Per row, the
   longest run with small gaps (≥ 35 % of the width), strength, continuity and the brightness/texture difference
   between bands above and below. Non-maximum suppression; best 3 candidates.
2. **Depth verdict** (`DropDepthAnalyzer.kt`): 7 columns × 3 offsets sampled just below (near side) and just above
   (far side) each edge, each normalised by the disparity a flat floor would have there. The far side is judged
   **relative to the near side of the same frame** (`1 − far/near`), which is independent of the ruler's exact
   scale; the near side must still be the user's floor (within ±35 %), so a table top can't pose as one.
   Verdict: SUPPORTS (drop ≥ 8 %), CONTRADICTS (floor continues, or far side nearer), or UNRELIABLE.
   Missing or stale depth is never evidence of a drop.
3. **Ground plane** (`GroundPlaneAnalyzer`): least-squares plane `height = a + b·ahead + c·lateral` through 3D floor
   points (Cramer's rule); reliable only with enough points, small residual and a plausible slope. Points beyond the
   edge lying ≥ 7 cm below the plane = a break.
4. **Object suppression:** an edge sitting on a tracked object's outline (bench, bag, chair…) loses confidence.
5. **Fusion** (`DropDecision.kt`): edge alone is never enough; STRONG needs a strong edge + supporting depth + (plane
   break or high depth confidence).
6. **Temporal state machine:** POSSIBLE when 2 of the last 3 evaluations have evidence; CONFIRMED when 3 of the last 5
   are STRONG; back to SAFE only after 8 clean evaluations. Camera blocked and "path not traversable" override.
7. **Reflection guard:** a "drop" deeper than 1.2 m behind an edge where the far side looks like the same floor (or
   a bright mirror image) is capped at POSSIBLE: shiny floors and puddles never produce "Stop".

Output: soft single pulse for POSSIBLE; for CONFIRMED, "Stop. Drop ahead, 2 metres" and **5 s of vibration**
(escalating, or full strength when descending).

### Stairs and step counting (`StairsUpAnalyzer`)
- A height profile along the walking corridor (median of 5 columns per row, near to far, through the floor ruler).
- **Stairs up:** the profile leaves the floor (> 8 cm) and keeps rising with a least-squares slope of 0.35–1.3
  (≈ 20°–52°) to at least 30 cm. Walls (near-vertical), ramps (gentle) and single kerbs (stop rising) are rejected.
  Needs 3 of the last 5 evaluations; replaces the "blocked" warning.
- **Step counting:** distinct height levels 10–30 cm apart, each held for 2+ samples. Up: "about N steps".
  Down: "at least N steps" (lower steps hide behind the top edge until within ~1.5 m); a platform drop (one big jump,
  then flat) is never called stairs.

### Other depth hazards
- **Head height:** a near point 1.2–2.1 m above the floor with free space beneath it (branches, signboards).
- **Waist height:** a surface 0.45–1.2 m high within reach with the floor continuing underneath (table tops,
  counters), checked walking and, with a calibrated ruler, standing.
- **Floor obstacle:** the corridor floor looks nearer than a flat floor should for several rows (walls, poles).
- **Blocked path:** centre and bottom of the view at the same depth (nothing but an obstacle in view).
- **Camera health:** mean brightness and Laplacian sharpness → covered, too dark, blurry; hazards are not reported
  from an unusable image.

---

## Layer 5: Decision and alert policy (`Alerts.kt`)

- **Order of importance:** camera health → drop-offs and head height → approaching objects → close obstacles in the
  path → awareness of moving and static objects.
- **Approaching** is spoken only within 8 m; **moving** objects only when they are coming closer and within 6 m;
  **static** objects within 5 m in the walking path, once, and again only if they loom 50 % larger. People walking
  away or crossing far ahead are silent.
- **Safety priority** when several objects compete: vehicle 1.8 > person 1.5 > animal 1.4 > seating/furniture and
  street furniture 1.2 > other 1.0, combined with distance (`urgency = distance / priority`).
- **Repeat limits** per hazard type and per object so warnings do not nag; a crowd is announced as "crowd ahead"
  instead of repeated "person" alerts.
- **Never a green light:** a filter blocks any spoken phrase that would tell the user it is safe to walk, cross or
  go, including answers from language models. "Is it safe?" is answered only with what was recently detected.
- **Quiet windows:** nothing is spoken while the microphone is open (vibration still works), and routine speech
  pauses for 3 s after an answer; danger still interrupts.
- **Alarm priority:** during a fall countdown or siren, routine alerts are held back and the emergency screen
  outranks every other state.

---

## Layer 6: Feedback: haptics, speech, languages

### Haptic vocabulary (`Haptics.kt`)
Distinct rhythms, learnable through a spoken lesson ("Teach me the vibrations"):

| Pattern | Meaning |
|---|---|
| Pulses growing stronger, 5 s | Stop: drop-off confirmed |
| One soft pulse | Possible edge ahead |
| Two rising swells | Head height |
| Four taps getting faster | Something coming at you |
| Long–short–long | A horn, siren or barking dog nearby |
| Two soft pulses | The camera can't see |
| One long / two short | Walk straight: drifted left / right |
| Ticks, faster when closer | Something in the path (parking-sensor style) |

Drop-off vibrations use the alarm usage class, so they are felt even when sound is off.

### Speech
- Vibration first; only the words that matter are spoken ("Stop. Drop.", "Head.", "Bus 218.").
- **English, Hindi and Telugu** (`Lang.kt`): the app's own alerts are translated from fixed templates written for each
  language (including Hindi gender agreement, e.g. "कार पास आ रही है" / "ट्रक पास आ रहा है"), spoken by the phone's
  offline voices. Free-form AI answers are generated and safety-checked in English, then translated on the phone
  with ML Kit (offline once the language pack is present). Any sentence without a template is spoken in English
  rather than garbled.

### Sound awareness (`Sounds.kt`)
An audio classifier (YAMNet) on the microphone recognises horns, sirens, bells, reversing beeps and barking dogs and
announces them with the sound pattern.

---

## Layer 7: Assistant: voice, vision-language, reading

### Voice (`Voice.kt`)
Press volume up, then speak. The on-device recogniser's alternatives are matched by a deterministic grammar;
safety questions are recognised first. Commands include: what is this / what's ahead, read the sign, find the
door / exit / stairs / chair / bottle, read the lift buttons, what's the room number, walk straight, is it safe,
teach me the vibrations, I'm sitting / on a bus / walking, speak everything / vibration first, stop.

### Vision-language model on the NPU (`Qwen.kt`)
- **Qwen3-VL-2B-Instruct** (4-bit language model, 8-bit vision projector) served by a **llama.cpp** build for
  Snapdragon, bundled as native libraries and started by the app on `127.0.0.1` only. The Hexagon backend encodes an
  image in ~0.25 s; answers take ~0.5–1.5 s.
- Prompt: name the main object first, one or two sentences under 20 words, left / ahead / right only, never guess
  distances, prices in rupees; the reply is trimmed to two sentences.
- 1,024-token context, one request slot, image capped at 256 tokens, full-resolution preview frame as input.
- A watchdog restarts the server if it dies (up to 3 times in a row). A second on-device model (Gemma) is used if the
  Qwen files are absent.

### Engine routing (`Cloud.kt`)
- Two modes: **On-device** (default) or **Cloud when online**. In cloud mode, questions go to hosted vision models
  through an OpenAI-compatible API, trying several models in order; offline, busy (HTTP 429) or slower than 8 s →
  the same question is answered on the phone at once; two failures pause the cloud for a minute.
- The API key is injected at build time from `local.properties` and never committed.
- Safety features never use the network. Diagnostics shows which engine answered, how long it took, and why.

### Finding things without a detector class (`Panel.kt → SceneFinder`)
Doors, exits, stairs, lifts, counters: the vision-language model is asked every 2.5 s while the user turns, and a
result is announced only when **two consecutive looks agree on the side** ("Door on your right, closed").
Everyday objects the detector knows are found by a distance-and-direction guide instead.

### Reading (`Reader.kt`, `Panel.kt`, `Bus.kt`)
- **Currency:** OCR text rules (reserve-bank phrases, denomination votes) with a running total.
- **Medicine:** strength with units and expiry parsing (`EXP 03/27`, `MAR.2027`…), expired medicine flagged.
- **Lift panels:** OCR words with positions, ordered top-to-bottom and left-to-right ("4, 5, 2, 3, ground, 1");
  **room numbers:** the largest number on the sign. The language model is used only if OCR finds nothing.
- **Bus route numbers:** when a bus fills enough of the view, its route board (top 45 % of the box) is cropped from the
  full-resolution preview, upscaled and read; route patterns (`218`, `10H`, `5K/1`) are accepted and number plates
  rejected; the same number must be read twice. One vision-model attempt if OCR fails.

---

## Layer 8: Emergency: fall detection, siren, SMS, black box

### Fall detection (`Fall.kt`)
A fall must pass four stages in order, on raw accelerometer data (~50 Hz):
1. **Free fall:** acceleration below 0.45 g; the distance fallen is integrated from the missing gravity
   (`v += (1−g)·9.81·dt`, `h += v·dt`) and must reach **≥ 0.5 m** (≈ 0.32 s of free fall).
2. **Impact:** above 2.3 g within 1.2 s.
3. **Stillness:** mean |g − 1| < 0.25 for 1.5 s after an 0.8 s settle window.
4. **Orientation change:** ≥ 45° between the carried and resting gravity vectors.
Jumps that land upright, walking, stumbles without impact and short drops onto furniture are rejected.

### Response
- "Fall detected. If you are OK, press a volume key…" plus an "alarm in 3 seconds" warning; no popup.
- No press in 7 s → a **continuous siren**: a 650→1500→650 Hz wail generated in the app and looped seamlessly
  (the pitch is scaled so each loop ends on a whole number of cycles, so there is no click or gap), on the alarm
  channel at full volume (restored afterwards), with continuous vibration.
- **SMS to up to 2 contacts** picked from the phone's contacts: a map link to the last known position, then an
  update when a fresh GPS fix arrives (GPS works without internet).
- Holding both volume keys for 2 s triggers the same emergency manually.

### Black box (`BlackBox.kt`)
While the app runs, the last 10 s are kept **in memory only**: a small JPEG every 0.5 s (320 px) and every
accelerometer sample. When a fall is detected, 3 more seconds are captured and then saved on the phone:
- an **overview image in the Gallery** (Pictures/Nadaka): all frames in a grid labelled "−8.0 s … FALL … +3.0 s",
  with the motion trace underneath (1 g still, ~0 g falling, spike = impact);
- every frame and a `motion.csv` in the app's folder for closer analysis.
Nothing is written unless a fall happens, and nothing is uploaded.

---

## Layer 9: Mobility aids

- **Walk straight** (`Straight.kt`): the current heading is locked; a drift beyond 10° held for 1 s triggers a
  vibration and "Drifting left, turn right a little"; a return is confirmed; a deliberate turn over 60° held for 2 s
  re-locks the heading; the aid ends after 90 s or on "stop".
- **Automatic torch** (`Lens.kt → TorchPolicy`): on after 1 s of real darkness. Because the camera cannot measure the
  room while lit by its own torch, the torch switches off for 0.7 s every 8 s to look: lit room → stays off, still
  dark → back on. Leaving the app always turns it off.
- **Quick launch** (`QuickLaunch.kt`): an accessibility service that only watches for three volume-up presses within
  1.5 s and opens the app from anywhere, including the lock screen. Every press still changes the volume; no screen
  content is read.

---

## Layer 10: Interface and accessibility (`ui/`)

- **One large safety state** (`Safety.kt`) derived purely from the pipeline state: PATH CLEAR, POSSIBLE DROP, STOP
  (drop, stairs down, head height), STAIRS UP, OBSTACLE, BLOCKED AHEAD, CAN'T SEE, CALIBRATING, FALL DETECTED,
  EMERGENCY and more. Each state has its **own shape** (circle-check, triangle, octagon, slashed circle, pause), words
  and border style, so it reads without colour.
- **Hero view:** camera, depth + camera, or the live depth map (smooth gradient, or brightness bands with contour lines
  in high-contrast mode), with annotated floor outline, drop edge, lower level, head height and objects.
- **Low vision:** four text sizes (plus the system font scale), three contrast levels including yellow-on-black
  (every text pair ≥ 7:1), simplified view, reduced motion, high-contrast or inverted camera image, a rounded screen
  frame, large controls (≥ 80 dp), full TalkBack descriptions and live announcements.
- **Settings:** calibration, language, audio, haptic, alert style, cloud mode, emergency contacts, quick launch,
  vibration lesson, test buttons.
- **Diagnostics:** live depth map with overlays; detector and depth backends, latency and NPU delegation (read from
  the runtime's own log); frame rate, lens, activity, heat; sensors; AI engine and why; every piece of drop-off
  evidence (edge score, depth verdict and confidence, ground plane, object suppression, history, state).

---

## Layer 11: Platform, performance and heat

- **NPU first:** both vision models are fully delegated to the Hexagon NPU; graph caching removes the first-launch
  compile on later starts.
- **Heat:** Android thermal status, thermal headroom, battery temperature and the app's own frame time are fused
  into NOMINAL / WARM / HOT / CRITICAL. The level is shown in the header and Diagnostics and "Phone is hot" is spoken
  once; features are not reduced (`Settings.heatThrottle` restores automatic slowdown if wanted).
- **Battery:** a spoken warning at low charge.
- **Robustness:** a crashed frame is reported on screen instead of silently stopping; the camera watchdog shows
  "camera off" if frames stop; missing sensors are reported.

---

## Measured performance

Measured on the iQOO test phone (Snapdragon, Hexagon NPU):

| Model | Backend | Time | Detail |
|---|---|---|---|
| YOLOX int8 | Hexagon NPU (QNN) | ~4 ms total, 1.8 ms inference | 317 / 317 operations on the NPU |
| Depth Anything V2 FP16 | Hexagon NPU (QNN) | ~30 ms inference | 598 / 598 operations on the NPU |
| Qwen3-VL-2B image encoding | Hexagon NPU | 0.25 s | GPU 14–17 s, CPU 33 s |
| Qwen3-VL-2B answer | Hexagon NPU | 0.5–1.5 s | ~37 tokens/s, ~660 tokens/s prompt |
| Model load with graph cache | — | YOLOX 143 ms, depth 170 ms, Qwen ~5 s | depth 5.2 s without cache |

Method and raw data: [`docs/npu-benchmark.md`](docs/npu-benchmark.md), `docs/Nadaka_NPU_Benchmark_Report.pdf`,
and the in-app benchmark (Diagnostics → Run NPU benchmark).

---

## Project structure

```
app/src/main/java/app/nadaka/
  MainActivity.kt        analysis loop, settings, speech output, wiring of every layer
  WideCamera.kt          Camera2 logical camera, frame gate, YUV → RGB
  Detector.kt            LiteRT + QNN runtime, YOLOX inference, NMS
  Perception.kt          categories, per-class confidence, letterbox
  Tracker.kt             tracks, label votes, looming, ego-motion, approaching
  EgoMotion.kt           IMU: steps, gyro, gravity, heading, fall input
  Depth.kt               Depth Anything V2, floor ruler, obstacles, distances
  drop/                  drop-off pipeline: edges, depth verdict, ground plane,
                         fusion, state machine, stairs, step counting
  Calibration.kt         voice-guided calibration
  Alerts.kt              decision and alert policy, priorities, distances in words
  Haptics.kt             haptic vocabulary, drop-off vibration controller
  Lang.kt                Hindi / Telugu templates, on-device answer translation
  Voice.kt               speech recognition, intents, never-green-light filter
  Qwen.kt / Gemma.kt     on-device vision-language models
  Cloud.kt               engine routing, cloud client
  Panel.kt               scene finder, lift panels, room numbers
  Reader.kt              currency and medicine reading
  Bus.kt                 bus route numbers
  Fall.kt / BlackBox.kt  fall detection, black-box recording
  Sms.kt / Find.kt       emergency SMS, siren, object finder
  Straight.kt            walk-straight aid
  Lens.kt                lens policy, torch policy
  QuickLaunch.kt         volume-up × 3 launcher service
  Sounds.kt              sound awareness
  Thermal.kt             heat levels
  A11y.kt                saved preferences, palettes, camera filters
  ui/                    live screen, safety states, hero view, settings, diagnostics
app/src/test/            unit tests for every decision layer
docs/                    NPU benchmark and design notes
training/                data collection and training scripts
```

---

## Build, install and test

- JDK: Android Studio's bundled JBR (`JAVA_HOME = C:\Program Files\Android\Android Studio\jbr`).
- Install on a connected phone: `./gradlew installDebug` (arm64, Android 12+).
- Vision-language model files go in the app's files folder under `qwen/`:
  `Qwen3VL-2B-Instruct-Q4_K_M.gguf` and `mmproj-Qwen3VL-2B-Instruct-Q8_0.gguf`.
- Optional cloud key: `openrouter.key=...` in `local.properties` (git-ignored).
- Unit tests: `./gradlew testDebugUnitTest` — drop-offs and their false alarms (shadows, rugs, tiles, painted lines,
  marble, furniture, reflections), stairs and step counts, falls, calibration, torch, tracking and label votes,
  letterboxing, alert policy, languages, walk-straight, bus numbers, panels.
- First run: Settings → Calibrate (30 s), then optionally enable quick launch in Android accessibility settings and
  add emergency contacts.

---

## Safety principles and known limits

**Principles**
- The system never says it is safe to walk, cross or go; the cane stays primary.
- When unsure it stays silent or says it cannot tell; it never invents a distance.
- Missing or stale depth is never treated as evidence of a hazard.
- Safety processing never depends on the network.
- Camera images stay on the phone unless the user chooses cloud answers; black-box images are saved locally only.

**Known limits**
- Glass doors and mirrors: depth sees through or into them.
- Stairs going down are counted only within ~1.5 m.
- Distances are most accurate after calibration; beyond 10 m they are reported as unknown.
- Auto-rickshaws are detected as vehicles, not named as such.
- Quick launch and the black box work while the app is running (the black box needs the camera active).
