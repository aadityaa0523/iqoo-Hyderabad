package app.nadaka

/**
 * Everything the screen shows; built on the analysis thread, drawn on the UI thread (ui/LiveScreen.kt).
 * The default value is the loading state: nothing has been analysed yet.
 */
data class HudState(
    val mode: String = "WALKING",
    val backend: String = "",
    val depthBackend: String = "",
    val fps: Int = 0,
    val detMs: Long = 0,
    val depthMs: Long = 0,
    val health: Health = Health.OK,
    val heat: HeatTier = HeatTier.NOMINAL,
    val lens: Float = 1f,
    val rec: String = "",
    val tracks: List<Track> = emptyList(),
    val hazards: Hazards = Hazards(),
    val said: String = "",
    val level: Buzz? = null,
    val depth: Array<FloatArray>? = null,
    val drop: app.nadaka.drop.DropOutput? = null,
    val imgW: Int = 3,
    val imgH: Int = 4,
    val loading: Boolean = true,
    val cameraError: String? = null,  // camera could not be opened / permission denied
    val error: String? = null,        // the analysis loop failed
    val sensorError: String? = null,  // a sensor the safety logic needs is missing
    val baroHPa: Float = Float.NaN,   // NaN = no barometer
    val atMs: Long = 0,
)
