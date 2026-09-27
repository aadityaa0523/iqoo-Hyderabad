package app.nadaka

/**
 * Layer 0 helpers for the detector: what the 80 COCO classes mean for walking, how sure the model must be per
 * class, and how a portrait frame is fitted into the square model input. Pure logic, unit-tested.
 */

/** COCO classes collapsed into what matters when walking. Priority, tracking and haptics use this. */
enum class Category { PERSON, ANIMAL, VEHICLE, SEAT, FURNITURE, OTHER }

fun categoryOf(label: String): Category = when (label) {
    "person" -> Category.PERSON
    "dog", "cat", "cow", "horse", "sheep", "elephant", "bear", "zebra", "giraffe", "bird" -> Category.ANIMAL
    "car", "truck", "bus", "motorcycle", "bicycle", "train", "boat", "airplane" -> Category.VEHICLE
    "chair", "couch", "bench", "bed", "toilet" -> Category.SEAT
    "dining table", "tv", "laptop", "refrigerator", "oven", "microwave", "sink" -> Category.FURNITURE
    else -> Category.OTHER
}

/** Classes that don't matter for walking: dropped at the detector (they only add noise and false alerts). */
private val IGNORED = setOf(
    "toothbrush", "hair drier", "frisbee", "skis", "snowboard", "surfboard", "kite", "baseball bat", "baseball glove",
    "tennis racket", "wine glass", "fork", "knife", "spoon", "donut", "pizza", "hot dog", "broccoli", "carrot",
    "sandwich", "orange", "banana", "apple", "cake", "scissors", "teddy bear", "tie",
)

/** Classes that often misfire indoors (a table top read as a bed, a dark screen as a TV): need more confidence. */
private val STRICT = mapOf(
    "bed" to 0.6f, "toilet" to 0.6f, "refrigerator" to 0.6f, "oven" to 0.6f, "microwave" to 0.6f, "sink" to 0.6f,
    "couch" to 0.55f, "tv" to 0.55f, "airplane" to 0.7f, "boat" to 0.65f, "elephant" to 0.7f, "bear" to 0.7f,
    "zebra" to 0.7f, "giraffe" to 0.7f,
)

/** Keep this detection? Safety classes use the base threshold; noisy ones need more; irrelevant ones never pass. */
fun keepDetection(label: String, score: Float, base: Float): Boolean =
    label !in IGNORED && score >= maxOf(base, STRICT[label] ?: 0f)

/**
 * Fit a w x h frame into the size x size model input WITHOUT stretching (as YOLO was trained): scale to fit, pad the
 * rest. Returns (scale, padX, padY) in input pixels. The old code stretched a 480x640 portrait to 640x640 (33 % wider),
 * which distorts every object's shape.
 */
fun letterbox(w: Int, h: Int, size: Int): Triple<Float, Float, Float> {
    val s = size.toFloat() / maxOf(w, h)
    return Triple(s, (size - w * s) / 2f, (size - h * s) / 2f)
}

/** Model-input pixel coordinate -> 0..1 of the original frame. */
fun unletterbox(v: Float, pad: Float, scale: Float, frameLen: Int): Float = ((v - pad) / scale / frameLen).coerceIn(0f, 1f)
