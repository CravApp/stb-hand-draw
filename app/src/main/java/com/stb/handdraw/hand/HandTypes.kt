package com.stb.handdraw.hand

import com.stb.handdraw.draw.NormPoint

/** La mano de la persona, no la etiqueta cruda de MediaPipe. */
enum class UserHand {
    RIGHT,
    LEFT,
    ;

    fun opposite(): UserHand = if (this == RIGHT) LEFT else RIGHT
}

enum class HandModel(val assetName: String, val logName: String) {
    LITE("hand_landmarker_lite.task", "lite"),
    FULL("hand_landmarker_full.task", "full"),
}

enum class DelegateKind(val logName: String) {
    CPU("CPU"),
    GPU("GPU"),
}

enum class AnalysisSize(val width: Int, val height: Int, val label: String) {
    R640(640, 480, "640×480"),
    R320(320, 240, "320×240"),
    ;

    fun next(): AnalysisSize = if (this == R640) R320 else R640
}

data class HandConfig(
    val model: HandModel,
    val delegate: DelegateKind,
    val numHands: Int,
    val analysis: AnalysisSize,
)

/**
 * Si lite no arranca se prueba el full con el mismo delegado. La GPU va antes
 * que la CPU: en este Mali-G31 la GPU sí corre y es más rápida.
 */
fun creationAttempts(requested: HandConfig): List<HandConfig> {
    val out = LinkedHashSet<HandConfig>()
    out.add(requested)
    if (requested.model == HandModel.LITE) {
        out.add(requested.copy(model = HandModel.FULL))
    }
    if (requested.delegate == DelegateKind.GPU) {
        out.add(requested.copy(delegate = DelegateKind.CPU))
        if (requested.model == HandModel.LITE) {
            out.add(requested.copy(model = HandModel.FULL, delegate = DelegateKind.CPU))
        }
    }
    return out.toList()
}

fun loadFailureLine(config: HandConfig, error: Throwable): String {
    val detail = error.message?.lineSequence()?.firstOrNull()?.take(180)?.ifBlank { null }
        ?: error.javaClass.simpleName
    return "${config.model.logName}/${config.delegate.logName}: ${error.javaClass.simpleName}: $detail"
}

data class DetectedHand(
    val userHand: UserHand,
    val score: Float,
    val landmarks: List<NormPoint>,
) {
    val indexTip: NormPoint
        get() = landmarks[INDEX_FINGER_TIP]
}

const val INDEX_FINGER_TIP = 8
const val HAND_LANDMARK_COUNT = 21

/**
 * MediaPipe nombra la mano como si el fotograma fuera un selfie (espejado).
 * El índice 0 del calculador binario es Right y el 1 es Left.
 * Si el bitmap de análisis no está espejado, se invierte para que Diestro sea
 * la mano derecha de la persona.
 */
fun userHandFromMediaPipe(
    categoryName: String,
    categoryIndex: Int,
    analysisFrameIsMirrored: Boolean,
): UserHand? {
    val reported = when {
        categoryName.equals("Right", ignoreCase = true) -> UserHand.RIGHT
        categoryName.equals("Left", ignoreCase = true) -> UserHand.LEFT
        categoryIndex == 0 -> UserHand.RIGHT
        categoryIndex == 1 -> UserHand.LEFT
        else -> null
    } ?: return null
    return if (analysisFrameIsMirrored) reported else reported.opposite()
}

fun selectHand(hands: List<DetectedHand>, preference: UserHand): DetectedHand? =
    hands.filter { it.userHand == preference }.maxByOrNull { it.score }

/** El fotograma que se manda a MediaPipe es el sensor en vertical, sin espejo. */
const val ANALYSIS_FRAME_IS_MIRRORED = false

fun handDrawPerfLine(
    fps: Int,
    inferMs: Int,
    convertMs: Int,
    drawMs: Int,
    frames: Int,
    model: String,
    requested: String,
    delegate: String,
    targetW: Int,
    targetH: Int,
    frameW: Int,
    frameH: Int,
    hands: Int,
    tracked: Int,
): String =
    "fps=$fps inferMs=$inferMs convertMs=$convertMs drawMs=$drawMs frames=$frames " +
        "model=$model requested=$requested delegate=$delegate " +
        "target=${targetW}x$targetH frame=${frameW}x$frameH hands=$hands tracked=$tracked"

fun loadReportLine(requested: HandConfig, loaded: HandConfig, error: String?): String =
    "load requested=${requested.model.logName}/${requested.delegate.logName} " +
        "loaded=${loaded.model.logName}/${loaded.delegate.logName} error=${error ?: "none"}"

const val HAND_DRAW_PERF_TAG = "HandDrawPerf"
