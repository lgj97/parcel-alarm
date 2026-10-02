package com.example.parcelalarm

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * 报警控制器：铃声循环 + 长震动 + TTS 语音播报命中的关键词。
 */
class AlarmController(private val context: Context) {

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    init {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager =
                context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        tts = TextToSpeech(context.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.SIMPLIFIED_CHINESE
            }
        }
    }

    /** 触发一次报警（重复调用时铃声/震动不会叠加）。 */
    fun start(matchedKeywords: List<String>) {
        startSound()
        startVibration()
        if (ttsReady) {
            val words = matchedKeywords.joinToString("，")
            tts?.speak(
                "注意，发现关键词，$words",
                TextToSpeech.QUEUE_FLUSH,
                null,
                "parcel_alarm"
            )
        }
    }

    /** 停止铃声、震动和语音，但不释放资源（可再次 start）。 */
    fun stop() {
        stopAndReleasePlayer()
        vibrator?.cancel()
        tts?.stop()
    }

    /** 页面销毁时释放全部资源。 */
    fun release() {
        stop()
        tts?.shutdown()
        tts = null
        vibrator = null
    }

    private fun startSound() {
        if (player?.isPlaying == true) return
        stopAndReleasePlayer()
        try {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_NOTIFICATION)
                ?: return
            player = MediaPlayer().apply {
                setDataSource(context, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                setOnPreparedListener { it.start() }
                prepareAsync()
            }
        } catch (e: Exception) {
            player = null
        }
    }

    private fun startVibration() {
        val v = vibrator ?: return
        val pattern = longArrayOf(0, 700, 300, 700, 300)
        v.cancel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(pattern, 0)
        }
    }

    private fun stopAndReleasePlayer() {
        try {
            player?.stop()
        } catch (e: Exception) {
            // 忽略：尚未 prepare 完成时 stop 会抛 IllegalStateException
        }
        try {
            player?.release()
        } catch (e: Exception) {
            // 忽略
        }
        player = null
    }
}
