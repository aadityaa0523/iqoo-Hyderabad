package app.nadaka

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.provider.MediaStore
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fall "black box": the last [Settings.blackBoxKeepMs] of low-quality camera snapshots and accelerometer data are
 * kept in memory (nothing is written while nothing happens). When a fall is detected, the moments before and a few
 * seconds after are saved on the phone: one overview image in the Gallery (Pictures/Nadaka) with every frame
 * labelled by time and the motion trace below, plus the frames and a CSV in the app's own folder. Never uploaded.
 */
class BlackBox(private val ctx: Context) {
    private val frames = ArrayDeque<Pair<Long, ByteArray>>() // (time, small JPEG)
    private val motion = ArrayDeque<Pair<Long, Float>>()      // (time, acceleration in g)
    private var lastFrameMs = -1_000_000L

    /** Analysis thread: keep a small JPEG every [Settings.blackBoxFrameMs]. */
    fun frame(now: Long, b: Bitmap) {
        if (now - lastFrameMs < Settings.blackBoxFrameMs) return
        lastFrameMs = now
        val s = Settings.blackBoxPx.toFloat() / maxOf(b.width, b.height)
        val small = Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true)
        val jpeg = ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 55, it) }.toByteArray()
        synchronized(frames) {
            frames.addLast(now to jpeg)
            while (frames.isNotEmpty() && now - frames.first().first > Settings.blackBoxKeepMs) frames.removeFirst()
        }
    }

    /** Sensor thread: acceleration magnitude in g (1 = still, ~0 = free fall, big = impact). */
    fun motion(now: Long, g: Float) = synchronized(motion) {
        motion.addLast(now to g)
        while (motion.isNotEmpty() && now - motion.first().first > Settings.blackBoxKeepMs) motion.removeFirst()
    }

    /** Save around [fallMs] (call a few seconds after the fall so "after" frames exist). Returns what was saved. */
    fun save(fallMs: Long): String? = runCatching {
        val fs = synchronized(frames) { frames.toList() }
        val ms = synchronized(motion) { motion.toList() }
        if (fs.isEmpty()) return null
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val dir = File(ctx.getExternalFilesDir(null), "falls/$stamp").apply { mkdirs() }
        fs.forEach { (t, j) -> File(dir, "frame_%+06d_ms.jpg".format(t - fallMs)).writeBytes(j) }
        File(dir, "motion.csv").writeText("ms_from_fall,acceleration_g\n" + ms.joinToString("\n") { (t, g) -> "${t - fallMs},%.3f".format(g) })
        val sheet = overview(fs, ms, fallMs, stamp)
        sheet.compress(Bitmap.CompressFormat.JPEG, 85, File(dir, "overview.jpg").outputStream())
        // Also to the Gallery, where a caregiver will look.
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Nadaka_fall_$stamp.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Nadaka")
        }
        ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)?.let { uri ->
            ctx.contentResolver.openOutputStream(uri)?.use { sheet.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        }
        Log.i(TAG, "black box: ${fs.size} frames saved to ${dir.path} and Pictures/Nadaka")
        dir.path
    }.onFailure { Log.e(TAG, "black box: save failed", it) }.getOrNull()

    /** All frames in a grid, each labelled with its time relative to the fall, and the motion trace underneath. */
    private fun overview(fs: List<Pair<Long, ByteArray>>, ms: List<Pair<Long, Float>>, fallMs: Long, stamp: String): Bitmap {
        val cols = 5; val cw = 240; val ch = 320
        val rows = (fs.size + cols - 1) / cols
        val graphH = 260; val headH = 70
        val out = Bitmap.createBitmap(cols * cw, headH + rows * ch + graphH, Bitmap.Config.ARGB_8888)
        val c = Canvas(out).apply { drawColor(Color.BLACK) }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 34f; isFakeBoldText = true }
        c.drawText("Nadaka fall record  $stamp", 16f, 48f, text)
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 30f; isFakeBoldText = true }
        val bg = Paint().apply { color = 0xAA000000.toInt() }
        fs.forEachIndexed { i, (t, j) ->
            val x = (i % cols) * cw.toFloat(); val y = headH + (i / cols) * ch.toFloat()
            BitmapFactory.decodeByteArray(j, 0, j.size)?.let { c.drawBitmap(it, null, android.graphics.RectF(x, y, x + cw, y + ch), null) }
            val d = (t - fallMs) / 1000f
            val s = if (kotlin.math.abs(d) < Settings.blackBoxFrameMs / 2000f) "FALL" else "%+.1f s".format(d)
            label.color = if (d >= 0) 0xFFFF6B5E.toInt() else Color.WHITE
            c.drawRect(x, y, x + label.measureText(s) + 16, y + 40, bg)
            c.drawText(s, x + 8, y + 31, label)
        }
        // Motion trace: 1 g = still, near 0 = falling, spike = impact.
        val gy = headH + rows * ch + 20f; val gh = graphH - 60f
        if (ms.size > 1) {
            val t0 = ms.first().first; val span = (ms.last().first - t0).coerceAtLeast(1)
            val axis = Paint().apply { color = 0xFF555555.toInt(); strokeWidth = 2f }
            val one = gy + gh - gh / 4f // 1 g line (scale 0..4 g)
            c.drawLine(0f, one, out.width.toFloat(), one, axis)
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF6FD3FF.toInt(); strokeWidth = 4f }
            var px = 0f; var py = one
            ms.forEachIndexed { i, (t, g) ->
                val x = (t - t0).toFloat() / span * out.width; val y = gy + gh - (g.coerceIn(0f, 4f) / 4f) * gh
                if (i > 0) c.drawLine(px, py, x, y, line)
                px = x; py = y
            }
            val fx = (fallMs - t0).toFloat() / span * out.width
            c.drawLine(fx, gy, fx, gy + gh, Paint().apply { color = 0xFFFF6B5E.toInt(); strokeWidth = 3f })
            text.textSize = 26f
            c.drawText("motion (g): 1 = still, ~0 = falling, spike = impact", 16f, gy + gh + 36f, text)
        }
        return out
    }
}
