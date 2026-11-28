package dev.nikita.voice

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** Run alongside LiveSegmenter.kt with any Kotlin/JVM compiler. */
fun main() {
    val rate = 48_000
    val random = Random(3)
    fun noise(seconds: Double) = ShortArray((seconds * rate).toInt()) { (random.nextInt(-60, 60)).toShort() }
    fun speech(seconds: Double) = ShortArray((seconds * rate).toInt()) {
        (9000 * sin(2 * PI * 180 * it / rate) * (0.6 + 0.4 * sin(2 * PI * 3 * it / rate)) + random.nextInt(-60, 60)).toInt().toShort()
    }
    fun run(vararg parts: ShortArray, flush: Boolean = true): List<Double> {
        val out = mutableListOf<Double>()
        val segmenter = LiveSegmenter(rate, { out.add(it.size.toDouble() / rate) })
        val all = parts.fold(ShortArray(0)) { a, b -> a + b }
        var i = 0
        while (i + segmenter.frame <= all.size) { segmenter.push(all, i); i += segmenter.frame }
        if (flush) segmenter.flush()
        return out
    }

    // Two phrases separated by a one-second pause become two segments with padding.
    val two = run(noise(1.0), speech(2.0), noise(1.0), speech(1.5), noise(1.0))
    check(two.size == 2) { "expected 2 segments, got $two" }
    check(two[0] in 2.2..2.8 && two[1] in 1.7..2.3) { "lengths $two" }

    // A short pause inside a phrase does not split it.
    val joined = run(noise(1.0), speech(1.0), noise(0.3), speech(1.0), noise(1.0))
    check(joined.size == 1 && joined[0] in 2.4..3.0) { "joined $joined" }

    // Continuous speech is cut at the 25 s limit and the rest is kept for the next segment.
    val long = run(noise(1.0), speech(40.0), noise(1.0))
    check(long.size == 2 && long[0] <= 25.4 && long.sum() >= 40.0) { "long $long" }

    // Noise alone and very short clicks produce nothing.
    check(run(noise(5.0)).isEmpty())
    check(run(noise(1.0), speech(0.1), noise(2.0)).isEmpty())

    // Stopping in the middle of a phrase still delivers it.
    val stopped = run(noise(1.0), speech(2.0))
    check(stopped.size == 1 && stopped[0] >= 2.0) { "stopped $stopped" }
    println("LiveSegmenter checks passed")
}
