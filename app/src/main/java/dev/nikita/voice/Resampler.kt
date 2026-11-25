package dev.nikita.voice

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Mono PCM16 to the input waveform expected by MMS. No utterance-level normalization here. */
object Resampler {
    const val TARGET_RATE = 16_000

    fun to16k(input: ShortArray, length: Int, sourceRate: Int): FloatArray {
        require(length in 0..input.size)
        require(sourceRate in 8_000..192_000)
        if (length == 0) return FloatArray(0)
        if (sourceRate == TARGET_RATE) return FloatArray(length) { input[it] / 32768f }

        // A bank of Blackman-windowed low-pass sinc filters. With the usual capture
        // rates every distinct fractional sample position has its own exact filter.
        val ratio = sourceRate.toDouble() / TARGET_RATE
        val radius = ceil(24 * maxOf(1.0, ratio)).toInt()
        val cutoff = 0.46 * minOf(1.0, 1.0 / ratio)
        val phases = minOf(256, TARGET_RATE / gcd(TARGET_RATE, sourceRate))
        val bank = Array(phases + 1) { phase ->
            val fraction = phase.toDouble() / phases
            val taps = FloatArray(2 * radius + 1) { tap ->
                val x = tap - radius - fraction
                if (abs(x) >= radius) {
                    0f
                } else {
                    val sinc = if (x == 0.0) 2.0 * cutoff else sin(2 * PI * cutoff * x) / (PI * x)
                    val window = 0.42 + 0.5 * cos(PI * x / radius) + 0.08 * cos(2 * PI * x / radius)
                    (sinc * window).toFloat()
                }
            }
            val sum = taps.sum()
            for (i in taps.indices) taps[i] /= sum
            taps
        }

        // Integer positions avoid cumulative drift (especially at 44.1 kHz).
        val size = ((length.toLong() * TARGET_RATE + sourceRate / 2) / sourceRate).toInt()
        return FloatArray(size) { index ->
            val position = index.toLong() * sourceRate
            val first = (position / TARGET_RATE).toInt() - radius
            val phase = ((position % TARGET_RATE) * phases.toDouble() / TARGET_RATE).roundToInt()
            val taps = bank[phase]
            var sample = 0f
            if (first >= 0 && first + taps.size <= length) {
                for (k in taps.indices) sample += input[first + k] * taps[k]
            } else {
                for (k in taps.indices) sample += input[(first + k).coerceIn(0, length - 1)] * taps[k]
            }
            (sample / 32768f).coerceIn(-1f, 1f)
        }
    }

    private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
}
