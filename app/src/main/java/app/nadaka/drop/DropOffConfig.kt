package app.nadaka.drop

/**
 * Every drop-off threshold lives here (no magic numbers in the analyzers). Image coordinates are
 * normalised 0..1 of the upright camera frame (x left->right, y top->bottom). Depth values are
 * Depth Anything V2 relative disparity; "ratio" means observed / expected-floor disparity.
 */
object DropOffConfig {
    // Region of interest: the lower part of the view where the walking surface is.
    var ROI_TOP = 0.42f
    var ROI_BOTTOM = 0.97f
    var ROI_LEFT = 0.18f
    var ROI_RIGHT = 0.82f

    // Analysis image (the camera frame is downscaled to this before edge analysis; buffers reused).
    const val EDGE_IMG_W = 120
    const val EDGE_IMG_H = 160

    // Edge lattice.
    var EDGE_PIXEL_GRAD = 10f        // min |d intensity / d y| (0-255 per px) for an edge pixel
    var EDGE_ORIENTATION_RATIO = 1.5f // |gy| must exceed this x |gx|: rejects diagonal (> ~34 deg) edges
    var EDGE_GAP_PX = 3              // gaps allowed inside one horizontal run
    var EDGE_MIN_LENGTH = 0.35f      // run length / ROI width
    var EDGE_GRAD_FULL = 40f         // gradient giving strength 1.0
    var EDGE_CANDIDATE_MIN = 0.2f    // returned candidates
    var EDGE_MIN_SCORE = 0.35f       // counts as an edge in fusion
    var EDGE_STRONG_SCORE = 0.55f    // "clear step edge"
    var EDGE_TEXTURE_BAND = 6        // rows above/below compared for texture difference

    // Depth (Depth Anything V2 + floor ruler); sample rows around the edge, in normalised y.
    val DEPTH_SAMPLE_OFFSETS = floatArrayOf(0.012f, 0.022f, 0.032f)
    const val DEPTH_SAMPLE_COLUMNS = 7
    var DEPTH_MIN_VALID = 0.6f           // valid samples / all samples
    var DEPTH_MIN_CONFIDENCE = 0.45f
    var DEPTH_JUMP_THRESHOLD = 0.08f     // far side at least 8 % less disparity than the near side (vs floor)
    var DEPTH_CONTINUITY_TOLERANCE = 0.05f // |jump| below this with a floor-like far side = floor continues
    var DEPTH_MAX_AGE_MS = 250L          // older depth frames are UNRELIABLE for this evaluation

    // Ground plane (3D fit in metres through the floor ruler).
    var GROUND_MIN_POINTS = 20
    var GROUND_MAX_RESIDUAL_M = 0.06f
    var GROUND_MAX_SLOPE = 0.35f        // rise/run of the fitted plane
    var GROUND_BREAK_M = 0.07f          // far side this far below the plane = breaks away
    var GROUND_FULL_DROP_M = 0.25f      // drop giving score 1.0
    var GROUND_PLANE_THRESHOLD = 0.3f   // groundPlaneScore that counts as a break

    // Object suppression (existing YOLOX tracks).
    var OBJECT_SUPPRESSION_THRESHOLD = 0.5f
    var OBJECT_PENALTY = 0.6f           // confidence x (1 - penalty x suppression): reduces, never zeroes

    // Fusion.
    var PRESENT_MIN_CONFIDENCE = 0.45f
    var STRONG_MIN_CONFIDENCE = 0.6f
    var STRONG_DEPTH_CONFIDENCE = 0.75f

    // Temporal filter and state machine.
    const val POSSIBLE_WINDOW_FRAMES = 3
    const val POSSIBLE_REQUIRED_FRAMES = 2
    const val CONFIRMED_WINDOW_FRAMES = 5
    const val CONFIRMED_REQUIRED_FRAMES = 3
    var SAFE_RECOVERY_FRAMES = 8
    var HISTORY_MAX_AGE_MS = 1500L      // frames older than this no longer count
    var EVAL_INTERVAL_MS = 66L          // ~15 evaluations per second

    // Path not traversable.
    var WALL_RATIO = 0.85f              // centre vs bottom disparity: a near wall fills both alike
    var WALL_NEAR_M = 0.6f
    var FEATURELESS_STD = 5f            // grey-level std over the ROI

    // Haptics.
    const val POSSIBLE_HAPTIC_INTERVAL_MS = 1500L

    // Barometer (optional; the iQOO I2501 has none).
    var BAROMETER_SHORT_ALPHA = 0.3f
    var BAROMETER_LONG_ALPHA = 0.02f
    var BAROMETER_DESCENT_THRESHOLD = 0.10f // hPa, ~0.8 m of descent
    var BAROMETER_PERSIST_SAMPLES = 5
}
