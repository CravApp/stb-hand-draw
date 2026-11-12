package com.stb.handdraw.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import androidx.camera.core.ImageProcessingUtil
import androidx.camera.core.ImageProxy

/**
 * Copia el fotograma a un bitmap ya creado.
 * RGBA_8888 entra con [ImageProcessingUtil.copyByteBufferToBitmap].
 * El escalado y la rotación se hacen sobre ese bitmap, sin reservar uno nuevo por fotograma.
 */
class FrameWriter {
    private var scratch: Bitmap? = null
    private val matrix = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val canvas = Canvas()

    fun write(image: ImageProxy, dest: Bitmap) {
        val rotation = ((image.imageInfo.rotationDegrees % 360) + 360) % 360
        val direct = image.planes.size == 1 &&
            rotation == 0 &&
            image.width == dest.width &&
            image.height == dest.height
        if (direct) {
            val plane = image.planes[0]
            val buffer = plane.buffer
            buffer.rewind()
            ImageProcessingUtil.copyByteBufferToBitmap(dest, buffer, plane.rowStride)
            return
        }
        val source = sourceBitmap(image)
        val uprightW = if (rotation == 90 || rotation == 270) source.height else source.width
        val uprightH = if (rotation == 90 || rotation == 270) source.width else source.height
        matrix.reset()
        when (rotation) {
            90 -> {
                matrix.postRotate(90f)
                matrix.postTranslate(source.height.toFloat(), 0f)
            }
            180 -> {
                matrix.postRotate(180f)
                matrix.postTranslate(source.width.toFloat(), source.height.toFloat())
            }
            270 -> {
                matrix.postRotate(270f)
                matrix.postTranslate(0f, source.width.toFloat())
            }
        }
        if (uprightW > 0 && uprightH > 0) {
            matrix.postScale(
                dest.width.toFloat() / uprightW,
                dest.height.toFloat() / uprightH,
            )
        }
        canvas.setBitmap(dest)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(source, matrix, paint)
        canvas.setBitmap(null)
        if (source !== scratch) source.recycle()
    }

    private fun sourceBitmap(image: ImageProxy): Bitmap {
        if (image.planes.size == 1) {
            val buffer = scratch(image.width, image.height)
            val plane = image.planes[0]
            plane.buffer.rewind()
            ImageProcessingUtil.copyByteBufferToBitmap(buffer, plane.buffer, plane.rowStride)
            return buffer
        }
        return yuv420ToBitmap(image)
    }

    private fun scratch(width: Int, height: Int): Bitmap {
        val current = scratch
        if (current != null && !current.isRecycled && current.width == width && current.height == height) {
            return current
        }
        current?.recycle()
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { scratch = it }
    }
}
