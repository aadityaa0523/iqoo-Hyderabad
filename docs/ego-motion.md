# Ego-motion in Nadaka: research notes and design

## The problem

The camera is strapped to a person who walks, sways and turns. Everything in the
image moves because **the user** moves. Without ego-motion:

- walking toward a parked chair makes it "grow" exactly like a person walking at you;
- turning your body sweeps every box sideways, so frame-to-frame matching breaks;
- step bounce (±3-5 cm vertical per step) jitters boxes up and down.

"Approaching" is only meaningful as **object motion after subtracting my own motion**.

## What the literature offers

| Approach | Idea | Fit for a phone at a hackathon |
|---|---|---|
| **Visual-inertial odometry** (VINS-Mono, Qin et al. 2018; ARCore) | Fuse feature tracks with the IMU to get full 6-DoF pose | Best accuracy, but heavy; ARCore needs a supported device and a textured scene |
| **Learned monocular ego-motion** (SfMLearner, Zhou et al. CVPR 2017; Monodepth2 PoseNet, Godard et al. ICCV 2019) | A CNN predicts relative camera pose between frames, trained self-supervised by view synthesis | Needs hours of GPU training on video; scale is ambiguous from one camera |
| **Pedestrian dead reckoning** (step detector + heading) | Speed = cadence x stride length; heading from gyro/rotation vector | Cheap, robust for walking, metric speed after a 10 m stride calibration |
| **Gyro rotation compensation** (standard in video stabilisation / EIS) | Gyroscope measures rotation directly; image shift = angular rate x dt / FOV | Exact for rotation, near-free |
| **Time-to-contact from looming** (Lee 1976 "tau"; Camus 1995) | TTC = h / (dh/dt): how fast an object's image grows. No distance or calibration needed | Ideal: one division per box, and it is what the brain uses |

Key facts we rely on:

1. **Rotation is easy, translation is hard.** A gyroscope gives rotation with
   no drift over a second; a single camera cannot know metric translation
   (scale ambiguity). So: rotation from the gyro, forward speed from steps.
2. **Looming is scale-free.** TTC comes from relative image growth alone, so it
   works without knowing distance. Distance is only needed to turn TTC into a
   speed (closing speed = distance / TTC).
3. **Distance from a known size.** Monocular distance ~ real height / (angular
   height). A person is ~1.7 m tall; the class prior gives rough metres, good
   enough to compare against walking speed (~1-1.4 m/s).
4. **Step bounce moves boxes vertically but barely changes their height**, so
   height growth (not position) is the robust looming signal.

## Our design (hybrid, runs every frame, < 1 ms)

```
gyroscope (y axis) ──► yaw rate ──► predicted image shift = yaw_rate x dt / HFOV
step detector ──────► cadence x stride ──► ego forward speed (m/s)
YOLOX boxes (NPU) ──► tracker: match boxes after shifting by the predicted rotation
                     per track, over a 1 s window:
                       growth  = ln(h_now / h_then) / dt        (1/s)
                       TTC     = 1 / growth                     (s)
                       distance= class_height / (h x VFOV)      (m, rough)
                       closing = distance x growth              (m/s)
                       object_speed = closing - ego_speed x cos(bearing)
                     approaching = object_speed > 0.5 m/s and TTC < 4 s
                                   (or the trained classifier, if present)
```

- A chair while I walk at 1 m/s: closing ~1 m/s, ego ~1 m/s, object speed ~0: **not** approaching, just "near".
- A person walking at me while I stand: closing ~1.2 m/s, ego 0: **approaching**.
- Turning on the spot: boxes slide sideways; gyro compensation keeps the same track IDs.

Calibration knobs (in `Settings`): HFOV/VFOV of the camera, stride length,
yaw sign, thresholds. The physical world needs tuning; these are the dials.

## Why train, and what

The rule above is hand-set. Real data has noise the rule does not model: box
jitter, partial occlusion, people walking beside you. So we **learn the decision
from features that already include ego-motion**, using data we record ourselves:

- **Record mode** (long-press the screen): every tracked object, every frame, is
  logged with its ego-motion features to `Download/nadaka-logs/ego_*.csv`.
- **Labelling while walking**: press **volume-up** when something starts
  coming toward you and again when it stops. Those rows get `label = 1`.
- **Train** `training/train_ego.py` (numpy only, runs in Termux or on the
  laptop): logistic regression on standardised features, class-balanced,
  compared against the hand rule on a held-out file.
- **Deploy**: the script writes `ego_model.json`; put it in
  `app/src/main/assets/`. The app uses the learned model when present, the rule
  otherwise.

A logistic model is deliberate: 9 weights you can read and explain to a judge
("ego speed has a negative weight: my own walking explains the growth"), it
trains in seconds on the phone CPU, and it cannot overfit a few minutes of data
the way a deep network would.

## Honest limits

- Stride-based speed lags ~1 step and assumes walking straight.
- Distance from class height is rough (children, sitting people).
- Objects moving **across** your path (not toward) are not "approaching" by design.
- No lateral translation estimate (side-stepping).

## Future (after the hackathon)

Visual-inertial odometry via ARCore when supported; a learned PoseNet
(SfMLearner-style) trained self-supervised on our recorded walks with the IMU as
extra supervision; optical-flow residuals to catch moving objects YOLO misses.
