package dev.nikita.voice

import java.time.YearMonth

/** Spell decimal digits individually, independent of Shona noun-class agreement. */
internal object ShonaSpeechText {
    // S. Chirume, Journal of Pan African Studies 12(3), Table 1. Zero: Africa Phrasebook, Shona.
    private val digits = listOf("ziro", "motsi", "piri", "tatu", "ina", "shanu", "tanhatu", "nomwe", "sere", "pfumbamwe")
    private val number = Regex("""\p{N}""")
    private val nonDecimalNumber = setOf(Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt())
    private val numericSigns = setOf(0x2030, 0x00B0, '#'.code, 0x2116)
    private val spaces = Regex("""[ \t]{2,}""")

    private val decimalDigit = Regex("""\p{Nd}""")
    private val joinedClockPrefix = Regex("""(?i)(?<![\p{L}\p{N}])(na|pa)(?=[0-9]{1,2}:[0-9]{2}(?![0-9]))""")
    private val dateOrTime = Regex(
        """(?<![\p{L}\p{N}])(?<![0-9][./:])""" +
        """(?:[0-9]{4}-[0-9]{2}-[0-9]{2}|[0-9]{1,2}/[0-9]{1,2}/[0-9]{4}|""" +
        """[0-9]{1,2}\.[0-9]{1,2}\.[0-9]{4}|[0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?)""" +
        """(?![\p{L}\p{N}]|[./:][0-9])"""
    )

    private fun writtenNumbers(text: String) = decimalDigit.replace(text) {
        Character.digit(it.value.codePointAt(0), 10).toString()
    }.replace(joinedClockPrefix, "$1 ")

    private fun groups(value: String): List<String>? {
        val parts = value.split('-', '.', '/', ':')
        if (':' in value) {
            return parts.takeIf { it[0].toInt() <= 23 && it.drop(1).all { part -> part.toInt() <= 59 } }
        }
        val iso = '-' in value
        val year = parts[if (iso) 0 else 2].toInt()
        val month = parts[1].toInt()
        val day = parts[if (iso) 2 else 0].toInt()
        return parts.takeIf { year > 0 && month in 1..12 && day in 1..YearMonth.of(year, month).lengthOfMonth() }
    }

    fun hasNumberGroups(text: String): Boolean =
        dateOrTime.findAll(writtenNumbers(text)).any { groups(it.value) != null }

    fun normalize(source: String): String {
        if (!number.containsMatchIn(source)) return source
        val text = dateOrTime.replace(writtenNumbers(source)) { match ->
            val parts = groups(match.value)
            if (parts == null) match.value else parts.joinToString(", ") { part ->
                part.map { digits[it - '0'] }.joinToString(" ")
            }
        }

        // Explicitly name punctuation between digits without assuming locale, decimal precision or date order.
        val formatted = buildString {
            var index = 0
            while (index < text.length) {
                val cp = text.codePointAt(index)
                val next = index + Character.charCount(cp)
                val digit = Character.digit(cp, 10)
                val word = when {
                    digit >= 0 -> digits[digit]
                    Character.getType(cp) in nonDecimalNumber -> unsupported(cp)
                    else -> symbol(text, index, next, cp)
                }
                if (word == null) {
                    appendCodePoint(cp)
                } else {
                    if (isNotEmpty() && !last().isWhitespace()) append(' ')
                    append(word)
                    if (next < text.length && !text[next].isWhitespace() && text[next] !in ".,?!:;…)]}") append(' ')
                }
                index = next
            }
        }
        return formatted.replace(spaces, " ").trim()
    }

    private fun symbol(text: String, start: Int, end: Int, cp: Int): String? {
        if (Character.isLetter(cp) || Character.isWhitespace(cp) || Character.isSpaceChar(cp)) return null
        val previous = text.codePointBeforeOrNone(start)
        val following = text.codePointAtOrNone(end)
        val inside = Character.isDigit(previous) && Character.isDigit(following)
        val before = nearest(text, start, backwards = true)
        val after = nearest(text, end, backwards = false)
        val betweenNumbers = Character.isDigit(before) && Character.isDigit(after)
        val nearNumber = Character.isDigit(before) || Character.isDigit(after)
        val leadingDecimal = Character.isDigit(following) && (previous < 0 || previous.toChar() in "([=+-−" || Character.isSpaceChar(previous))
        return when (cp) {
            '.'.code, 0x066B -> if (inside || leadingDecimal) "chindaguma" else null
            ','.code, 0x066C -> if (inside || leadingDecimal) "chifemo" else null
            '+'.code, 0xFF0B -> "purasi"
            '-'.code, 0x2010, 0x2011, 0x2013, 0x2014 -> when {
                betweenNumbers -> "chisungazwi"
                Character.isDigit(after) || after.toChar() in ".," -> "mainasi"
                else -> null
            }
            0x2212 -> "mainasi"
            '%'.code, 0x066A, 0xFF05 -> "muzana"
            '$'.code -> "dhora"
            0x00D7 -> "taimiza"
            0x00F7 -> "govanisa"
            else -> {
                val category = Character.getType(cp)
                val significant = category == Character.CURRENCY_SYMBOL.toInt() ||
                    category == Character.MATH_SYMBOL.toInt() || cp in numericSigns
                if (betweenNumbers || (significant && nearNumber)) unsupported(cp)
                null
            }
        }
    }

    private fun nearest(text: String, index: Int, backwards: Boolean): Int {
        var pos = index
        while (pos in 0..text.length) {
            val cp = if (backwards) text.codePointBeforeOrNone(pos) else text.codePointAtOrNone(pos)
            if (cp < 0 || !Character.isSpaceChar(cp) && cp != '\t'.code) return cp
            pos += if (backwards) -Character.charCount(cp) else Character.charCount(cp)
        }
        return -1
    }

    private fun String.codePointBeforeOrNone(index: Int): Int = if (index > 0) codePointBefore(index) else -1
    private fun String.codePointAtOrNone(index: Int): Int = if (index < length) codePointAt(index) else -1
    private fun unsupported(cp: Int): Nothing {
        val sign = String(Character.toChars(cp))
        throw IllegalArgumentException("Голос шона не может точно прочитать знак «$sign» в числе. Запишите выражение словами.")
    }
}
