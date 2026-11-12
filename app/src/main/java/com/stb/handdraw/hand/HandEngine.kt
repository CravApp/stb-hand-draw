package com.stb.handdraw.hand

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.stb.handdraw.draw.NormPoint
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * Hand Landmarker en LIVE_STREAM, un fotograma en vuelo.
 * La vista previa sigue en espejo; este bitmap no se voltea, y la mano se corrige en [userHandFromMediaPipe].
 */
class HandEngine(
    context: Context,
    listener: Listener,
) {
    interface Listener {
        fun onReady(applied: HandConfig, requested: HandConfig)
        fun onFatal(error: Throwable)
        fun onHands(frame: HandFrame)
        fun onGpuGaveUp()
    }

    data class HandFrame(
        val imageWidth: Int,
        val imageHeight: Int,
        val hands: List<DetectedHand>,
        val inferMs: Int,
    )

    data class PerfSample(
        val fps: Int,
        val inferMs: Int,
        val frames: Int,
        val frameW: Int,
        val frameH: Int,
        val tracked: Int,
    )

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val statsLock = Any()

    @Volatile
    private var listenerRef: Listener? = listener

    @Volatile
    private var landmarker: HandLandmarker? = null

    @Volatile
    private var closed = false

    @Volatile
    var applied: HandConfig? = null
        private set

    private val inFlight = AtomicBoolean(false)
    private val inFlightSince = AtomicLong(0L)
    private val generation = AtomicInteger(0)
    private val gpuFallbackTicket = AtomicInteger(Int.MIN_VALUE)
    private var lastTimestampMs = Long.MIN_VALUE

    private var statFrames = 0
    private var statInferSum = 0L
    private var statLastInfer = 0
    private var statFrameW = 0
    private var statFrameH = 0
    private var statTracked = 0
    private var statWindowStart = 0L

    fun start(requested: HandConfig) {
        val ticket = generation.incrementAndGet()
        Thread({
            try {
                val opened = open(requested, ticket)
                synchronized(lock) {
                    if (closed || ticket != generation.get()) {
                        opened.landmarker.close()
                        return@Thread
                    }
                    landmarker?.close()
                    landmarker = opened.landmarker
                    applied = opened.config
                    inFlight.set(false)
                }
                Log.i(
                    TAG,
                    "Hand Landmarker listo modelo=${opened.config.model.logName} " +
                        "delegado=${opened.config.delegate.logName} manos=${opened.config.numHands}",
                )
                mainHandler.post {
                    if (ticket == generation.get()) {
                        listenerRef?.onReady(opened.config, requested)
                    }
                }
            } catch (error: Throwable) {
                Log.e(TAG, "No se pudo crear el Hand Landmarker", error)
                mainHandler.post {
                    if (ticket == generation.get()) listenerRef?.onFatal(error)
                }
            }
        }, "hand-init").start()
    }

    fun canAcceptFrame(): Boolean {
        if (closed || landmarker == null) return false
        if (inFlight.get()) {
            val elapsed = SystemClock.elapsedRealtime() - inFlightSince.get()
            if (elapsed > STUCK_MS) {
                inFlight.set(false)
            } else {
                return false
            }
        }
        return true
    }

    fun submit(bitmap: Bitmap, timestampNs: Long): Boolean {
        if (!canAcceptFrame()) return false
        val timestampMs = nextTimestamp(timestampNs / 1_000_000L)
        val image = try {
            BitmapImageBuilder(bitmap).build()
        } catch (error: Throwable) {
            Log.e(TAG, "No se pudo envolver el fotograma", error)
            return false
        }
        try {
            synchronized(lock) {
                val engine = landmarker
                if (engine == null || closed) {
                    image.close()
                    return false
                }
                inFlight.set(true)
                inFlightSince.set(SystemClock.elapsedRealtime())
                engine.detectAsync(image, timestampMs)
            }
            return true
        } catch (error: Throwable) {
            inFlight.set(false)
            runCatching { image.close() }
            Log.e(TAG, "detectAsync rechazó el fotograma", error)
            return false
        }
    }

    fun takeSample(): PerfSample {
        val now = SystemClock.elapsedRealtime()
        synchronized(statsLock) {
            if (statWindowStart == 0L) statWindowStart = now
            val elapsed = (now - statWindowStart).coerceAtLeast(1L)
            val fps = (statFrames * 1000f / elapsed).roundToInt()
            val infer = if (statFrames == 0) statLastInfer else (statInferSum / statFrames).toInt()
            val sample = PerfSample(fps, infer, statFrames, statFrameW, statFrameH, statTracked)
            statFrames = 0
            statInferSum = 0L
            statWindowStart = now
            return sample
        }
    }

    fun close() {
        listenerRef = null
        generation.incrementAndGet()
        synchronized(lock) {
            closed = true
            landmarker?.close()
            landmarker = null
        }
    }

    private fun open(requested: HandConfig, ticket: Int): Opened {
        var last: Throwable? = null
        for (attempt in creationAttempts(requested)) {
            try {
                return Opened(build(attempt, ticket), attempt)
            } catch (error: Throwable) {
                last = error
                Log.w(
                    TAG,
                    "Fallo al crear modelo=${attempt.model.logName} delegado=${attempt.delegate.logName}",
                    error,
                )
            }
        }
        throw last ?: IllegalStateException("No se pudo crear el Hand Landmarker")
    }

    private fun build(config: HandConfig, ticket: Int): HandLandmarker {
        val delegate = if (config.delegate == DelegateKind.GPU) Delegate.GPU else Delegate.CPU
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath(config.model.assetName)
                    .setDelegate(delegate)
                    .build(),
            )
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(config.numHands.coerceIn(1, 2))
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, image ->
                val width = image.width
                val height = image.height
                try {
                    if (ticket == generation.get()) deliver(result, width, height)
                } finally {
                    runCatching { image.close() }
                    if (ticket == generation.get()) inFlight.set(false)
                }
            }
            .setErrorListener { error ->
                if (ticket == generation.get()) inFlight.set(false)
                Log.e(TAG, "Fallo en la detección de manos", error)
                if (config.delegate == DelegateKind.GPU && gpuFallbackTicket.get() != ticket) {
                    gpuFallbackTicket.set(ticket)
                    val cpu = config.copy(delegate = DelegateKind.CPU)
                    mainHandler.post {
                        if (ticket != generation.get()) return@post
                        listenerRef?.onGpuGaveUp()
                        start(cpu)
                    }
                }
            }
            .build()
        return HandLandmarker.createFromOptions(appContext, options)
    }

    private fun deliver(result: HandLandmarkerResult, width: Int, height: Int) {
        val started = inFlightSince.get()
        val inferMs = (SystemClock.elapsedRealtime() - started).toInt().coerceAtLeast(0)
        val hands = readHands(result)
        record(inferMs, width, height, hands.size)
        val frame = HandFrame(width, height, hands, inferMs)
        mainHandler.post { listenerRef?.onHands(frame) }
    }

    private fun readHands(result: HandLandmarkerResult): List<DetectedHand> {
        val landmarks = result.landmarks()
        val handed = result.handedness()
        val out = ArrayList<DetectedHand>(landmarks.size)
        for (index in landmarks.indices) {
            val points = landmarks[index]
            if (points.size < HAND_LANDMARK_COUNT) continue
            val category = handed.getOrNull(index)?.firstOrNull()
            val name = category?.categoryName()?.ifBlank { category.displayName() }.orEmpty()
            val user = userHandFromMediaPipe(
                categoryName = name,
                categoryIndex = category?.index() ?: -1,
                analysisFrameIsMirrored = ANALYSIS_FRAME_IS_MIRRORED,
            ) ?: continue
            out.add(
                DetectedHand(
                    userHand = user,
                    score = category?.score() ?: 0f,
                    landmarks = points.map { NormPoint(it.x(), it.y()) },
                ),
            )
        }
        return out
    }

    private fun record(inferMs: Int, width: Int, height: Int, tracked: Int) {
        synchronized(statsLock) {
            if (statWindowStart == 0L) statWindowStart = SystemClock.elapsedRealtime()
            statFrames += 1
            statInferSum += inferMs
            statLastInfer = inferMs
            statFrameW = width
            statFrameH = height
            statTracked = tracked
        }
    }

    private fun nextTimestamp(candidate: Long): Long {
        synchronized(lock) {
            val timestamp = if (candidate > lastTimestampMs) candidate else lastTimestampMs + 1L
            lastTimestampMs = timestamp
            return timestamp
        }
    }

    private data class Opened(val landmarker: HandLandmarker, val config: HandConfig)

    private companion object {
        const val TAG = "HandDraw"
        const val STUCK_MS = 2000L
    }
}
