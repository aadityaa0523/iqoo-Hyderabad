package app.nadaka

import android.Manifest.permission.ACTIVITY_RECOGNITION
import android.Manifest.permission.CAMERA
import android.content.Context
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
import kotlin.math.roundToInt

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
                bindToLifecycle(this@MainActivity, CameraSelector.DEFAULT_BACK_CAMERA, previewUse, analysis)
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

    private fun analyze(image: ImageProxy) {
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
        feedback.onTracks(tracks)

        val fps = if (lastFrameMs == 0L) 0 else 1000 / (t2 - lastFrameMs).coerceAtLeast(1)
        lastFrameMs = t2
        val rec = if (egoLog.recording) "  REC${if (egoLog.label == 1) "+" else ""}" else ""
        val status = "${detector.backend} $fps fps det ${t2 - t1}ms  walk %.1fm/s yaw %.1f$rec".format(motion.speed, motion.yawRate)
        Log.d(TAG, "$status  ${tracks.joinToString { "${it.label}#${it.id}${if (it.approaching) "!" else ""}" }}")
        hud.post { hud.show(tracks, status, frame.width, frame.height) }
    }
}

/** Approaching objects (ego-motion removed) first, else the nearest object. Clock-face directions. */
class Feedback(ctx: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(ctx, this)
    private val vibrator = ctx.getSystemService(VibratorManager::class.java).defaultVibrator
    private val lastSaid = HashMap<String, Long>()
    private val side = VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE)
    private val ahead = VibrationEffect.createWaveform(longArrayOf(0, 80, 80, 80), -1)
    private val result = VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE)
    private val approach = VibrationEffect.createWaveform(longArrayOf(0, 60, 40, 60, 40, 60, 40, 200), -1)
    private var lastApproachMs = 0L

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts.language = Locale.ENGLISH
    }

    fun say(text: String, strong: Boolean = false) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, text)
        if (strong) vibrator.vibrate(result)
    }

    fun onTracks(tracks: List<Track>) {
        val now = SystemClock.elapsedRealtime()
        val urgent = tracks.filter { it.approaching }.minByOrNull { it.ttc }
        if (urgent != null) {
            if (now - lastApproachMs < Settings.approachCooldownMs) return
            lastApproachMs = now
            tts.speak("${urgent.label} approaching, ${clock(urgent)}", TextToSpeech.QUEUE_FLUSH, null, "approach")
            vibrator.vibrate(approach)
            return
        }
        val t = tracks.maxByOrNull { it.box.height() } ?: return // nearest-looking
        if (now - (lastSaid[t.label] ?: 0L) < Settings.speechCooldownMs) return
        lastSaid[t.label] = now
        val c = clock(t)
        tts.speak("${t.label}, $c", TextToSpeech.QUEUE_FLUSH, null, t.label)
        vibrator.vibrate(if (c == CLOCK_AHEAD) ahead else side)
    }

    /** Orientation-and-mobility style direction: 12 = straight ahead. */
    private fun clock(t: Track): String {
        val hour = (Math.toDegrees(t.bearing.toDouble()) / 30).roundToInt()
        return "${if (hour == 0) 12 else (12 + hour - 1) % 12 + 1}$OCLOCK"
    }

    private companion object {
        const val OCLOCK = " o'clock"
        const val CLOCK_AHEAD = "12 o'clock"
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
