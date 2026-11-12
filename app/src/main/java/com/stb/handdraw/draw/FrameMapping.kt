package com.stb.handdraw.draw

data class NormPoint(val x: Float, val y: Float)

data class ViewPoint(val x: Float, val y: Float)

/**
 * Proyección FIT_CENTER (letterbox) de la imagen de análisis sobre la vista.
 * Con espejo, x se invierte antes de proyectar para coincidir con un PreviewView
 * scaleX = -1 cuando el letterbox es simétrico.
 */
data class FrameMapping(
    val viewWidth: Float,
    val viewHeight: Float,
    val imageWidth: Float,
    val imageHeight: Float,
    val mirror: Boolean,
) {
    val scale: Float
    val offsetX: Float
    val offsetY: Float

    init {
        if (imageWidth <= 0f || imageHeight <= 0f || viewWidth <= 0f || viewHeight <= 0f) {
            scale = 1f
            offsetX = 0f
            offsetY = 0f
        } else {
            val fitted = minOf(viewWidth / imageWidth, viewHeight / imageHeight)
            scale = fitted
            offsetX = (viewWidth - imageWidth * fitted) / 2f
            offsetY = (viewHeight - imageHeight * fitted) / 2f
        }
    }

    fun toView(normX: Float, normY: Float): ViewPoint {
        val x = if (mirror) 1f - normX else normX
        return ViewPoint(
            x = offsetX + x * imageWidth * scale,
            y = offsetY + normY * imageHeight * scale,
        )
    }
}

/** Encaja el fotograma dentro del tamaño de análisis pedido, sin deformar. */
fun fittedSize(srcW: Int, srcH: Int, maxW: Int, maxH: Int): Pair<Int, Int> {
    if (srcW <= 0 || srcH <= 0 || maxW <= 0 || maxH <= 0) return 1 to 1
    if (srcW <= maxW && srcH <= maxH) return srcW to srcH
    val scale = minOf(maxW.toFloat() / srcW, maxH.toFloat() / srcH)
    val width = (srcW * scale).toInt().coerceAtLeast(1)
    val height = (srcH * scale).toInt().coerceAtLeast(1)
    return width to height
}
