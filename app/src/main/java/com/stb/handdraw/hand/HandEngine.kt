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
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * Hand Landmarker en LIVE_STREAM.
 * Hay dos bitmaps propios: uno puede convertirse mientras el otro está en la inferencia.
 * Solo hay un detectAsync a la vez.
 */
class HandEngine(
    context: Context,
    listener: Listener,
) {
    interface Listener {
        fun onReady(applied: HandConfig, requested: HandConfig, loadError: String?)
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
        val convertMs: Int,
        val frames: Int,
        val frameW: Int,
        val frameH: Int,
        val tracked: Int,
    )

    private class HeldFrame(
        val bitmap: Bitmap,
        val convertMs: Int,
        val timestampNs: Long,
        val poolGen: Int,
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

    private var poolGen = 0
    private var poolW = 0
    private var poolH = 0
    private val freeFrames = ArrayDeque<Bitmap>(2)
    private var inflightFrame: Bitmap? = null
    private var inflightConvertMs = 0
    private var pendingFrame: HeldFrame? = null

    private var statFrames = 0
    private var statInferSum = 0L
    private var statConvertSum = 0L
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
                    inflightFrame = null
                    pendingFrame?.bitmap?.let { recycleIfCurrent(it, poolGen) }
                    pendingFrame = null
                }
                val error = opened.loadError
                Log.i(
                    HAND_DRAW_PERF_TAG,
                    loadReportLine(requested, opened.config, error),
                )
                mainHandler.post {
                    if (ticket == generation.get()) {
                        listenerRef?.onReady(opened.config, requested, error)
                    }
                }
            } catch (error: Throwable) {
                Log.e(HAND_DRAW_PERF_TAG, "load fail ${loadFailureLine(requested, error)}", error)
                mainHandler.post {
                    if (ticket == generation.get()) listenerRef?.onFatal(error)
                }
            }
        }, "hand-init").start()
    }

    /** Bitmap reutilizable del tamaño de análisis. Null si los dos están ocupados. */
    fun borrowFrame(width: Int, height: Int): Bitmap? {
        if (width <= 0 || height <= 0) return null
        synchronized(lock) {
            if (closed) return null
            if (width != poolW || height != poolH) {
                poolGen += 1
                poolW = width
                poolH = height
                freeFrames.clear()
            }
            val ready = if (freeFrames.isEmpty()) null else freeFrames.remove()
            if (ready != null) return ready
            val busy = (if (inflightFrame != null) 1 else 0) + (if (pendingFrame != null) 1 else 0)
            if (busy >= 2) return null
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
    }

    fun releaseFrame(bitmap: Bitmap) {
        synchronized(lock) {
            recycleIfCurrent(bitmap, poolGen)
        }
    }

    /**
     * Encola el bitmap ya relleno. Si la inferencia está libre, sale ya.
     * Si no, queda pendiente y sustituye al pendiente anterior.
     */
    fun submitFrame(bitmap: Bitmap, timestampNs: Long, convertMs: Int) {
        val launch = synchronized(lock) {
            if (landmarker == null || closed) {
                recycleIfCurrent(bitmap, poolGen)
                return
            }
            if (inFlight.get()) {
                pendingFrame?.let { recycleIfCurrent(it.bitmap, it.poolGen) }
                pendingFrame = HeldFrame(bitmap, convertMs, timestampNs, poolGen)
                return
            }
            claim(bitmap, convertMs, timestampNs)
        }
        dispatch(launch)
    }

    fun takeSample(): PerfSample {
        val now = SystemClock.elapsedRealtime()
        synchronized(statsLock) {
            if (statWindowStart == 0L) statWindowStart = now
            val elapsed = (now - statWindowStart).coerceAtLeast(1L)
            val frames = statFrames
            val fps = (frames * 1000f / elapsed).roundToInt()
            val infer = if (frames == 0) 0 else (statInferSum / frames).toInt()
            val convert = if (frames == 0) 0 else (statConvertSum / frames).toInt()
            val sample = PerfSample(fps, infer, convert, frames, statFrameW, statFrameH, statTracked)
            statFrames = 0
            statInferSum = 0L
            statConvertSum = 0L
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
            freeFrames.forEach { it.recycle() }
            freeFrames.clear()
            inflightFrame = null
            pendingFrame = null
        }
    }

    private fun claim(bitmap: Bitmap, convertMs: Int, timestampNs: Long): Launch {
        inflightFrame = bitmap
        inflightConvertMs = convertMs
        inFlight.set(true)
        inFlightSince.set(SystemClock.elapsedRealtime())
        val engine = landmarker ?: error("sin landmarker")
        return Launch(engine, bitmap, timestampNs, generation.get())
    }

    private fun dispatch(launch: Launch) {
        val timestampMs = synchronized(lock) { nextTimestampLocked(launch.timestampNs / 1_000_000L) }
        val image = try {
            BitmapImageBuilder(launch.bitmap).build()
        } catch (error: Throwable) {
            Log.e(HAND_DRAW_PERF_TAG, "No se pudo envolver el fotograma: ${error.message}", error)
            synchronized(lock) { finishInflight(launch.ticket) }
            return
        }
        try {
            launch.engine.detectAsync(image, timestampMs)
        } catch (error: Throwable) {
            Log.e(HAND_DRAW_PERF_TAG, "detectAsync rechazó el fotograma: ${error.message}", error)
            runCatching { image.close() }
            synchronized(lock) { finishInflight(launch.ticket) }
        }
    }

    private fun finishInflight(ticket: Int) {
        if (ticket != generation.get()) {
            inflightFrame?.recycle()
            inflightFrame = null
            return
        }
        inflightFrame?.let { recycleIfCurrent(it, poolGen) }
        inflightFrame = null
        inFlight.set(false)
    }

    private fun recycleIfCurrent(bitmap: Bitmap, gen: Int) {
        if (gen != poolGen || bitmap.width != poolW || bitmap.height != poolH || bitmap.isRecycled) {
            if (!bitmap.isRecycled) bitmap.recycle()
            return
        }
        freeFrames.addLast(bitmap)
    }

    private fun open(requested: HandConfig, ticket: Int): Opened {
        val errors = ArrayList<String>(4)
        var last: Throwable? = null
        for (attempt in creationAttempts(requested)) {
            try {
                val created = build(attempt, ticket)
                val note = errors.joinToString(" | ").ifBlank { null }
                return Opened(created, attempt, note)
            } catch (error: Throwable) {
                last = error
                val line = loadFailureLine(attempt, error)
                errors += line
                Log.e(HAND_DRAW_PERF_TAG, "load fail $line", error)
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
                val inferMs = (SystemClock.elapsedRealtime() - inFlightSince.get()).toInt().coerceAtLeast(0)
                val convertMs = synchronized(lock) { inflightConvertMs }
                try {
                    if (ticket == generation.get()) deliver(result, width, height, inferMs, convertMs)
                } finally {
                    runCatching { image.close() }
                    var follow: Launch? = null
                    synchronized(lock) {
                        if (ticket == generation.get()) {
                            finishInflight(ticket)
                            val next = pendingFrame
                            pendingFrame = null
                            if (next != null && landmarker != null && !closed) {
                                follow = claim(next.bitmap, next.convertMs, next.timestampNs)
                            }
                        } else {
                            finishInflight(ticket)
                        }
                    }
                    follow?.let { dispatch(it) }
                }
            }
            .setErrorListener { error ->
                Log.e(HAND_DRAW_PERF_TAG, "inferencia ${config.model.logName}/${config.delegate.logName}: ${error.message}", error)
                synchronized(lock) {
                    if (ticket == generation.get()) {
                        finishInflight(ticket)
                        pendingFrame?.let { recycleIfCurrent(it.bitmap, it.poolGen) }
                        pendingFrame = null
                    }
                }
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

    private fun deliver(
        result: HandLandmarkerResult,
        width: Int,
        height: Int,
        inferMs: Int,
        convertMs: Int,
    ) {
        val hands = readHands(result)
        record(inferMs, convertMs, width, height, hands.size)
        mainHandler.post {
            listenerRef?.onHands(HandFrame(width, height, hands, inferMs))
        }
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

    private fun record(inferMs: Int, convertMs: Int, width: Int, height: Int, tracked: Int) {
        synchronized(statsLock) {
            if (statWindowStart == 0L) statWindowStart = SystemClock.elapsedRealtime()
            statFrames += 1
            statInferSum += inferMs
            statConvertSum += convertMs
            statFrameW = width
            statFrameH = height
            statTracked = tracked
        }
    }

    private fun nextTimestampLocked(candidate: Long): Long {
        val timestamp = if (candidate > lastTimestampMs) candidate else lastTimestampMs + 1L
        lastTimestampMs = timestamp
        return timestamp
    }

    private data class Opened(
        val landmarker: HandLandmarker,
        val config: HandConfig,
        val loadError: String?,
    )

    private data class Launch(
        val engine: HandLandmarker,
        val bitmap: Bitmap,
        val timestampNs: Long,
        val ticket: Int,
    )
}
