package dev.nikita.voice

import android.media.AudioRecord
import android.os.Process
import kotlin.concurrent.thread

/**
 * Microphone loop for hands-free translation. Recording never pauses: each phrase found by
 * [LiveSegmenter] is handed to [onSegment] (on the capture thread) while capture continues,
 * so recognition and translation of one phrase overlap with recording of the next.
 */
internal class LiveCapture(
    private val source: Int,
    /** The phone's own microphone, so a headset worn by the listener does not record the room. */
    private val preferredInput: android.media.AudioDeviceInfo?,
    private val onSegment: (ShortArray, Int) -> Unit,
    private val onSpeech: (Boolean) -> Unit,
    private val onClosed: (Exception?) -> Unit,
) {
    @Volatile private var stopping = false
    @Volatile private var cancelled = false
    @Volatile private var segmenter: LiveSegmenter? = null
    @Volatile private var pendingStrict = false

    /** Raise the speech threshold while the app's voice plays through the loudspeaker. */
    fun setStrict(value: Boolean) {
        pendingStrict = value
        segmenter?.strict = value
    }

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

    /** Stop listening. The phrase in progress is still recognized and translated. */
    fun stop() { stopping = true }

    /** Stop listening and drop the phrase in progress. */
    fun cancel() {
        cancelled = true
        stopping = true
    }

    private fun capture() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val mic = VoiceRecorder.openMicrophone(source)
        preferredInput?.let { runCatching { mic.setPreferredDevice(it) } }
        val effects = mutableListOf<android.media.audiofx.AudioEffect>()
        try {
            // Voice-communication capture enables the platform echo canceller on most phones;
            // attach it and noise suppression explicitly where the device offers them.
            if (source == android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
                runCatching {
                    if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
                        android.media.audiofx.AcousticEchoCanceler.create(mic.audioSessionId)?.let { it.enabled = true; effects.add(it) }
                    }
                    if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                        android.media.audiofx.NoiseSuppressor.create(mic.audioSessionId)?.let { it.enabled = true; effects.add(it) }
                    }
                }
            }
            val rate = mic.sampleRate
            val segmenter = LiveSegmenter(rate, { onSegment(it, rate) }, onSpeech)
            segmenter.strict = pendingStrict
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
            effects.forEach { runCatching { it.release() } }
            try {
                if (mic.recordingState == AudioRecord.RECORDSTATE_RECORDING) mic.stop()
            } finally {
                mic.release()
            }
        }
    }
}
