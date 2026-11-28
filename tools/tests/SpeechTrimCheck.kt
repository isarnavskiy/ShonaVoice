package dev.nikita.voice

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** Run alongside SpeechTrim.kt with any Kotlin/JVM compiler. */
fun main() {
    val rate = 16_000
    val random = Random(7)
    fun noise(seconds: Double, level: Float) = FloatArray((seconds * rate).toInt()) { (random.nextFloat() * 2 - 1) * level }
    fun tone(seconds: Double, level: Float) = FloatArray((seconds * rate).toInt()) {
        (level * sin(2 * PI * 220 * it / rate) + (random.nextFloat() * 2 - 1) * 0.002f).toFloat()
    }

    // 1.5 s of room noise, 2 s of "speech", 1.5 s of room noise.
    val speech = tone(2.0, 0.3f)
    val recording = noise(1.5, 0.002f) + speech + noise(1.5, 0.002f)
    val trimmed = SpeechTrim.trim(recording)
    val seconds = trimmed.size.toDouble() / rate
    check(seconds in 2.6..2.9) { "Expected speech plus padding, got $seconds s" }
    // Every speech sample survives, with the padding before it.
    val offset = (1.5 * rate).toInt() - (recording.size - trimmed.size) / 2
    check(offset > 0)
    val start = recording.indexOfFirst { kotlin.math.abs(it) > 0.1f }
    val end = recording.indexOfLast { kotlin.math.abs(it) > 0.1f }
    val trimmedStart = trimmed.indexOfFirst { kotlin.math.abs(it) > 0.1f }
    val trimmedEnd = trimmed.indexOfLast { kotlin.math.abs(it) > 0.1f }
    check(trimmedEnd - trimmedStart == end - start)
    check(trimmedStart >= (0.3 * rate).toInt() && trimmed.size - trimmedEnd >= (0.3 * rate).toInt())

    // A soft ending well above the noise floor is kept.
    val soft = noise(1.0, 0.002f) + tone(1.0, 0.3f) + tone(0.4, 0.03f) + noise(1.0, 0.002f)
    val softTrimmed = SpeechTrim.trim(soft)
    check(softTrimmed.count { kotlin.math.abs(it) > 0.02f } == soft.count { kotlin.math.abs(it) > 0.02f })

    // Without a clear contrast (continuous speech or plain noise) the recording is left as it is.
    val continuous = tone(3.0, 0.3f)
    check(SpeechTrim.trim(continuous) === continuous)
    val quiet = noise(3.0, 0.01f)
    check(SpeechTrim.trim(quiet) === quiet)
    // Short recordings and ones with little silence are untouched.
    val short = noise(0.2, 0.002f) + tone(0.5, 0.3f)
    check(SpeechTrim.trim(short) === short)
    val tight = noise(0.3, 0.002f) + tone(2.0, 0.3f) + noise(0.3, 0.002f)
    check(SpeechTrim.trim(tight) === tight)
    println("SpeechTrim checks passed")
}
