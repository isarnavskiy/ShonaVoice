package dev.nikita.voice

/** Checks on recognized text before it is translated in continuous and simultaneous modes. */
internal object SpeechFilter {
    private val shonaVowels = "aeiou".toSet()
    private val russianVowels = "аеёиоуыэюя".toSet()

    /**
     * False for typical recognizer output on noise, breathing or the app's own echo: no vowel,
     * a single repeated letter, or far fewer letters than the audio could hold.
     */
    fun plausible(text: String, lang: String, seconds: Double): Boolean {
        val letters = text.lowercase().filter { it.isLetter() }
        if (letters.length < 2) return false
        val vowels = if (lang == "sna") shonaVowels else russianVowels
        if (letters.none { it in vowels }) return false
        val top = letters.groupingBy { it }.eachCount().values.max()
        if (letters.length >= 4 && top * 10 > letters.length * 6) return false
        if (seconds >= 3.0 && letters.length < 3) return false
        if (letters.length > seconds * 30 + 10) return false
        return true
    }

    // Explicit letter boundaries: \b is ASCII-only in some regex engines.
    private const val START = "(?<![\\p{L}])"
    private const val END = "(?![\\p{L}])"

    private val russianFixes = listOf(
        // MMS often drops one of a doubled consonant. These stems are unambiguous in Russian.
        Regex("(?iu)${START}рус(к)") to "русс$1",
        Regex("(?iu)${START}белорус(к)") to "белорусс$1",
        Regex("(?iu)${START}роси(я|и|ю|ей|йск)") to "росси$1",
        Regex("(?iu)${START}прогам") to "програм",
        Regex("(?iu)${START}програм(?!м)") to "программ",
        Regex("(?iu)${START}клас(?![сcт])") to "класс",
        Regex("(?iu)${START}груп(а|ы|е|у|ой|ам|ах|ами)$END") to "групп$1",
        Regex("(?iu)${START}сум(а|ы|е|у|ой)$END") to "сумм$1",
        Regex("(?iu)${START}адресс") to "адрес",
    )

    /** Repairs frequent spelling slips of the Russian recognizer that derail translation. */
    fun fixRussian(text: String): String {
        var result = text
        for ((pattern, replacement) in russianFixes) {
            result = pattern.replace(result) { match ->
                val fixed = replacement.replace("$1", match.groupValues.getOrElse(1) { "" })
                if (match.value.first().isUpperCase()) fixed.replaceFirstChar { it.uppercaseChar() } else fixed
            }
        }
        return result
    }
}
