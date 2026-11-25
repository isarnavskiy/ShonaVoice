package dev.nikita.voice

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/** Boundaries are detected before tokenization because the MMS Shona vocabulary has no commas or stops. */
internal object ShonaProsody {
    data class Phrase(val text: String, val pauseAfterMs: Int)

    fun plan(source: String, preserveSymbols: Boolean = false, supported: (Char) -> Boolean): List<Phrase> {
        val text = Normalizer.normalize(if (preserveSymbols) source else source.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        val result = mutableListOf<Phrase>()
        val current = StringBuilder()
        fun flush(pause: Int) {
            val raw = current.toString().trim()
            val phrase = if (preserveSymbols && raw.none(Char::isLetter)) "" else raw
            current.setLength(0)
            var start = 0
            while (start < phrase.length) {
                val limit = minOf(start + 90, phrase.length)
                val space = if (limit < phrase.length) phrase.lastIndexOf(' ', limit) else limit
                val end = if (space > start) space else limit
                result.add(Phrase(phrase.substring(start, end), 0))
                start = end
                while (start < phrase.length && phrase[start] == ' ') start++
            }
            val previous = result.lastOrNull() ?: return
            if (pause > previous.pauseAfterMs) result[result.lastIndex] = previous.copy(pauseAfterMs = pause)
        }
        var pendingPause = 0
        for (c in text) {
            val pause = when (c) {
                '.', '!', '?', '…', '\n', '\r' -> 320
                ':', ';', '–', '—' -> 200
                ',' -> 125
                else -> 0
            }
            if (pause > 0) {
                if (preserveSymbols && supported(c)) current.append(c)
                pendingPause = maxOf(pendingPause, pause)
                continue
            }
            if (pendingPause > 0) {
                flush(pendingPause)
                pendingPause = 0
            }
            if (Character.getType(c) == Character.NON_SPACING_MARK.toInt()) continue
            val letter = if (!preserveSymbols && (c == '’' || c == '‘')) '\'' else c
            if (letter != ' ' && supported(letter)) current.append(letter)
            else {
                require(!preserveSymbols || !c.isLetter()) {
                    "EveryVoice не умеет читать букву «$c». Выберите голос MMS в настройках."
                }
                if (current.isNotEmpty() && current.last() != ' ') current.append(' ')
            }
        }
        flush(pendingPause)
        if (result.isNotEmpty()) result[result.lastIndex] = result.last().copy(pauseAfterMs = 0)
        return result
    }

    /** Remove only quiet edge padding, retaining at least 20 ms around speech. */
    fun withPause(input: ShortArray, sampleRate: Int, pauseMs: Int): ShortArray {
        require(input.isNotEmpty() && sampleRate > 0 && pauseMs >= 0)
        // -60 dBFS threshold and a 250 ms trim ceiling protect quiet onsets and sustained consonants.
        val edge = minOf(sampleRate / 4, input.size / 2)
        val guard = sampleRate / 50
        var first = 0
        while (first < edge && abs(input[first].toInt()) < 33) first++
        var end = input.size
        while (end > input.size - edge && abs(input[end - 1].toInt()) < 33) end--
        first = maxOf(0, first - guard)
        end = minOf(input.size, end + guard)
        val length = end - first
        val output = ShortArray(length + sampleRate * pauseMs / 1000)
        input.copyInto(output, 0, first, end)
        // A very short fade avoids clicks at a model/window edge.
        val fade = minOf(sampleRate / 500, length / 2)
        for (i in 0 until fade) {
            output[i] = (output[i].toInt() * i / fade).toShort()
            val j = length - 1 - i
            output[j] = (output[j].toInt() * i / fade).toShort()
        }
        return output
    }
}
