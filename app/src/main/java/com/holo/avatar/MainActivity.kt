package com.holo.avatar

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.min

/**
 * 全息数字人主界面。
 *
 * 职责：WebView 承载形象渲染，原生承载"听"和"说"。
 * 云端只负责 ASR + LLM + TTS，柜内不做任何神经网络推理。
 */
class MainActivity : android.app.Activity() {

    private lateinit var web: WebView
    private val main = Handler(Looper.getMainLooper())

    private var config: AppConfig = AppConfig()
    private var client: RealtimeClient? = null
    private var mic: AudioCapture? = null
    private var speaker: AudioPlayer? = null

    private var shouldRun = false
    private var retry = 0
    private var lastLevel = -1f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        goImmersive()
        setupWebView()

        config = ConfigStore.load(this)
    }

    // ---------------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        web = findViewById<WebView>(R.id.web)

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            // 全息屏靠黑底成像，禁用滚动条与滚动
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
        }

        web.setBackgroundColor(0xFF000000.toInt())
        web.isVerticalScrollBarEnabled = false
        web.isHorizontalScrollBarEnabled = false
        web.overScrollMode = View.OVER_SCROLL_NEVER

        WebView.setWebContentsDebuggingEnabled(true)

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.i(TAG, "page finished: $url")
            }
        }
        web.webChromeClient = WebChromeClient()

        web.addJavascriptInterface(Bridge(), "HoloBridge")
        web.loadUrl("file:///android_asset/www/index.html")
    }

    private fun goImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.decorView.keepScreenOn = true
    }

    // ---------------------------------------------------------------- 生命周期

    override fun onResume() {
        super.onResume()
        goImmersive()
        if (shouldRun && client == null) startConversation()
    }

    override fun onPause() {
        super.onPause()
        // 展柜场景保持常驻，不因失焦停止对话
    }

    override fun onDestroy() {
        shouldRun = false
        teardown()
        super.onDestroy()
    }

    @Deprecated("展柜设备需要屏蔽返回键")
    override fun onBackPressed() {
        // 拦截返回，避免退出展柜
    }

    // ---------------------------------------------------------------- 流程控制

    private fun ensurePermission(): Boolean {
        return if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) true else {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC
            )
            false
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startConversation()
        } else {
            emit("""{"type":"error","message":"没有麦克风权限，无法对话"}""")
            emitState("error")
        }
    }

    private fun startConversation() {
        if (!config.isReady()) {
            emitState("need_config")
            return
        }
        if (!ensurePermission()) return

        if (shouldRun) return
        shouldRun = true
        retry = 0
        openClient()
    }

    private fun openClient() {
        if (!shouldRun) return
        teardown(keepRunFlag = true)
        emitState("connecting")

        val c = RealtimeClient(config, object : RealtimeClient.Listener {
            override fun onOpen() = Unit

            override fun onReady() {
                retry = 0
                startAudio()
                emitState("listening")
            }

            override fun onSpeechStarted() {
                // 打断：立刻静音并清空待播，口型归零
                speaker?.flush()
                emitLevel(0f)
                main.post { emitState("listening") }
            }

            override fun onSpeechStopped() {
                main.post { emitState("thinking") }
            }

            override fun onAudio(pcm24k: ByteArray) {
                speaker?.enqueue(pcm24k)
                main.post { emitState("speaking") }
            }

            override fun onAssistantText(delta: String) {
                if (delta.isEmpty()) return
                main.post { emitCaption("assistant", delta) }
            }

            override fun onAssistantDone() {
                main.post {
                    emitState("listening")
                }
            }

            override fun onUserText(text: String) {
                if (text.isEmpty()) return
                main.post { emitCaption("user", text) }
            }

            override fun onError(message: String) {
                main.post { emit("""{"type":"error","message":${JSONObject.quote(message)}}""") }
            }

            override fun onClosed() {
                main.post { scheduleReconnect() }
            }
        })

        client = c
        c.connect()
    }

    private fun scheduleReconnect() {
        if (!shouldRun) return
        teardown(keepRunFlag = true)
        emitState("reconnecting")
        retry++
        val delay = min(15000L, 3000L * retry)
        main.postDelayed({
            if (shouldRun) openClient()
        }, delay)
    }

    private fun startAudio() {
        if (speaker == null) {
            speaker = AudioPlayer { level -> emitLevel(level) }.also { it.start() }
        }
        if (mic == null) {
            val m = AudioCapture { chunk -> client?.sendAudio(chunk) }
            if (m.start()) mic = m else emitState("error")
        }
    }

    private fun teardown(keepRunFlag: Boolean = false) {
        if (!keepRunFlag) shouldRun = false
        mic?.stop(); mic = null
        speaker?.stop(); speaker = null
        client?.close(); client = null
    }

    // ---------------------------------------------------------------- 与 JS 通信

    private fun emit(json: String) {
        val js = "window.Holo&&window.Holo.onEvent(${JSONObject.quote(json)})"
        web.evaluateJavascript(js, null)
    }

    private fun emitState(state: String) {
        emit("""{"type":"state","state":${JSONObject.quote(state)}}""")
    }

    private fun emitCaption(role: String, text: String) {
        emit(
            """{"type":"caption","role":${JSONObject.quote(role)},"text":${JSONObject.quote(text)}}"""
        )
    }

    private fun emitLevel(v: Float) {
        val value = v.coerceIn(0f, 1f)
        if (lastLevel >= 0f && abs(value - lastLevel) < 0.012f && value > 0.01f) return
        lastLevel = value
        val js = "window.Holo&&window.Holo.onLevel(${String.format("%.3f", value)})"
        web.evaluateJavascript(js, null)
    }

    /** 暴露给页面脚本的原生接口 */
    inner class Bridge {

        @JavascriptInterface
        fun loadConfig(): String = ConfigStore.rawJson(this@MainActivity) ?: ""

        @JavascriptInterface
        fun saveConfig(json: String): Boolean {
            return try {
                config = AppConfig.fromJson(json)
                ConfigStore.save(this@MainActivity, config)
                true
            } catch (e: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun start(): Boolean {
            main.post { startConversation() }
            return true
        }

        @JavascriptInterface
        fun stop() {
            main.post {
                shouldRun = false
                teardown()
                emitState("idle")
            }
        }

        @JavascriptInterface
        fun isReady(): Boolean = config.isReady()

        @JavascriptInterface
        fun version(): String = "1.0"

        /** 调试用：长按屏幕 5 秒可退出，交付时注释掉调用即可 */
        @JavascriptInterface
        fun quit() {
            main.post { teardown(); finish() }
        }
    }

    companion object {
        private const val TAG = "HoloMain"
        private const val REQ_MIC = 1001
    }
}
