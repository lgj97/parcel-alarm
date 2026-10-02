package com.example.parcelalarm

import android.content.Context

/**
 * 扫描模式持久化：记住用户上次选择的模式。
 * true  = 单次手动拍照识别（默认）
 * false = 持续自动扫描
 */
object ModeStore {

    private const val PREFS_NAME = "parcel_alarm"
    private const val KEY_MANUAL = "manual_mode"

    fun isManualMode(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_MANUAL, true)

    fun setManualMode(context: Context, manual: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_MANUAL, manual)
            .apply()
    }
}
