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
    class Chunk(val seconds: Double, val phrases: List<Double>)
    fun run(vararg parts: ShortArray, minSeconds: Int = 12): List<Chunk> {
        val out = mutableListOf<Chunk>()
        val segmenter = LiveSegmenter(rate, { pcm, marks ->
            out.add(Chunk(pcm.size.toDouble() / rate, marks.map { it.toDouble() / rate }))
        }, minSeconds = minSeconds)
        val all = parts.fold(ShortArray(0)) { a, b -> a + b }
        var i = 0
        while (i + segmenter.frame <= all.size) { segmenter.push(all, i); i += segmenter.frame }
        segmenter.flush()
        return out
    }
    fun talk(phrases: Int, phrase: Double, pause: Double): Array<ShortArray> =
        Array(phrases * 2) { if (it % 2 == 0) speech(phrase) else noise(pause) }

    // A long talk with one-second pauses between 4 s phrases becomes chunks of 12-30 s,
    // each split into its phrases, and nothing is lost.
    val talk = run(noise(1.0), *talk(12, 4.0, 1.0), noise(3.0))
    check(talk.size in 2..4) { "chunks ${talk.map { it.seconds }}" }
    check(talk.dropLast(1).all { it.seconds in 12.0..30.5 }) { "chunk lengths ${talk.map { it.seconds }}" }
    val speechSeconds = 12 * 4.0
    check(talk.sumOf { it.seconds } >= speechSeconds) { "lost audio ${talk.map { it.seconds }}" }
    // Pauses are shortened to 0.4 s, so a chunk is mostly speech.
    check(talk.sumOf { it.seconds } <= speechSeconds + 12 * 0.5 + 1.0) { "kept silence ${talk.map { it.seconds }}" }
    check(talk.first().phrases.size >= 2) { "phrase marks ${talk.first().phrases}" }

    // When the speaker stops for two seconds, the chunk ends even if it is short.
    val short = run(noise(1.0), speech(3.0), noise(2.5), speech(2.0), noise(2.5))
    check(short.size == 2 && short[0].seconds in 3.0..3.8) { "short ${short.map { it.seconds }}" }

    // A short pause inside a phrase neither splits the chunk nor marks a phrase.
    val joined = run(noise(1.0), speech(1.0), noise(0.3), speech(1.0), noise(3.0))
    check(joined.size == 1 && joined[0].seconds in 2.4..3.0 && joined[0].phrases.isEmpty()) {
        "joined ${joined.map { it.seconds }} ${joined.firstOrNull()?.phrases}"
    }

    // Speech without any pause is cut at 30 s and the rest continues in the next chunk.
    val long = run(noise(1.0), speech(70.0), noise(3.0))
    check(long.size == 3 && long.dropLast(1).all { it.seconds <= 30.4 } && long.sumOf { it.seconds } >= 70.0) {
        "long ${long.map { it.seconds }}"
    }

    // A pause in the second half is preferred over a cut in the middle of a word.
    val breath = run(noise(1.0), *talk(4, 7.5, 0.6), noise(3.0), minSeconds = 40)
    check(breath[0].seconds in 15.0..30.0 && breath.sumOf { it.seconds } >= 30.0) { "breath ${breath.map { it.seconds }}" }

    // Noise alone and very short clicks produce nothing.
    check(run(noise(5.0)).isEmpty())
    check(run(noise(1.0), speech(0.1), noise(3.0)).isEmpty())

    // Stopping in the middle of a phrase still delivers it.
    val stopped = run(noise(1.0), speech(2.0))
    check(stopped.size == 1 && stopped[0].seconds >= 2.0) { "stopped ${stopped.map { it.seconds }}" }
    println("LiveSegmenter checks passed")
}
