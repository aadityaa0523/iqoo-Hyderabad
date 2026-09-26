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
class Detector(ctx: Context) {
    private val labels = ctx.assets.open("labels.txt").bufferedReader().readLines()
    private val interpreter: Interpreter
    val backend: String
    private val size: Int
    private val input: ByteBuffer
    private val pixels: IntArray
    private val outputs: List<ByteBuffer>
    private val boxQ: Pair<Float, Int>
    private val scoreQ: Pair<Float, Int>
    private val n: Int

    init {
        val fd = ctx.assets.openFd("detect.tflite")
        val model = FileInputStream(fd.fileDescriptor).channel
            .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        val (interp, name) = open(ctx, model)
        interpreter = interp
        backend = name
        interpreter.allocateTensors()
        size = interpreter.getInputTensor(0).shape()[1]
        input = ByteBuffer.allocateDirect(size * size * 3).order(ByteOrder.nativeOrder())
        pixels = IntArray(size * size)
        outputs = (0 until interpreter.outputTensorCount).map {
            ByteBuffer.allocateDirect(interpreter.getOutputTensor(it).numBytes()).order(ByteOrder.nativeOrder())
        }
        fun q(i: Int) = interpreter.getOutputTensor(i).quantizationParams().let { it.scale to it.zeroPoint }
        boxQ = q(0)
        scoreQ = q(1)
        n = interpreter.getOutputTensor(1).shape()[1]
        Log.i(TAG, "detector on $backend, input ${size}x$size, $n anchors, box q=$boxQ score q=$scoreQ")
    }

    fun detect(frame: Bitmap, minScore: Float): List<Detection> {
        Bitmap.createScaledBitmap(frame, size, size, true).getPixels(pixels, 0, size, 0, 0, size, size)
        input.rewind()
        for (p in pixels) {
            input.put((p shr 16 and 0xFF).toByte())
            input.put((p shr 8 and 0xFF).toByte())
            input.put((p and 0xFF).toByte())
        }
        input.rewind()
        outputs.forEach { it.rewind() }
        interpreter.runForMultipleInputsOutputs(arrayOf(input), outputs.withIndex().associate { it.index to it.value })

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
        return nms(found)
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

    /** Hexagon NPU (QNN HTP), then GPU, then CPU. */
    private fun open(ctx: Context, model: MappedByteBuffer): Pair<Interpreter, String> {
        try {
            check(QnnDelegate.checkCapability(QnnDelegate.Capability.HTP_RUNTIME_QUANTIZED)) { "no HTP" }
            val qnn = QnnDelegate(QnnDelegate.Options().apply {
                setBackendType(QnnDelegate.Options.BackendType.HTP_BACKEND)
                setSkelLibraryDir(ctx.applicationInfo.nativeLibraryDir)
                setCacheDir(ctx.cacheDir.absolutePath) // compiled graph cached: faster next launch
                // Sustained, not burst: a walking aid runs for hours, so avoid thermal throttling.
                setHtpPerformanceMode(QnnDelegate.Options.HtpPerformanceMode.HTP_PERFORMANCE_SUSTAINED_HIGH_PERFORMANCE)
            })
            return Interpreter(model, Interpreter.Options().addDelegate(qnn)) to "NPU"
        } catch (e: Throwable) {
            Log.w(TAG, "NPU unavailable: $e")
        }
        try { // no allow-list check: the list doesn't know the 8 Elite Gen 5's GPU yet
            return Interpreter(model, Interpreter.Options().addDelegate(GpuDelegate())) to "GPU"
        } catch (e: Throwable) {
            Log.w(TAG, "GPU unavailable: $e")
        }
        return Interpreter(model, Interpreter.Options().setNumThreads(4)) to "CPU"
    }
}
