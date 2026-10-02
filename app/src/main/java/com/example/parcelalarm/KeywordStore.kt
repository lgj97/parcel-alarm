package com.example.parcelalarm

import android.content.Context
import org.json.JSONArray

/**
 * 关键词的本地持久化存储（SharedPreferences + JSON）。
 */
object KeywordStore {

    private const val PREFS_NAME = "parcel_alarm_prefs"
    private const val KEY_KEYWORDS = "keywords"

    /** 读取已保存的关键词列表，首次使用时返回默认示例。 */
    fun load(context: Context): MutableList<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_KEYWORDS, null) ?: return defaults()
        return try {
            val array = JSONArray(json)
            val list = mutableListOf<String>()
            for (i in 0 until array.length()) {
                list.add(array.getString(i))
            }
            list
        } catch (e: Exception) {
            defaults()
        }
    }

    /** 保存关键词列表。 */
    fun save(context: Context, keywords: List<String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_KEYWORDS, JSONArray(keywords).toString()).apply()
    }

    /**
     * 文本归一化：去掉所有空白并转小写。
     * 这样用户设置 "顺丰" 也能命中识别结果中的 "顺 丰"，设置 "SF" 也能命中 "sf"。
     */
    fun normalize(text: String): String =
        text.replace(Regex("\\s+"), "").lowercase()

    private fun defaults(): MutableList<String> =
        mutableListOf("易碎", "生鲜", "加急")
}
