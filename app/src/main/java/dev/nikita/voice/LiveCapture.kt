package dev.nikita.voice

import android.media.AudioRecord
import android.os.Process
import kotlin.concurrent.thread

/**
 * Microphone loop for hands-free translation of long talks. Recording never pauses and has no
 * time limit: each chunk found by [LiveSegmenter] is handed to [onSegment] (on the capture thread)
 * with its phrase boundaries while capture continues, so recognition and translation of one chunk
 * overlap with recording of the next.
 */
internal class LiveCapture(
    /** The phone's own microphone, so a plugged-in headset does not record instead of the room. */
    private val preferredInput: android.media.AudioDeviceInfo?,
    /** Chunk samples, phrase boundary offsets and the sample rate. */
    private val onSegment: (ShortArray, IntArray, Int) -> Unit,
    private val onSpeech: (Boolean) -> Unit,
    private val onClosed: (Exception?) -> Unit,
) {
    @Volatile private var stopping = false
    @Volatile private var cancelled = false
    @Volatile private var segmenter: LiveSegmenter? = null

    /** Seconds recorded in the chunk in progress. */
    fun chunkSeconds(): Double = segmenter?.chunkSeconds ?: 0.0

    fun start() {
        thread(name = "Shona Voice live capture") {
            var failure: Exception? = null
            try {
                capture()
            } catch (e: Exception) {
                failure = e
            }
            if (!cancelled) onClosed(failure)
        }
    }

    /** Stop listening. The chunk in progress is still recognized and translated. */
    fun stop() { stopping = true }

    /** Stop listening and drop the chunk in progress. */
    fun cancel() {
        cancelled = true
        stopping = true
    }

    private fun capture() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val mic = VoiceRecorder.openMicrophone()
        preferredInput?.let { runCatching { mic.setPreferredDevice(it) } }
        try {
            val rate = mic.sampleRate
            val segmenter = LiveSegmenter(rate, { pcm, marks -> onSegment(pcm, marks, rate) }, onSpeech)
            this.segmenter = segmenter
            val chunk = ShortArray(segmenter.frame)
            mic.startRecording()
            check(mic.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Не удалось начать запись с микрофона." }
            while (!stopping) {
                var filled = 0
                while (filled < chunk.size && !stopping) {
                    val n = mic.read(chunk, filled, chunk.size - filled)
                    check(n >= 0) { "Ошибка чтения микрофона (код $n)." }
                    filled += n
                }
                if (filled == chunk.size) segmenter.push(chunk)
            }
            if (!cancelled) segmenter.flush()
        } finally {
            try {
                if (mic.recordingState == AudioRecord.RECORDSTATE_RECORDING) mic.stop()
            } finally {
                mic.release()
            }
        }
    }
}
