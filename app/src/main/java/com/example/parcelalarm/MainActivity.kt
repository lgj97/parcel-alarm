package com.example.parcelalarm

import android.Manifest
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.parcelalarm.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 主界面：后置摄像头，两种扫描模式（顶部按钮切换，选择会被记住）：
 * 1. 单次手动拍照识别（默认）：点“拍照识别”对全分辨率照片做 OCR；
 * 2. 持续自动扫描：相机帧持续 OCR，可暂停/继续。
 * 识别到自定义关键词 -> 声光报警。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var alarmController: AlarmController
    private val ocrEngine = OcrEngine()

    private var camera: Camera? = null
    private var cameraExecutor: ExecutorService? = null

    /** 拍照识别用例（仅手动模式）。 */
    private var imageCapture: ImageCapture? = null

    /** 持续扫描分析器（仅自动模式）。 */
    private var ocrAnalyzer: OcrAnalyzer? = null

    /** true=单次手动拍照识别，false=持续自动扫描。 */
    private var manualMode = true

    /** 手动模式下一次识别进行中。 */
    private var recognizing = false

    private var keywords: List<String> = emptyList()
    private var scanning = true
    private var alarming = false
    private var torchOn = false
    private var suppressUntil = 0L
    private var flashAnimator: ValueAnimator? = null

    /** 停止报警后 4 秒内不再重复触发，避免同一张单子反复响。 */
    private val alarmSuppressMillis = 4000L

    /** “识别成功”徽标显示时长。 */
    private val okBadgeMillis = 1500L

    /** 隐藏“识别成功”徽标的任务。 */
    private val hideOkRunnable = Runnable {
        binding.textRecognizedOk.visibility = View.GONE
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else showPermissionNeeded()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        alarmController = AlarmController(this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        // 记住用户上次选择的模式
        manualMode = ModeStore.isManualMode(this)

        binding.btnMode.setOnClickListener { switchMode() }
        binding.btnCapture.setOnClickListener { captureAndRecognize() }
        binding.btnToggleScan.setOnClickListener {
            scanning = !scanning
            updateStatus()
        }
        binding.btnKeywords.setOnClickListener {
            startActivity(Intent(this, KeywordsActivity::class.java))
        }
        binding.btnTorch.setOnClickListener { toggleTorch() }
        binding.btnStopAlarm.setOnClickListener { stopAlarm() }
        binding.btnGrant.setOnClickListener {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }

        updateModeUi()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        keywords = KeywordStore.load(this)
        updateStatus()
    }

    override fun onStop() {
        super.onStop()
        stopAlarm()
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.textRecognizedOk.removeCallbacks(hideOkRunnable)
        cameraExecutor?.shutdown()
        cameraExecutor = null
        ocrAnalyzer?.release()
        ocrAnalyzer = null
        ocrEngine.release()
        alarmController.release()
    }

    private fun startCamera() {
        binding.layoutPermission.visibility = View.GONE
        val executor = cameraExecutor ?: return

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

                provider.unbindAll()
                // 固定使用后置摄像头
                if (manualMode) {
                    ocrAnalyzer?.release()
                    ocrAnalyzer = null
                    imageCapture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .build()
                    camera = provider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageCapture
                    )
                } else {
                    imageCapture = null
                    ocrAnalyzer?.release()
                    ocrAnalyzer = OcrAnalyzer(
                        isScanningEnabled = { scanning },
                        onText = { text -> runOnUiThread { handleOcrText(text) } }
                    )
                    val analysis = OcrAnalyzer.buildAnalysisUseCase()
                    analysis.setAnalyzer(executor, ocrAnalyzer!!)
                    camera = provider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis
                    )
                }
            } catch (e: Exception) {
                binding.textStatus.text = getString(R.string.camera_error)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** 切换扫描模式并持久化，立即重新绑定相机用例。 */
    private fun switchMode() {
        if (recognizing) return // 手动识别进行中不允许切换
        manualMode = !manualMode
        ModeStore.setManualMode(this, manualMode)
        updateModeUi()
        startCamera()
    }

    /** 根据当前模式更新按钮可见性与文案。 */
    private fun updateModeUi() {
        binding.btnMode.text =
            getString(if (manualMode) R.string.mode_manual else R.string.mode_auto)
        binding.btnCapture.visibility = if (manualMode) View.VISIBLE else View.GONE
        binding.btnToggleScan.visibility = if (manualMode) View.GONE else View.VISIBLE
        updateStatus()
    }

    /** 手动模式：拍一张全分辨率照片并识别。 */
    private fun captureAndRecognize() {
        val capture = imageCapture ?: return
        if (recognizing) return
        recognizing = true
        binding.btnCapture.isEnabled = false
        binding.btnCapture.text = getString(R.string.recognizing)

        capture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = image.toUprightBitmap()
                    image.close()
                    if (bitmap == null) {
                        resetCaptureUi()
                        binding.textRecognized.text = getString(R.string.capture_failed)
                        return
                    }
                    ocrEngine.recognize(bitmap) { text ->
                        bitmap.recycle()
                        resetCaptureUi()
                        if (text.isNullOrBlank()) {
                            binding.textRecognized.text =
                                getString(R.string.recognize_failed_or_empty)
                        } else {
                            handleOcrText(text)
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    resetCaptureUi()
                    binding.textRecognized.text = getString(R.string.capture_failed)
                }
            }
        )
    }

    private fun resetCaptureUi() {
        recognizing = false
        binding.btnCapture.isEnabled = true
        binding.btnCapture.text = getString(R.string.capture_recognize)
    }

    /** ImageProxy(JPEG) 转正立 Bitmap，按相机旋转角度纠正方向。 */
    private fun ImageProxy.toUprightBitmap(): Bitmap? = try {
        val buffer = planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val rotation = imageInfo.rotationDegrees
        if (bitmap != null && rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            val rotated =
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) bitmap.recycle()
            bitmap = rotated
        }
        bitmap
    } catch (e: Exception) {
        null
    }

    /** 处理一次 OCR 识别结果（主线程），两种模式共用。 */
    private fun handleOcrText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        binding.textRecognized.text = trimmed
        showRecognizedOk()

        val normalizedText = KeywordStore.normalize(trimmed)
        val matched = keywords
            .map { KeywordStore.normalize(it) }
            .filter { it.isNotEmpty() && normalizedText.contains(it) }
            .distinct()

        if (matched.isEmpty()) return
        if (SystemClock.elapsedRealtime() < suppressUntil) return

        if (alarming) {
            // 报警中：只更新横幅文字，不重复响铃
            binding.alarmText.text = getString(R.string.alarm_found) + "：" +
                matched.joinToString("、")
        } else {
            triggerAlarm(matched)
        }
    }

    private fun triggerAlarm(matched: List<String>) {
        alarming = true
        binding.alarmBanner.visibility = View.VISIBLE
        binding.alarmText.text = getString(R.string.alarm_found) + "：" +
            matched.joinToString("、")
        startFlash()
        alarmController.start(matched)
        updateStatus()
    }

    private fun stopAlarm() {
        alarmController.stop()
        stopFlash()
        binding.alarmBanner.visibility = View.GONE
        if (alarming) {
            alarming = false
            suppressUntil = SystemClock.elapsedRealtime() + alarmSuppressMillis
        }
        updateStatus()
    }

    /** 报警时红色全屏闪烁。 */
    private fun startFlash() {
        stopFlash()
        flashAnimator = ValueAnimator.ofFloat(0.10f, 0.55f).apply {
            duration = 350
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { animator ->
                binding.flashView.alpha = animator.animatedValue as Float
            }
            start()
        }
    }

    private fun stopFlash() {
        flashAnimator?.cancel()
        flashAnimator = null
        binding.flashView.alpha = 0f
    }

    private fun toggleTorch() {
        val cam = camera ?: return
        torchOn = !torchOn
        cam.cameraControl.enableTorch(torchOn)
        binding.btnTorch.text = getString(if (torchOn) R.string.torch_off else R.string.torch_on)
    }

    private fun updateStatus() {
        val state = when {
            alarming -> getString(R.string.status_alarming)
            keywords.isEmpty() -> getString(R.string.status_no_keywords)
            manualMode -> getString(R.string.status_ready)
            scanning -> getString(R.string.status_scanning) + " · " +
                keywords.size + " " + getString(R.string.keyword_unit)
            else -> getString(R.string.status_paused)
        }
        binding.textStatus.text = state
        binding.btnToggleScan.text =
            getString(if (scanning) R.string.pause_scan else R.string.resume_scan)
    }

    private fun showPermissionNeeded() {
        binding.layoutPermission.visibility = View.VISIBLE
    }

    /** 每次成功识别到文字时，短暂显示绿色“识别成功”徽标。 */
    private fun showRecognizedOk() {
        val badge = binding.textRecognizedOk
        badge.removeCallbacks(hideOkRunnable)
        badge.visibility = View.VISIBLE
        badge.postDelayed(hideOkRunnable, okBadgeMillis)
    }
}
