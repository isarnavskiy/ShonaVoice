package dev.nikita.voice

/** Run: kotlinc SpeechFilter.kt SpeechFilterCheck.kt, then dev.nikita.voice.SpeechFilterCheckKt. */
fun main() {
    fun expect(actual: Any, expected: Any, what: String) =
        check(actual == expected) { "$what: expected <$expected>, got <$actual>" }

    expect(SpeechFilter.fixRussian("так тест перевода с руского языка"), "так тест перевода с русского языка", "руского")
    expect(SpeechFilter.fixRussian("Руская программа"), "Русская программа", "capital + correct word kept")
    expect(SpeechFilter.fixRussian("новая програма для групы"), "новая программа для группы", "програма, групы")
    expect(SpeechFilter.fixRussian("Росия и класный"), "Россия и классный", "Росия, класный")
    expect(SpeechFilter.fixRussian("класть сумку в группу"), "класть сумку в группу", "no false fixes")
    expect(SpeechFilter.fixRussian("сума денег"), "сумма денег", "сума")
    expect(SpeechFilter.fixRussian("Русаков"), "Русаков", "surname untouched")

    expect(SpeechFilter.plausible("mhoro, makadii", "sna", 1.5), true, "Shona phrase")
    expect(SpeechFilter.plausible("привет как дела", "rus", 1.5), true, "Russian phrase")
    expect(SpeechFilter.plausible("m", "sna", 1.0), false, "one letter")
    expect(SpeechFilter.plausible("hmm", "sna", 1.0), false, "no vowel")
    expect(SpeechFilter.plausible("aaaaaa", "sna", 1.0), false, "repeated letter")
    expect(SpeechFilter.plausible("ma", "sna", 4.0), false, "too short for 4 s")
    expect(SpeechFilter.plausible("a".repeat(5) + "bcdefghijk".repeat(10), "sna", 1.0), false, "too long for 1 s")
    println("SpeechFilter checks passed")
}
