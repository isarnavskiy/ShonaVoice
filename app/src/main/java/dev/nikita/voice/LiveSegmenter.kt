package dev.nikita.voice

import kotlin.math.log10

/**
 * Splits a continuous microphone stream into phrases for hands-free translation.
 *
 * Audio arrives in 20 ms frames. A phrase starts after 60 ms of speech (with 300 ms of the audio
 * before it) and ends after [pauseMs] of silence. Speech without a pause is cut at the quietest
 * frame of its last three seconds once it reaches [maxSeconds], and the rest starts the next
 * phrase, so no audio is dropped between segments. The noise floor adapts to the room.
 */
internal class LiveSegmenter(
    private val rate: Int,
    private val emit: (ShortArray) -> Unit,
    private val onSpeech: (Boolean) -> Unit = {},
    pauseMs: Int = 700,
    maxSeconds: Int = 25,
) {
    val frame = (rate / 50).coerceAtLeast(1)
    private val pauseFrames = pauseMs / 20
    private val maxFrames = maxSeconds * 50
    private val prerollFrames = 15
    private val tailFrames = 15
    private val startFrames = 3
    private val minVoicedFrames = 15

    private val samples = ShortArray((maxFrames + prerollFrames + 2) * frame)
    private val levels = DoubleArray(maxFrames + prerollFrames + 2)
    private var frames = 0 // Frames in the current segment, or in the pre-roll while idle.
    private var inSegment = false
    private var voiced = 0
    private var silence = 0
    private var run = 0
    private var floor = Double.NaN
    private var speaking = false

    /** Accepts exactly [frame] samples. */
    fun push(chunk: ShortArray, offset: Int = 0) {
        val db = level(chunk, offset)
        floor = when {
            floor.isNaN() -> db
            db < floor -> db
            else -> floor + 0.05 // Rises 2.5 dB per second, so speech never becomes the floor.
        }
        val speech = db > maxOf(floor + 12.0, -58.0)
        run = if (speech) run + 1 else 0

        if (!inSegment) {
            if (frames == prerollFrames) shift(1)
            append(chunk, offset, db)
            if (run >= startFrames) {
                inSegment = true
                voiced = run
                silence = 0
                setSpeaking(true)
            }
            return
        }

        append(chunk, offset, db)
        if (speech) {
            voiced++
            silence = 0
            setSpeaking(true)
        } else {
            silence++
            if (silence >= 8) setSpeaking(false)
        }
        if (silence >= pauseFrames) {
            // Keep a short tail after the last speech frame.
            val keep = frames - silence + tailFrames
            finish(keep.coerceAtMost(frames))
        } else if (frames >= maxFrames) {
            // Continuous speech: cut at the quietest moment of the last three seconds.
            val searchFrom = maxOf(frames - 150, frames / 2)
            var cut = frames - 1
            for (i in searchFrom until frames) if (levels[i] < levels[cut]) cut = i
            val remainder = frames - (cut + 1)
            val part = samples.copyOfRange(0, (cut + 1) * frame)
            emit(part)
            shift(cut + 1)
            frames = remainder
            voiced = remainder
        }
    }

    /** Emits the phrase in progress, if it contains speech. */
    fun flush() {
        if (inSegment) finish(frames)
        frames = 0
        inSegment = false
        setSpeaking(false)
    }

    private fun finish(keep: Int) {
        if (voiced >= minVoicedFrames) emit(samples.copyOfRange(0, keep * frame))
        frames = 0
        inSegment = false
        voiced = 0
        silence = 0
        run = 0
    }

    private fun append(chunk: ShortArray, offset: Int, db: Double) {
        System.arraycopy(chunk, offset, samples, frames * frame, frame)
        levels[frames] = db
        frames++
    }

    private fun shift(count: Int) {
        val remaining = frames - count
        if (remaining > 0) {
            System.arraycopy(samples, count * frame, samples, 0, remaining * frame)
            System.arraycopy(levels, count, levels, 0, remaining)
        }
        frames = remaining.coerceAtLeast(0)
    }

    private fun setSpeaking(value: Boolean) {
        if (value != speaking) {
            speaking = value
            onSpeech(value)
        }
    }

    private fun level(chunk: ShortArray, offset: Int): Double {
        var mean = 0.0
        for (i in offset until offset + frame) mean += chunk[i]
        mean /= frame
        var sum = 0.0
        for (i in offset until offset + frame) {
            val v = (chunk[i] - mean) / 32768.0
            sum += v * v
        }
        return 10 * log10(sum / frame + 1e-12)
    }
}
