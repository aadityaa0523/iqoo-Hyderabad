package app.nadaka

import android.Manifest.permission.ACTIVITY_RECOGNITION
import android.Manifest.permission.CAMERA
import android.Manifest.permission.RECORD_AUDIO
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.camera.core.Camera
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.util.Log
import android.util.Size
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import java.util.Locale
import java.util.concurrent.Executors

const val TAG = "NADAKA"

/** Every tunable lives here; the caregiver screen will edit these. */
object Settings {
    var minScore = 0.45f
    var speechCooldownMs = 2000L
    var readTimeoutMs = 12000L
    var minTextArea = 0.04f // text must cover this fraction of the frame, else "Move closer"
    var coachCooldownMs = 2500L
    var moneyTotalResetMs = 60000L // a new note after this gap starts a new total

    // Ego-motion (docs/ego-motion.md). Calibration knobs: measure FOV and stride on the real phone/user.
    var hfovDeg = 52f // portrait width of the analysis frame
    var vfovDeg = 67f // portrait height
    @Volatile var zoom = 1f // current camera zoom (0.6 = ultra-wide); FOV-dependent geometry follows it
    val hfovRad get() = zoomedFov(hfovDeg, zoom)
    val vfovRad get() = zoomedFov(vfovDeg, zoom)
    var wideZoom = 0.6f
    var wideNearM = 2.0f // something this close (or half out of view) -> ultra-wide
    var lensClearMs = 4000L // nothing close for this long -> main lens, to see far
    var lensDwellMs = 3000L // minimum time between switches
    var yawSign = 1f // flip to -1 if boxes lose their track while turning
    var strideM = 0.7f // walk 10 m, count steps, stride = 10 / steps
    var stepWindowMs = 3000L
    var stepStaleMs = 1500L // no step for this long = standing
    var trackIou = 0.3f
    var trackKeepMs = 500L
    var growthWindowMs = 1000L
    var minGrowthSpanS = 0.25f
    var approachMps = 0.7f // object's own speed toward me
    var approachTtcS = 3f
    var approachCooldownMs = 2500L

    // Edge cases (Alerts.kt). Calibrate luma/blur thresholds on the real phone.
    var minHits = 5 // frames an object must be seen before it is spoken
    var speechGapMs = 2500L
    var habituationGrowth = 1.5f // re-announce a known object only once it looks 50% bigger
    var crowdCount = 4
    var crowdRepeatMs = 10000L
    var healthPersistMs = 1000L
    var healthRepeatMs = 8000L
    var blockedLuma = 20f
    var darkLuma = 35f
    var torchOffLuma = 170f // torch stays on until the scene is this bright (daylight)
    var blurVar = 15f
    var minPitchDeg = -25f // camera looking up
    var maxPitchDeg = 45f  // camera looking at the floor
    var maxRollDeg = 30f
    var lowBatteryPct = 15

    // Thermal governor (Thermal.kt)
    var heatWindow = 60 // frames per p90 window
    var heatWarmupFrames = 240 // NPU warm-up: ignore our own speed until then
    var heatCalmMs = 20000L // calm needed before stepping down a tier

    // Voice questions (Voice.kt)
    var gemmaModelFile = "gemma-4-E2B-it.litertlm" // copied from Edge Gallery into Nadaka's files dir
    var gemmaImagePx = 512
    var listenWindowMs = 8000L // after a press, wait this long for the user to start talking
    var soundMinScore = 0.35f // YAMNet score for horn / siren / bell / reversing / bark
    var soundRepeatMs = 8000L
    var findRepeatMs = 3000L
    var findTimeoutMs = 45000L
    var modeStickyMs = 300000L // a mode set by voice holds for 5 minutes (or until "let's go")
    var emergencyHoldMs = 2000L
    var voiceLanguage = "en-US" // the offline speech pack installed on the loaner phone
    var hazardMemoryMs = 1500L // a hazard seen this recently is still reported when asked "is it safe?"

    // Confidence (Tracker.kt): unsure objects are not announced unless approaching or touching.
    var sureHits = 5
    var sureScore = 0.45f
    var agreeRatio = 1.6f // depth vs size distance may differ by up to 60%
    var movingMps = 0.6f // below this, box jitter, not motion

    // Quiet by default: only safety-relevant speech (Alerts.kt). chatty = also announce far/new objects.
    var hapticsFirst = true // vibration carries routine alerts; speech only for "Stop. Drop." etc. (docs/haptics.md)
    var pulseMaxM = 2.5f // proximity ticks start when something in the path is this close
    var pulseProgressM = 0.3f // the gap must shrink by this much to count as "getting closer"
    var pulseStaleMs = 1000L // no progress for this long -> stop ticking (except at touching range)
    var tickMs = 45L // long enough to feel on the chest
    var hapticGain = 1.0f // one knob for overall strength if the user finds it weak/strong
    var chatty = false // true = static objects at any distance, not just within staticRangeM
    var staticRangeM = 5f  // announce static objects within this range (once each)
    var staticPathDeg = 30f // ...and roughly ahead: things far to the side don't block the way
    var movingRangeM = 10f // announce moving objects within this range
    var movingRepeatMs = 12000L
    var pathHalfDeg = 20f // "in my path" = within this angle of straight ahead

    // Activity modes (Activity.kt). vehicleVibration is a calibration knob: check it on a real bus.
    var walkingStepMs = 2000L
    var vehicleVibration = 0.25f
    var activityGraceMs = 10000L

    // Speech (Alerts.kt)
    var maxAlerts = 2 // e.g. "person approaching" AND "chair close" in the same breath
    var closeM = 1.5f
    var closeRepeatMs = 4000L
    var veryCloseM = 0.75f
    var hazardRepeatMs = 3500L

    // Depth (Depth.kt). cameraHeightM is THE calibration knob: measure lens height on the wearer.
    var depthEvery = 2 // run the depth model every Nth analysed frame
    var cameraHeightM = 1.3f
    var floorCalMinM = 0.8f
    var floorCalMaxM = 2.0f
    var floorFlatness = 0.3f
    var dropMinM = 0.7f
    var dropMaxM = 3.5f
    var dropRatio = 0.45f // floor >45% farther than a flat floor would be = a big drop
    var stepRatio = 0.08f // >8% farther + a sharp lip = a step or kerb
    var lipJump = 0.06f // how sudden the jump must be between neighbouring rows
    var dropWidthFrac = 0.6f // share of the walking corridor that must drop
    var dropTrackTolM = 0.6f // frame-to-frame distance must follow my walking within this
    var obstacleRatio = 0.25f
    var hazardRows = 3
    var depthHits = 4 // depth frames in a row before a drop/overhang is announced
    var scaleLockFrames = 5 // consistent floor frames before the depth ruler is trusted
    var scaleTolerance = 1.6f // a "floor" whose scale differs more than this is a table top, not the floor
    var hazardPitchMinDeg = -5f // depth hazards only with a chest-worn camera looking ahead / slightly down
    var hazardPitchMaxDeg = 35f
    var staticRepeatMs = 15000L // same label, same direction: don't re-announce within this
    var headMinM = 1.2f
    var headMaxM = 2.1f
    var overheadMaxM = 2.0f
    var overheadGapM = 0.8f
}

@androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
class MainActivity : ComponentActivity() {
    private lateinit var preview: View // TextureView (Camera2 logical camera) or PreviewView (CameraX fallback)
    private var wideId: String? = null
    private var wide: WideCamera? = null
    private lateinit var hud: Hud
    private lateinit var root: FrameLayout
    private lateinit var gear: android.widget.Button
    private lateinit var settings: SettingsPanel
    private lateinit var feedback: Feedback
    private val analysisThread = Executors.newSingleThreadExecutor()
    private val detector by lazy { Detector(this) } // created on the analysis thread
    private val reader by lazy { Reader() } // analysis thread only
    private val tracker by lazy { Tracker(EgoModel.loadOrNull { assets.open("ego_model.json").bufferedReader().readText() }) }
    private lateinit var ego: EgoMotion
    private lateinit var egoLog: EgoLog // analysis thread only
    private val policy = AlertPolicy() // analysis thread only
    private val depth by lazy { DepthModel(this) } // analysis thread only
    private val depthAnalyzer = DepthAnalyzer()
    private var hazards = Hazards()
    private var depthMs = 0L
    private var depthFrames = 0
    private var said = "" // last sentence spoken, shown as the caption
    private var saidLevel: Buzz? = null
    private val activity = ActivityDetector() // analysis thread only
    private val lens = LensPolicy() // analysis thread only
    private var minZoom = 1f
    private val memory = HazardMemory()
    @Volatile private var latestTracks = emptyList<Track>()
    @Volatile private var latestHazards = Hazards()
    private lateinit var voice: VoiceInput
    private lateinit var gemma: Gemma
    private lateinit var sounds: SoundWatch
    private lateinit var emergency: Emergency
    @Volatile private var finder: Finder? = null
    private val keysDown = HashSet<Int>()
    private var chord = false
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val emergencyHold = Runnable { startEmergency() }
    @Volatile private var latestFrame: Bitmap? = null
    private var camera: Camera? = null
    private var torchOn = false
    private var frameCount = 0
    private val heat = ThermalGovernor() // analysis thread only
    private var headroom = Float.NaN
    private var batteryC = Float.NaN
    private var lastHeatPollMs = 0L
    private var lastFrameStartMs = 0L
    private var batterySaid = false
    private var lastBatteryCheckMs = 0L
    private val tiny = IntArray(64 * 48)
    private var reading = false // analysis thread only
    private var lastFrameMs = 0L
    private var lastStatusLogMs = 0L // per-frame logging got the app's logs throttled by the OS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Prefs.load(this)
        wideId = findWideCameraId(this)
        preview = if (wideId != null) android.view.TextureView(this) else PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE // TextureView: lets us filter the image
        }
        hud = Hud(this)
        settings = SettingsPanel(this, say = { feedback.say(it) }, onChange = ::applyDisplay, onLesson = { feedback.lesson() })
        val gear = android.widget.Button(this).apply {
            text = "Settings"
            isAllCaps = false
            contentDescription = "Settings"
            setOnClickListener { if (settings.open) settings.hide() else settings.show() }
        }
        root = FrameLayout(this).apply {
            if (preview is android.view.TextureView) {
                val w = resources.displayMetrics.widthPixels
                addView(preview, FrameLayout.LayoutParams(w, w * 4 / 3, android.view.Gravity.CENTER))
            } else addView(preview)
            addView(hud)
            addView(gear, FrameLayout.LayoutParams(-2, -2, android.view.Gravity.TOP or android.view.Gravity.START).apply {
                topMargin = (150 * resources.displayMetrics.density).toInt(); leftMargin = (16 * resources.displayMetrics.density).toInt()
            })
            // Above the navigation bar; the panel has its own Done, so the Settings button hides while it is open.
            addView(settings.view, FrameLayout.LayoutParams(-1, -2, android.view.Gravity.BOTTOM).apply {
                bottomMargin = (56 * resources.displayMetrics.density).toInt()
            })
        }
        this.gear = gear
        settings.onOpenChange = { open -> gear.visibility = if (open) android.view.View.GONE else android.view.View.VISIBLE }
        setContentView(root)
        feedback = Feedback(this)
        applyDisplay()
        if (!Prefs.wizardDone) hud.post { settings.show(wizard = true) }
        ego = EgoMotion(this)
        egoLog = EgoLog(this)
        // Record mode for ego-motion training data (team only; blind users never need it).
        hud.setOnLongClickListener {
            analysisThread.execute {
                if (egoLog.recording) { egoLog.stop(); feedback.say("Recording saved.") }
                else { egoLog.start(); feedback.say("Recording. Volume up marks approaching.") }
            }
            true
        }

        if (checkSelfPermission(CAMERA) == PERMISSION_GRANTED) startCamera()
        else registerForActivityResult(RequestMultiplePermissions()) { if (it[CAMERA] == true) startCamera() }
            .launch(arrayOf(CAMERA, ACTIVITY_RECOGNITION, RECORD_AUDIO))
        gemma = Gemma(this).also { it.load() }
        emergency = Emergency(this)
        sounds = SoundWatch(this, paused = { voice.listening || emergency.active }) { d -> runOnUiThread { heard(d) } }
        voice = VoiceInput(this, onReady = { feedback.readyCue() }, onText = ::answer) { why ->
            Log.i(TAG, "voice failed: $why")
            feedback.say(if (why == "no speech heard" || why == "no match") "I didn't catch that. Press volume up, wait for the buzz, then speak."
                         else "Voice problem: $why.")
        }
    }

    /** Palette, text size and camera filter changed: repaint everything (live preview in the settings). */
    private fun applyDisplay() {
        applyCameraView(preview)
        root.setBackgroundColor(if (Prefs.palette.highContrast) Prefs.palette.bg else android.graphics.Color.BLACK)
        val p = Prefs.palette
        gear.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f * Prefs.textScale)
        gear.setTextColor(if (p.highContrast) p.bg else 0xFF101820.toInt())
        gear.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(if (p.highContrast) p.fg else 0xFFFFB300.toInt()); cornerRadius = 40f
            setStroke(6, if (p.highContrast) p.bg else android.graphics.Color.WHITE)
        }
        hud.invalidate()
    }

    override fun onResume() {
        super.onResume()
        ego.start()
        if (wide != null && checkSelfPermission(CAMERA) == PERMISSION_GRANTED) wide?.start()
        if (checkSelfPermission(RECORD_AUDIO) == PERMISSION_GRANTED) sounds.start()
    }

    override fun onPause() { ego.stop(); sounds.stop(); wide?.stop(); super.onPause() }

    /**
     * Logical camera (main + ultra-wide in one session) when the phone has one; CameraX otherwise.
     * Lens changes are then just zoom ratio changes: no restart, no lost frames.
     */
    private fun startCamera() {
        val id = wideId
        val tv = preview as? android.view.TextureView
        if (id != null && tv != null) {
            wide = WideCamera(this, id, tv, analysisThread, onFrame = ::onWideFrame) { why ->
                Log.w(TAG, "lens: logical camera failed ($why)")
                runOnUiThread { feedback.say("Camera problem. Restart the app.") }
            }.also { minZoom = it.minZoom; if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) it.start() } // else onResume starts it
            return
        }
        startCameraX()
    }

    private fun onWideFrame(frame: Bitmap) {
        if (skipFrame()) return
        process(frame, SystemClock.elapsedRealtime())
    }

    private fun setZoom(z: Float) { wide?.setZoom(z) ?: camera?.cameraControl?.setZoomRatio(z) }
    private fun setTorch(on: Boolean) { wide?.setTorch(on) ?: camera?.cameraControl?.enableTorch(on) }

    private fun startCameraX() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            // Same 4:3 aspect for preview and analysis so overlay boxes line up.
            val fourThree = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            val previewUse = Preview.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(fourThree).build())
                .build()
                .also { it.setSurfaceProvider((preview as PreviewView).surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(fourThree)
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                        )
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { it.setAnalyzer(analysisThread, ::analyze) }
            future.get().run {
                unbindAll()
                // Among listed cameras, prefer one that can zoom below 1x.
                val selector = CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).addCameraFilter { infos ->
                    infos.sortedBy { (it.zoomState.value?.minZoomRatio ?: 1f) }.take(1)
                }.build()
                camera = bindToLifecycle(this@MainActivity, selector, previewUse, analysis)
                minZoom = camera?.cameraInfo?.zoomState?.value?.minZoomRatio ?: 1f
                Log.i(TAG, "lens: zoom range $minZoom..${camera?.cameraInfo?.zoomState?.value?.maxZoomRatio}")
            }
        }, mainExecutor)
    }

    // ponytail: volume-down starts READ; hold-to-switch and voice commands come in M5.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return super.onKeyDown(keyCode, event)
        if (event.repeatCount == 0) keysDown += keyCode
        if (keysDown.size == 2 && !chord) { chord = true; main.postDelayed(emergencyHold, Settings.emergencyHoldMs) }
        return true
    }

    /** Single presses act on release; holding both keys for 2 s is the emergency gesture. */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return super.onKeyUp(keyCode, event)
        keysDown -= keyCode
        main.removeCallbacks(emergencyHold)
        if (chord) { if (keysDown.isEmpty()) chord = false; return true }
        if (emergency.active) { emergency.stop(); feedback.say("Alarm stopped."); return true }
        singlePress(keyCode, event)
        return true
    }

    private fun startEmergency() {
        emergency.start()
        said = "EMERGENCY"
        val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val pct = b?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100) }
        main.postDelayed({ if (emergency.active) feedback.say("Emergency. I need help. Battery $pct percent. Press a volume key to stop the alarm.") }, 1500)
    }

    /** Horn, siren, bell, reversing, barking: vibration pattern plus two words. */
    private fun heard(d: Danger) {
        if (voice.listening || activity.current == Activity.VEHICLE) return // inside a vehicle, horns are constant
        feedback.play(listOf(Alert(d.spoken, Buzz.WARN, Tacton.SOUND, d.spoken)))
        said = "Heard: ${d.spoken}"
        saidLevel = Buzz.WARN
    }

    private fun singlePress(keyCode: Int, event: KeyEvent) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (egoLog.recording) analysisThread.execute { // label toggle while recording training data
                egoLog.label = 1 - egoLog.label
                feedback.say(if (egoLog.label == 1) "Approaching." else "Clear.")
            } else if (voice.available) { feedback.hush(); voice.press() } // ask; press again = done
            else feedback.say("Voice questions are not available on this phone.")
            return
        }
        analysisThread.execute {
            if (!reading) { reader.start(); reading = true; feedback.say("Reading.") }
        }
    }

    /** Headroom is rate-limited by the OS; battery temperature changes slowly. Poll every 2 s. */
    private fun pollHeat(now: Long) {
        if (now - lastHeatPollMs < 2000) return
        lastHeatPollMs = now
        headroom = getSystemService(PowerManager::class.java).getThermalHeadroom(10)
        val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        batteryC = b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1000)?.takeIf { it > -1000 }?.div(10f) ?: Float.NaN
    }

    /** Voice question -> deterministic answer. Safety questions never reach anything that could say yes. */
    private fun answer(alternatives: List<String>) {
        val (text, ask) = bestIntent(alternatives)
        Log.i(TAG, "asked: $alternatives -> $ask")
        val reply = when (ask) {
            Ask.SAFETY -> Answers.safety(memory.recent())
            Ask.EMERGENCY -> { startEmergency(); return }
            Ask.FIND -> {
                val (what, label) = findTarget(text)!!
                if (label != null) { finder = Finder(label, SystemClock.elapsedRealtime()); "Looking for the $what. Turn slowly." }
                else if (askGemma("Where is the $what? Answer with its clock direction and rough distance in metres, or say it is not visible.",
                        fallback = "I can't see a $what.")) return
                else "I can only find everyday objects like chairs, people, bottles or bags."
            }
            Ask.STOP -> { finder = null; "Stopped." }
            Ask.SIT -> { analysisThread.execute { activity.force(Activity.SITTING, SystemClock.elapsedRealtime()) }; Activity.SITTING.spoken }
            Ask.VEHICLE -> { analysisThread.execute { activity.force(Activity.VEHICLE, SystemClock.elapsedRealtime()) }; Activity.VEHICLE.spoken }
            Ask.WALK -> { analysisThread.execute { activity.force(Activity.WALKING, SystemClock.elapsedRealtime()) }; "Walking. Full guidance." }
            Ask.DESCRIBE -> {
                val facts = Answers.describe(latestTracks, latestHazards)
                if (askGemma(Gemma.describePrompt(if (facts.startsWith("I don't")) "" else facts), fallback = facts)) return
                facts
            }
            Ask.SIGN -> {
                if (askGemma(Gemma.READ_PROMPT, fallback = "I can't read it clearly.")) return
                analysisThread.execute { if (!reading) { reader.start(); reading = true } }
                "Reading. Hold it in front of the camera."
            }
            Ask.READ -> { analysisThread.execute { if (!reading) { reader.start(); reading = true } }; "Reading. Hold it in front of the camera." }
            Ask.SPEECH -> { Settings.hapticsFirst = false; "OK, I'll speak every alert." }
            Ask.HAPTIC -> { Settings.hapticsFirst = true; "OK, vibration first. I'll only speak for danger." }
            Ask.LEARN -> { feedback.lesson(); return }
            Ask.CHATTY -> { Settings.chatty = true; "OK, I'll tell you more." }
            Ask.QUIET -> { Settings.chatty = false; "OK, only important things." }
            Ask.HELP -> {
                if (text.isNotBlank() && askGemma(Gemma.questionPrompt(text), fallback = "I heard: $text. $HELP_TEXT")) return
                if (text.isBlank()) HELP_TEXT else "I heard: $text. $HELP_TEXT"
            }
        }
        feedback.answer(reply, strong = ask == Ask.SAFETY)
        said = reply
    }

    /**
     * Sends a question plus the current camera frame to local Gemma. Returns false if Gemma isn't ready.
     * The answer is spoken only if it contains no movement green-light; otherwise [fallback] is.
     */
    private fun askGemma(prompt: String, fallback: String): Boolean {
        if (!gemma.ready) { Log.i(TAG, "gemma not ready: ${gemma.status}"); return false }
        feedback.say("Looking.")
        said = "Gemma is looking…"
        gemma.ask(prompt, latestFrame) { reply ->
            val safe = reply?.takeIf { !SafetyGate.greenLight(it) } ?: fallback
            runOnUiThread { feedback.answer(safe); said = "Gemma: $safe" }
        }
        return true
    }

    /** What the sighted view shows: what the user heard, or felt when it was vibration only. */
    private fun caption(alerts: List<Alert>): String =
        if (!Settings.hapticsFirst) "Heard: " + alerts.joinToString(" ") { it.text }
        else alerts.joinToString("  ") { a -> a.short?.let { "Heard: $it" } ?: "Felt: ${a.text}" }

    /** Ultra-wide for close quarters, main lens for far awareness and reading (Lens.kt). */
    private fun chooseLens(now: Long, tracks: List<Track>) {
        if (minZoom >= 1f) return // no ultra-wide on this phone
        val trusted = tracks.filter { it.hits >= Settings.minHits && !it.metres.isNaN() }
        val change = lens.update(
            now, reading = reading, walking = activity.current == Activity.WALKING, finding = finder != null,
            nearestM = trusted.minOfOrNull { it.metres } ?: Float.NaN,
            edgeNear = trusted.any { it.edge && it.metres < Settings.wideNearM },
        ) ?: return
        val z = if (change) maxOf(minZoom, Settings.wideZoom) else 1f
        setZoom(z)
        Settings.zoom = z
        // Geometry changed: boxes jump and the depth ruler no longer fits. Start both fresh.
        tracker.reset()
        depthAnalyzer.relearn()
        Log.i(TAG, "lens: ${if (change) "ultra-wide" else "main"} ($z x)")
    }

    private fun checkBattery(now: Long) {
        if (batterySaid || now - lastBatteryCheckMs < 60_000) return
        lastBatteryCheckMs = now
        val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val pct = b.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) * 100 / b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (pct <= Settings.lowBatteryPct) { batterySaid = true; feedback.say("Battery $pct percent. Charge soon.", strong = true) }
    }

    /** Work less when it matters less: standing = every 2nd frame, vehicle = every 4th; heat can only slow further. */
    private fun skipFrame(): Boolean {
        val modeStride = when (activity.current) { Activity.WALKING -> 1; Activity.STILL -> 2; Activity.SITTING -> 3; Activity.VEHICLE -> 4 }
        return frameCount++ % maxOf(heat.tier.detectEvery, modeStride) != 0 && !reading
    }

    /** CameraX fallback path. */
    private fun analyze(image: ImageProxy) {
        if (skipFrame()) { image.close(); return }
        val t0 = SystemClock.elapsedRealtime()
        val frame = image.use {
            val bmp = it.toBitmap()
            val rot = it.imageInfo.rotationDegrees
            if (rot == 0) bmp
            else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
        }
        process(frame, t0)
    }

    private fun process(frame: Bitmap, t0: Long) {
        val t1 = SystemClock.elapsedRealtime()
        if (reading) {
            val (speech, done) = reader.step(frame)
            speech?.let { feedback.say(it, strong = done); said = it }
            if (done) reading = false
            Log.d(TAG, "READ ocr ${SystemClock.elapsedRealtime() - t1}ms  ${speech.orEmpty()}")
            val st = HudState(mode = "READ", said = said, imgW = frame.width, imgH = frame.height)
            hud.post { hud.show(st); hud.contentDescription = said }
            return
        }
        latestFrame = frame
        val dets = detector.detect(frame, Settings.minScore)
        val t2 = SystemClock.elapsedRealtime()
        val motion = ego.snapshot()
        val tracks = tracker.update(dets, t2, motion)
        if (egoLog.recording) tracks.forEach { egoLog.row(t2, it) }

        // Edge cases: is the image usable? Dark -> torch, then honesty.
        Bitmap.createScaledBitmap(frame, 64, 48, false).getPixels(tiny, 0, 64, 0, 0, 64, 48)
        val (luma, sharp) = frameStats(tiny, 64, 48)
        val (pitch, roll) = ego.gravity.let { tiltDegrees(it[0], it[1], it[2]) }
        val health = assess(luma, sharp, pitch, roll)

        // Depth on the NPU every Nth frame: drop-offs, head height, unnamed obstacles, and metres per object.
        activity.update(t2, ego.lastStepMs, ego.vibration)?.let { feedback.say(it.spoken); said = it.spoken }
        val depthOn = activity.current != Activity.VEHICLE && activity.current != Activity.SITTING // bus lurches fake drop-offs
        if (!depthOn) hazards = Hazards()
        if (depthOn && depthFrames++ % maxOf(Settings.depthEvery, heat.tier.depthEvery) == 0 && health == Health.OK) {
            val d0 = SystemClock.elapsedRealtime()
            // Drop-offs only matter while walking; at a desk the table top would be mistaken for the floor.
            val walking = activity.current == Activity.WALKING
            hazards = withoutFurnitureFloor(depthAnalyzer.analyze(depth.run(frame), pitch, walking, t2, motion.speed), tracks)
            depthMs = SystemClock.elapsedRealtime() - d0
        }
        tracks.forEach { it.depthM = depthAnalyzer.metresIn(it.box) }
        if (health == Health.DARK && !torchOn) { torchOn = true; setTorch(true) }
        else if (torchOn && luma > Settings.torchOffLuma) { torchOn = false; setTorch(false) }
        chooseLens(t2, tracks)
        memory.record(t2, hazards, tracks, health)
        latestTracks = tracks
        latestHazards = hazards
        finder?.let { f ->
            f.update(t2, tracks)?.let { if (!voice.listening) { feedback.say(it); said = "Finding: $it" } }
            if (f.done) finder = null
        }
        policy.decide(tracks, health, t2, hazards, activity.current).takeIf { it.isNotEmpty() && !voice.listening && finder == null }?.let { feedback.play(it); said = caption(it); saidLevel = it.maxOf { a -> a.buzz } }

        // Parking-sensor ticks for the nearest thing in my path (tracks or an unnamed depth obstacle).
        if (activity.current == Activity.WALKING && !policy.blind && !voice.listening) { // ticks only while walking
            val inPathM = tracks.filter { it.hits >= Settings.minHits && !it.metres.isNaN() && inPath(it) && (it.sure || it.metres < Settings.veryCloseM) }
                .minOfOrNull { it.metres }
            feedback.haptics.proximity(listOfNotNull(inPathM, hazards.floorObstacleAtM).minOrNull() ?: Float.NaN)
        }
        checkBattery(t2)
        pollHeat(t2)
        heat.update(t2, getSystemService(PowerManager::class.java).currentThermalStatus, headroom, batteryC,
            SystemClock.elapsedRealtime() - t0)?.let { feedback.say(it.spoken, strong = it > HeatTier.WARM); said = it.spoken }

        val fps = if (lastFrameMs == 0L) 0 else 1000 / (t2 - lastFrameMs).coerceAtLeast(1)
        lastFrameMs = t2
        val rec = if (egoLog.recording) "REC${if (egoLog.label == 1) " +" else ""}" else ""
        if (t2 - lastStatusLogMs > 5000) { lastStatusLogMs = t2; Log.i(TAG, "${detector.backend} $fps fps det ${t2 - t1}ms depth ${depthMs}ms  ${tracks.joinToString { "${it.label}#${it.id} %.1fm${if (it.approaching) "!" else ""}".format(it.metres) }}") }
        val st = HudState(
            mode = activity.current.name, heat = heat.tier, lens = Settings.zoom, backend = detector.backend, depthBackend = depth.backend, fps = fps.toInt(), detMs = t2 - t1, depthMs = depthMs,
            level = saidLevel, health = health, rec = rec, tracks = tracks, hazards = hazards, said = said,
            depth = depthAnalyzer.latest(), imgW = frame.width, imgH = frame.height,
        )
        hud.post { hud.show(st); hud.contentDescription = said }
    }
}

/** Speech + vibration output. What to say is decided by AlertPolicy. */
class Feedback(ctx: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(ctx, this)
    val haptics = Haptics(ctx)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val vibrator = ctx.getSystemService(VibratorManager::class.java).defaultVibrator
    private val side = VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE)
    private val ahead = VibrationEffect.createWaveform(longArrayOf(0, 80, 80, 80), -1)
    private val result = VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE)
    private val approach = VibrationEffect.createWaveform(longArrayOf(0, 60, 40, 60, 40, 60, 40, 200), -1)

    /** True while an answer (Gemma, "what's ahead", "is it safe") is being spoken: routine alerts must not cut it off. */
    @Volatile var answering = false
        private set

    init {
        tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit
            override fun onDone(id: String?) { if (id == ANSWER) answering = false }
            @Deprecated("") override fun onError(id: String?) { if (id == ANSWER) answering = false }
            override fun onStop(id: String?, interrupted: Boolean) { if (id == ANSWER) answering = false }
        })
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts.language = Locale.ENGLISH
    }

    /** Speaks an answer to the user's question in full. Only danger (see [play]) may interrupt it. */
    fun answer(text: String, strong: Boolean = false) {
        answering = true
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, ANSWER)
        if (strong) vibrator.vibrate(result)
    }

    fun buzz() = vibrator.vibrate(side)

    /** Stop talking before listening: the recognizer must not hear us. */
    fun hush() { answering = false; tts.stop() }

    /** The microphone is open now: a crisp double tap (vibration, so it doesn't pollute the audio). */
    fun readyCue() = vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 40, 60, 40), intArrayOf(0, 255, 0, 255), -1))

    fun say(text: String, strong: Boolean = false) {
        // Never cut off an answer: queue behind it.
        tts.speak(text, if (answering) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH, null, text)
        if (strong) vibrator.vibrate(result)
    }

    /** Plays each pattern with its meaning: speak, pause, vibrate. About 20 s. */
    fun lesson() {
        LESSON.forEachIndexed { i, (words, tacton) ->
            main.postDelayed({ tts.speak(words, TextToSpeech.QUEUE_FLUSH, null, "lesson$i") }, i * 4500L)
            main.postDelayed({ if (tacton == Tacton.TICK) repeat(4) { k -> main.postDelayed({ haptics.play(Tacton.TICK) }, k * 350L) } else haptics.play(tacton) }, i * 4500L + 2800)
        }
    }

    /** Haptics-first: the most urgent pattern, plus only the short words that must be heard. */
    fun play(alerts: List<Alert>) {
        if (answering) {
            // The user is listening to an answer: feel routine alerts, hear only danger (it interrupts).
            alerts.firstNotNullOfOrNull { it.tacton }?.let(haptics::play)
            val danger = alerts.filter { it.buzz == Buzz.WARN }.map { it.short ?: it.text }
            if (danger.isNotEmpty()) { answering = false; tts.speak(danger.joinToString(" "), TextToSpeech.QUEUE_FLUSH, null, "danger") }
            return
        }
        if (Settings.hapticsFirst) {
            alerts.firstNotNullOfOrNull { it.tacton }?.let(haptics::play)
            val words = alerts.mapNotNull { it.short }
            if (words.isNotEmpty()) tts.speak(words.joinToString(" "), TextToSpeech.QUEUE_FLUSH, null, "short")
            return
        }
        speakAll(alerts)
    }

    private companion object {
        const val ANSWER = "answer"
    }

    /** Speech mode: full sentences, most urgent first; the strongest buzz of the batch. */
    private fun speakAll(alerts: List<Alert>) {
        alerts.forEachIndexed { i, a -> tts.speak(a.text, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, a.text) }
        vibrator.vibrate(
            when (alerts.maxOf { it.buzz.ordinal }) {
                Buzz.WARN.ordinal -> result
                Buzz.APPROACH.ordinal -> approach
                Buzz.AHEAD.ordinal -> ahead
                else -> side
            }
        )
    }
}
