package com.holo.avatar

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.min

/**
 * 扬声器播放：24kHz / 单声道 / PCM16 流式播放。
 *
 * - 队列化，收到一段播一段，不做整段缓存（低延迟）
 * - 播放前计算 RMS 作为口型包络，30Hz 上报
 * - flush() 用于打断：清空队列并立刻静音
 */
class AudioPlayer(private val onLevel: (Float) -> Unit) {

    private val queue = LinkedBlockingQueue<ByteArray>()
    private val alive = AtomicBoolean(false)

    private var track: AudioTrack? = null
    private var writerThread: Thread? = null
    private var levelThread: Thread? = null

    @Volatile
    private var level = 0f

    @Volatile
    private var playing = false

    fun start() {
        if (alive.get()) return

        val minBuf = AudioTrack.getMinBufferSize(
            RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf * 2, RATE / 10 * 2 * 2)   // ≈200ms

        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack build failed", e)
            return
        }

        if (t.state != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack not initialized")
            t.release()
            return
        }

        track = t
        alive.set(true)

        writerThread = Thread({ writeLoop() }, "holo-spk").apply { start() }
        levelThread = Thread({ levelLoop() }, "holo-level").apply { start() }
    }

    private fun writeLoop() {
        val t = track ?: return
        while (alive.get()) {
            val chunk = try {
                queue.take()
            } catch (e: InterruptedException) {
                break
            }
            if (chunk.isEmpty()) continue

            if (!playing) {
                try {
                    t.play()
                    playing = true
                } catch (e: Exception) {
                    Log.e(TAG, "play failed", e)
                }
            }

            level = rms(chunk)

            try {
                t.write(chunk, 0, chunk.size)
            } catch (e: Exception) {
                Log.e(TAG, "write failed", e)
            }
        }

        try {
            if (playing) t.stop()
        } catch (e: Exception) { }
        playing = false
    }

    /** 33ms 上报一次口型量，带衰减保证嘴能闭上 */
    private fun levelLoop() {
        while (alive.get()) {
            val v = level
            level *= 0.55f          // 无新数据时快速衰减
            onLevel(min(1f, v))
            try {
                Thread.sleep(33)
            } catch (e: InterruptedException) {
                break
            }
        }
        onLevel(0f)
    }

    private fun rms(pcm: ByteArray): Float {
        if (pcm.size < 2) return 0f
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val v = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
            sum += (v * v).toDouble()
            count++
            i += 2
        }
        if (count == 0) return 0f
        val r = Math.sqrt(sum / count) / 32768.0
        return (abs(r) * 4.0).toFloat().coerceIn(0f, 1f)
    }

    fun enqueue(pcm: ByteArray) {
        if (!alive.get() || pcm.isEmpty()) return
        if (queue.size > 60) queue.clear()   // 防止积压导致延迟膨胀
        queue.offer(pcm)
    }

    /** 打断：清空待播数据并立刻停声 */
    fun flush() {
        queue.clear()
        level = 0f
        val t = track ?: return
        try {
            if (playing) {
                t.pause()
                t.flush()
                t.play()
            }
        } catch (e: Exception) {
            Log.w(TAG, "flush failed", e)
        }
    }

    fun stop() {
        alive.set(false)
        queue.clear()
        writerThread?.interrupt()
        levelThread?.interrupt()
        writerThread = null
        levelThread = null

        try {
            track?.pause()
            track?.flush()
            track?.release()
        } catch (e: Exception) { }
        track = null
        playing = false
    }

    companion object {
        private const val TAG = "HoloSpk"
        private const val RATE = 24000
    }
}
