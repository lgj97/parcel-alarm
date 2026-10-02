package com.example.parcelalarm

import android.Manifest
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.parcelalarm.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 主界面：后置摄像头实时取流 -> ML Kit 离线中文 OCR -> 命中关键词则报警。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var alarmController: AlarmController

    private var camera: Camera? = null
    private var cameraExecutor: ExecutorService? = null
    private var ocrAnalyzer: OcrAnalyzer? = null

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

                ocrAnalyzer = OcrAnalyzer(
                    isScanningEnabled = { scanning },
                    onText = { text -> runOnUiThread { handleOcrText(text) } }
                )

                val analysis = OcrAnalyzer.buildAnalysisUseCase()
                analysis.setAnalyzer(executor, ocrAnalyzer!!)

                provider.unbindAll()
                // 固定使用后置摄像头
                camera = provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } catch (e: Exception) {
                binding.textStatus.text = getString(R.string.camera_error)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** 处理一次 OCR 识别结果（主线程）。 */
    private fun handleOcrText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        binding.textRecognized.text = trimmed
        showRecognizedOk()
        if (!scanning) return

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
            !scanning -> getString(R.string.status_paused)
            keywords.isEmpty() -> getString(R.string.status_no_keywords)
            else -> getString(R.string.status_scanning) + " · " +
                keywords.size + " " + getString(R.string.keyword_unit)
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
