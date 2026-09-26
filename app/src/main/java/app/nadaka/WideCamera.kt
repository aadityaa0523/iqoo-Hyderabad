package app.nadaka

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.TextureView
import java.util.concurrent.Executor

/**
 * Finds a back camera whose zoom goes below 1x: a logical multi-camera that includes the ultra-wide.
 * On the iQOO loaner it is id "2" (0.6x-100x): hidden from the camera list but openable by apps.
 */
fun findWideCameraId(ctx: Context): String? {
    val cm = ctx.getSystemService(CameraManager::class.java)
    return (0..9).map { it.toString() }.firstOrNull { id ->
        runCatching {
            val c = cm.getCameraCharacteristics(id)
            c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK &&
                (c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.lower ?: 1f) < 1f
        }.getOrDefault(false)
    }
}

/**
 * Camera2 on the logical camera: preview on a TextureView plus 640x480 analysis frames, in ONE session.
 * Changing lens is just CONTROL_ZOOM_RATIO on the repeating request: no restart, no lost frames.
 * Frames arrive upright (portrait) like CameraX's; one in flight at a time (latest wins).
 */
class WideCamera(
    ctx: Context,
    private val id: String,
    private val view: TextureView,
    private val analysis: Executor,
    private val onFrame: (Bitmap) -> Unit,
    private val onFail: (String) -> Unit,
    private val take: () -> Boolean = { true }, // frame-rate gate, asked before any conversion work
) {
    private val cm = ctx.getSystemService(CameraManager::class.java)
    private val chars = cm.getCameraCharacteristics(id)
    private val sensorDeg = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
    val minZoom = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.lower ?: 1f
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null
    @Volatile private var busy = false
    @Volatile private var zoom = 1f
    @Volatile private var torch = false

    fun start() {
        if (thread != null) return
        val t = HandlerThread("wide-cam").also { it.start() }
        thread = t
        handler = Handler(t.looper)
        if (view.isAvailable) open() else view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) = open()
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) = Unit
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) = Unit
        }
    }

    fun stop() {
        runCatching { session?.close() }; session = null
        runCatching { device?.close() }; device = null
        runCatching { reader?.close() }; reader = null
        thread?.quitSafely(); thread = null
    }

    fun setZoom(z: Float) { zoom = z.coerceAtLeast(minZoom); repeat() }
    fun setTorch(on: Boolean) { torch = on; repeat() }

    @SuppressLint("MissingPermission")
    private fun open() {
        val h = handler ?: return
        val st = view.surfaceTexture ?: return
        st.setDefaultBufferSize(1440, 1080) // 4:3 like the analysis frames, so the overlay lines up
        previewSurface = Surface(st)
        reader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 3).also { r ->
            r.setOnImageAvailableListener({ onImage(it) }, h)
        }
        try {
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) { device = d; createSession(d) }
                override fun onDisconnected(d: CameraDevice) { d.close(); device = null }
                override fun onError(d: CameraDevice, e: Int) { d.close(); device = null; onFail("camera error $e") }
            }, h)
        } catch (t: Throwable) { onFail("${t.javaClass.simpleName}: ${t.message}") }
    }

    private fun createSession(d: CameraDevice) {
        val outs = listOf(OutputConfiguration(previewSurface!!), OutputConfiguration(reader!!.surface))
        d.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outs, { handler?.post(it) },
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) { session = s; repeat(); Log.i(TAG, "lens: Camera2 logical camera $id, zoom ${minZoom}x..") }
                override fun onConfigureFailed(s: CameraCaptureSession) = onFail("session configure failed")
            }))
    }

    private fun repeat() {
        val s = session ?: return
        val d = device ?: return
        runCatching {
            val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewSurface!!); addTarget(reader!!.surface)
                set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                set(CaptureRequest.FLASH_MODE, if (torch) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
            }.build()
            s.setRepeatingRequest(req, null, handler)
        }.onFailure { Log.w(TAG, "lens: repeat failed $it") }
    }

    private fun onImage(r: ImageReader) {
        val img = r.acquireLatestImage() ?: return
        if (busy || !take()) { img.close(); return } // still analysing, or not due yet: drop before converting
        busy = true
        val bmp = try { yuvToUpright(img) } finally { img.close() }
        analysis.execute { try { onFrame(bmp) } finally { busy = false } }
    }

    /** YUV_420_888 -> ARGB bitmap, rotated upright by the sensor orientation in the same pass. */
    private fun yuvToUpright(img: android.media.Image): Bitmap {
        val w = img.width; val h = img.height
        val yP = img.planes[0]; val uP = img.planes[1]; val vP = img.planes[2]
        val y = ByteArray(yP.buffer.remaining()).also { yP.buffer.get(it) }
        val u = ByteArray(uP.buffer.remaining()).also { uP.buffer.get(it) }
        val v = ByteArray(vP.buffer.remaining()).also { vP.buffer.get(it) }
        val yRow = yP.rowStride; val cRow = uP.rowStride; val cPix = uP.pixelStride
        val rot = (sensorDeg % 360)
        val (ow, oh) = if (rot == 90 || rot == 270) h to w else w to h
        val out = IntArray(ow * oh)
        for (j in 0 until h) {
            val yBase = j * yRow
            val cBase = (j shr 1) * cRow
            for (i in 0 until w) {
                val yy = (y[yBase + i].toInt() and 0xFF) - 16
                val ci = cBase + (i shr 1) * cPix
                val uu = (u[ci].toInt() and 0xFF) - 128
                val vv = (v[ci].toInt() and 0xFF) - 128
                val yc = 1192 * maxOf(yy, 0)
                val rr = ((yc + 1634 * vv) shr 10).coerceIn(0, 255)
                val gg = ((yc - 833 * vv - 400 * uu) shr 10).coerceIn(0, 255)
                val bb = ((yc + 2066 * uu) shr 10).coerceIn(0, 255)
                val idx = when (rot) { // no per-pixel allocation: this runs 300k times a frame
                    90 -> i * ow + (h - 1 - j)
                    180 -> (h - 1 - j) * ow + (w - 1 - i)
                    270 -> (w - 1 - i) * ow + j
                    else -> j * ow + i
                }
                out[idx] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
            }
        }
        return Bitmap.createBitmap(out, ow, oh, Bitmap.Config.ARGB_8888)
    }
}
