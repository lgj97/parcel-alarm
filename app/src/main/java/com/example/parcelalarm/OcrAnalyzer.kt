package com.example.parcelalarm

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Size
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 持续自动扫描模式的分析器：把相机帧转成 Bitmap 交给 ML Kit 中文 OCR。
 */
class OcrAnalyzer(
    private val onText: (String) -> Unit,
    private val isScanningEnabled: () -> Boolean
) : ImageAnalysis.Analyzer {

    private val recognizer =
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    /** 防止 ML Kit 任务堆积：上一次识别完成前丢弃新帧。 */
    private val busy = AtomicBoolean(false)

    /** 连续识别节流间隔（毫秒），避免持续满负荷识别浪费电。 */
    private val throttleMillis = 1200L
    private var lastAnalyzeAt = 0L

    override fun analyze(image: ImageProxy) {
        if (!isScanningEnabled() || !busy.compareAndSet(false, true)) {
            image.close()
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastAnalyzeAt < throttleMillis) {
            busy.set(false)
            image.close()
            return
        }
        lastAnalyzeAt = now
        try {
            val rotation = image.imageInfo.rotationDegrees
            val bitmap = image.toArgbBitmap().let { bmp ->
                if (rotation == 0) bmp
                else {
                    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                    Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                }
            }
            recognizer.process(InputImage.fromBitmap(bitmap, rotation))
                .addOnSuccessListener { result ->
                    if (result.text.isNotBlank()) onText(result.text)
                }
                .addOnCompleteListener {
                    busy.set(false)
                }
        } catch (e: Exception) {
            busy.set(false)
        } finally {
            image.close()
        }
    }

    fun release() {
        recognizer.close()
    }

    /** CameraX 默认输出的 RGBA_8888 帧转 ARGB_8888 Bitmap。 */
    private fun ImageProxy.toArgbBitmap(): Bitmap {
        val plane = planes[0]
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val width = this.width
        val height = this.height
        val rowPadding = (rowStride - width * pixelStride) / pixelStride
        val bitmapWidth = width + rowPadding
        val tight = ByteArray(bitmapWidth * height * pixelStride)
        if (pixelStride == 1) {
            plane.buffer.get(tight, 0, tight.size)
        } else {
            val src = ByteArray(rowStride * height)
            plane.buffer.get(src, 0, src.size)
            var dst = 0
            for (row in 0 until height) {
                var col = 0
                val rowStart = row * rowStride
                while (col < width) {
                    val index = rowStart + col * pixelStride
                    tight[dst++] = src[index]
                    col++
                }
                dst += rowPadding * pixelStride
            }
        }
        val bitmap = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(tight))
        return if (rowPadding == 0) bitmap
        else Bitmap.createBitmap(bitmap, 0, 0, width, height)
    }

    companion object {
        /** 构建持续扫描用例：720P 左右，帧率足够 OCR 使用。 */
        fun buildAnalysisUseCase(): ImageAnalysis = ImageAnalysis.Builder()
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
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
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
    }
}
