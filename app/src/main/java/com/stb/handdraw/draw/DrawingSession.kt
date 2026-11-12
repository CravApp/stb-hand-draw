package com.stb.handdraw.draw

import kotlin.math.hypot

data class Stroke(
    val color: Int,
    val widthPx: Float,
    val points: MutableList<NormPoint>,
)

/**
 * Trazos en coordenadas normalizadas del fotograma de análisis (sin espejo).
 * La vista los proyecta con letterbox y el espejo de la vista previa.
 */
class DrawingSession(
    private val smoother: PointSmoother = PointSmoother(),
) {
    val finished: MutableList<Stroke> = mutableListOf()
    var active: Stroke? = null
        private set
    var buttonHeld: Boolean = false
        private set
    var cursor: NormPoint? = null
        private set
    var skeleton: List<NormPoint>? = null
        private set

    private var paintColor: Int = 0
    private var paintWidth: Float = 8f

    fun begin(color: Int, widthPx: Float) {
        buttonHeld = true
        paintColor = color
        paintWidth = widthPx
        if (active == null) {
            active = Stroke(color, widthPx, mutableListOf())
        }
        cursor?.let { append(it) }
    }

    fun end() {
        buttonHeld = false
        commitActive()
    }

    fun onHand(tip: NormPoint?, landmarks: List<NormPoint>?) {
        if (tip == null) {
            smoother.reset()
            cursor = null
            skeleton = null
            commitActive()
            return
        }
        val smoothed = smoother.apply(tip.x, tip.y)
        cursor = NormPoint(smoothed.first, smoothed.second)
        skeleton = landmarks
        if (buttonHeld) {
            if (active == null) {
                active = Stroke(paintColor, paintWidth, mutableListOf())
            }
            append(cursor!!)
        }
    }

    fun undo() {
        if (active != null) {
            active = null
            return
        }
        if (finished.isNotEmpty()) finished.removeAt(finished.lastIndex)
    }

    fun clear() {
        finished.clear()
        active = null
    }

    fun hasInk(): Boolean {
        if (finished.isNotEmpty()) return true
        return (active?.points?.size ?: 0) > 0
    }

    private fun append(point: NormPoint) {
        val stroke = active ?: return
        if (stroke.points.size >= MAX_POINTS) return
        val last = stroke.points.lastOrNull()
        if (last != null && hypot(point.x - last.x, point.y - last.y) < MIN_DIST) return
        stroke.points.add(point)
    }

    private fun commitActive() {
        val stroke = active ?: return
        active = null
        if (stroke.points.size >= 2) finished.add(stroke)
    }

    private companion object {
        const val MIN_DIST = 0.004f
        const val MAX_POINTS = 4000
    }
}

class PointSmoother(
    private val alpha: Float = 0.55f,
    private val jump: Float = 0.20f,
    private val fast: Float = 0.85f,
) {
    private var x = 0f
    private var y = 0f
    private var has = false

    fun reset() {
        has = false
    }

    fun apply(rawX: Float, rawY: Float): Pair<Float, Float> {
        if (!has) {
            x = rawX
            y = rawY
            has = true
            return x to y
        }
        val distance = hypot(rawX - x, rawY - y)
        val blend = if (distance > jump) fast else alpha
        x += (rawX - x) * blend
        y += (rawY - y) * blend
        return x to y
    }
}
