package dev.nikita.voice

import java.io.File

internal enum class ShonaVoice(val id: String, val label: String, val folder: String, private vararg val graphs: String) {
    EVERYVOICE("everyvoice", "EveryVoice · голос шона", "sna-everyvoice", "acoustic.onnx", "vocoder.onnx"),
    MMS_INT8("mms-int8", "MMS · компактная модель", "sna", "model.onnx");

    fun directory(root: File) = File(root, "tts/$folder")

    companion object {
        fun available(root: File?): List<ShonaVoice> {
            if (root == null) return emptyList()
            return entries.filter { entry ->
                val dir = entry.directory(root)
                entry.graphs.all { name -> File(dir, name).let { it.isFile && it.length() > 0 } } &&
                    File(dir, "voice.json").let { it.isFile && it.length() > 0 }
            }
        }

        fun select(root: File, id: String): ShonaVoice {
            val voices = available(root)
            return (if (id.isEmpty()) voices.firstOrNull() else voices.find { it.id == id })
                ?: error("Выбранная модель озвучивания шона недоступна.")
        }
    }
}
