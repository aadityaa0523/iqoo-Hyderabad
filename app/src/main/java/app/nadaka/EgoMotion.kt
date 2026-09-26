package app.nadaka

import android.content.ContentValues
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.provider.MediaStore
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * User's own motion from the IMU: rotation from the gyroscope (exact over short spans),
 * forward speed from the step detector (cadence x stride). See docs/ego-motion.md.
 */
class EgoMotion(ctx: Context) : SensorEventListener {
    private val sm = ctx.getSystemService(SensorManager::class.java)
    @Volatile private var yawRate = 0f
    @Volatile private var pitchRate = 0f
    private val steps = ArrayDeque<Long>()

    fun start() {
        sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
    }

    fun stop() = sm.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            // Portrait phone, camera forward: device y is vertical (yaw), x is horizontal (pitch). Light smoothing.
            Sensor.TYPE_GYROSCOPE -> {
                yawRate = 0.7f * yawRate + 0.3f * e.values[1]
                pitchRate = 0.7f * pitchRate + 0.3f * e.values[0]
            }
            Sensor.TYPE_STEP_DETECTOR -> synchronized(steps) { steps.addLast(SystemClock.elapsedRealtime()) }
        }
    }

    override fun onAccuracyChanged(s: Sensor, accuracy: Int) = Unit

    fun snapshot(): Ego {
        val now = SystemClock.elapsedRealtime()
        val speed = synchronized(steps) {
            while (steps.isNotEmpty() && now - steps.first() > Settings.stepWindowMs) steps.removeFirst()
            val stale = steps.isEmpty() || now - steps.last() > Settings.stepStaleMs
            if (stale) 0f else steps.size * 1000f / Settings.stepWindowMs * Settings.strideM
        }
        return Ego(speed, yawRate, pitchRate)
    }
}

/** Record mode: one CSV row per tracked object per frame, into Download/nadaka-logs/, for train_ego.py. */
class EgoLog(private val ctx: Context) {
    private var out: Writer? = null
    val recording get() = out != null
    var label = 0 // 1 while the user marks "something is approaching" (volume-up toggles)

    fun start() {
        val name = "ego_" + SimpleDateFormat("MMdd_HHmmss", Locale.US).format(Date()) + ".csv"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/nadaka-logs")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        out = ctx.contentResolver.openOutputStream(uri)?.bufferedWriter()?.apply {
            write((listOf("t_ms", "track", "class") + FEATURE_NAMES + "distance" + "label").joinToString(",") + "\n")
        }
        label = 0
    }

    fun row(now: Long, t: Track) {
        if (t.features.isEmpty()) return
        out?.write("$now,${t.id},${t.label},${t.features.joinToString(",")},${t.distance},$label\n")
    }

    fun stop() {
        out?.close()
        out = null
    }
}
