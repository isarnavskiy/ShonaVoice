package dev.nikita.voice

import kotlin.math.log10

/**
 * Energy-based endpointing for push-to-talk recordings at 16 kHz.
 *
 * People usually press the microphone a moment before speaking and release it after a pause.
 * MMS-1B spends the same compute on that silence as on speech, and background noise in it can
 * turn into stray letters. The trim keeps generous padding around the loudest region and leaves
 * the recording untouched when it cannot find a clear speech/silence contrast.
 */
internal object SpeechTrim {
    private const val FRAME = 320 // 20 ms
    private const val PAD_FRAMES = 18 // 360 ms on each side keeps soft onsets and final consonants
    private const val MIN_GAIN_FRAMES = 15 // Do nothing for less than 300 ms of savings.

    fun trim(audio: FloatArray): FloatArray {
        val frames = audio.size / FRAME
        if (frames < 2 * PAD_FRAMES + MIN_GAIN_FRAMES) return audio
        val level = DoubleArray(frames) { frame ->
            var sum = 0.0
            var mean = 0.0
            val start = frame * FRAME
            for (i in start until start + FRAME) mean += audio[i]
            mean /= FRAME
            for (i in start until start + FRAME) {
                val centered = audio[i] - mean
                sum += centered * centered
            }
            10 * log10(sum / FRAME + 1e-12)
        }
        val sorted = level.sorted()
        val floor = sorted[frames / 10]
        val peak = sorted[frames - 1 - frames / 50]
        // Without at least 15 dB between the quiet frames and speech the recording is noise
        // or continuous speech. Either way the full signal is safer.
        if (peak - floor < 15.0) return audio
        val threshold = maxOf(floor + 0.3 * (peak - floor), -55.0)
        val first = level.indexOfFirst { it >= threshold }
        val last = level.indexOfLast { it >= threshold }
        if (first < 0) return audio
        val startFrame = (first - PAD_FRAMES).coerceAtLeast(0)
        val endFrame = (last + 1 + PAD_FRAMES).coerceAtMost(frames)
        val startSample = startFrame * FRAME
        val endSample = if (endFrame == frames) audio.size else endFrame * FRAME
        if (audio.size - (endSample - startSample) < MIN_GAIN_FRAMES * FRAME) return audio
        return audio.copyOfRange(startSample, endSample)
    }
}
