package com.stb.handdraw

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.view.KeyEvent
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import com.stb.handdraw.camera.CameraController
import com.stb.handdraw.databinding.ActivityMainBinding
import com.stb.handdraw.draw.DrawingSession
import com.stb.handdraw.hand.AnalysisSize
import com.stb.handdraw.hand.DelegateKind
import com.stb.handdraw.hand.HAND_DRAW_PERF_TAG
import com.stb.handdraw.hand.HandConfig
import com.stb.handdraw.hand.HandEngine
import com.stb.handdraw.hand.HandModel
import com.stb.handdraw.hand.UserHand
import com.stb.handdraw.hand.handDrawPerfLine
import com.stb.handdraw.hand.loadReportLine
import com.stb.handdraw.hand.selectHand
import com.stb.handdraw.input.KeyResult
import com.stb.handdraw.input.RemoteCommand
import com.stb.handdraw.input.RemoteDecoder
import com.stb.handdraw.input.ScreenMode
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), HandEngine.Listener {
    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val cameraExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "camera-analysis").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val perfHandler = Handler(Looper.getMainLooper())
    private val decoder = RemoteDecoder()
    private val session = DrawingSession()

    private var hand = UserHand.RIGHT
    private var colorIndex = 2
    private var thicknessIndex = 1
    private var model = HandModel.LITE
    private var delegate = DelegateKind.GPU
    private var numHands = 1
    private var analysis = AnalysisSize.R320
    private var loaded = HandConfig(HandModel.LITE, DelegateKind.GPU, 1, AnalysisSize.R320)
    private var mirror = true
    private var settingsOpen = false
    private var settingIndex = 0
    private var modelReady = false
    private var askedPermission = false
    private var notice: String? = null
    private var handEngine: HandEngine? = null
    private var cameraController: CameraController? = null

    private val colors = intArrayOf(
        0xFFFFFFFF.toInt(),
        0xFFFFE14A.toInt(),
        0xFF3DDCFF.toInt(),
        0xFF7DFF6A.toInt(),
        0xFFFF5A6A.toInt(),
        0xFFFF7AD9.toInt(),
    )
    private val colorNames = arrayOf("blanco", "amarillo", "cian", "verde", "rojo", "rosa")
    private val thicknessDp = floatArrayOf(4f, 8f, 14f, 22f)
    private val thicknessNames = arrayOf("fino", "medio", "grueso", "muy grueso")

    private val perfTick = object : Runnable {
        override fun run() {
            if (!isDestroyed) logPerf()
            perfHandler.postDelayed(this, 1000L)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        askedPermission = true
        if (granted) startPipeline() else showPermissionDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insets = WindowInsetsControllerCompat(window, binding.root)
        insets.hide(WindowInsetsCompat.Type.systemBars())
        insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        binding.preview.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        binding.preview.scaleType = PreviewView.ScaleType.FIT_CENTER
        loadPrefs()
        applyMirror()
        binding.confirmYes.setOnClickListener {
            session.clear()
            hideConfirm()
            pushOverlay()
            refreshHud()
        }
        binding.confirmNo.setOnClickListener { hideConfirm() }
        refreshHud()
        binding.root.requestFocus()
        if (hasCameraPermission()) startPipeline() else showNeedPermission()
    }

    override fun onStart() {
        super.onStart()
        perfHandler.removeCallbacks(perfTick)
        perfHandler.post(perfTick)
        maybeStartCamera()
    }

    override fun onStop() {
        perfHandler.removeCallbacks(perfTick)
        cameraController?.stop()
        super.onStop()
    }

    override fun onDestroy() {
        perfHandler.removeCallbacksAndMessages(null)
        cameraController?.stop()
        cameraController = null
        handEngine?.close()
        handEngine = null
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (binding.messagePanel.visibility == View.VISIBLE) {
            return super.dispatchKeyEvent(event)
        }
        if (binding.confirmPanel.visibility == View.VISIBLE) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_DOWN &&
                    (event.isLongPress || event.repeatCount >= 6)
                ) {
                    finish()
                } else if (event.action == KeyEvent.ACTION_UP && event.repeatCount == 0) {
                    hideConfirm()
                }
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        val mode = if (settingsOpen) ScreenMode.SETTINGS else ScreenMode.CAMERA
        when (
            val result = decoder.onKey(
                mode = mode,
                action = event.action,
                keyCode = event.keyCode,
                repeatCount = event.repeatCount,
                longPress = event.isLongPress,
            )
        ) {
            KeyResult.Pass -> return super.dispatchKeyEvent(event)
            is KeyResult.Consume -> {
                result.command?.let { apply(it) }
                return true
            }
        }
    }

    override fun onReady(applied: HandConfig, requested: HandConfig, loadError: String?) {
        if (isDestroyed) return
        loaded = applied
        modelReady = true
        Log.i(HAND_DRAW_PERF_TAG, loadReportLine(currentConfig(), applied, loadError))
        when {
            loadError != null && requested.model != applied.model ->
                showNotice(getString(R.string.notice_model_fallback))
            loadError != null && requested.delegate != applied.delegate ->
                showNotice(getString(R.string.notice_gpu_fallback))
        }
        binding.statusText.text = getString(R.string.hud_searching)
        refreshHud()
        maybeStartCamera()
    }

    override fun onGpuGaveUp() {
        if (isDestroyed) return
        Log.i(HAND_DRAW_PERF_TAG, "load event=gpu_runtime_error fallback=CPU")
        showNotice(getString(R.string.notice_gpu_fallback))
    }

    override fun onFatal(error: Throwable) {
        if (isDestroyed) return
        modelReady = false
        showPanel(getString(R.string.msg_model_error), getString(R.string.btn_retry)) {
            handEngine?.close()
            handEngine = null
            startPipeline()
        }
    }

    override fun onHands(frame: HandEngine.HandFrame) {
        if (isDestroyed) return
        val selected = selectHand(frame.hands, hand)
        session.onHand(selected?.indexTip, selected?.landmarks)
        binding.overlay.imageWidth = frame.imageWidth
        binding.overlay.imageHeight = frame.imageHeight
        binding.overlay.otherTips = frame.hands
            .filter { it !== selected }
            .map { it.indexTip }
        pushOverlay()
        binding.inferText.text = getString(R.string.hud_infer, frame.inferMs)
        binding.statusText.text = when {
            session.buttonHeld && selected != null -> getString(R.string.hud_drawing)
            selected != null -> getString(
                R.string.hud_tracking,
                if (selected.userHand == UserHand.RIGHT) {
                    getString(R.string.detected_right)
                } else {
                    getString(R.string.detected_left)
                },
            )
            frame.hands.isNotEmpty() -> getString(R.string.hud_other_hand)
            else -> getString(R.string.hud_searching)
        }
    }

    private fun apply(command: RemoteCommand) {
        when (command) {
            RemoteCommand.StrokeStart -> session.begin(colors[colorIndex], thicknessPx())
            RemoteCommand.StrokeEnd -> session.end()
            RemoteCommand.ToggleHand -> setHand(hand.opposite())
            is RemoteCommand.SetHand -> setHand(command.hand)
            RemoteCommand.CycleColor -> cycleColor(1)
            is RemoteCommand.CycleColorBy -> cycleColor(command.delta)
            RemoteCommand.CycleThickness -> cycleThickness(1)
            is RemoteCommand.CycleThicknessBy -> cycleThickness(command.delta)
            RemoteCommand.Undo -> session.undo()
            RemoteCommand.ClearImmediate -> session.clear()
            RemoteCommand.AskClearOrExit -> {
                session.end()
                if (session.hasInk()) showConfirm() else finish()
            }
            RemoteCommand.Exit -> finish()
            RemoteCommand.OpenSettings -> {
                session.end()
                settingsOpen = true
                binding.settingsPanel.visibility = View.VISIBLE
            }
            RemoteCommand.CloseSettings -> {
                settingsOpen = false
                binding.settingsPanel.visibility = View.GONE
            }
            RemoteCommand.SettingsUp -> {
                settingIndex = (settingIndex + SETTING_COUNT - 1) % SETTING_COUNT
            }
            RemoteCommand.SettingsDown -> {
                settingIndex = (settingIndex + 1) % SETTING_COUNT
            }
            is RemoteCommand.SettingsNudge -> nudge(command.delta)
            RemoteCommand.CycleModel -> {
                model = if (model == HandModel.LITE) HandModel.FULL else HandModel.LITE
                savePrefs()
                restartEngine()
            }
            RemoteCommand.ToggleDelegate -> {
                delegate = if (delegate == DelegateKind.CPU) DelegateKind.GPU else DelegateKind.CPU
                savePrefs()
                restartEngine()
            }
            RemoteCommand.ToggleResolution -> {
                analysis = analysis.next()
                savePrefs()
                cameraController?.restart()
            }
            RemoteCommand.ToggleNumHands -> {
                numHands = if (numHands == 1) 2 else 1
                savePrefs()
                restartEngine()
            }
            RemoteCommand.ToggleMirror -> {
                mirror = !mirror
                savePrefs()
                applyMirror()
            }
        }
        if (settingsOpen) scrollToSetting()
        pushOverlay()
        refreshHud()
    }

    private fun nudge(delta: Int) {
        if (delta == 0) return
        when (settingIndex) {
            0 -> setHand(hand.opposite())
            1 -> cycleColor(delta)
            2 -> cycleThickness(delta)
            3 -> apply(RemoteCommand.CycleModel)
            4 -> apply(RemoteCommand.ToggleNumHands)
            5 -> apply(RemoteCommand.ToggleDelegate)
            6 -> apply(RemoteCommand.ToggleResolution)
            else -> apply(RemoteCommand.ToggleMirror)
        }
    }

    private fun setHand(value: UserHand) {
        hand = value
        savePrefs()
    }

    private fun cycleColor(delta: Int) {
        colorIndex = (colorIndex + delta).floorMod(colors.size)
        savePrefs()
    }

    private fun cycleThickness(delta: Int) {
        thicknessIndex = (thicknessIndex + delta).floorMod(thicknessDp.size)
        savePrefs()
    }

    private fun startPipeline() {
        if (!hasCameraPermission()) {
            showNeedPermission()
            return
        }
        hidePanel()
        binding.statusText.text = getString(R.string.hud_loading)
        restartEngine()
    }

    private fun restartEngine() {
        modelReady = false
        binding.statusText.text = getString(R.string.hud_loading)
        val engine = handEngine ?: HandEngine(this, this).also { handEngine = it }
        engine.start(currentConfig())
    }

    private fun maybeStartCamera() {
        if (!modelReady || !hasCameraPermission()) return
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        val controller = cameraController ?: CameraController(
            owner = this,
            previewView = binding.preview,
            analysisExecutor = cameraExecutor,
            analysisSize = { Size(analysis.width, analysis.height) },
            engineProvider = { handEngine },
            onUnavailable = { kind ->
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    val message = if (kind == CameraController.Unavailable.NONE) {
                        R.string.msg_no_camera
                    } else {
                        R.string.msg_camera_error
                    }
                    showPanel(getString(message), getString(R.string.btn_retry)) {
                        startPipeline()
                    }
                }
            },
        ).also { cameraController = it }
        controller.start()
    }

    private fun currentConfig(): HandConfig = HandConfig(model, delegate, numHands, analysis)

    private fun pushOverlay() {
        binding.overlay.mirror = mirror
        binding.overlay.drawing = session.buttonHeld
        binding.overlay.cursor = session.cursor
        binding.overlay.cursorColor = colors[colorIndex]
        binding.overlay.skeleton = session.skeleton
        binding.overlay.finished = session.finished
        binding.overlay.active = session.active
        binding.overlay.refresh()
    }

    private fun applyMirror() {
        binding.preview.scaleX = if (mirror) -1f else 1f
        binding.overlay.mirror = mirror
    }

    private fun refreshHud() {
        val configLine = getString(
            R.string.hud_config,
            if (hand == UserHand.RIGHT) getString(R.string.hand_right) else getString(R.string.hand_left),
            loaded.model.logName,
            loaded.delegate.logName,
            analysis.label,
            numHands,
        )
        binding.detailText.text = notice?.let { "$it\n$configLine" } ?: configLine
        bindSettings()
    }

    private fun bindSettings() {
        val rows = listOf(
            binding.settingHand,
            binding.settingColor,
            binding.settingThickness,
            binding.settingModel,
            binding.settingHands,
            binding.settingDelegate,
            binding.settingResolution,
            binding.settingMirror,
        )
        val labels = listOf(
            getString(R.string.set_hand, if (hand == UserHand.RIGHT) getString(R.string.hand_right) else getString(R.string.hand_left)),
            getString(R.string.set_color, colorNames[colorIndex]),
            getString(R.string.set_thickness, thicknessNames[thicknessIndex]),
            getString(R.string.set_model, model.logName),
            getString(R.string.set_hands, numHands),
            getString(R.string.set_delegate, delegate.logName),
            getString(R.string.set_resolution, analysis.label),
            getString(R.string.set_mirror, getString(if (mirror) R.string.mirror_on else R.string.mirror_off)),
        )
        rows.forEachIndexed { index, view ->
            view.text = labels[index]
            view.setBackgroundColor(if (settingsOpen && index == settingIndex) 0x663DDCFF else 0x00000000)
        }
    }

    private fun scrollToSetting() {
        val rows = listOf(
            binding.settingHand,
            binding.settingColor,
            binding.settingThickness,
            binding.settingModel,
            binding.settingHands,
            binding.settingDelegate,
            binding.settingResolution,
            binding.settingMirror,
        )
        rows.getOrNull(settingIndex)?.requestRectangleOnScreen(android.graphics.Rect(), false)
    }

    private fun logPerf() {
        val sample = handEngine?.takeSample()
        val fps = sample?.fps ?: 0
        if (sample != null && sample.frames > 0) {
            binding.fpsText.text = getString(R.string.hud_fps, fps)
        } else if (sample == null) {
            binding.fpsText.text = getString(R.string.hud_fps_unknown)
        } else {
            binding.fpsText.text = getString(R.string.hud_fps, 0)
        }
        Log.i(
            HAND_DRAW_PERF_TAG,
            handDrawPerfLine(
                fps = fps,
                inferMs = sample?.inferMs ?: 0,
                convertMs = sample?.convertMs ?: 0,
                drawMs = binding.overlay.lastDrawMs,
                frames = sample?.frames ?: 0,
                model = loaded.model.logName,
                requested = model.logName,
                delegate = loaded.delegate.logName,
                targetW = analysis.width,
                targetH = analysis.height,
                frameW = sample?.frameW ?: 0,
                frameH = sample?.frameH ?: 0,
                hands = numHands,
                tracked = sample?.tracked ?: 0,
            ),
        )
    }

    private fun thicknessPx(): Float = thicknessDp[thicknessIndex] * resources.displayMetrics.density

    private fun loadPrefs() {
        hand = if (prefs.getString(KEY_HAND, "right") == "left") UserHand.LEFT else UserHand.RIGHT
        colorIndex = prefs.getInt(KEY_COLOR, 2).coerceIn(0, colors.lastIndex)
        thicknessIndex = prefs.getInt(KEY_THICKNESS, 1).coerceIn(0, thicknessDp.lastIndex)
        numHands = prefs.getInt(KEY_HANDS, 1).coerceIn(1, 2)
        mirror = prefs.getBoolean(KEY_MIRROR, true)
        // La 1.x guardaba el modelo de respaldo (full/CPU/640) como si fuera la elección.
        if (prefs.getInt(KEY_VERSION, 0) < PREFS_VERSION) {
            model = HandModel.LITE
            delegate = DelegateKind.GPU
            analysis = AnalysisSize.R320
            prefs.edit()
                .putInt(KEY_VERSION, PREFS_VERSION)
                .putString(KEY_MODEL, model.logName)
                .putString(KEY_DELEGATE, delegate.logName.lowercase())
                .putString(KEY_RES, "320")
                .apply()
        } else {
            model = if (prefs.getString(KEY_MODEL, "lite") == "full") HandModel.FULL else HandModel.LITE
            delegate = if (prefs.getString(KEY_DELEGATE, "gpu") == "cpu") DelegateKind.CPU else DelegateKind.GPU
            analysis = if (prefs.getString(KEY_RES, "320") == "640") AnalysisSize.R640 else AnalysisSize.R320
        }
        loaded = currentConfig()
    }

    private fun savePrefs() {
        prefs.edit()
            .putString(KEY_HAND, if (hand == UserHand.RIGHT) "right" else "left")
            .putInt(KEY_COLOR, colorIndex)
            .putInt(KEY_THICKNESS, thicknessIndex)
            .putString(KEY_MODEL, model.logName)
            .putString(KEY_DELEGATE, delegate.logName.lowercase())
            .putInt(KEY_HANDS, numHands)
            .putString(KEY_RES, if (analysis == AnalysisSize.R320) "320" else "640")
            .putBoolean(KEY_MIRROR, mirror)
            .putInt(KEY_VERSION, PREFS_VERSION)
            .apply()
    }

    private fun showNotice(text: String) {
        notice = text
        refreshHud()
        perfHandler.postDelayed({
            if (notice == text) {
                notice = null
                refreshHud()
            }
        }, 4000L)
    }

    private fun showConfirm() {
        decoder.reset()
        binding.confirmPanel.visibility = View.VISIBLE
        binding.confirmYes.post { binding.confirmYes.requestFocus() }
    }

    private fun hideConfirm() {
        binding.confirmPanel.visibility = View.GONE
        binding.root.requestFocus()
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun showNeedPermission() {
        showPanel(getString(R.string.msg_need_permission), getString(R.string.btn_allow_camera)) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun showPermissionDenied() {
        val openSettings = askedPermission &&
            !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        if (openSettings) {
            showPanel(getString(R.string.msg_permission_denied), getString(R.string.btn_settings)) {
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null),
                )
                runCatching { startActivity(intent) }
            }
        } else {
            showPanel(getString(R.string.msg_permission_denied), getString(R.string.btn_retry)) {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun showPanel(message: String, action: String, onClick: () -> Unit) {
        session.end()
        decoder.reset()
        binding.messagePanel.visibility = View.VISIBLE
        binding.messageText.text = message
        binding.actionButton.text = action
        binding.actionButton.setOnClickListener { onClick() }
        binding.actionButton.post { binding.actionButton.requestFocus() }
    }

    private fun hidePanel() {
        binding.messagePanel.visibility = View.GONE
        binding.root.requestFocus()
    }

    private fun Int.floorMod(size: Int): Int {
        val mod = this % size
        return if (mod < 0) mod + size else mod
    }

    private companion object {
        const val PREFS = "handdraw"
        const val KEY_HAND = "hand"
        const val KEY_COLOR = "color"
        const val KEY_THICKNESS = "thickness"
        const val KEY_MODEL = "model"
        const val KEY_DELEGATE = "delegate"
        const val KEY_HANDS = "hands"
        const val KEY_RES = "res"
        const val KEY_MIRROR = "mirror"
        const val KEY_VERSION = "prefs_version"
        const val PREFS_VERSION = 2
        const val SETTING_COUNT = 8
    }
}
