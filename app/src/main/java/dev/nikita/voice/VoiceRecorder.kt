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
    companion object { const val MAX_SECONDS = 12 }

    private class Capture {
        @Volatile var stopRequested = false
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
    fun start(onLimit: () -> Unit) {
        check(active == null) { "Запись уже идёт." }
        val session = Capture()
        active = session
        thread(name = "Shona Voice microphone") {
            try {
                synchronized(hardwareLock) {
                    if (!session.stopRequested) capture(session)
                }
            } catch (e: Exception) {
                session.error = e
            } finally {
                session.done.countDown()
            }
            if (!session.stopRequested) onLimit()
        }
    }

    /** Freeze this recording on release. Its audio can be collected later on the inference worker. */
    @Synchronized
    fun stopCapture(): () -> FloatArray {
        val session = checkNotNull(active) { "Нет активной записи." }
        session.stopRequested = true
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
        active?.stopRequested = true
        active = null
    }

    @SuppressLint("MissingPermission")
    private fun capture(session: Capture) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val mic = openMicrophone()
        try {
            if (session.stopRequested) return
            val rate = mic.sampleRate
            val samples = ShortArray(rate * MAX_SECONDS)
            session.rate = rate
            session.samples = samples
            mic.startRecording()
            check(mic.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Не удалось начать запись с микрофона." }
            var lastFrameAt = SystemClock.elapsedRealtime()
            val deadline = lastFrameAt + MAX_SECONDS * 1_000L
            val chunk = maxOf(1, rate / 25)
            while (!session.stopRequested && session.count < samples.size && SystemClock.elapsedRealtime() < deadline) {
                val wanted = minOf(chunk, samples.size - session.count)
                val n = mic.read(samples, session.count, wanted, AudioRecord.READ_NON_BLOCKING)
                when {
                    n > 0 -> {
                        session.count += n
                        lastFrameAt = SystemClock.elapsedRealtime()
                    }
                    n == 0 -> {
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

    @SuppressLint("MissingPermission")
    private fun openMicrophone(): AudioRecord {
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
