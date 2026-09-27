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
    var autoLens = false // switching lenses reset the depth ruler and tracking every few seconds: off, main lens only
    var maxPriorM = 10f   // size-based distance beyond this is noise, not a measurement
    var maxDepthM = 10f   // depth-ruler distance beyond this is extrapolation (= the moving-object alert range)
    var wideNearM = 2.0f // something this close (or half out of view) -> ultra-wide
    var lensClearMs = 1000L // nothing close for this long -> main lens, to see far
    var lensDwellMs = 1000L // minimum time between switches
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
    var torchOnMs = 1000L       // dark this long before the torch comes on (one dark frame is not enough)
    var torchProbeMs = 8000L    // while on, look at the room's own light this often
    var torchSettleMs = 700L    // torch off this long for the look (exposure settles)
    var torchAmbientLuma = 55f  // the room alone is at least this bright: torch stays off
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
    // Qwen3-VL-2B via llama.cpp: preferred when its files are in files/qwen/, else Gemma.
    var useQwen = true
    var qwenModelFile = "Qwen3VL-2B-Instruct-Q4_K_M.gguf"
    var qwenMmprojFile = "mmproj-Qwen3VL-2B-Instruct-Q8_0.gguf"
    var qwenPort = 8089
    var qwenDevice = "HTP0" // Hexagon NPU: image 0.25 s, answer ~2 s (GPU 14-17 s, CPU 33 s just to encode the image)
    var qwenImageTokens = 256 // cap; the full-resolution photo otherwise becomes thousands of tokens
    var qwenMaxTokens = 48 // short answers; also caps worst-case latency
    // Cloud answers when online (Cloud.kt)
    // Free OpenRouter vision models, tried in order (they are often busy). Not "openrouter/free": its router
    // sent images to a safety classifier that answered "User Safety: safe".
    var cloudModels = listOf("google/gemma-4-31b-it:free", "qwen/qwen3.8-27b:free", "google/gemma-4-26b-a4b-it:free")
    var cloudTimeoutMs = 8000L   // slower than this: answered on the phone instead
    var cloudBackoffMs = 60_000L
    var qwenContext = 1024
    var gemmaImagePx = 1024 // Gemma sees the full preview, not the 640x480 analysis frame
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
    var answerGapMs = 3000L // after an answer (Gemma, "what's ahead"): no routine speech for this long, vibration only

    // Depth (Depth.kt). cameraHeightM is THE calibration knob: measure lens height on the wearer.
    var depthEvery = 1 // depth on every analysed frame (30 ms on the NPU); every 2nd left standing users with stale depth
    var fpsWalking = 10
    var fpsStill = 5
    var fpsSitting = 3
    var fpsVehicle = 2
    var cameraHeightM = 1.3f
    // Calibration (Calibration.kt)
    var calPitchMinDeg = 8f     // floor 0.8-2 m ahead must be in view for the ruler to learn
    var calPitchMaxDeg = 32f
    var calSteadyMs = 2000L
    var calCoachMs = 3000L
    // Fall detection (Fall.kt)
    var fallFreeG = 0.45f        // below this = falling (weightless)
    var fallFreeMs = 40L         // for at least this long before tracking starts
    var fallMinDropM = 0.5f      // only falls of 50 cm or more (a jolt or a short drop is not a fall)
    var fallImpactG = 2.3f       // then a hit above this
    var fallImpactWindowMs = 1200L
    var fallSettleMs = 800L      // ignore the bounce right after the hit
    var fallStillMs = 1500L      // then lying still for this long
    var fallStillG = 0.25f
    var fallTurnDeg = 45f        // and the phone ended up turned this much
    var fallCancelMs = 7000L
    var dropHapticMs = 5000 // a confirmed drop-off vibrates this long
    var heatThrottle = false // heat is only shown (header, Diagnostics); set true to slow work down when hot
    var quickLaunchMs = 1500L // volume up x3 within this opens Nadaka from anywhere (QuickLaunch.kt)
    // Walk straight (Straight.kt)
    var veerDeg = 10f
    var veerHoldMs = 1000L
    var veerRepeatMs = 4000L
    var veerTurnDeg = 60f
    var veerTurnMs = 2000L
    var veerMaxMs = 90_000L
    // Bus route numbers (Bus.kt)
    var busTryMs = 700L
    var busMinBoxH = 0.12f  // bus must fill this much of the frame height (close enough to read)
    var busVotes = 2
    var busMaxTries = 6
    // Finding things YOLO has no class for (doors, exits, stairs, lifts...) with Qwen
    var sceneFindMs = 2500L
    var sceneFindMaxMs = 30_000L
    var stairsRepeatMs = 7000L     // "press a volume key if you're OK" window before the siren
    var calStepTimeoutMs = 25_000L
    var calWalkTimeoutMs = 30_000L  // per walk, counted from its first volume-down press
    var calIdleTimeoutMs = 120_000L // waiting for a press before giving up
    var calStepLatencyMs = 700L     // the step sensor reports each step slightly late
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
    var hazardPitchMaxDeg = 55f // people tilt the phone well down to look at stairs (60 = pointing at the desk)
    var staticRepeatMs = 15000L // same label, same direction: don't re-announce within this
    var waistMinM = 0.45f // waist-height obstacles (tables, counters)
    var waistMaxM = 1.2f
    var headMinM = 1.2f
    var headMaxM = 2.1f
    var overheadMaxM = 2.0f
    var overheadGapM = 0.8f
}

@androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
class MainActivity : ComponentActivity() {
    companion object {
        /** On screen now: quick launch then leaves volume down to the app. */
        @Volatile var visible = false
    }

    private lateinit var preview: View // TextureView (Camera2 logical camera) or PreviewView (CameraX fallback)
    private var wideId: String? = null
    private var wide: WideCamera? = null
    private lateinit var screen: app.nadaka.ui.LiveScreen
    private lateinit var feedback: Feedback
    private var motionMissing = false
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
    private var depthReady = false // the depth model has produced at least one map

    // Layer 3 drop-off. The iQOO has no barometer: the pipeline then runs on vision + depth alone.
    private val sensors by lazy { getSystemService(android.hardware.SensorManager::class.java) }
    private val pressure by lazy { sensors.getDefaultSensor(android.hardware.Sensor.TYPE_PRESSURE) }
    private val drop by lazy {
        val debuggable = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        app.nadaka.drop.DropPipeline(pressure != null, if (debuggable) java.io.File(getExternalFilesDir(null), "drop_log.csv") else null)
    }
    private var dropOut: app.nadaka.drop.DropOutput? = null
    private var dropSaidMs = -1_000_000L
    private var stairsSaidMs = -1_000_000L
    private val baroListener = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(e: android.hardware.SensorEvent) = drop.barometer.update(e.values[0])
        override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) = Unit
    }
    private var depthFrames = 0
    @Volatile private var calibration: Calibration? = null
    @Volatile private var straight: StraightLine? = null
    @Volatile private var sceneFinder: SceneFinder? = null
    private val translator = AnswerTranslator()
    private val bus = BusReader() // analysis thread only
    private val busOcr by lazy { com.google.mlkit.vision.text.TextRecognition.getClient(com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val sms by lazy { EmergencySms(this) }
    private val pickContact = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = r.data?.data ?: return@registerForActivityResult
        contentResolver.query(uri, arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val contact = Contact(c.getString(0) ?: "Contact", c.getString(1) ?: return@use)
                Prefs.contacts = (Prefs.contacts + contact).distinctBy { it.number }.take(2)
                Prefs.save(this)
                feedback.say("${contact.name} added as an emergency contact.")
                screen.restyle()
            }
        }
        // Texting and location are only asked for once someone is actually set up to receive them.
        smsPermissions.launch(arrayOf(android.Manifest.permission.SEND_SMS, android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    private val smsPermissions = registerForActivityResult(RequestMultiplePermissions()) { }
    private var calRulerFresh = false // the ruler was reset at the first walk press
    private var said = "" // last sentence spoken, shown as the caption
    private var saidLevel: Buzz? = null
    private val activity = ActivityDetector() // analysis thread only
    private val lens = LensPolicy() // analysis thread only
    private var minZoom = 1f
    private val memory = HazardMemory()
    @Volatile private var latestTracks = emptyList<Track>()
    @Volatile private var latestHazards = Hazards()
    private lateinit var voice: VoiceInput
    private lateinit var gemma: Vlm // HybridVlm: cloud when online (Auto), else Qwen3-VL / Gemma on the phone
    private lateinit var sounds: SoundWatch
    private lateinit var emergency: Emergency
    @Volatile private var finder: Finder? = null
    private val keysDown = HashSet<Int>()
    private var chord = false
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val emergencyHold = Runnable { startEmergency() }
    @Volatile private var latestFrame: Bitmap? = null
    private var camera: Camera? = null
    private val torch = TorchPolicy() // analysis thread only
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
        feedback = Feedback(this)
        screen = app.nadaka.ui.LiveScreen(this, preview, app.nadaka.ui.Actions(
            say = { feedback.say(it) },
            testHaptic = { feedback.haptics.test() },
            testAudio = { feedback.test() },
            lesson = { feedback.lesson() },
            displayChanged = ::applyDisplay,
            recordToggle = ::toggleRecording,
            openBenchmark = { startActivity(Intent(this, BenchActivity::class.java)) },
            calibrate = ::startCalibration,
            addContact = { pickContact.launch(Intent(Intent.ACTION_PICK, android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI)) },
            removeContact = { i -> Prefs.contacts = Prefs.contacts.filterIndexed { k, _ -> k != i }; Prefs.save(this) },
            testSms = { feedback.say(sms.alert("", test = true)) },
            route = { (gemma as? HybridVlm)?.snapshot() },
            languageChanged = { translator.prepare(Prefs.speechLang) },
            openAccessibility = { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            quickLaunchOn = { quickLaunchEnabled() },
        ))
        if (Prefs.calibrated) depthAnalyzer.restore(Prefs.depthScale)
        setContentView(screen.root)
        applyDisplay()
        if (!Prefs.wizardDone) screen.root.post { screen.settings.show(wizard = true) }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (!screen.back()) finish() }
        })
        motionMissing = sensors.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER) == null
        ego = EgoMotion(this).also { it.onFall = { runOnUiThread(::fallDetected) } }
        egoLog = EgoLog(this)

        if (checkSelfPermission(CAMERA) == PERMISSION_GRANTED) startCamera()
        else registerForActivityResult(RequestMultiplePermissions()) {
            if (it[CAMERA] == true) startCamera() else screen.cameraError("Camera permission is off.")
        }
            .launch(arrayOf(CAMERA, ACTIVITY_RECOGNITION, RECORD_AUDIO))
        val local = Qwen(this).takeIf { Settings.useQwen && it.installed } ?: Gemma(this)
        gemma = HybridVlm(local, CloudVlm(), this) { heat.tier }.also { it.load() }
        emergency = Emergency(this)
        sounds = SoundWatch(this, paused = { voice.listening || emergency.active }) { d -> runOnUiThread { heard(d) } }
        feedback.listening = { voice.listening }
        voice = VoiceInput(this, onReady = { feedback.readyCue() }, onText = ::answer) { why ->
            Log.i(TAG, "voice failed: $why")
            calibration?.takeIf { it.step == Calibration.Step.HEIGHT }?.let { c ->
                val next = c.noAnswer(SystemClock.elapsedRealtime()); feedback.answer(next); said = next
                return@VoiceInput
            }
            feedback.say(if (why == "no speech heard" || why == "no match") "I didn't catch that. Press volume up, wait for the buzz, then speak."
                         else "Voice problem: $why.")
        }
    }

    /**
     * One button (Settings > Calibrate, TalkBack-labelled): voice-guided height, mounting and a ten-step walk.
     * Also reads this phone's real lens field of view. Results are saved (Prefs) and restored at launch.
     */
    private fun startCalibration() {
        lensFov()?.let { (h, v) -> Settings.hfovDeg = h; Settings.vfovDeg = v; Log.i(TAG, "calibration: lens FOV %.1f x %.1f deg".format(h, v)) }
        val c = Calibration(SystemClock.elapsedRealtime())
        calRulerFresh = false
        calibration = c
        feedback.answer(Calibration.HEIGHT_PROMPT); said = Calibration.HEIGHT_PROMPT
        // Open the mic by itself once the prompt has been read out: the only action is to speak.
        main.postDelayed({ if (calibration === c && c.step == Calibration.Step.HEIGHT) {
            if (voice.available && !voice.listening) { feedback.hush(); voice.press() } // buzz, then the user speaks
            else { val next = c.noAnswer(SystemClock.elapsedRealtime()); feedback.answer(next); said = next }
        } }, 9000)
    }

    /** Analysis thread: advance calibration by one frame; save when done. */
    private fun calibrationFrame(c: Calibration, now: Long, pitch: Float) {
        if (c.consumeRulerReset()) {
            calRulerFresh = true
            depthAnalyzer.relearn() // learn the ruler fresh, at this height and this mounting
        }
        if (c.walking) activity.force(Activity.WALKING, now)
        c.frame(now, pitch, ego.stepCount, calRulerFresh && !depthAnalyzer.scale.isNaN())?.let { runOnUiThread { feedback.answer(it) }; said = it }
        if (!c.finished) return
        if (c.step == Calibration.Step.DONE) {
            Prefs.calibrated = true
            Prefs.calibratedAtMs = System.currentTimeMillis()
            Prefs.bodyHeightM = c.heightM
            Prefs.cameraHeightM = Settings.cameraHeightM
            Prefs.depthScale = depthAnalyzer.scale
            Prefs.hfovDeg = Settings.hfovDeg; Prefs.vfovDeg = Settings.vfovDeg
            // Metres per *sensed* step: stride from height, corrected by how many steps the sensor misses or adds.
            val stride = if (c.heightM.isNaN()) Settings.strideM else Calibration.strideFor(c.heightM)
            if (!c.stepFactor.isNaN()) { Settings.strideM = stride * c.stepFactor; Prefs.strideM = Settings.strideM }
            Log.i(TAG, "calibration: step factor %.2f, %.2f s/step, stride %.2f m".format(c.stepFactor, c.secondsPerStep, Settings.strideM))
            Prefs.save(this)
            Log.i(TAG, "calibration: done, camera %.2f m, ruler %.3f".format(Settings.cameraHeightM, depthAnalyzer.scale))
        } else Log.i(TAG, "calibration: failed at the walk")
        calibration = null
    }

    /** Portrait field of view of the main back camera, from its focal length and sensor size. */
    private fun lensFov(): Pair<Float, Float>? = runCatching {
        val cm = getSystemService(android.hardware.camera2.CameraManager::class.java)
        val ch = cm.cameraIdList.map { cm.getCameraCharacteristics(it) }.first {
            it.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
        }
        val f = ch.get(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)!![0]
        val s = ch.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)!!
        fun deg(mm: Float) = Math.toDegrees(2 * kotlin.math.atan(mm / (2 * f)).toDouble()).toFloat()
        (deg(s.height) to deg(s.width)).takeIf { (h, v) -> h in 35f..85f && v in 45f..100f } // portrait: width uses sensor height
    }.getOrNull()

    /** Camera filter changed (the screen restyles itself). */
    private fun applyDisplay() = applyCameraView(preview)

    /** Record mode for ego-motion training data (team only; long-press the camera view). */
    private fun toggleRecording() = analysisThread.execute {
        if (egoLog.recording) { egoLog.stop(); feedback.say("Recording saved.") }
        else { egoLog.start(); feedback.say("Recording. Volume up marks approaching.") }
    }

    override fun onResume() {
        super.onResume()
        ego.start()
        visible = true
        screen.resumed()
        translator.prepare(Prefs.speechLang)
        pressure?.let { sensors.registerListener(baroListener, it, android.hardware.SensorManager.SENSOR_DELAY_NORMAL) }
        if (wide != null && checkSelfPermission(CAMERA) == PERMISSION_GRANTED) wide?.start()
        if (checkSelfPermission(RECORD_AUDIO) == PERMISSION_GRANTED) sounds.start()
    }

    override fun onDestroy() { ((gemma as? HybridVlm)?.local as? Qwen)?.stop(); super.onDestroy() }

    override fun onPause() {
        visible = false
        // The camera closes: torch off and forgotten, so it can't come back on by itself on return.
        analysisThread.execute { torch.reset() }; setTorch(false)
        ego.stop(); sensors.unregisterListener(baroListener); sounds.stop(); wide?.stop(); super.onPause() }

    /**
     * Logical camera (main + ultra-wide in one session) when the phone has one; CameraX otherwise.
     * Lens changes are then just zoom ratio changes: no restart, no lost frames.
     */
    private fun startCamera() {
        val id = wideId
        val tv = preview as? android.view.TextureView
        if (id != null && tv != null) {
            wide = WideCamera(this, id, tv, analysisThread, onFrame = ::onWideFrame, take = ::frameDue, onFail = { why ->
                Log.w(TAG, "lens: logical camera failed ($why)")
                runOnUiThread { feedback.say("Camera problem. Restart the app.") }
                screen.cameraError("The camera could not open.")
            }).also { minZoom = it.minZoom; if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) it.start() } // else onResume starts it
            return
        }
        startCameraX()
    }

    private fun onWideFrame(frame: Bitmap) {
        safely { process(frame, SystemClock.elapsedRealtime()) }
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
        if (fallPending) { cancelFall(); return true }
        if (emergency.active) { emergency.stop(); feedback.say("Alarm stopped."); return true }
        singlePress(keyCode, event)
        return true
    }

    /** Is the quick-launch accessibility service switched on (by the user, in Android settings)? */
    private fun quickLaunchEnabled(): Boolean {
        val on = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return on.split(':').any { it.equals("$packageName/${QuickLaunchService::class.java.name}", true) || it.equals("$packageName/.QuickLaunchService", true) }
    }

    /** Qwen looks for a door / exit / anything YOLO can't name while the user turns; two agreeing looks to announce. */
    private fun sceneFind(now: Long, frame: Bitmap) {
        val f = sceneFinder ?: return
        if (f.asking || !gemma.ready || voice.listening || now - f.lastAskMs < Settings.sceneFindMs) return
        f.asking = true; f.lastAskMs = now
        gemma.ask(f.prompt(), frame) { reply ->
            f.asking = false
            val (words, done) = f.answer(SystemClock.elapsedRealtime(), reply)
            if (done && sceneFinder === f) sceneFinder = null
            words?.let { w -> runOnUiThread { feedback.answer(w); said = w } }
        }
    }

    /**
     * Lift buttons or a room number, read from positions on-device (no model guessing); Qwen only if OCR finds
     * nothing. The sharp preview is grabbed here (UI thread), the reading happens on the analysis thread.
     */
    private fun readPanel(question: String) {
        val img = (preview as? android.view.TextureView)?.takeIf { it.isAvailable }?.bitmap ?: latestFrame ?: run { feedback.answer("I can't see yet."); return }
        feedback.say("Reading.")
        analysisThread.execute {
            val words = runCatching {
                com.google.android.gms.tasks.Tasks.await(busOcr.process(com.google.mlkit.vision.common.InputImage.fromBitmap(img, 0)))
                    .textBlocks.flatMap { b -> b.lines.flatMap { l -> l.elements } }
                    .mapNotNull { e -> e.boundingBox?.let { r -> Word(e.text, r.left, r.top, r.height()) } }
            }.getOrDefault(emptyList())
            val room = Panel.isRoomQuestion(question)
            val result = if (room) Panel.roomNumber(words) else Panel.liftButtons(words) ?: Panel.roomNumber(words)
            if (result != null) { runOnUiThread { speakAnswer(result) }; return@execute }
            val ask = if (room) "Read the room or door number on the sign. Answer only the number, or: no number." else
                "This is a lift panel or display. Read the floor numbers on the buttons from top to bottom, the floor shown on the display, and which button is lit. Be brief."
            if (gemma.ready) gemma.ask(ask, img) { a -> val w = a ?: "I couldn't read it. Move a little closer."; runOnUiThread { speakAnswer(w) } }
            else runOnUiThread { feedback.answer("I couldn't read it. Move a little closer.") }
        }
    }

    /** Walk straight: drift cues as vibration (one long = turn right, two short = turn left) plus a few words. */
    private fun walkStraight(now: Long) {
        val s = straight ?: return
        val v = s.update(now, ego.headingDeg) ?: return
        if (v == Veer.DONE) straight = null
        val tacton = when (v) { Veer.DRIFT_LEFT -> Tacton.VEER_LEFT; Veer.DRIFT_RIGHT -> Tacton.VEER_RIGHT; else -> null }
        val words = StraightLine.words(v)
        runOnUiThread { feedback.play(listOf(Alert(words, Buzz.AHEAD, tacton, words))) }
        said = words
    }

    /**
     * Bus route number: crop the board from the full-resolution preview, read it on-device (ML Kit), vote over
     * tries; if it won't read, ask Qwen once. Announced once per bus.
     */
    private fun readBus(now: Long, tracks: List<Track>) {
        val t = bus.due(now, tracks) ?: return
        val area = BusReader.boardArea(t)
        val img = BusReader.crop(previewFrame() ?: latestFrame ?: return, area) ?: return
        val text = runCatching { com.google.android.gms.tasks.Tasks.await(busOcr.process(com.google.mlkit.vision.common.InputImage.fromBitmap(img, 0))).text }.getOrDefault("")
        when (val r = bus.read(t.id, text)) {
            null -> return
            BusReader.GIVE_UP -> if (gemma.ready) gemma.ask("Read the route number on the front of this bus. Answer only the number, or none.", img) { a ->
                bus.fallback(a.orEmpty())?.let { w -> runOnUiThread { announceBus(w) } }
            }
            else -> runOnUiThread { announceBus(r) }
        }
    }

    private fun announceBus(words: String) {
        if (alarmOn) return
        feedback.play(listOf(Alert(words, Buzz.SIDE, null, words))); said = words
        Log.i(TAG, "bus: $words")
    }

    /** The full-resolution preview (UI thread only), fetched from the analysis thread; null if not quick. */
    private fun previewFrame(): Bitmap? {
        val tv = preview as? android.view.TextureView ?: return null
        val task = java.util.concurrent.FutureTask { if (tv.isAvailable) tv.bitmap else null }
        main.post(task)
        return runCatching { task.get(300, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrNull()
    }

    /** Fall: say so and wait 7 s for a volume key (no popup). No press: siren + "calling emergency contacts". */
    @Volatile private var fallPending = false
    /** Fall countdown or siren: the banner and the siren own the phone; routine alerts wait. */
    private val alarmOn get() = fallPending || emergency.active
    private val fallAlarm = Runnable {
        if (!fallPending) return@Runnable
        fallPending = false
        emergency.start()
        said = "EMERGENCY"
        feedback.urgent(sms.alert("I may have fallen and need help."))
        main.postDelayed({ if (emergency.active) feedback.urgent("I have fallen and need help.") }, 4000)
        Log.i(TAG, "fall: no response in ${Settings.fallCancelMs / 1000} s, siren on")
    }

    private fun fallDetected() {
        if (fallPending || emergency.active) return
        fallPending = true
        said = "FALL"
        feedback.urgent("Fall detected. If you are OK, press a volume key. Otherwise in 7 seconds I will sound an alarm and call your emergency contacts.")
        feedback.buzz()
        main.postDelayed(fallAlarm, Settings.fallCancelMs)
        main.postDelayed(fallWarn, Settings.fallCancelMs - 3000)
        Log.i(TAG, "fall: detected, waiting ${Settings.fallCancelMs} ms for a key")
    }

    private val fallWarn = Runnable { if (fallPending) { feedback.urgent("Alarm in 3 seconds."); feedback.buzz() } }

    private fun cancelFall() {
        fallPending = false
        main.removeCallbacks(fallAlarm); main.removeCallbacks(fallWarn)
        said = "OK"
        feedback.urgent("OK. Glad you're fine.")
        Log.i(TAG, "fall: cancelled by the user")
    }

    private fun startEmergency() {
        emergency.start()
        main.postDelayed({ if (emergency.active) feedback.urgent(sms.alert("I need help.")) }, 6000)
        said = "EMERGENCY"
        val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val pct = b?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100) }
        main.postDelayed({ if (emergency.active) feedback.say("Emergency. I need help. Battery $pct percent. Press a volume key to stop the alarm.") }, 1500)
    }

    /** Horn, siren, bell, reversing, barking: vibration pattern plus two words. */
    private fun heard(d: Danger) {
        if (voice.listening || alarmOn || activity.current == Activity.VEHICLE) return // inside a vehicle, horns are constant
        feedback.play(listOf(Alert(d.spoken, Buzz.WARN, Tacton.SOUND, d.spoken)))
        said = "Heard: ${d.spoken}"
        saidLevel = Buzz.WARN
    }

    private fun singlePress(keyCode: Int, event: KeyEvent) {
        // Calibration: volume down marks each step done (and starts / stops each walk).
        calibration?.let { c ->
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                feedback.buzz() // felt confirmation of the press
                val now = SystemClock.elapsedRealtime(); val steps = ego.stepCount
                analysisThread.execute { c.press(now, steps).takeIf { it.isNotEmpty() }?.let { runOnUiThread { feedback.answer(it) }; said = it } }
                return
            }
        }
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
        calibration?.takeIf { it.step == Calibration.Step.HEIGHT }?.let { c ->
            val heard = alternatives.firstOrNull { Calibration.parseHeightM(it) != null } ?: alternatives.firstOrNull().orEmpty()
            val next = c.heard(heard, SystemClock.elapsedRealtime())
            if (!c.heightM.isNaN()) Settings.cameraHeightM = Calibration.cameraHeightFor(c.heightM)
            feedback.answer(next); said = next
            return
        }
        val (text, ask) = bestIntent(alternatives)
        Log.i(TAG, "asked: $alternatives -> $ask")
        val reply = when (ask) {
            Ask.SAFETY -> Answers.safety(memory.recent())
            Ask.EMERGENCY -> { startEmergency(); return }
            Ask.FIND -> {
                val (what, label) = findTarget(text)!!
                if (label != null) { finder = Finder(label, SystemClock.elapsedRealtime()); "Looking for the $what. Turn slowly." }
                else if (gemma.ready) { sceneFinder = SceneFinder(what, SystemClock.elapsedRealtime()); "Looking for the $what. Turn slowly." }
                else "I can only find everyday objects like chairs, people, bottles or bags."
            }
            Ask.STOP -> { finder = null; straight = null; sceneFinder = null; "Stopped." }
            Ask.PANEL -> { readPanel(text); return }
            Ask.STRAIGHT -> { straight = StraightLine(ego.headingDeg, SystemClock.elapsedRealtime()); "Keeping you straight. Walk." }
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
    /**
     * An answer in the user's language: the app's own sentences use the fixed Hindi / Telugu templates (in Feedback);
     * anything else (a Qwen or cloud answer, a lift panel reading) is translated on the phone first.
     */
    private fun speakAnswer(text: String) {
        said = text
        if (Prefs.speechLang == SpeechLang.EN || Say.tr(text, Prefs.speechLang) != null) { feedback.answer(text); return }
        translator.translate(text) { t -> runOnUiThread { feedback.answer(t); said = t } }
    }

    private fun askGemma(prompt: String, fallback: String): Boolean {
        if (!gemma.ready) { Log.i(TAG, "gemma not ready: ${gemma.status}"); return false }
        feedback.say("Looking.")
        said = "Gemma is looking…"
        gemma.ask(prompt, gemmaImage()) { reply ->
            val safe = reply?.takeIf { !SafetyGate.greenLight(it) } ?: fallback // checked in English, before translating
            runOnUiThread { speakAnswer(safe) }
        }
        return true
    }

    /**
     * The sharpest picture we have without touching the camera: the full-resolution preview
     * (TextureView, ~1440x1920) instead of the 640x480 analysis frame. Falls back to the frame.
     */
    private fun gemmaImage(): Bitmap? {
        val tv = preview as? android.view.TextureView
        if (tv != null && tv.isAvailable && android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
            runCatching { tv.bitmap }.getOrNull()?.let { return it }
        return latestFrame
    }

    /** What the sighted view shows: what the user heard, or felt when it was vibration only. */
    private fun caption(alerts: List<Alert>): String =
        if (!Settings.hapticsFirst) "Heard: " + alerts.joinToString(" ") { it.text }
        else alerts.joinToString("  ") { a -> a.short?.let { "Heard: $it" } ?: "Felt: ${a.text}" }

    /** Ultra-wide for close quarters, main lens for far awareness and reading (Lens.kt). */
    private fun chooseLens(now: Long, tracks: List<Track>) {
        if (minZoom >= 1f || !Settings.autoLens) return // no ultra-wide on this phone, or auto-switching off
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

    /**
     * Work less when it matters less, by time (the camera runs at 30 fps; analysing all of it only makes heat):
     * walking 10 fps, standing 5, sitting 3, vehicle 2; a hot phone slows further. Reading gets full speed.
     * Asked before the frame is converted, so a skipped frame costs nothing.
     */
    @Volatile private var lastTakenMs = 0L
    private fun frameDue(): Boolean {
        val now = SystemClock.elapsedRealtime()
        val fps = if (reading) Settings.fpsWalking else when (activity.current) {
            Activity.WALKING -> Settings.fpsWalking; Activity.STILL -> Settings.fpsStill
            Activity.SITTING -> Settings.fpsSitting; Activity.VEHICLE -> Settings.fpsVehicle
        }
        val slow = if (Settings.heatThrottle) heat.tier.detectEvery else 1
        if (now - lastTakenMs < 1000L * slow / fps) return false
        lastTakenMs = now
        return true
    }

    /** CameraX fallback path. */
    private fun analyze(image: ImageProxy) {
        if (!frameDue()) { image.close(); return }
        val t0 = SystemClock.elapsedRealtime()
        val frame = image.use {
            val bmp = it.toBitmap()
            val rot = it.imageInfo.rotationDegrees
            if (rot == 0) bmp
            else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
        }
        safely { process(frame, t0) }
    }

    /** A crash in one frame must not silently stop safety: say so on screen and keep going. */
    private fun safely(run: () -> Unit) = try { run() } catch (e: Exception) {
        Log.e(TAG, "frame failed", e)
        screen.analysisError(e.javaClass.simpleName)
    }

    private fun process(frame: Bitmap, t0: Long) {
        val t1 = SystemClock.elapsedRealtime()
        if (reading) {
            val (speech, done) = reader.step(frame)
            speech?.let { feedback.say(it, strong = done); said = it }
            if (done) reading = false
            Log.d(TAG, "READ ocr ${SystemClock.elapsedRealtime() - t1}ms  ${speech.orEmpty()}")
            screen.post(HudState(mode = "READ", said = said, imgW = frame.width, imgH = frame.height, loading = false))
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
        calibration?.let { c -> calibrationFrame(c, t2, pitch) }

        // Depth on the NPU every Nth frame: drop-offs, head height, unnamed obstacles, and metres per object.
        activity.update(t2, ego.lastStepMs, ego.vibration) // only throttles detection; not announced
        val depthOn = activity.current != Activity.VEHICLE && activity.current != Activity.SITTING // bus lurches fake drop-offs
        if (!depthOn) hazards = Hazards()
        val depthStride = if (Settings.heatThrottle) maxOf(Settings.depthEvery, heat.tier.depthEvery) else Settings.depthEvery
        if (depthOn && depthFrames++ % depthStride == 0 && health == Health.OK) {
            val d0 = SystemClock.elapsedRealtime()
            // Drop-offs only matter while walking; at a desk the table top would be mistaken for the floor.
            val walking = activity.current == Activity.WALKING || calibration?.walking == true
            hazards = withoutFurnitureFloor(depthAnalyzer.analyze(depth.run(frame), pitch, walking, t2, motion.speed), tracks)
            depthMs = SystemClock.elapsedRealtime() - d0
            depthReady = true
        }

        // Drop-off: DropStateMachine is the authority; the old depth drop cue is replaced by its decision.
        val depthIn = if (depthOn && depthReady) app.nadaka.drop.DepthInput(depth.rawDisparity, depth.rawSize,
            depthAnalyzer.scale, depthAnalyzer.floorTrusted, SystemClock.elapsedRealtime() - depth.lastRunMs) else null
        drop.evaluate(SystemClock.elapsedRealtime(), frame, depthIn, pitch, tracks, health)?.let { dropOut = it }
        val dropNow = dropOut
        val confirmed = dropNow?.state == app.nadaka.drop.DropState.CONFIRMED_DROP
        val risingDrop = confirmed && dropNow?.transition?.to == app.nadaka.drop.DropState.CONFIRMED_DROP
        hazards = hazards.copy(dropAtM = if (confirmed) dropNow!!.dropAheadM else null, dropIsStep = false)
        val dropHaptic = dropNow?.haptic ?: app.nadaka.drop.DropHaptic.NONE
        dropOut = dropNow?.copy(transition = null, haptic = app.nadaka.drop.DropHaptic.NONE) // each event once
        tracks.forEach { it.depthM = depthAnalyzer.metresIn(it.box, it.label) }
        torch.update(t2, luma)?.let(::setTorch)
        chooseLens(t2, tracks)
        memory.record(t2, hazards, tracks, health)
        latestTracks = tracks
        latestHazards = hazards
        finder?.let { f ->
            f.update(t2, tracks)?.let { if (!voice.listening) { feedback.say(it); said = "Finding: $it" } }
            if (f.done) finder = null
        }
        // Drop-off is always spoken: on confirmation, then again every hazardRepeatMs while it stays confirmed.
        // Not gated on listening, finding or an answer in progress: this one interrupts everything.
        if (!alarmOn && confirmed && (risingDrop || t2 - dropSaidMs >= Settings.hazardRepeatMs)) {
            dropSaidMs = t2
            val m = dropNow!!.dropAheadM
            val n = dropNow.stairsDownSteps
            val what = if (n >= 2) "Stairs down" else "Drop"
            val words = (if (m.isNaN()) "Stop. $what ahead." else "Stop. $what ahead, ${metres(m)}.") + (if (n >= 2) " At least $n steps." else "")
            feedback.warn(words); said = words; saidLevel = Buzz.WARN
        }
        // Stairs going up: say it once, again every few seconds while it stays in view.
        dropNow?.stairsUpM?.takeIf { !it.isNaN() && !alarmOn && t2 - stairsSaidMs >= Settings.stairsRepeatMs }?.let {
            stairsSaidMs = t2
            val n = dropNow.stairsUpSteps
            val words = "Stairs going up ahead, ${metres(it)}." + (if (n >= 2) " About $n steps." else "")
            feedback.play(listOf(Alert(words, Buzz.WARN, Tacton.HEAD, "Stairs up."))); said = words; saidLevel = Buzz.WARN
        }
        if (!alarmOn) { walkStraight(t2); readBus(t2, tracks); sceneFind(t2, frame) }
        policy.decide(tracks, health, t2, hazards.copy(dropAtM = null), activity.current).takeIf { it.isNotEmpty() && finder == null && !alarmOn }?.let { feedback.play(it); said = caption(it); saidLevel = it.maxOf { a -> a.buzz } }

        if (!alarmOn) feedback.haptics.drop(dropHaptic) // after the alert batch so nothing overrides it; ignores sound settings

        // Parking-sensor ticks for the nearest thing in my path (tracks or an unnamed depth obstacle).
        if (activity.current == Activity.WALKING && !policy.blind && !alarmOn) { // ticks only while walking (also while the mic is open)
            val inPathM = tracks.filter { it.hits >= Settings.minHits && !it.metres.isNaN() && inPath(it) && (it.sure || it.metres < Settings.veryCloseM) }
                .minOfOrNull { it.metres }
            feedback.haptics.proximity(listOfNotNull(inPathM, hazards.floorObstacleAtM).minOrNull() ?: Float.NaN)
        }
        checkBattery(t2)
        pollHeat(t2)
        heat.update(t2, getSystemService(PowerManager::class.java).currentThermalStatus, headroom, batteryC,
            SystemClock.elapsedRealtime() - t0)?.let { if (it >= HeatTier.HOT) { feedback.say(it.spoken); said = it.spoken } } // only "hot", no chatter

        val fps = if (lastFrameMs == 0L) 0 else 1000 / (t2 - lastFrameMs).coerceAtLeast(1)
        lastFrameMs = t2
        val rec = if (egoLog.recording) "REC${if (egoLog.label == 1) " +" else ""}" else ""
        if (t2 - lastStatusLogMs > 5000) { lastStatusLogMs = t2; Log.i(TAG, "${detector.backend} $fps fps det ${t2 - t1}ms depth ${depthMs}ms  ${tracks.joinToString { "${it.label}#${it.id} %.1fm${if (it.approaching) "!" else ""}".format(it.metres) }}") }
        val st = HudState(
            mode = activity.current.name, heat = heat.tier, lens = Settings.zoom, backend = detector.backend, depthBackend = depth.backend, fps = fps.toInt(), detMs = t2 - t1, depthMs = depthMs,
            level = saidLevel, health = health, rec = rec, tracks = tracks, hazards = hazards, said = said,
            depth = depthAnalyzer.latest(), drop = dropNow, imgW = frame.width, imgH = frame.height,
            loading = false, alarm = when { fallPending -> "FALL"; emergency.active -> "SIREN"; else -> null }, floorTrusted = depthAnalyzer.floorTrusted, calibrating = calibration?.instruction, sensorError = if (motionMissing) "No motion sensor." else null,
            baroHPa = if (pressure == null) Float.NaN else drop.barometer.filteredPressure, atMs = t2,
        )
        screen.post(st)
    }
}

/** Speech + vibration output. What to say is decided by AlertPolicy. */
class Feedback(private val ctx: Context) : TextToSpeech.OnInitListener {
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

    /** Shared by the English and the Hindi / Telugu voice: an answer finishing starts the quiet gap. */
    private val progress = object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit
            override fun onDone(id: String?) { if (id == ANSWER) answerEnded() }
            @Deprecated("") override fun onError(id: String?) { if (id == ANSWER) answerEnded() }
            override fun onStop(id: String?, interrupted: Boolean) { if (id == ANSWER) answerEnded() }
        }

    init {
        tts.setOnUtteranceProgressListener(progress)
    }

    // Hindi / Telugu voice (the phone's own offline TTS voices), created when that language is chosen.
    private var local: TextToSpeech? = null
    private var localLang: SpeechLang? = null
    @Volatile private var localReady = false

    private fun ensureLocal(lang: SpeechLang) {
        if (lang == localLang) return
        local?.shutdown(); local = null; localReady = false; localLang = lang
        if (lang == SpeechLang.EN) return
        local = TextToSpeech(ctx) { st ->
            val t = local ?: return@TextToSpeech
            val r = if (st == TextToSpeech.SUCCESS) t.setLanguage(lang.locale) else TextToSpeech.LANG_NOT_SUPPORTED
            localReady = r >= TextToSpeech.LANG_AVAILABLE
            t.setOnUtteranceProgressListener(progress)
            Log.i(TAG, "tts: ${lang.name} voice ${if (localReady) "ready" else "missing ($r)"}")
            if (!localReady) tts.speak("The ${lang.name.lowercase().let { if (it == "hi") "Hindi" else "Telugu" }} voice is not installed. " +
                "Install it in the phone's text to speech settings. Using English for now.", TextToSpeech.QUEUE_ADD, null, "lang")
        }
    }

    /** All speech goes through here: translated alerts use the Hindi / Telugu voice; anything else English. */
    private fun speak(text: String, mode: Int, params: android.os.Bundle?, id: String) {
        val lang = Prefs.speechLang
        ensureLocal(lang)
        val out = if (lang == SpeechLang.EN) null else Say.tr(text, lang) ?: text.takeIf { Say.isIndic(it) }
        val l = local
        if (out != null && l != null && localReady) {
            if (mode == TextToSpeech.QUEUE_FLUSH) tts.stop()
            l.speak(out, mode, params, id)
        } else {
            if (mode == TextToSpeech.QUEUE_FLUSH) l?.stop()
            tts.speak(text, mode, params, id)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts.language = Locale.ENGLISH
    }

    /** Speaks an answer to the user's question in full. Only danger (see [play]) may interrupt it. */
    /** The mic is open (volume up pressed): nothing may be spoken, vibration still works. */
    var listening: () -> Boolean = { false }

    /** Quiet gap after an answer so it can sink in before routine speech resumes. */
    @Volatile private var quietUntilMs = 0L
    private fun answerEnded() { answering = false; quietUntilMs = SystemClock.elapsedRealtime() + Settings.answerGapMs }
    private val inGap get() = SystemClock.elapsedRealtime() < quietUntilMs

    fun answer(text: String, strong: Boolean = false) {
        answering = true
        speak(text, TextToSpeech.QUEUE_FLUSH, null, ANSWER)
        if (strong) vibrator.vibrate(result)
    }

    fun buzz() = vibrator.vibrate(side)

    /** Must be heard whatever the Audio switch or an open mic says (fall, emergency). */
    fun urgent(text: String) { answering = false; speak(text, TextToSpeech.QUEUE_FLUSH, null, "urgent") }

    /** Danger that must be heard now: cuts off anything being said, including an answer. */
    fun warn(text: String) { if (!Prefs.audioOn || listening()) return; answering = false; speak(text, TextToSpeech.QUEUE_FLUSH, null, "warn") }

    /** Settings > Test audio: always audible, whatever the Audio switch says. */
    fun test() = speak("Audio test. Stop. Drop ahead, 1 metre.", TextToSpeech.QUEUE_FLUSH, null, "test")

    /** Stop talking before listening: the recognizer must not hear us. */
    fun hush() { answering = false; tts.stop(); local?.stop() }

    /** The microphone is open now: a crisp double tap (vibration, so it doesn't pollute the audio). */
    fun readyCue() = vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 40, 60, 40), intArrayOf(0, 255, 0, 255), -1))

    fun say(text: String, strong: Boolean = false) {
        if (!Prefs.audioOn || listening() || inGap) { if (strong && Prefs.hapticOn) vibrator.vibrate(result); return }
        // Never cut off an answer: queue behind it.
        speak(text, if (answering) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH, null, text)
        if (strong) vibrator.vibrate(result)
    }

    /** Plays each pattern with its meaning: speak, pause, vibrate. About 20 s. */
    fun lesson() {
        LESSON.forEachIndexed { i, (words, tacton) ->
            main.postDelayed({ speak(words, TextToSpeech.QUEUE_FLUSH, null, "lesson$i") }, i * 4500L)
            main.postDelayed({ if (tacton == Tacton.TICK) repeat(4) { k -> main.postDelayed({ haptics.play(Tacton.TICK) }, k * 350L) } else haptics.play(tacton) }, i * 4500L + 2800)
        }
    }

    /** Haptics-first: the most urgent pattern, plus only the short words that must be heard. */
    fun play(alerts: List<Alert>) {
        if (!Prefs.audioOn || listening()) { alerts.firstNotNullOfOrNull { it.tacton }?.let(haptics::play); return }
        if (inGap) { // after an answer: feel routine alerts, hear only danger
            alerts.firstNotNullOfOrNull { it.tacton }?.let(haptics::play)
            val danger = alerts.filter { it.buzz == Buzz.WARN }.map { it.short ?: it.text }
            if (danger.isNotEmpty()) speak(danger.joinToString(" "), TextToSpeech.QUEUE_FLUSH, null, "danger")
            return
        }
        if (answering) {
            // The user is listening to an answer: feel routine alerts, hear only danger (it interrupts).
            alerts.firstNotNullOfOrNull { it.tacton }?.let(haptics::play)
            val danger = alerts.filter { it.buzz == Buzz.WARN }.map { it.short ?: it.text }
            if (danger.isNotEmpty()) { answering = false; speak(danger.joinToString(" "), TextToSpeech.QUEUE_FLUSH, null, "danger") }
            return
        }
        if (Settings.hapticsFirst) {
            alerts.firstNotNullOfOrNull { it.tacton }?.let(haptics::play)
            val words = alerts.mapNotNull { it.short }
            if (words.isNotEmpty()) speak(words.joinToString(" "), TextToSpeech.QUEUE_FLUSH, null, "short")
            return
        }
        speakAll(alerts)
    }

    private companion object {
        const val ANSWER = "answer"
    }

    /** Speech mode: full sentences, most urgent first; the strongest buzz of the batch. */
    private fun speakAll(alerts: List<Alert>) {
        alerts.forEachIndexed { i, a -> speak(a.text, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, a.text) }
        if (Prefs.hapticOn) vibrator.vibrate(
            when (alerts.maxOf { it.buzz.ordinal }) {
                Buzz.WARN.ordinal -> result
                Buzz.APPROACH.ordinal -> approach
                Buzz.AHEAD.ordinal -> ahead
                else -> side
            }
        )
    }
}
