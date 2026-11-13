package com.holo.avatar

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.min

/**
 * 全息数字人主界面。
 *
 * 交互模型（人脸唤醒）：
 *   SLEEP  —— 不连服务器、不开麦。摄像头以 5fps 检测，人走近 → 唤醒
 *   ACTIVE —— 连接 + 开麦 + 对话；连续 40s 没检测到脸 → 断开、休眠
 *
 * 唤醒时优先用系统 TTS 说一句问候（本地、0 延迟）；
 * 设备没有 TTS 引擎时退回云端生成问候。
 */
class MainActivity : android.app.Activity(), LifecycleOwner {

    private lateinit var web: WebView
    private val main = Handler(Looper.getMainLooper())

    private var config: AppConfig = AppConfig()
    private var client: RealtimeClient? = null
    private var mic: AudioCapture? = null
    private var speaker: AudioPlayer? = null

    // ---------------- 会话状态 ----------------
    private var shouldRun = false
    private var retry = 0
    private var lastLevel = -1f

    @Volatile
    private var sleeping = true

    @Volatile
    private var micMuted = false          // 打招呼期间静音，避免把自己的问候语送回去

    private var greetingPending = false

    // ---------------- 人脸检测 ----------------
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var detector: FaceDetector? = null
    private var cameraStarted = false

    @Volatile
    private var lastSeenAt = 0L

    @Volatile
    private var lastAnalyzed = 0L

    private var presentFrames = 0

    // ---------------- TTS ----------------
    private var tts: TextToSpeech? = null

    @Volatile
    private var ttsReady = false

    // ---------------- 生命周期 ----------------
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        installCrashHandler()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        goImmersive()
        setupWebView()

        config = ConfigStore.load(this)

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        initTts()
        requestPermissionsIfNeeded()
        startPresenceWatchdog()
    }

    // ---------------------------------------------------------------- 崩溃记录

    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val body = "${e.javaClass.simpleName}: ${e.message}\n" +
                    e.stackTrace.take(8).joinToString("\n") { "  at $it" }
                java.io.File(filesDir, CRASH_FILE).writeText(body.take(1200))
            } catch (_: Exception) {
            }
            prev?.uncaughtException(thread, e)
        }
    }

    private fun checkLastCrash() {
        try {
            val f = java.io.File(filesDir, CRASH_FILE)
            if (!f.exists()) return
            val text = f.readText().take(500)
            f.delete()
            if (text.isNotBlank()) {
                emit("""{"type":"error","message":${JSONObject.quote("上次崩溃：\n$text")}}""")
            }
        } catch (_: Exception) {
        }
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
                checkLastCrash()
                // 启动后先处于休眠，等人靠近
                if (config.isReady()) emitState("sleep") else emitState("need_config")
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

    // ---------------------------------------------------------------- 权限

    private fun requestPermissionsIfNeeded() {
        val missing = ArrayList<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) missing.add(Manifest.permission.RECORD_AUDIO)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) missing.add(Manifest.permission.CAMERA)

        if (missing.isEmpty()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_PERMS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return

        val micOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val camOk = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

        if (camOk) startCamera() else Log.w(TAG, "camera permission denied")
        if (!micOk) {
            emit("""{"type":"error","message":"没有麦克风权限，无法对话"}""")
            emitState("error")
        }
    }

    // ---------------------------------------------------------------- 摄像头与人脸检测

    private fun startCamera() {
        if (cameraStarted) return
        cameraStarted = true

        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.08f)
            .build()
        detector = FaceDetection.getClient(options)

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analysis.setAnalyzer(cameraExecutor) { proxy -> analyzeFrame(proxy) }

                provider.unbindAll()
                provider.bindToLifecycle(this, pickCamera(provider), analysis)
                Log.i(TAG, "camera bound")
            } catch (e: Exception) {
                Log.e(TAG, "camera bind failed", e)
                emit("""{"type":"error","message":${JSONObject.quote("摄像头不可用：" + e.message)}}""")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** 优先后置（USB 摄像头常报成后置），其次前置，最后随便一个 */
    private fun pickCamera(provider: ProcessCameraProvider): CameraSelector {
        if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
            return CameraSelector.DEFAULT_BACK_CAMERA
        }
        if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
            return CameraSelector.DEFAULT_FRONT_CAMERA
        }
        return CameraSelector.Builder()
            .addCameraFilter { list -> list.take(1) }
            .build()
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun analyzeFrame(proxy: ImageProxy) {
        val now = System.currentTimeMillis()
        val media = proxy.image
        if (media == null || now - lastAnalyzed < FRAME_INTERVAL_MS) {
            proxy.close()
            return
        }
        lastAnalyzed = now

        val rotation = proxy.imageInfo.rotationDegrees
        val rotatedWidth = if (rotation == 90 || rotation == 270) proxy.height else proxy.width

        val input = InputImage.fromMediaImage(media, rotation)
        val d = detector
        if (d == null) {
            proxy.close()
            return
        }

        d.process(input)
            .addOnSuccessListener { faces -> evaluateFaces(faces, rotatedWidth) }
            .addOnFailureListener { e -> Log.w(TAG, "detect failed", e) }
            .addOnCompleteListener { proxy.close() }
    }

    private fun evaluateFaces(faces: List<Face>, imageWidth: Int) {
        if (imageWidth <= 0) return
        val now = System.currentTimeMillis()

        val best = faces.maxByOrNull { it.boundingBox.width() }
        if (best == null) {
            presentFrames = 0
            return
        }

        val ratio = best.boundingBox.width().toFloat() / imageWidth.toFloat()
        val yaw = best.headEulerAngleY
        val facing = abs(yaw) <= MAX_YAW

        if (ratio >= FACE_RATIO && facing) {
            presentFrames++
            lastSeenAt = now
            if (presentFrames >= CONFIRM_FRAMES && sleeping) {
                main.post { wake() }
            }
        } else {
            presentFrames = 0
        }
    }

    private fun startPresenceWatchdog() {
        val runnable = object : Runnable {
            override fun run() {
                if (!sleeping && System.currentTimeMillis() - lastSeenAt > ABSENT_TIMEOUT_MS) {
                    sleep()
                }
                main.postDelayed(this, 1000)
            }
        }
        main.postDelayed(runnable, 1000)
    }

    // ---------------------------------------------------------------- 休眠 / 唤醒

    private fun sleep() {
        if (sleeping) return
        sleeping = true
        shouldRun = false
        micMuted = false
        greetingPending = false
        teardown()
        emitState("sleep")
        Log.i(TAG, "sleep")
    }

    private fun wake() {
        if (!sleeping) return
        if (!config.isReady()) {
            emitState("need_config")
            sleeping = true
            return
        }
        sleeping = false
        presentFrames = 0
        lastSeenAt = System.currentTimeMillis()
        greetingPending = true
        micMuted = true
        emitState("connecting")
        startConversation()
        Log.i(TAG, "wake")
    }

    // ---------------------------------------------------------------- 会话

    private fun startConversation() {
        if (shouldRun) return
        shouldRun = true
        retry = 0
        openClient()
    }

    private fun openClient() {
        if (!shouldRun) return
        teardown(keepRunFlag = true)

        val c = RealtimeClient(config, object : RealtimeClient.Listener {
            override fun onOpen() = Unit

            override fun onReady() {
                retry = 0
                main.post {
                    startAudio()
                    if (greetingPending) {
                        greetingPending = false
                        main.postDelayed({ greet() }, 150)
                    } else {
                        micMuted = false
                        emitState("listening")
                    }
                }
            }

            override fun onSpeechStarted() {
                main.post {
                    speaker?.flush()
                    emitLevel(0f)
                    lastSeenAt = System.currentTimeMillis()
                    emitState("listening")
                }
            }

            override fun onSpeechStopped() {
                main.post { emitState("thinking") }
            }

            override fun onAudio(pcm24k: ByteArray) {
                speaker?.enqueue(pcm24k)
                if (!micMuted) main.post { emitState("speaking") }
            }

            override fun onAssistantText(delta: String) {
                if (delta.isEmpty()) return
                main.post { emitCaption("assistant", delta) }
            }

            override fun onAssistantDone() {
                main.post {
                    micMuted = false
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
        if (!shouldRun || sleeping) return
        teardown(keepRunFlag = true)
        emitState("reconnecting")
        retry++
        val delay = min(15000L, 3000L * retry)
        main.postDelayed({
            if (shouldRun && !sleeping) openClient()
        }, delay)
    }

    private fun startAudio() {
        if (speaker == null) {
            speaker = AudioPlayer { level -> emitLevel(level) }.also { it.start() }
        }
        if (mic == null) {
            val m = AudioCapture { chunk ->
                // 打招呼期间不往上传，避免把自己的问候语当成用户说话
                if (!micMuted && !sleeping) client?.sendAudio(chunk)
            }
            if (m.start()) mic = m
        }
    }

    private fun teardown(keepRunFlag: Boolean = false) {
        if (!keepRunFlag) shouldRun = false
        mic?.stop(); mic = null
        speaker?.stop(); speaker = null
        client?.close(); client = null
    }

    // ---------------------------------------------------------------- 问候语

    private fun initTts() {
        try {
            tts = TextToSpeech(this) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    try {
                        tts?.language = Locale.SIMPLIFIED_CHINESE
                    } catch (e: Exception) {
                        ttsReady = false
                    }
                }
                Log.i(TAG, "tts ready=$ttsReady")
            }
        } catch (e: Exception) {
            Log.w(TAG, "tts init failed", e)
            ttsReady = false
        }
    }

    private fun greet() {
        emitState("speaking")
        emitCaption("assistant", GREETING)

        val t = tts
        if (ttsReady && t != null) {
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    main.post {
                        micMuted = false
                        emitState("listening")
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    main.post { fallbackGreeting() }
                }
            })
            val r = t.speak(GREETING, TextToSpeech.QUEUE_FLUSH, null, "holo_greet")
            if (r == TextToSpeech.SUCCESS) return
        }
        fallbackGreeting()
    }

    /** 没有 TTS 引擎时，让云端说这句问候 */
    private fun fallbackGreeting() {
        client?.greetWelcome()
        main.postDelayed({
            if (micMuted) {
                micMuted = false
                emitState("listening")
            }
        }, 6000)
    }

    // ---------------------------------------------------------------- 生命周期

    override fun onResume() {
        super.onResume()
        goImmersive()
        if (!sleeping && client == null) startConversation()
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onDestroy() {
        shouldRun = false
        teardown()
        try { cameraProvider?.unbindAll() } catch (_: Exception) { }
        try { detector?.close() } catch (_: Exception) { }
        try { cameraExecutor.shutdown() } catch (_: Exception) { }
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) { }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    @Deprecated("展柜设备需要屏蔽返回键")
    override fun onBackPressed() {
        // 拦截返回，避免退出展柜
    }

    // ---------------------------------------------------------------- 与 JS 通信

    private fun emit(json: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { emit(json) }
            return
        }
        if (isFinishing) return
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
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { emitLevel(v) }
            return
        }
        if (isFinishing) return

        val value = v.coerceIn(0f, 1f)
        if (lastLevel >= 0f && abs(value - lastLevel) < 0.012f && value > 0.01f) return
        lastLevel = value
        val js = "window.Holo&&window.Holo.onLevel(${String.format(Locale.US, "%.3f", value)})"
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

        /** 配置保存后调用：直接唤醒（此时用户就站在柜子前） */
        @JavascriptInterface
        fun start(): Boolean {
            main.post {
                if (sleeping) {
                    lastSeenAt = System.currentTimeMillis()
                    wake()
                }
            }
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
        fun version(): String = "1.1"
    }

    companion object {
        private const val TAG = "HoloMain"
        private const val REQ_PERMS = 1001
        private const val CRASH_FILE = "last_crash.txt"

        // ------- 唤醒参数（现场可调） -------
        private const val FRAME_INTERVAL_MS = 200L      // 5 fps
        private const val FACE_RATIO = 0.12f            // 脸宽占画面 12%，约 1.5 米
        private const val MAX_YAW = 45f                 // 偏航角超过 45° 视为没正对
        private const val CONFIRM_FRAMES = 2            // 连续 2 帧确认
        private const val ABSENT_TIMEOUT_MS = 40_000L   // 40 秒没脸就断开

        private const val GREETING = "你好呀，我是小橘，有什么想聊的尽管问我。"
    }
}
