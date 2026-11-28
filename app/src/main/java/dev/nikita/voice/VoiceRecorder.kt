package dev.nikita.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class VoiceRecorder {
    companion object {
        const val MAX_SECONDS = 30

        @SuppressLint("MissingPermission")
        internal fun openMicrophone(): AudioRecord {
            // Omitting the rate allows Android to pick the route default. Some OEMs
            // require an explicit one. Always use sampleRate from the opened recorder.
            for (requested in intArrayOf(0, 48_000, 44_100, 16_000)) {
                val format = AudioFormat.Builder()
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                if (requested > 0) format.setSampleRate(requested)
                val mic = try {
                    AudioRecord.Builder()
                        .setAudioSource(MediaRecorder.AudioSource.MIC)
                        .setAudioFormat(format.build())
                        .build()
                } catch (_: IllegalArgumentException) {
                    continue
                } catch (_: UnsupportedOperationException) {
                    continue
                }
                if (mic.state == AudioRecord.STATE_INITIALIZED && mic.sampleRate in 8_000..192_000 && mic.channelCount == 1) {
                    return mic
                }
                mic.release()
            }
            error("Не удалось открыть микрофон в режиме моно PCM16.")
        }
    }

    private class Capture {
        @Volatile var stopAtNanos = Long.MAX_VALUE
        @Volatile var cancelled = false
        val done = CountDownLatch(1)
        var samples = ShortArray(0)
        var count = 0
        var rate = 0
        var error: Exception? = null
    }

    private var active: Capture? = null
    private val hardwareLock = Any()

    /** The callback runs on the capture thread on timeout or failure. Call finish() on a worker. */
    @Synchronized
    fun start(onStarted: () -> Unit, onLimit: () -> Unit) {
        check(active == null) { "Запись уже идёт." }
        val session = Capture()
        active = session
        thread(name = "Shona Voice microphone") {
            try {
                synchronized(hardwareLock) {
                    if (!session.cancelled && session.stopAtNanos == Long.MAX_VALUE) capture(session, onStarted)
                }
            } catch (e: Exception) {
                session.error = e
            } finally {
                session.done.countDown()
            }
            if (!session.cancelled && session.stopAtNanos == Long.MAX_VALUE) onLimit()
        }
    }

    /** Freeze this recording on release. Its audio can be collected later on the inference worker. */
    @Synchronized
    fun stopCapture(): () -> FloatArray {
        val session = checkNotNull(active) { "Нет активной записи." }
        session.stopAtNanos = SystemClock.elapsedRealtimeNanos()
        active = null
        return {
            session.done.await()
            session.error?.let { throw it }
            require(session.count > 0 && session.count >= session.rate / 5) {
                "Слишком короткая запись. Удерживайте кнопку, пока говорите."
            }
            Resampler.to16k(session.samples, session.count, session.rate)
        }
    }

    @Synchronized
    fun cancel() {
        active?.cancelled = true
        active = null
    }

    @SuppressLint("MissingPermission")
    private fun capture(session: Capture, onStarted: () -> Unit) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val mic = openMicrophone()
        try {
            if (session.cancelled || session.stopAtNanos != Long.MAX_VALUE) return
            val rate = mic.sampleRate
            val samples = ShortArray(rate * MAX_SECONDS)
            session.rate = rate
            session.samples = samples
            val startedAt = SystemClock.elapsedRealtimeNanos()
            mic.startRecording()
            check(mic.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Не удалось начать запись с микрофона." }
            onStarted()
            var lastFrameAt = SystemClock.elapsedRealtime()
            val chunk = maxOf(1, rate / 25)
            while (!session.cancelled) {
                val stopAt = session.stopAtNanos
                // A route can deliver its last packet slightly after the wall clock reaches 30 s.
                // End automatic capture at the sample boundary; retain queued audio on manual stop.
                val limit = if (stopAt == Long.MAX_VALUE) samples.size else {
                    val elapsed = (stopAt - startedAt).coerceIn(0L, MAX_SECONDS * 1_000_000_000L)
                    (elapsed * rate / 1_000_000_000L).toInt()
                }
                val wanted = minOf(chunk, limit - session.count)
                if (wanted <= 0) {
                    // A read that was in flight at manual release may finish after the cutoff.
                    session.count = minOf(session.count, limit)
                    break
                }
                val n = mic.read(samples, session.count, wanted, AudioRecord.READ_NON_BLOCKING)
                when {
                    n > 0 -> {
                        session.count += n
                        lastFrameAt = SystemClock.elapsedRealtime()
                    }
                    n == 0 -> {
                        if (stopAt != Long.MAX_VALUE) break
                        check(SystemClock.elapsedRealtime() - lastFrameAt < 2_000) { "Микрофон перестал передавать звук." }
                        SystemClock.sleep(5)
                    }
                    else -> error("Ошибка чтения микрофона (код ${n}).")
                }
            }
        } finally {
            try {
                if (mic.recordingState == AudioRecord.RECORDSTATE_RECORDING) mic.stop()
            } finally {
                mic.release()
            }
        }
    }
}
