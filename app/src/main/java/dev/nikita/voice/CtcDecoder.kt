package dev.nikita.voice

import org.json.JSONObject
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.ln1p

/** Prefix search for the single-character MMS vocabularies. Log scores include the language prior. */
internal object CtcDecoder {
    private val impossible = Double.NEGATIVE_INFINITY
    private data class Ending(var blank: Double, var character: Double) {
        fun total() = logAdd(blank, character)
    }

    private fun logAdd(a: Double, b: Double): Double {
        val larger = maxOf(a, b)
        val smaller = minOf(a, b)
        return if (smaller == impossible) larger else larger + ln1p(exp(smaller - larger))
    }

    private class CharacterPrior(language: String) {
        private val counts: JSONObject
        private val denominator: Double
        private val scores = HashMap<String, Double>()

        init {
            val stream = requireNotNull(CtcDecoder::class.java.getResourceAsStream("/asr_language/$language.json")) {
                "Отсутствует словарь распознавания $language."
            }
            counts = stream.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            denominator = 1.0 + counts.keys().asSequence().filter { it.length == 1 }
                .sumOf { counts.getInt(it) + 1 }
        }

        fun score(context: String): Double = scores.getOrPut(context) {
            var probability = (counts.optInt(context.takeLast(1), 0) + 1) / denominator
            for (length in 2..context.length) {
                val gram = context.takeLast(length)
                val prefixCount = counts.optInt(gram.dropLast(1), 0)
                if (prefixCount > 0) {
                    probability = (counts.optInt(gram, 0) + 12 * probability) / (prefixCount + 12)
                }
            }
            ln(probability) + 2.0
        }
    }

    fun decode(scores: FloatBuffer, frames: Int, meta: JSONObject, language: String, weight: Double): String {
        val vocab = meta.getJSONArray("tokens")
        val delimiter = meta.getString("delimiter")
        val symbols = Array(vocab.length()) { vocab.getString(it).let { s -> if (s == delimiter) " " else s } }
        val special = meta.getJSONArray("special_ids")
        val skip = (0 until special.length()).map { special.getInt(it) }.toSet()
        val blankId = meta.getInt("blank_id")
        val prior = if (weight > 0) CharacterPrior(language) else null
        // The adapters also emit letters of other scripts, which turned noise and the app's own
        // Shona voice into mixed Latin-Cyrillic words. Only the language's own alphabet is decoded.
        val alphabet = if (language == "rus") RUSSIAN else SHONA
        val allowed = BooleanArray(symbols.size) { i -> symbols[i].lowercase().all { it == ' ' || it in alphabet } }
        var beams: Map<String, Ending> = mapOf("" to Ending(0.0, impossible))
        val frame = FloatArray(symbols.size)
        repeat(frames) {
            scores.get(frame)
            val top = frame.indices.sortedByDescending { frame[it] }.take(8)
            val threshold = frame[top.first()] - 10
            val candidates = top.filter { it !in skip && allowed[it] && frame[it] >= threshold }
            val next = LinkedHashMap<String, Ending>()
            for ((prefix, ending) in beams) {
                val same = next.getOrPut(prefix) { Ending(impossible, impossible) }
                val total = ending.total()
                same.blank = logAdd(same.blank, total + frame[blankId])
                for (index in candidates) {
                    val symbol = symbols[index]
                    var base = total
                    if (prefix.endsWith(symbol)) {
                        same.character = logAdd(same.character, ending.character + frame[index])
                        base = ending.blank
                    }
                    if (base == impossible) continue
                    val extended = prefix + symbol
                    var score = base + frame[index]
                    if (prior != null) score += weight * prior.score(("    " + extended).takeLast(5))
                    val destination = next.getOrPut(extended) { Ending(impossible, impossible) }
                    destination.character = logAdd(destination.character, score)
                }
            }
            beams = next.entries.sortedByDescending { it.value.total() }.take(8).associate { it.key to it.value }
        }
        return beams.keys.first().trim()
    }

    private const val RUSSIAN = "абвгдеёжзийклмнопрстуфхцчшщъыьэюя-'’"
    private const val SHONA = "abcdefghijklmnopqrstuvwxyz-'’"
}
