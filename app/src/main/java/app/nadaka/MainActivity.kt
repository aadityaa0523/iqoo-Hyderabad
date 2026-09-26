package app.nadaka

import android.Manifest.permission.ACTIVITY_RECOGNITION
import android.Manifest.permission.CAMERA
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
    var minScore = 0.4f
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
    var approachMps = 0.5f // object's own speed toward me
    var approachTtcS = 4f
    var approachCooldownMs = 1500L

    // Edge cases (Alerts.kt). Calibrate luma/blur thresholds on the real phone.
    var minHits = 3 // frames an object must be seen before it is spoken
    var speechGapMs = 1200L
    var habituationGrowth = 1.3f // re-announce a known object only once it looks 30% bigger
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
}

class MainActivity : ComponentActivity() {
    private lateinit var preview: PreviewView
    private lateinit var hud: Overlay
    private lateinit var feedback: Feedback
    private val analysisThread = Executors.newSingleThreadExecutor()
    private val detector by lazy { Detector(this) } // created on the analysis thread
    private val reader by lazy { Reader() } // analysis thread only
    private val tracker by lazy { Tracker(EgoModel.loadOrNull { assets.open("ego_model.json").bufferedReader().readText() }) }
    private lateinit var ego: EgoMotion
    private lateinit var egoLog: EgoLog // analysis thread only
    private val policy = AlertPolicy() // analysis thread only
    private var camera: Camera? = null
    private var torchOn = false
    private var frameCount = 0
    @Volatile private var frameStride = 1 // raised when the phone gets hot
    private var thermalSaid = false
    private var batterySaid = false
    private var lastBatteryCheckMs = 0L
    private val tiny = IntArray(64 * 48)
    private var reading = false // analysis thread only
    private var lastFrameMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
        hud = Overlay(this)
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
            .launch(arrayOf(CAMERA, ACTIVITY_RECOGNITION))
    }

    override fun onResume() { super.onResume(); ego.start() }

    /** Sustained NPU mode keeps us cool; if the phone still heats up, analyse fewer frames and say so once. */
    private val thermal = PowerManager.OnThermalStatusChangedListener { status ->
        frameStride = when {
            status >= PowerManager.THERMAL_STATUS_SEVERE -> 3
            status >= PowerManager.THERMAL_STATUS_MODERATE -> 2
            else -> 1
        }
        if (status >= PowerManager.THERMAL_STATUS_SEVERE && !thermalSaid) {
            thermalSaid = true
            feedback.say("Phone is hot. Slowing down, alerts may be late.", strong = true)
        }
    }

    override fun onStart() { super.onStart(); getSystemService(PowerManager::class.java).addThermalStatusListener(mainExecutor, thermal) }
    override fun onStop() { getSystemService(PowerManager::class.java).removeThermalStatusListener(thermal); super.onStop() }
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
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) { // label toggle while recording
            analysisThread.execute {
                if (!egoLog.recording) return@execute
                egoLog.label = 1 - egoLog.label
                feedback.say(if (egoLog.label == 1) "Approaching." else "Clear.")
            }
            return true
        }
        if (keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return super.onKeyDown(keyCode, event)
        analysisThread.execute {
            if (!reading) { reader.start(); reading = true; feedback.say("Reading.") }
        }
        return true
    }

    private fun checkBattery(now: Long) {
        if (batterySaid || now - lastBatteryCheckMs < 60_000) return
        lastBatteryCheckMs = now
        val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val pct = b.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) * 100 / b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (pct <= Settings.lowBatteryPct) { batterySaid = true; feedback.say("Battery $pct percent. Charge soon.", strong = true) }
    }

    private fun analyze(image: ImageProxy) {
        if (frameCount++ % frameStride != 0 && !reading) { image.close(); return }
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
            speech?.let { feedback.say(it, strong = done) }
            if (done) reading = false
            val status = "READ  ocr ${SystemClock.elapsedRealtime() - t1}ms"
            Log.d(TAG, "$status  ${speech.orEmpty()}")
            hud.post { hud.show(emptyList(), status, frame.width, frame.height) }
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
        if (health == Health.DARK && !torchOn) { torchOn = true; camera?.cameraControl?.enableTorch(true) }
        else if (torchOn && luma > Settings.torchOffLuma) { torchOn = false; camera?.cameraControl?.enableTorch(false) }
        policy.decide(tracks, health, t2)?.let { feedback.play(it) }
        checkBattery(t2)

        val fps = if (lastFrameMs == 0L) 0 else 1000 / (t2 - lastFrameMs).coerceAtLeast(1)
        lastFrameMs = t2
        val rec = if (egoLog.recording) "  REC${if (egoLog.label == 1) "+" else ""}" else ""
        val warn = if (health != Health.OK) "  ${health.name}" else ""
        val status = "${detector.backend} $fps fps det ${t2 - t1}ms  walk %.1fm/s$warn$rec".format(motion.speed)
        Log.d(TAG, "$status  ${tracks.joinToString { "${it.label}#${it.id}${if (it.approaching) "!" else ""}" }}")
        hud.post { hud.show(tracks, status, frame.width, frame.height) }
    }
}

/** Speech + vibration output. What to say is decided by AlertPolicy. */
class Feedback(ctx: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(ctx, this)
    private val vibrator = ctx.getSystemService(VibratorManager::class.java).defaultVibrator
    private val side = VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE)
    private val ahead = VibrationEffect.createWaveform(longArrayOf(0, 80, 80, 80), -1)
    private val result = VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE)
    private val approach = VibrationEffect.createWaveform(longArrayOf(0, 60, 40, 60, 40, 60, 40, 200), -1)

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts.language = Locale.ENGLISH
    }

    fun say(text: String, strong: Boolean = false) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, text)
        if (strong) vibrator.vibrate(result)
    }

    fun play(a: Alert) {
        tts.speak(a.text, TextToSpeech.QUEUE_FLUSH, null, a.text)
        vibrator.vibrate(
            when (a.buzz) {
                Buzz.SIDE -> side
                Buzz.AHEAD -> ahead
                Buzz.APPROACH -> approach
                Buzz.WARN -> result
            }
        )
    }
}

/** Draws boxes over a FIT_CENTER preview plus a status line. */
class Overlay(ctx: Context) : View(ctx) {
    private var tracks = emptyList<Track>()
    private var status = ""
    private var imgW = 3
    private var imgH = 4
    private val box = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 6f; color = Color.YELLOW }
    private val hot = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 10f; color = Color.RED }
    private val text = Paint().apply { color = Color.YELLOW; textSize = 42f; isAntiAlias = true }
    private val bar = Paint().apply { color = 0xAA000000.toInt() }

    fun show(t: List<Track>, s: String, w: Int, h: Int) {
        tracks = t; status = s; imgW = w; imgH = h
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val scale = minOf(width / imgW.toFloat(), height / imgH.toFloat())
        val dx = (width - imgW * scale) / 2
        val dy = (height - imgH * scale) / 2
        val w = imgW * scale
        val h = imgH * scale
        for (t in tracks) {
            val r = RectF(dx + t.box.left * w, dy + t.box.top * h, dx + t.box.right * w, dy + t.box.bottom * h)
            c.drawRect(r, if (t.approaching) hot else box)
            val dist = if (t.distance.isNaN()) "" else " %.1fm".format(t.distance)
            val tag = if (t.approaching) " APPROACH %.1fs".format(t.ttc) else ""
            c.drawText("${t.label}$dist$tag", r.left + 8, r.top + 44, text)
        }
        c.drawRect(0f, 100f, width.toFloat(), 170f, bar)
        c.drawText(status, 24f, 150f, text)
    }
}
