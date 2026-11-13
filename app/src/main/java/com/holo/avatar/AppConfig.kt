package com.holo.avatar

import android.content.Context
import org.json.JSONObject

/**
 * 运行配置。以 JSON 字符串在原生与 WebView 之间传递。
 */
data class AppConfig(
    val apiKey: String = "",
    val workspaceId: String = "",
    val region: String = "cn-beijing",
    val model: String = "qwen3.8-omni-flash-realtime",
    val voice: String = "Tina",
    val instructions: String = DEFAULT_PROMPT,
    val nickname: String = "小全",
    val avatarAsset: String = ""      // 立绘文件名（assets/www/avatar/ 下），空则用内置全息人形
) {

    fun isReady(): Boolean = apiKey.isNotBlank() && workspaceId.isNotBlank()

    /** 百炼实时语音的 WebSocket 地址（北京 / 新加坡均为此格式） */
    fun wsUrl(): String =
        "wss://$workspaceId.$region.maas.aliyuncs.com/api-ws/v1/realtime?model=$model"

    fun toJson(): JSONObject = JSONObject().apply {
        put("apiKey", apiKey)
        put("workspaceId", workspaceId)
        put("region", region)
        put("model", model)
        put("voice", voice)
        put("instructions", instructions)
        put("nickname", nickname)
        put("avatarAsset", avatarAsset)
    }

    companion object {
        const val DEFAULT_PROMPT =
            "你是摆放在展厅里的一台圆柱形全息展示柜中的数字人讲解员。" +
                "你的回答要口语化、简短，每次不超过三句话，不要使用列表、编号和括号补充说明。" +
                "语气亲切自然，像面对面聊天。遇到不知道的事情就大方说明，不要编造。"

        fun fromJson(text: String?): AppConfig {
            if (text.isNullOrBlank()) return AppConfig()
            return try {
                val o = JSONObject(text)
                AppConfig(
                    apiKey = o.optString("apiKey", ""),
                    workspaceId = o.optString("workspaceId", ""),
                    region = o.optString("region", "cn-beijing").ifBlank { "cn-beijing" },
                    model = o.optString("model", "qwen3.8-omni-flash-realtime")
                        .ifBlank { "qwen3.8-omni-flash-realtime" },
                    voice = o.optString("voice", "Tina").ifBlank { "Tina" },
                    instructions = o.optString("instructions", DEFAULT_PROMPT)
                        .ifBlank { DEFAULT_PROMPT },
                    nickname = o.optString("nickname", "小全").ifBlank { "小全" },
                    avatarAsset = o.optString("avatarAsset", "")
                )
            } catch (e: Exception) {
                AppConfig()
            }
        }
    }
}

object ConfigStore {
    private const val SP_NAME = "holo_config"
    private const val KEY_JSON = "config_json"

    fun load(ctx: Context): AppConfig {
        val sp = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        return AppConfig.fromJson(sp.getString(KEY_JSON, null))
    }

    fun save(ctx: Context, cfg: AppConfig) {
        ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_JSON, cfg.toJson().toString())
            .apply()
    }

    fun rawJson(ctx: Context): String? =
        ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE).getString(KEY_JSON, null)
}
