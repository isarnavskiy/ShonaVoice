package dev.nikita.voice

import kotlin.math.log10

/**
 * Splits a continuous microphone stream into chunks for hands-free translation of long talks.
 *
 * Audio arrives in 20 ms frames. Speech starts a chunk after 60 ms (with 300 ms of the audio
 * before it). A chunk ends at the first pause of [pauseMs] once it holds [minSeconds] of audio,
 * or at any pause of [endPauseMs] (the speaker stopped). Speech without such a pause is cut at
 * [maxSeconds]: at the last breath pause in its second half, otherwise at the quietest frame of
 * its last three seconds, and the rest starts the next chunk, so no audio is dropped.
 *
 * Inside a chunk, pauses longer than 0.4 s are shortened to 0.4 s and their middles are reported
 * as phrase boundaries, so the chunk can be recognized and translated phrase by phrase.
 * The noise floor adapts to the room.
 */
internal class LiveSegmenter(
    private val rate: Int,
    /** A chunk and the sample offsets of its phrase boundaries. */
    private val emit: (ShortArray, IntArray) -> Unit,
    private val onSpeech: (Boolean) -> Unit = {},
    pauseMs: Int = 700,
    endPauseMs: Int = 1800,
    minSeconds: Int = 12,
    maxSeconds: Int = 30,
) {
    val frame = (rate / 50).coerceAtLeast(1)
    private val pauseFrames = pauseMs / 20
    private val endPauseFrames = endPauseMs / 20
    private val minFrames = minSeconds * 50
    private val maxFrames = maxSeconds * 50
    private val prerollFrames = 15
    private val tailFrames = 15
    private val keptSilence = 20 // Longer pauses are shortened to 0.4 s inside a chunk.
    private val startFrames = 3
    private val minVoicedFrames = 20 // 0.4 s of speech: shorter fragments are mostly clicks or cut words.

    private val samples = ShortArray((maxFrames + prerollFrames + 2) * frame)
    private val levels = DoubleArray(maxFrames + prerollFrames + 2)
    private val boundaries = ArrayList<Int>() // Frame indices of phrase boundaries in the chunk.
    private var frames = 0 // Frames in the current chunk, or in the pre-roll while idle.
    private var inSegment = false
    private var voiced = 0
    private var silence = 0
    private var run = 0
    private var floor = Double.NaN
    private var speaking = false

    /** Seconds of audio in the chunk being recorded, 0 between chunks. */
    @Volatile var chunkSeconds = 0.0
        private set

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
                boundaries.clear()
                setSpeaking(true)
            }
            return
        }

        if (speech) {
            // Speech after a pause of at least 0.4 s: mark the middle of the kept pause as a phrase boundary.
            if (silence >= keptSilence) boundaries.add(frames - keptSilence / 2)
            append(chunk, offset, db)
            voiced++
            silence = 0
            setSpeaking(true)
        } else {
            silence++
            if (silence <= keptSilence) append(chunk, offset, db)
            if (silence >= 8) setSpeaking(false)
        }
        chunkSeconds = frames / 50.0
        if (silence >= endPauseFrames || (silence >= pauseFrames && frames >= minFrames)) {
            finish(endOfSpeech())
        } else if (frames >= maxFrames) {
            // Continuous speech: prefer a breath pause in the second half, else the quietest moment.
            val pause = boundaries.lastOrNull { it >= frames / 2 && it < frames - 10 }
            val cut = pause ?: quietestCut()
            emit(samples.copyOfRange(0, cut * frame), offsets(cut))
            val remainder = frames - cut
            val later = boundaries.filter { it > cut }.map { it - cut }
            shift(cut)
            frames = remainder
            voiced = remainder
            boundaries.clear()
            boundaries.addAll(later)
            chunkSeconds = frames / 50.0
        }
    }

    /** Emits the chunk in progress, if it contains speech. */
    fun flush() {
        if (inSegment) finish(endOfSpeech())
        frames = 0
        inSegment = false
        chunkSeconds = 0.0
        setSpeaking(false)
    }

    private fun quietestCut(): Int {
        val searchFrom = maxOf(frames - 150, frames / 2)
        var quietest = frames - 1
        for (i in searchFrom until frames) if (levels[i] < levels[quietest]) quietest = i
        return quietest + 1
    }

    /** End of the chunk: the last speech frame plus a short tail. */
    private fun endOfSpeech(): Int = (frames - minOf(silence, keptSilence) + tailFrames).coerceAtMost(frames)

    private fun offsets(end: Int): IntArray = boundaries.filter { it in 1 until end }.map { it * frame }.toIntArray()

    private fun finish(keep: Int) {
        if (voiced >= minVoicedFrames) emit(samples.copyOfRange(0, keep * frame), offsets(keep))
        frames = 0
        inSegment = false
        voiced = 0
        silence = 0
        run = 0
        boundaries.clear()
        chunkSeconds = 0.0
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
