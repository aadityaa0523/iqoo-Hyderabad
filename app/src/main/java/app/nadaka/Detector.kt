package app.nadaka

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.qualcomm.qti.QnnDelegate
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/** box is normalised 0..1 in the (rotated, upright) frame. */
data class Detection(val label: String, val score: Float, val box: Box)

/**
 * YOLOX (Qualcomm AI Hub, int8 w8a8, 640x640) -> boxes [x1,y1,x2,y2] px, scores, class_idx; we do NMS.
 * Must be created and used on one thread.
 */
class Detector(ctx: Context, only: Backend? = null) {
    private val labels = ctx.assets.open("labels.txt").bufferedReader().readLines()
    private val interpreter: Interpreter
    val backend: String
    private val size: Int
    private val input: ByteBuffer
    private val pixels: IntArray
    private val rgb: ByteArray
    private val outputs: List<ByteBuffer>
    private val boxQ: Pair<Float, Int>
    private val scoreQ: Pair<Float, Int>
    private val n: Int
    private val delegate: AutoCloseable?
    // Last call's stage timings (ms), for the debug view and the benchmark.
    var preMs = 0.0; var inferMs = 0.0; var postMs = 0.0
    val initMs: Double

    init {
        val fd = ctx.assets.openFd("detect.tflite")
        val model = FileInputStream(fd.fileDescriptor).channel
            .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        val t0 = System.nanoTime()
        val o = openInterpreter(ctx, model, token = "yolox_w8a8_aihub_v0.63.0", only = only)
        interpreter = o.interpreter
        backend = o.backend
        delegate = o.delegate
        interpreter.allocateTensors()
        initMs = (System.nanoTime() - t0) / 1e6
        size = interpreter.getInputTensor(0).shape()[1]
        input = ByteBuffer.allocateDirect(size * size * 3).order(ByteOrder.nativeOrder())
        pixels = IntArray(size * size)
        rgb = ByteArray(size * size * 3)
        outputs = (0 until interpreter.outputTensorCount).map {
            ByteBuffer.allocateDirect(interpreter.getOutputTensor(it).numBytes()).order(ByteOrder.nativeOrder())
        }
        fun q(i: Int) = interpreter.getOutputTensor(i).quantizationParams().let { it.scale to it.zeroPoint }
        boxQ = q(0)
        scoreQ = q(1)
        n = interpreter.getOutputTensor(1).shape()[1]
        Log.i(TAG, "detector on $backend, input ${size}x$size, $n anchors, box q=$boxQ score q=$scoreQ")
    }

    fun close() { interpreter.close(); runCatching { delegate?.close() } }

    fun detect(frame: Bitmap, minScore: Float): List<Detection> {
        val t0 = System.nanoTime()
        Bitmap.createScaledBitmap(frame, size, size, true).getPixels(pixels, 0, size, 0, 0, size, size)
        // Fill a plain array, then one bulk copy: per-element ByteBuffer.put was 1.2 M JNI-checked calls a frame.
        var k = 0
        for (p in pixels) { rgb[k++] = (p shr 16).toByte(); rgb[k++] = (p shr 8).toByte(); rgb[k++] = p.toByte() }
        input.rewind(); input.put(rgb); input.rewind()
        outputs.forEach { it.rewind() }
        val t1 = System.nanoTime()
        interpreter.runForMultipleInputsOutputs(arrayOf(input), outputs.withIndex().associate { it.index to it.value })
        val t2 = System.nanoTime()

        val (bs, bz) = boxQ
        val (ss, sz) = scoreQ
        val boxes = outputs[0]
        val scores = outputs[1]
        val classes = outputs[2]
        val found = ArrayList<Detection>()
        for (i in 0 until n) {
            val score = ss * ((scores.get(i).toInt() and 0xFF) - sz)
            if (score < minScore) continue
            fun c(k: Int) = (bs * ((boxes.get(4 * i + k).toInt() and 0xFF) - bz) / size).coerceIn(0f, 1f)
            val label = labels.getOrElse(classes.get(i).toInt() and 0xFF) { "object" }
            found += Detection(label, score, Box(c(0), c(1), c(2), c(3)))
        }
        return nms(found).also {
            val t3 = System.nanoTime()
            preMs = (t1 - t0) / 1e6; inferMs = (t2 - t1) / 1e6; postMs = (t3 - t2) / 1e6
        }
    }

    /** Class-aware greedy NMS. ponytail: O(n^2), fine for the few hundred boxes above threshold. */
    private fun nms(dets: List<Detection>, maxIou: Float = 0.45f, max: Int = 25): List<Detection> {
        val kept = ArrayList<Detection>()
        for (d in dets.sortedByDescending { it.score }) {
            if (kept.size == max) break
            if (kept.none { it.label == d.label && iou(it.box, d.box) > maxIou }) kept += d
        }
        return kept
    }
}

enum class Backend { HTP, GPU, CPU }

class Opened(val interpreter: Interpreter, val backend: String, val delegate: AutoCloseable?)

/**
 * Qualcomm Hexagon HTP (QNN LiteRT delegate), then GPU, then CPU; never throws. [only] forces one backend
 * (benchmark); a forced backend that fails throws so the benchmark can report it instead of silently
 * measuring another one. [token] names the model so QNN can cache the compiled HTP graph in cacheDir
 * (without a token the delegate recompiles the graph on every launch).
 */
fun openInterpreter(ctx: Context, model: MappedByteBuffer, fp16: Boolean = false, token: String, only: Backend? = null): Opened {
    if (only == null || only == Backend.HTP) try {
        val cap = if (fp16) QnnDelegate.Capability.HTP_RUNTIME_FP16 else QnnDelegate.Capability.HTP_RUNTIME_QUANTIZED
        check(QnnDelegate.checkCapability(cap)) { "no HTP $cap" }
        val qnn = QnnDelegate(QnnDelegate.Options().apply {
            setBackendType(QnnDelegate.Options.BackendType.HTP_BACKEND)
            setSkelLibraryDir(ctx.applicationInfo.nativeLibraryDir)
            setCacheDir(ctx.cacheDir.absolutePath)
            setModelToken(token)
            // Sustained, not burst: a walking aid runs for hours, so avoid thermal throttling.
            setHtpPerformanceMode(QnnDelegate.Options.HtpPerformanceMode.HTP_PERFORMANCE_SUSTAINED_HIGH_PERFORMANCE)
            if (fp16) setHtpPrecision(QnnDelegate.Options.HtpPrecision.HTP_PRECISION_FP16)
        })
        return Opened(Interpreter(model, Interpreter.Options().addDelegate(qnn)), "HTP", qnn)
    } catch (e: Throwable) {
        Log.w(TAG, "HTP unavailable: $e")
        if (only == Backend.HTP) throw e
    }
    if (only == null || only == Backend.GPU) try { // no allow-list check: it doesn't know this GPU yet
        val gpu = GpuDelegate()
        return Opened(Interpreter(model, Interpreter.Options().addDelegate(gpu)), "GPU", gpu)
    } catch (e: Throwable) {
        Log.w(TAG, "GPU unavailable: $e")
        if (only == Backend.GPU) throw e
    }
    return Opened(Interpreter(model, Interpreter.Options().setNumThreads(4)), "CPU", null)
}
