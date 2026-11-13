package com.holo.avatar

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 阿里云百炼 Qwen-Omni-Realtime 的 WebSocket 客户端。
 *
 * 上行：input_audio_buffer.append（PCM 16k，Base64）
 * 下行：response.audio.delta（PCM 24k，Base64）
 *
 * 选择原生 WebSocket 而不是浏览器，是因为鉴权要求 Authorization 头，
 * 浏览器无法设置，且这样可以省掉一个中转服务器。
 */
class RealtimeClient(
    private val cfg: AppConfig,
    private val listener: Listener
) {

    interface Listener {
        fun onOpen()
        fun onReady()
        fun onSpeechStarted()
        fun onSpeechStopped()
        fun onAudio(pcm24k: ByteArray)
        fun onAssistantText(delta: String)
        fun onAssistantDone()
        fun onUserText(text: String)
        fun onError(message: String)
        fun onClosed()
    }

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    @Volatile
    private var ws: WebSocket? = null

    @Volatile
    private var ready = false

    @Volatile
    private var closedByUser = false

    fun connect() {
        closedByUser = false
        ready = false

        val req = Request.Builder()
            .url(cfg.wsUrl())
            .header("Authorization", "Bearer ${cfg.apiKey}")
            .header("User-Agent", "HoloAvatar/1.0 (Android)")
            .build()

        ws = http.newWebSocket(req, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "ws open")
                listener.onOpen()
                // session.created 时服务端会先发事件，这里统一在收到后下发配置
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    handleEvent(JSONObject(text))
                } catch (e: Exception) {
                    Log.w(TAG, "bad event: $text", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "ws failure: ${t.message}", t)
                ready = false
                listener.onError(t.message ?: "连接失败")
                if (!closedByUser) listener.onClosed()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "ws closed: $code $reason")
                ready = false
                if (!closedByUser) listener.onClosed()
            }
        })
    }

    /** 建立连接后按服务端事件推进，session.created 到达即下发会话配置 */
    private fun handleEvent(o: JSONObject) {
        when (val type = o.optString("type")) {

            "session.created" -> sendSessionUpdate()

            "session.updated" -> {
                ready = true
                listener.onReady()
            }

            "input_audio_buffer.speech_started" -> listener.onSpeechStarted()

            "input_audio_buffer.speech_stopped" -> listener.onSpeechStopped()

            "response.audio.delta" -> {
                val b64 = o.optString("delta")
                if (b64.isNotEmpty()) {
                    try {
                        listener.onAudio(Base64.decode(b64, Base64.DEFAULT))
                    } catch (e: Exception) {
                        Log.w(TAG, "audio decode failed", e)
                    }
                }
            }

            "response.audio_transcript.delta" ->
                listener.onAssistantText(o.optString("delta"))

            "response.done" -> listener.onAssistantDone()

            "conversation.item.input_audio_transcription.completed" ->
                listener.onUserText(o.optString("transcript"))

            "error" -> {
                val msg = o.optJSONObject("error")?.optString("message") ?: "未知错误"
                Log.e(TAG, "server error: $msg")
                listener.onError(msg)
            }

            else -> Log.v(TAG, "event: $type")
        }
    }

    private fun sendSessionUpdate() {
        val session = JSONObject().apply {
            put("modalities", org.json.JSONArray().put("text").put("audio"))
            put("instructions", cfg.instructions)
            put("audio", JSONObject().put(
                "output",
                JSONObject().put("voice", cfg.voice)
            ))
            put("turn_detection", JSONObject().apply {
                put("type", "server_vad")
                put("threshold", 0.5)
                put("silence_duration_ms", 500)
                put("interrupt_response", true)
                put("create_response", true)
            })
            put("input_audio_transcription", JSONObject().put("model", "qwen3-asr-flash-realtime"))
        }
        val payload = JSONObject().apply {
            put("type", "session.update")
            put("session", session)
        }
        ws?.send(payload.toString())
    }

    /** 上行一段 16k PCM，Base64 编码 */
    fun sendAudio(pcm16k: ByteArray) {
        if (!ready) return
        val b64 = Base64.encodeToString(pcm16k, Base64.NO_WRAP)
        val payload = JSONObject().apply {
            put("type", "input_audio_buffer.append")
            put("audio", b64)
        }
        ws?.send(payload.toString())
    }

    /** 主动打断当前回答（VAD 已自动打断，这里是兜底） */
    fun cancelResponse() {
        ws?.send(JSONObject().put("type", "response.cancel").toString())
    }

    /**
     * 唤醒问候：让模型主动说一句欢迎语。
     * 设备没有系统 TTS 时用这个兜底。
     */
    fun greetWelcome() {
        if (!ready) return
        val item = JSONObject().apply {
            put("type", "conversation.item.create")
            put("item", JSONObject().apply {
                put("type", "message")
                put("role", "user")
                put("content", org.json.JSONArray().put(JSONObject().apply {
                    put("type", "input_text")
                    put("text", "（有人刚走到展柜前，请用一句话主动打招呼，语气自然，不要提到这条提示）")
                }))
            })
        }
        ws?.send(item.toString())
        ws?.send(JSONObject().put("type", "response.create").toString())
    }

    fun close() {
        closedByUser = true
        ready = false
        ws?.close(1000, "bye")
        ws = null
    }

    companion object {
        private const val TAG = "HoloRealtime"
    }
}
