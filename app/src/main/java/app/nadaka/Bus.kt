package app.nadaka

import android.graphics.Bitmap

/**
 * Reads a bus's route number (e.g. "218", "10H", "5K/1") from its front board, once per bus. The caller supplies
 * crops (full-resolution preview) and OCR text; this decides when to try, votes over tries, and when to give up.
 * Pure logic, unit-tested.
 */
class BusReader {
    private class State(var tries: Int = 0, var lastTryMs: Long = -1_000_000L, val votes: HashMap<String, Int> = HashMap(), var done: Boolean = false)
    private val buses = HashMap<Int, State>()

    /** The bus track to read now, or null. Only close-enough, stable buses; one read every [Settings.busTryMs]. */
    fun due(now: Long, tracks: List<Track>): Track? {
        buses.keys.retainAll(tracks.map { it.id }.toSet())
        return tracks.filter { it.label == "bus" && it.hits >= Settings.minHits && it.box.height() > Settings.busMinBoxH }
            .filter { t -> buses.getOrPut(t.id) { State() }.let { !it.done && now - it.lastTryMs >= Settings.busTryMs } }
            .maxByOrNull { it.box.height() } // the nearest bus first
            ?.also { buses.getValue(it.id).run { tries++; lastTryMs = now } }
    }

    /** OCR text from one try. Returns what to say ("Bus 218."), GIVE_UP when the number won't read, or null. */
    fun read(id: Int, text: String): String? {
        val s = buses[id] ?: return null
        routeNumbers(text).forEach { s.votes.merge(it, 1, Int::plus) }
        s.votes.entries.firstOrNull { it.value >= Settings.busVotes }?.let { s.done = true; return "Bus ${it.key}." }
        if (s.tries >= Settings.busMaxTries) { s.done = true; return GIVE_UP }
        return null
    }

    /** An answer from the fallback (Qwen) for a bus that OCR gave up on. */
    fun fallback(text: String): String? = routeNumbers(text).firstOrNull()?.let { "Bus $it." }

    companion object {
        const val GIVE_UP = "?"

        /** The top-front part of a bus box, where the route board is. Normalised (left, top, right, bottom). */
        fun boardArea(t: Track) = floatArrayOf(t.box.left, t.box.top, t.box.right, t.box.top + t.box.height() * 0.45f)

        /** Route numbers in OCR text: 1-3 digits with up to 2 letters and an optional /suffix. Plates and years are not. */
        fun routeNumbers(text: String): List<String> {
            val up = text.uppercase().replace('O', '0').replace('I', '1').replace('|', '1')
            return Regex("""(?<![A-Z0-9/])(\d{1,3}[A-Z]{0,2}(?:/\d{1,3}[A-Z]?)?)(?![A-Z0-9/])""").findAll(up)
                .map { it.groupValues[1] }
                .filter { it.first() != '0' && it.any(Char::isDigit) }
                .toList()
        }

        /** Crop a normalised area from a bitmap, scaled up if small so OCR has enough pixels. */
        fun crop(b: Bitmap, a: FloatArray): Bitmap? {
            val x0 = (a[0] * b.width).toInt().coerceIn(0, b.width - 1); val y0 = (a[1] * b.height).toInt().coerceIn(0, b.height - 1)
            val x1 = (a[2] * b.width).toInt().coerceIn(x0 + 1, b.width); val y1 = (a[3] * b.height).toInt().coerceIn(y0 + 1, b.height)
            if (x1 - x0 < 24 || y1 - y0 < 12) return null
            val c = Bitmap.createBitmap(b, x0, y0, x1 - x0, y1 - y0)
            val k = (640f / c.width).coerceIn(1f, 3f)
            return if (k > 1.05f) Bitmap.createScaledBitmap(c, (c.width * k).toInt(), (c.height * k).toInt(), true) else c
        }
    }
}
