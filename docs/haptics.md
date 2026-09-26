# Haptics-first feedback: research notes and design

## Why vibration first

A blind pedestrian navigates by **hearing**: traffic, footsteps, echoes off walls, the cane's tap.
Every word the phone speaks masks those sounds. So Nadaka speaks as little as possible and puts
routine information into vibration, keeping speech for the few moments that need words.

## What the research says

| Source | Finding | How we use it |
|---|---|---|
| Brewster & Brown, *Tactons: structured tactile messages for non-visual information display* (AUIC 2004) | Abstract vibration "icons" can carry meaning; **rhythm** is the most distinguishable parameter, then duration; people learn a small set quickly | One distinct rhythm per hazard class, 4 classes only |
| van Erp, *Guidelines for the use of vibro-tactile displays in HCI* (Eurohaptics 2002) | Users reliably tell apart only ~3 intensity levels; timing differences are easier than intensity differences | Meaning is carried by rhythm; intensity only reinforces urgency |
| Cassinelli, Reynolds & Ishikawa, *Augmenting spatial awareness with Haptic Radar* (ISWC 2006) | Mapping proximity to vibration lets people avoid unseen obstacles without training | Continuous proximity pulse for the nearest thing in the walking path |
| Car parking sensors; commercial blind aids (WeWALK smart cane, Sunu band) | "Faster = closer" is understood instantly, with no learning | Pulse rate rises as the obstacle gets closer, continuous buzz when about to touch |
| Android haptics guidance (VibrationEffect.Composition primitives) | Short crisp primitives feel clear; long buzzy vibrations feel like notifications and annoy | Built from CLICK / THUD / TICK / QUICK_RISE primitives on this phone's linear motor, with waveform fallback |

Hardware on the iQOO loaner (checked with `dumpsys vibrator_manager`): one linear resonant
actuator (150 Hz), amplitude control, all composition primitives supported. One actuator
means **no spatial direction** from vibration alone: direction stays in speech, on request.

## The haptic vocabulary (5 patterns, learnable in a minute)

| Meaning | Pattern | Why this shape |
|---|---|---|
| **Something in my path** | Single ticks, faster as it gets closer: none beyond 3 m, ~1 per second at 3 m, ~4 per second at 1 m, continuous under 0.75 m | Parking-sensor metaphor; needs no training |
| **Stop: drop-off** | Three long heavy pulses (THUD x3, 350 ms apart) | Heaviest, slowest, unmistakable; the one pattern that must never be confused |
| **Head-height obstacle** | Two rising swells (QUICK_RISE x2) | "Rising" = up at head level |
| **Something approaching** | Quick accelerating triple tap (CLICK, 120/80/40 ms) | Accelerating rhythm = coming at you |
| **Camera can't see** | Two low thuds, soft | Distinct, calm: "I'm not reliable right now" |

## What is still spoken (haptics mode, the default)

Only moments where a wrong or missing word is dangerous, in **one or two words**:

- Drop-off: **"Stop. Drop."**
- Head height: **"Head."**
- Camera can't see: **"Can't see. Use your cane."** (once)
- Mode changes and heat warnings (rare, once)
- Answers to questions ("what's ahead?" gives full detail with distance and clock direction)

Everything else (close obstacles, approaching people, moving things, static things within 5 m)
is vibration only. Speech mode (say "use speech") restores full sentences.

## Learning

Say **"teach me the vibrations"**: the phone plays each pattern once and names it.

## Honest limits

- One motor: no left/right in vibration. The user asks "what's ahead?" for direction.
- A phone on the chest is felt less than a wristband; pulses use strong amplitude near objects.
- Pattern meanings need a minute of learning; the proximity pulse does not.
