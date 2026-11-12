package com.stb.handdraw.camera

import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.stb.handdraw.hand.HandEngine
import java.util.concurrent.Executor

/**
 * Vista previa 640x480 y análisis a la resolución pedida (4:3).
 * Se prefiere la cámara frontal: en el decodificador la webcam UVC es id "0", LENS_FACING FRONT.
 * Si el HAL ignora los 320x240, el bitmap se reduce antes de la inferencia.
 * Solo se convierte un fotograma cuando el detector puede aceptarlo.
 */
class CameraController(
    private val owner: LifecycleOwner,
    private val previewView: PreviewView,
    private val analysisExecutor: Executor,
    private val analysisSize: () -> Size,
    private val engineProvider: () -> HandEngine?,
    private val onUnavailable: (kind: Unavailable) -> Unit,
) {
    enum class Unavailable { NONE, BUSY }

    private var provider: ProcessCameraProvider? = null
    private var startGeneration = 0
    private val writer = FrameWriter()
    private var useRgba = true

    fun start() {
        val generation = ++startGeneration
        val context = previewView.context.applicationContext
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (generation != startGeneration) return@addListener
            if (owner.lifecycle.currentState == Lifecycle.State.DESTROYED) return@addListener
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                if (cameraProvider.availableCameraInfos.isEmpty()) {
                    Log.w(TAG, "No hay cámaras")
                    onUnavailable(Unavailable.NONE)
                    return@addListener
                }
                cameraProvider.availableCameraInfos.forEachIndexed { index, info ->
                    val facing = runCatching { info.lensFacing }.getOrNull()
                    Log.i(TAG, "Cámara[$index] lensFacing=$facing")
                }
                bind(cameraProvider)
            } catch (error: Throwable) {
                Log.e(TAG, "No se pudo preparar la cámara", error)
                onUnavailable(Unavailable.BUSY)
            }
        }, ContextCompat.getMainExecutor(previewView.context))
    }

    fun stop() {
        startGeneration += 1
        provider?.unbindAll()
    }

    fun restart() {
        stop()
        start()
    }

    private fun bind(cameraProvider: ProcessCameraProvider) {
        if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        val selector = choose(cameraProvider)
        val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
        val preview = Preview.Builder()
            .setResolutionSelector(resolution(Size(640, 480)))
            .setTargetRotation(rotation)
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val target = analysisSize()
        val analysis = analysisUseCase(target, rotation, useRgba)
        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(owner, selector, preview, analysis)
            Log.i(
                TAG,
                "Cámara enlazada, análisis ${target.width}x${target.height} rgba=$useRgba",
            )
        } catch (error: Throwable) {
            if (useRgba) {
                useRgba = false
                Log.w(TAG, "RGBA_8888 no enlazó; se usa YUV", error)
                bind(cameraProvider)
                return
            }
            Log.e(TAG, "bindToLifecycle falló", error)
            onUnavailable(Unavailable.BUSY)
        }
    }

    private fun analysisUseCase(target: Size, rotation: Int, rgba: Boolean): ImageAnalysis {
        val format = if (rgba) {
            ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
        } else {
            ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888
        }
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolution(target))
            .setTargetRotation(rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(format)
            .build()
        analysis.setAnalyzer(analysisExecutor) { image ->
            val started = SystemClock.elapsedRealtime()
            var borrowed: android.graphics.Bitmap? = null
            try {
                val engine = engineProvider() ?: return@setAnalyzer
                val bitmap = engine.borrowFrame(target.width, target.height) ?: return@setAnalyzer
                borrowed = bitmap
                writer.write(image, bitmap)
                val convertMs = (SystemClock.elapsedRealtime() - started).toInt()
                engine.submitFrame(bitmap, image.imageInfo.timestamp, convertMs)
                borrowed = null
            } catch (error: Throwable) {
                borrowed?.let { engineProvider()?.releaseFrame(it) }
                Log.e(TAG, "No se pudo leer un fotograma", error)
            } finally {
                image.close()
            }
        }
        return analysis
    }

    private fun choose(cameraProvider: ProcessCameraProvider): CameraSelector {
        val infos = cameraProvider.availableCameraInfos
        if (CameraSelector.DEFAULT_FRONT_CAMERA.filter(infos).isNotEmpty()) {
            Log.i(TAG, "Usando cámara frontal")
            return CameraSelector.DEFAULT_FRONT_CAMERA
        }
        if (CameraSelector.DEFAULT_BACK_CAMERA.filter(infos).isNotEmpty()) {
            Log.i(TAG, "No hay cámara frontal; usando la trasera")
            return CameraSelector.DEFAULT_BACK_CAMERA
        }
        val first = infos.first()
        Log.i(TAG, "Usando la primera cámara disponible")
        return CameraSelector.Builder()
            .addCameraFilter { cameras -> cameras.filter { it == first } }
            .build()
    }

    private fun resolution(size: Size): ResolutionSelector = ResolutionSelector.Builder()
        .setAspectRatioStrategy(
            AspectRatioStrategy(AspectRatio.RATIO_4_3, AspectRatioStrategy.FALLBACK_RULE_AUTO),
        )
        .setResolutionStrategy(
            ResolutionStrategy(size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
        )
        .build()

    private companion object {
        const val TAG = "HandDraw"
    }
}
