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
    var minScore = 0.5f
    var speechCooldownMs = 2000L
    var readTimeoutMs = 12000L
    var minTextArea = 0.04f // text must cover this fraction of the frame, else "Move closer"
    var coachCooldownMs = 2500L
    var moneyTotalResetMs = 60000L // a new note after this gap starts a new total

    // Ego-motion (docs/ego-motion.md). Calibration knobs: measure FOV and stride on the real phone/user.
    var hfovDeg = 52f // portrait width of the analysis frame
    var vfovDeg = 67f // portrait height
    val hfovRad get() = Math.toRadians(hfovDeg.toDouble()).toFloat()
    val vfovRad get() = Math.toRadians(vfovDeg.toDouble()).toFloat()
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
    var hazardMemoryMs = 1500L // a hazard seen this recently is still reported when asked "is it safe?"

    // Confidence (Tracker.kt): unsure objects are not announced unless approaching or touching.
    var sureHits = 5
    var sureScore = 0.5f
    var agreeRatio = 1.6f // depth vs size distance may differ by up to 60%
    var movingMps = 0.6f // below this, box jitter, not motion

    // Quiet by default: only safety-relevant speech (Alerts.kt). chatty = also announce far/new objects.
    var hapticsFirst = true // vibration carries routine alerts; speech only for "Stop. Drop." etc. (docs/haptics.md)
    var pulseMaxM = 2.5f // proximity ticks start when something in the path is this close
    var pulseProgressM = 0.3f // the gap must shrink by this much to count as "getting closer"
    var pulseStaleMs = 4000L // no progress for this long -> stop ticking (except at touching range)
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
    var dropRatio = 0.3f // floor >30% farther than a flat floor would be = it drops away
    var obstacleRatio = 0.25f
    var hazardRows = 2
    var depthHits = 3 // depth frames in a row before a drop/overhang is announced
    var headMinM = 1.2f
    var headMaxM = 2.1f
    var overheadMaxM = 2.0f
    var overheadGapM = 0.8f
}

class MainActivity : ComponentActivity() {
    private lateinit var preview: PreviewView
    private lateinit var hud: Hud
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
    private val memory = HazardMemory()
    @Volatile private var latestTracks = emptyList<Track>()
    @Volatile private var latestHazards = Hazards()
    private lateinit var voice: VoiceInput
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
        hud = Hud(this)
        setContentView(FrameLayout(this).apply { addView(preview); addView(hud) })
        feedback = Feedback(this)
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
        voice = VoiceInput(this, ::answer) { feedback.say("Sorry, I didn't catch that.") }
    }

    override fun onResume() { super.onResume(); ego.start() }

    override fun onPause() { ego.stop(); super.onPause() }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            // Same 4:3 aspect for preview and analysis so overlay boxes line up.
            val fourThree = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            val previewUse = Preview.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(fourThree).build())
                .build()
                .also { it.setSurfaceProvider(preview.surfaceProvider) }
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
                camera = bindToLifecycle(this@MainActivity, CameraSelector.DEFAULT_BACK_CAMERA, previewUse, analysis)
            }
        }, mainExecutor)
    }

    // ponytail: volume-down starts READ; hold-to-switch and voice commands come in M5.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (egoLog.recording) analysisThread.execute { // label toggle while recording training data
                egoLog.label = 1 - egoLog.label
                feedback.say(if (egoLog.label == 1) "Approaching." else "Clear.")
            } else if (voice.available) { feedback.buzz(); voice.listen() } // ask a question
            else feedback.say("Voice questions are not available on this phone.")
            return true
        }
        if (keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return super.onKeyDown(keyCode, event)
        analysisThread.execute {
            if (!reading) { reader.start(); reading = true; feedback.say("Reading.") }
        }
        return true
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
    private fun answer(text: String) {
        Log.i(TAG, "asked: $text")
        val reply = when (intentOf(text)) {
            Ask.SAFETY -> Answers.safety(memory.recent())
            Ask.DESCRIBE -> Answers.describe(latestTracks, latestHazards)
            Ask.READ -> { analysisThread.execute { if (!reading) { reader.start(); reading = true } }; "Reading. Hold it in front of the camera." }
            Ask.SPEECH -> { Settings.hapticsFirst = false; "OK, I'll speak every alert." }
            Ask.HAPTIC -> { Settings.hapticsFirst = true; "OK, vibration first. I'll only speak for danger." }
            Ask.LEARN -> { feedback.lesson(); return }
            Ask.CHATTY -> { Settings.chatty = true; "OK, I'll tell you more." }
            Ask.QUIET -> { Settings.chatty = false; "OK, only important things." }
            Ask.HELP -> HELP_TEXT
        }
        feedback.say(reply, strong = intentOf(text) == Ask.SAFETY)
        said = reply
    }

    /** What the sighted view shows: what the user heard, or felt when it was vibration only. */
    private fun caption(alerts: List<Alert>): String =
        if (!Settings.hapticsFirst) "Heard: " + alerts.joinToString(" ") { it.text }
        else alerts.joinToString("  ") { a -> a.short?.let { "Heard: $it" } ?: "Felt: ${a.text}" }

    private fun checkBattery(now: Long) {
        if (batterySaid || now - lastBatteryCheckMs < 60_000) return
        lastBatteryCheckMs = now
        val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val pct = b.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) * 100 / b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (pct <= Settings.lowBatteryPct) { batterySaid = true; feedback.say("Battery $pct percent. Charge soon.", strong = true) }
    }

    private fun analyze(image: ImageProxy) {
        // Work less when it matters less: standing = every 2nd frame, vehicle = every 4th; heat can only slow further.
        val modeStride = when (activity.current) { Activity.WALKING -> 1; Activity.STILL -> 2; Activity.VEHICLE -> 4 }
        if (frameCount++ % maxOf(heat.tier.detectEvery, modeStride) != 0 && !reading) { image.close(); return }
        val t0 = SystemClock.elapsedRealtime()
        val frame = image.use {
            val bmp = it.toBitmap()
            val rot = it.imageInfo.rotationDegrees
            if (rot == 0) bmp
            else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
        }
        val t1 = SystemClock.elapsedRealtime()
        if (reading) {
            val (speech, done) = reader.step(frame)
            speech?.let { feedback.say(it, strong = done); said = it }
            if (done) reading = false
            Log.d(TAG, "READ ocr ${SystemClock.elapsedRealtime() - t1}ms  ${speech.orEmpty()}")
            val st = HudState(mode = "READ", said = said, imgW = frame.width, imgH = frame.height)
            hud.post { hud.show(st) }
            return
        }
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
        val depthOn = activity.current != Activity.VEHICLE // bus lurches fake drop-offs
        if (!depthOn) hazards = Hazards()
        if (depthOn && depthFrames++ % maxOf(Settings.depthEvery, heat.tier.depthEvery) == 0 && health == Health.OK) {
            val d0 = SystemClock.elapsedRealtime()
            hazards = depthAnalyzer.analyze(depth.run(frame), pitch)
            depthMs = SystemClock.elapsedRealtime() - d0
        }
        tracks.forEach { it.depthM = depthAnalyzer.metresIn(it.box) }
        if (health == Health.DARK && !torchOn) { torchOn = true; camera?.cameraControl?.enableTorch(true) }
        else if (torchOn && luma > Settings.torchOffLuma) { torchOn = false; camera?.cameraControl?.enableTorch(false) }
        memory.record(t2, hazards, tracks, health)
        latestTracks = tracks
        latestHazards = hazards
        policy.decide(tracks, health, t2, hazards, activity.current).takeIf { it.isNotEmpty() }?.let { feedback.play(it); said = caption(it); saidLevel = it.maxOf { a -> a.buzz } }

        // Parking-sensor ticks for the nearest thing in my path (tracks or an unnamed depth obstacle).
        if (activity.current == Activity.WALKING && !policy.blind) { // ticks only while walking
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
        Log.d(TAG, "${detector.backend} $fps fps det ${t2 - t1}ms depth ${depthMs}ms  ${tracks.joinToString { "${it.label}#${it.id} %.1fm${if (it.approaching) "!" else ""}".format(it.metres) }}")
        val st = HudState(
            mode = activity.current.name, heat = heat.tier, backend = detector.backend, depthBackend = depth.backend, fps = fps.toInt(), detMs = t2 - t1, depthMs = depthMs,
            level = saidLevel, health = health, rec = rec, tracks = tracks, hazards = hazards, said = said,
            depth = depthAnalyzer.latest(), imgW = frame.width, imgH = frame.height,
        )
        hud.post { hud.show(st) }
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

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts.language = Locale.ENGLISH
    }

    fun buzz() = vibrator.vibrate(side)

    fun say(text: String, strong: Boolean = false) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, text)
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
        if (Settings.hapticsFirst) {
            alerts.firstNotNullOfOrNull { it.tacton }?.let(haptics::play)
            val words = alerts.mapNotNull { it.short }
            if (words.isNotEmpty()) tts.speak(words.joinToString(" "), TextToSpeech.QUEUE_FLUSH, null, "short")
            return
        }
        speakAll(alerts)
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
