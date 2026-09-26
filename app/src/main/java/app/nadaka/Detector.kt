package app.nadaka

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** box is normalised 0..1 in the (rotated, upright) frame. */
data class Detection(val label: String, val score: Float, val box: RectF)

/** EfficientDet-Lite0 with TFLite_Detection_PostProcess. Must be created and used on one thread (GPU delegate). */
class Detector(ctx: Context) {
    private val labels = ctx.assets.open("labels.txt").bufferedReader().readLines()
    private val interpreter: Interpreter
    val backend: String
    private val size: Int
    private val input: ByteBuffer
    private val pixels: IntArray
    private val outputs: List<ByteBuffer>

    init {
        val fd = ctx.assets.openFd("detect.tflite")
        val model = FileInputStream(fd.fileDescriptor).channel
            .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        // ponytail: GPU then CPU; QNN (NPU) delegate slots in first once qnn-litert-delegate is added.
        val (interp, name) = try {
            check(CompatibilityList().isDelegateSupportedOnThisDevice) { "GPU not supported" }
            Interpreter(model, Interpreter.Options().addDelegate(GpuDelegate())) to "GPU"
        } catch (e: Throwable) {
            Log.w(TAG, "GPU delegate unavailable, using CPU: $e")
            Interpreter(model, Interpreter.Options().setNumThreads(4)) to "CPU"
        }
        interpreter = interp
        backend = name
        interpreter.allocateTensors()
        size = interpreter.getInputTensor(0).shape()[1]
        input = ByteBuffer.allocateDirect(size * size * 3).order(ByteOrder.nativeOrder())
        pixels = IntArray(size * size)
        outputs = (0 until interpreter.outputTensorCount).map {
            val t = interpreter.getOutputTensor(it)
            Log.i(TAG, "output $it ${t.name()} ${t.shape().contentToString()}")
            ByteBuffer.allocateDirect(t.numBytes()).order(ByteOrder.nativeOrder())
        }
        Log.i(TAG, "detector on $backend, input ${size}x$size")
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

        // TFLite_Detection_PostProcess order: boxes [ymin,xmin,ymax,xmax], classes, scores, count.
        val boxes = outputs[0].floats()
        val classes = outputs[1].floats()
        val scores = outputs[2].floats()
        val count = outputs[3].floats()[0].toInt().coerceAtMost(scores.size)
        return (0 until count)
            .filter { scores[it] >= minScore }
            .map { i ->
                Detection(
                    labels.getOrElse(classes[i].toInt()) { "???" }, scores[i],
                    RectF(boxes[4 * i + 1], boxes[4 * i], boxes[4 * i + 3], boxes[4 * i + 2]),
                )
            }
            .filter { it.label != "???" }
    }

    private fun ByteBuffer.floats() = FloatArray(capacity() / 4).also { rewind(); asFloatBuffer().get(it) }
}
