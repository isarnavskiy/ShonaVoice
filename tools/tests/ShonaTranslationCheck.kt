package dev.nikita.voice

/** Run with ShonaTranslationText.kt on the JVM, without loading translation weights. */
fun main() {
    val mixed = "Вход по https://example.org/a_(b)?id=003, код SV-0047."
    check(ShonaTranslationText.parts(mixed).joinToString("") { it.text } == mixed)
    check(ShonaTranslationText.preserves(mixed, "Pinda pa https://example.org/a_(b)?id=003, SV-0047."))
    check(!ShonaTranslationText.preserves(mixed, "Pinda pa https://example.org/a_(b)?id=003, SV-47."))
    check(ShonaTranslationText.preserves("В 14:30.", "Tichasangana na14:30."))
    check(!ShonaTranslationText.preserves("В 14:30.", "Tichasangana na14:03."))
    check(!ShonaTranslationText.preserves("Для info@example.org.", "Ku info@example.com."))
    check(ShonaTranslationText.preserves("+263 77 123 4567", "+263 77 123 4567"))
    check(!ShonaTranslationText.preserves("Код 0047", "Kodhi 47"))
    check(ShonaTranslationText.numbers("−1 234,50 и +4 %") == listOf("-1234.50", "+4%"))
    check(ShonaTranslationText.parts("Напишите мне завтра.").isEmpty())
    check(ShonaTranslationText.inlineTranslation("код", "kodhi.") == "kodhi")
    check(ShonaTranslationText.parts("info@example.org").none { it.translate })
}
