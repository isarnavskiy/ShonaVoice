package dev.nikita.voice

import org.json.JSONObject
import java.io.File

/** The desktop installer publishes this index after transferring the complete pack. */
internal fun modelPackIssue(root: File): String? {
    val index = File(root, "pack-v1.json")
    if (!index.isFile) return "Подключите модели с компьютера по инструкции из архива. Затем откройте настройки переводчика."
    return try {
        val data = JSONObject(index.readText())
        require(data.getInt("version") == 1)
        val files = data.getJSONArray("files")
        require(files.length() > 0)
        val prefix = root.canonicalPath + File.separator
        for (i in 0 until files.length()) {
            val entry = files.getJSONObject(i)
            val relative = entry.getString("path")
            val local = File(root, relative)
            require(local.canonicalPath.startsWith(prefix))
            if (!local.isFile || local.length() != entry.getLong("bytes") || local.length() == 0L) {
                return "Файл модели повреждён или отсутствует: $relative. Повторите установку моделей."
            }
        }
        null
    } catch (_: Exception) {
        "Не удалось прочитать описание комплекта. Повторите установку моделей."
    }
}
