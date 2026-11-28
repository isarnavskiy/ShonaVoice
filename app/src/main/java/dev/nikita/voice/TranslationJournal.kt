package dev.nikita.voice

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Every translated phrase with its time, direction and source (typed, voice or continuous),
 * one JSON object per line in app storage. It survives updates and is cleared on uninstall.
 */
internal class TranslationJournal(private val file: File) {
    @Synchronized
    fun add(at: Long, from: String, to: String, source: String, translation: String, mode: String) {
        if (source.isBlank() || translation.isBlank()) return
        val entry = JSONObject().put("at", at).put("from", from).put("to", to)
            .put("source", source.take(4000)).put("translation", translation.take(8000)).put("mode", mode)
        file.parentFile?.mkdirs()
        file.appendText(entry.toString() + "\n")
    }

    /** Newest first. */
    @Synchronized
    fun read(limit: Int = 5000): JSONArray {
        val result = JSONArray()
        if (!file.isFile) return result
        val lines = file.readLines()
        for (line in lines.asReversed()) {
            if (result.length() >= limit) break
            if (line.isBlank()) continue
            runCatching { result.put(JSONObject(line)) }
        }
        return result
    }

    @Synchronized
    fun clear() {
        file.delete()
    }

    /** Plain text, oldest first, for reading in any editor. */
    fun exportText(): String {
        val entries = read(Int.MAX_VALUE)
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
        val out = StringBuilder("Журнал переводов Shona Voice\nЗаписей: ${entries.length()}\n\n")
        for (i in entries.length() - 1 downTo 0) {
            val e = entries.getJSONObject(i)
            out.append(time.format(Date(e.optLong("at")))).append(" · ")
                .append(language(e.optString("from"))).append(" → ").append(language(e.optString("to")))
                .append(" · ").append(mode(e.optString("mode"))).append('\n')
                .append(e.optString("source")).append('\n')
                .append(e.optString("translation")).append("\n\n")
        }
        return out.toString()
    }

    companion object {
        fun language(code: String) = when (code) {
            "ru", "rus" -> "русский"
            "sn", "sna" -> "шона"
            else -> code
        }

        fun mode(code: String) = when (code) {
            "voice" -> "голос"
            "live" -> "непрерывный перевод"
            else -> "текст"
        }
    }
}
