package com.example.parcelalarm

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

/**
 * ML Kit 离线中文 OCR 引擎封装。
 */
class OcrEngine {

    private val recognizer =
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    /**
     * 识别 Bitmap 中的文字；onDone 在主线程回调，
     * 识别失败时回调参数为 null。
     */
    fun recognize(bitmap: Bitmap, onDone: (String?) -> Unit) {
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result -> onDone(result.text) }
            .addOnFailureListener { onDone(null) }
    }

    /** 页面销毁时释放引擎资源。 */
    fun release() {
        recognizer.close()
    }
}
