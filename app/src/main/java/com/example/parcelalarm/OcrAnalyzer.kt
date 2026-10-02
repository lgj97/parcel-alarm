package com.example.parcelalarm

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.nio.ByteBuffer

/**
 * CameraX 帧分析器：把相机帧转成 Bitmap 后交给 ML Kit 离线中文 OCR，
 * 识别到的完整文字通过 onText 回调（主线程）返回。
 *
 * 内置节流：两次识别至少间隔 SCAN_INTERVAL_MS，且上一帧未处理完时丢弃新帧，
 * 避免低端机 CPU 过载。
 */
class OcrAnalyzer(
    private val isScanningEnabled: () -> Boolean,
    private val onText: (String) -> Unit
) : ImageAnalysis.Analyzer {

    private val recognizer =
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    private var busy = false
    private var lastRunAt = 0L

    override fun analyze(image: ImageProxy) {
        val enabled = isScanningEnabled()
        val now = SystemClock.elapsedRealtime()
        if (!enabled || busy || now - lastRunAt < SCAN_INTERVAL_MS) {
            image.close()
            return
        }
        lastRunAt = now
        busy = true

        val rotationDegrees = image.imageInfo.rotationDegrees
        val bitmap = image.toArgbBitmap()
        image.close()

        if (bitmap == null) {
            busy = false
            return
        }

        recognizer.process(InputImage.fromBitmap(bitmap, rotationDegrees))
            .addOnSuccessListener { result ->
                if (enabled) {
                    onText(result.text)
                }
            }
            .addOnCompleteListener {
                busy = false
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
    }

    /**
     * CameraX 输出格式为 RGBA_8888 时只有 plane[0] 一个平面。
     * 注意处理 rowStride 大于 width*4 的情况（行末对齐填充）。
     */
    private fun ImageProxy.toArgbBitmap(): Bitmap? = try {
        val plane = planes[0]
        val buffer = plane.buffer
        val w = width
        val h = height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val rowBytes = w * pixelStride

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (rowStride == rowBytes) {
            bitmap.copyPixelsFromBuffer(buffer)
        } else {
            // 逐行去除 padding 后再填充
            val tight = ByteArray(rowBytes * h)
            var offset = 0
            for (y in 0 until h) {
                buffer.position(y * rowStride)
                buffer.get(tight, offset, rowBytes)
                offset += rowBytes
            }
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(tight))
        }
        bitmap
    } catch (t: Throwable) {
        null
    }

    companion object {
        private const val SCAN_INTERVAL_MS = 1200L

        /** 构建图像分析用例：720P、RGBA 输出、只保留最新帧。 */
        fun buildAnalysisUseCase(): ImageAnalysis =
            ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(1280, 720),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        .build()
                )
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
    }
}
