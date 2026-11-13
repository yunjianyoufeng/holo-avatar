package com.holo.avatar

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log

/**
 * 麦克风采集：16kHz / 单声道 / PCM16，每 100ms 回调一块。
 *
 * 优先使用 VOICE_COMMUNICATION 音源并挂载系统 AEC / 降噪，
 * 这是数字人"不自问自答"的关键。部分廉价主板不支持 16k 直接采集，
 * 这里会自动退化为 48k 采集 + 3:1 抽取。
 */
class AudioCapture(private val onChunk: (ByteArray) -> Unit) {

    @Volatile
    private var running = false
    private var thread: Thread? = null

    private var record: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null

    private var sampleRate = 16000
    private var decimate = 1

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true

        // 先尝试 16k，失败则退到 48k
        var ok = open(16000, 1)
        if (!ok) {
            Log.w(TAG, "16k not supported, fallback to 48k")
            ok = open(48000, 3)
        }
        if (!ok) {
            Log.e(TAG, "AudioRecord init failed")
            return false
        }

        running = true
        thread = Thread({ loop() }, "holo-mic").apply { start() }
        return true
    }

    private fun open(rate: Int, dec: Int): Boolean {
        return try {
            val minBuf = AudioRecord.getMinBufferSize(
                rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) return false

            val chunkBytes = (rate / 10) * 2               // 100ms
            val bufSize = maxOf(minBuf * 2, chunkBytes * 4)

            val r = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                r.release()
                return false
            }

            record = r
            sampleRate = rate
            decimate = dec

            // 回声消除与降噪：设备不支持时会返回 null，属正常情况
            try {
                if (AcousticEchoCanceler.isAvailable()) {
                    aec = AcousticEchoCanceler.create(r.audioSessionId)?.apply { enabled = true }
                }
            } catch (e: Exception) {
                Log.w(TAG, "AEC unavailable", e)
            }
            try {
                if (NoiseSuppressor.isAvailable()) {
                    ns = NoiseSuppressor.create(r.audioSessionId)?.apply { enabled = true }
                }
            } catch (e: Exception) {
                Log.w(TAG, "NS unavailable", e)
            }

            Log.i(TAG, "opened rate=$rate decimate=$dec aec=${aec != null} ns=${ns != null}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "open failed at $rate", e)
            false
        }
    }

    private fun loop() {
        val r = record ?: return
        val srcSamples = sampleRate / 10
        val shortBuf = ShortArray(srcSamples)

        try {
            r.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "startRecording failed", e)
            return
        }

        while (running) {
            val n = r.read(shortBuf, 0, shortBuf.size)
            if (n <= 0) continue

            val pcm: ByteArray = if (decimate == 1) {
                toBytes(shortBuf, n)
            } else {
                val outLen = n / decimate
                val out = ShortArray(outLen)
                for (i in 0 until outLen) {
                    var sum = 0
                    for (k in 0 until decimate) sum += shortBuf[i * decimate + k]
                    out[i] = (sum / decimate).toShort()
                }
                toBytes(out, outLen)
            }

            if (pcm.isNotEmpty()) onChunk(pcm)
        }

        try {
            r.stop()
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun toBytes(src: ShortArray, len: Int): ByteArray {
        val out = ByteArray(len * 2)
        for (i in 0 until len) {
            val v = src[i].toInt()
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null

        try { aec?.release() } catch (e: Exception) { }
        try { ns?.release() } catch (e: Exception) { }
        aec = null
        ns = null

        try { record?.release() } catch (e: Exception) { }
        record = null
    }

    companion object {
        private const val TAG = "HoloMic"
    }
}
