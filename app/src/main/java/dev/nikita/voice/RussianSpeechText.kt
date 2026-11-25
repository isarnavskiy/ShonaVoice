package dev.nikita.voice

import java.util.Locale

/** Convert numbers to words because the Russian MMS voice only knows a few digit symbols. */
internal object RussianSpeechText {
    private val digits = listOf("ноль", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять")
    private val teens = listOf("десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать", "пятнадцать", "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать")
    private val tens = listOf("", "", "двадцать", "тридцать", "сорок", "пятьдесят", "шестьдесят", "семьдесят", "восемьдесят", "девяносто")
    private val hundreds = listOf("", "сто", "двести", "триста", "четыреста", "пятьсот", "шестьсот", "семьсот", "восемьсот", "девятьсот")
    private val units = listOf(
        listOf("", "", ""),
        listOf("тысяча", "тысячи", "тысяч"),
        listOf("миллион", "миллиона", "миллионов"),
        listOf("миллиард", "миллиарда", "миллиардов")
    )
    private val alphabet = listOf("эй","би","си","ди","и","эф","джи","эйч","ай","джей","кей","эл","эм","эн","оу","пи","кью","ар","эс","ти","ю","ви","дабл ю","икс","уай","зет")
    private val quantities = Regex("""[0-9]+(?:[.,][0-9]+)?""")
    private val latin = Regex("[a-z]+")
    private val punctuation = Regex("""[^а-я\s]+""")
    private val whitespace = Regex("""\s+""")

    private fun spellDigits(value: String) = value.map { digits[it - '0'] }.joinToString(" ")

    private fun integer(value: String): String {
        if (value == "0" || value.length > 12 || value.startsWith("0")) return spellDigits(value)
        val groups = value.reversed().chunked(3).map { it.reversed().toInt() }
        val words = mutableListOf<String>()
        for (place in groups.indices.reversed()) {
            val group = groups[place]
            if (group == 0) continue
            if (group >= 100) words += hundreds[group / 100]
            val rest = group % 100
            val last = rest % 10
            if (rest in 10..19) {
                words += teens[rest - 10]
            } else {
                if (rest >= 20) words += tens[rest / 10]
                if (last > 0) words += when {
                    place == 1 && last == 1 -> "одна"
                    place == 1 && last == 2 -> "две"
                    else -> digits[last]
                }
            }
            if (place > 0) {
                val form = when {
                    rest in 11..14 -> 2
                    last == 1 -> 0
                    last in 2..4 -> 1
                    else -> 2
                }
                words += units[place][form]
            }
        }
        return words.joinToString(" ")
    }

    fun normalize(text: String): String {
        val uniform = text.lowercase(Locale.ROOT).replace('ё', 'е').map {
            val digit = Character.digit(it, 10)
            if (digit >= 0) ('0'.code + digit).toChar() else it
        }.joinToString("")
        val grouped = uniform.replace(Regex("""(?<=[0-9])[ \u00a0\u202f](?=[0-9]{3}(?![0-9]))"""), "")
        val signed = grouped.replace(Regex("""(?<=[0-9])[-–](?=[0-9])"""), " тире ")
            .replace(Regex("""[-−](?=[0-9])"""), " минус ")
            .replace("+", " плюс ").replace("%", " процентов ").replace("№", " номер ")
            .replace(Regex("""(?<=[0-9])/(?=[0-9])"""), " дробь ")
        val numbers = quantities.replace(signed) { match ->
            val parts = match.value.split('.', ',')
            val fraction = if (parts.size == 2) " запятая " + spellDigits(parts[1]) else ""
            " " + integer(parts[0]) + fraction + " "
        }
        val letters = latin.replace(numbers) { match -> match.value.map { alphabet[it - 'a'] }.joinToString(" ", " ", " ") }
        val cleaned = letters.replace(punctuation, " ").replace(whitespace, " ").trim()
        require(cleaned.isNotEmpty()) { "В тексте нет букв или цифр для русского голоса." }
        return cleaned
    }
}
