package dev.nikita.voice

/** Keep exact contact details and identifiers when translating a message in either direction. */
internal object ShonaTranslationText {
    private val protected = Regex(
        """(?i:https?://|www\.)[^\s<>«»“”"']+|[\p{L}\p{N}._%+\-]+@[\p{L}\p{N}\-]+(?:\.[\p{L}\p{N}\-]+)+|(?<![\p{L}\p{N}])[A-Z]{1,8}-[0-9]{2,}(?![\p{L}\p{N}])|(?<!\d)(?:\+\d(?:[ ()\-]*\d){6,14}|\d{4}-\d{2}-\d{2}|\d{2}[./]\d{2}[./]\d{4}|(?:[01]?\d|2[0-3]):[0-5]\d(?::[0-5]\d)?|0\d{2,})(?![\p{L}\p{N}])"""
    )
    private val digits = Regex("""[+\-]?\d+(?:[.,]\d+)*(?:[ \t]*[%‰])?""")
    private val thousands = Regex("""(?<=\d)[ \u00a0\u202f](?=\d{3}(?!\d))""")
    data class Part(val text: String, val translate: Boolean)

    private fun literals(source: String): List<IntRange> = protected.findAll(source).map { match ->
        var end = match.range.last
        if (match.value.startsWith("http", true) || match.value.startsWith("www.", true)) {
            // Exclude prose punctuation. Keep balanced parentheses inside a URL, e.g. a wiki title.
            while (end > match.range.first) {
                val char = source[end]
                if (char in ".,;:!?…") { end--; continue }
                val opening = when (char) { ')' -> '('; ']' -> '['; '}' -> '{'; else -> break }
                val candidate = source.substring(match.range.first, end + 1)
                if (candidate.count { it == char } <= candidate.count { it == opening }) break
                end--
            }
        }
        match.range.first..end
    }.toList()

    fun parts(source: String): List<Part> {
        val ranges = literals(source)
        if (ranges.isEmpty()) return emptyList()
        val result = mutableListOf<Part>()
        fun plain(value: String) {
            val first = value.indexOfFirst { !it.isWhitespace() }
            if (first < 0 || value.none { it.isLetter() }) { if (value.isNotEmpty()) result.add(Part(value, false)); return }
            val last = value.indexOfLast { !it.isWhitespace() }
            if (first > 0) result.add(Part(value.substring(0, first), false))
            result.add(Part(value.substring(first, last + 1), true))
            if (last < value.lastIndex) result.add(Part(value.substring(last + 1), false))
        }
        var offset = 0
        for (range in ranges) {
            plain(source.substring(offset, range.first))
            result.add(Part(source.substring(range), false))
            offset = range.last + 1
        }
        plain(source.substring(offset))
        return result
    }

    fun preserves(source: String, translation: String): Boolean =
        literals(source).map { source.substring(it) } == literals(translation).map { translation.substring(it) }

    fun numbers(text: String): List<String> {
        val normalized = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC).replace('−', '-')
        return digits.findAll(thousands.replace(normalized, "")).map { match ->
            match.value.replace(',', '.').filterNot { it.isWhitespace() }
        }.toList()
    }

    fun inlineTranslation(source: String, translated: String): String {
        // A translated fragment can acquire a full stop when the next part is an inline identifier.
        val trimmed = if (source.last() !in ".!?…") translated.trimEnd('.', '!', '?', '…').trimEnd() else translated
        return trimmed.ifEmpty { translated }
    }
}
