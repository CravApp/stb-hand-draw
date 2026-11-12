package com.stb.handdraw.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProcessingUtil
import androidx.camera.core.ImageProxy
import com.stb.handdraw.draw.fittedSize
import kotlin.math.roundToInt

/**
 * Convierte el fotograma a un bitmap en vertical de pantalla.
 * CameraX 1.3 no tiene ImageProxy.toBitmap(); se usa libyuv y, si falla, un conversor en Kotlin.
 * La rotación del sensor se aplica aquí para que los landmarks coincidan con la vista previa.
 */
fun ImageProxy.toBitmapCompat(): Bitmap {
    val upright = try {
        val raw = ImageProcessingUtil.convertYUVToBitmap(this)
        val software = if (raw.config == Bitmap.Config.HARDWARE) {
            val copy = raw.copy(Bitmap.Config.ARGB_8888, false)
            raw.recycle()
            copy
        } else {
            raw
        }
        rotateBitmap(software, imageInfo.rotationDegrees)
    } catch (error: Throwable) {
        yuv420ToBitmap(this)
    }
    return upright
}

fun limitBitmap(source: Bitmap, maxW: Int, maxH: Int): Bitmap {
    val (width, height) = fittedSize(source.width, source.height, maxW, maxH)
    if (width == source.width && height == source.height) return source
    val scaled = Bitmap.createScaledBitmap(source, width, height, true)
    if (scaled != source) source.recycle()
    return scaled
}

internal fun yuv420ToBitmap(image: ImageProxy): Bitmap {
    val width = image.width
    val height = image.height
    require(image.planes.size >= 3) { "El fotograma no está en YUV" }
    val yPlane = image.planes[0]
    val uPlane = image.planes[1]
    val vPlane = image.planes[2]
    val yBuffer = yPlane.buffer
    val uBuffer = uPlane.buffer
    val vBuffer = vPlane.buffer
    val argb = IntArray(width * height)
    for (row in 0 until height) {
        for (col in 0 until width) {
            val y = byteAt(yBuffer, row * yPlane.rowStride + col * yPlane.pixelStride)
            val uvRow = row / 2
            val uvCol = col / 2
            val u = byteAt(uBuffer, uvRow * uPlane.rowStride + uvCol * uPlane.pixelStride) - 128
            val v = byteAt(vBuffer, uvRow * vPlane.rowStride + uvCol * vPlane.pixelStride) - 128
            val r = (y + 1.370705f * v).roundToInt().coerceIn(0, 255)
            val g = (y - 0.337633f * u - 0.698001f * v).roundToInt().coerceIn(0, 255)
            val b = (y + 1.732446f * u).roundToInt().coerceIn(0, 255)
            argb[row * width + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
    val bitmap = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
    return rotateBitmap(bitmap, image.imageInfo.rotationDegrees)
}

private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
    val normalized = ((degrees % 360) + 360) % 360
    if (normalized == 0) return bitmap
    val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    if (rotated != bitmap) bitmap.recycle()
    return rotated
}

private fun byteAt(buffer: java.nio.ByteBuffer, index: Int): Int {
    val absolute = buffer.position() + index
    if (absolute < 0 || absolute >= buffer.limit()) return 0
    return buffer.get(absolute).toInt() and 0xFF
}
