package dev.nikita.voice

/**
 * Run alongside ShonaPlayback.kt using the Android SDK android.jar on the Kotlin compiler classpath.
 * AudioTrack on Android rejects a PCM16 mono buffer whose byte count is not divisible by 2.
 */
fun main() {
    fun expect(rate: Int, minimum: Int, expected: Int) {
        val actual = ShonaPlayback.bufferSizeBytes(rate, minimum)
        check(actual == expected) { "rate=$rate min=$minimum expected=$expected actual=$actual" }
        check(actual >= minimum && actual % 2 == 0 && actual > 0)
    }

    // EveryVoice speaks “Kuongorora Inzwi” at 22,050 Hz. Its old 11,025-byte buffer split a frame.
    expect(22_050, 4096, 11_026)
    expect(22_050, 4097, 11_026)
    expect(22_050, 11_025, 11_026)
    expect(22_050, 11_026, 11_026)
    expect(22_050, 16_383, 16_384)
    expect(22_050, 16_384, 16_384)
    // MMS uses 16,000 Hz. Preserve the quarter-second size and larger valid device minimum.
    expect(16_000, 4096, 8000)
    expect(16_000, 4097, 8000)
    expect(16_000, 8000, 8000)
    expect(16_000, 8191, 8192)
    expect(16_000, 8192, 8192)
    expect(22_050, Int.MAX_VALUE - 1, Int.MAX_VALUE - 1)
    check(runCatching { ShonaPlayback.bufferSizeBytes(22_050, Int.MAX_VALUE) }.isFailure)
    check(runCatching { ShonaPlayback.bufferSizeBytes(22_050, -2) }.isFailure)
    check(runCatching { ShonaPlayback.bufferSizeBytes(16_000, 0) }.isFailure)
    println("Shona AudioTrack frame alignment checks passed.")
}
