package dev.nikita.voice

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.Base64

/** Add probability outputs to the installed INT8 graph without downloading new weights. */
internal object BeamDecoder {
    private data class Stamp(val path: String, val length: Long, val modified: Long)
    private var lastSource: Pair<Stamp, String>? = null

    private fun sourceChecksum(source: File): String {
        val stamp = Stamp(source.canonicalPath, source.length(), source.lastModified())
        lastSource?.takeIf { it.first == stamp }?.let { return it.second }
        // Hashing 476 MB took seconds on every app start before the first translation into Shona.
        // Remember the result for this exact file size and modification time across launches.
        val remembered = File(source.parentFile, source.name + ".sha256")
        val key = "${stamp.length} ${stamp.modified} "
        runCatching { remembered.readText() }.getOrNull()?.takeIf { it.startsWith(key) }?.let {
            val hash = it.removePrefix(key).trim()
            if (hash.length == 64) {
                lastSource = stamp to hash
                return hash
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        source.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            var count = input.read(buffer)
            while (count >= 0) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                digest.update(buffer, 0, count)
                count = input.read(buffer)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        lastSource = stamp to hash
        runCatching { remembered.writeText(key + hash) }
        return hash
    }

    /** The prepared derivative, if a previous translation into Shona already created and verified it. */
    @Synchronized
    fun existing(directory: File): File? {
        val recipe = checkNotNull(javaClass.getResourceAsStream("/decoder-beam.json")) {
            "В приложении отсутствует описание декодера."
        }.bufferedReader().use { JSONObject(it.readText()) }
        val target = File(directory, "decoder-${recipe.getString("sha256").take(16)}.onnx")
        return target.takeIf { it.isFile && it.length() == recipe.getLong("bytes") }
    }

    @Synchronized
    fun model(directory: File): File {
        val recipe = checkNotNull(javaClass.getResourceAsStream("/decoder-beam.json")) {
            "В приложении отсутствует описание декодера."
        }.bufferedReader().use { JSONObject(it.readText()) }
        val checksum = recipe.getString("sha256")
        val expectedBytes = recipe.getLong("bytes")
        val target = File(directory, "decoder-${checksum.take(16)}.onnx")
        val source = File(directory, "decoder.onnx")
        require(source.isFile) { "Не найден установленный декодер." }
        // Locally prepared packs may contain another compatible decoder. Keep its
        // original weights and honor the outputs the author actually exported.
        if (source.length() != recipe.getLong("source_bytes") ||
            sourceChecksum(source) != recipe.getString("source_sha256")) return source
        if (target.isFile && target.length() == expectedBytes) return target
        val temporary = File(directory, target.name + ".part")
        // A process killed during the first launch can leave an incomplete derivative.
        if (temporary.exists()) check(temporary.delete()) { "Не удалось очистить незавершённый декодер." }
        require(directory.usableSpace >= expectedBytes) { "Для подготовки декодера требуется ещё 476 МБ свободного места." }
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val operations = recipe.getJSONArray("ops")
            RandomAccessFile(source, "r").use { input ->
                FileOutputStream(temporary).use { file ->
                    val output = DigestOutputStream(file, digest)
                    val buffer = ByteArray(1024 * 1024)
                    for (index in 0 until operations.length()) {
                        val operation = operations.getJSONObject(index)
                        if (operation.has("data")) {
                            output.write(Base64.getDecoder().decode(operation.getString("data")))
                        } else {
                            val range = operation.getJSONArray("copy")
                            input.seek(range.getLong(0))
                            var remaining = range.getLong(1)
                            while (remaining > 0) {
                                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                                val count = minOf(remaining, buffer.size.toLong()).toInt()
                                input.readFully(buffer, 0, count)
                                output.write(buffer, 0, count)
                                remaining -= count
                            }
                        }
                    }
                    file.fd.sync()
                }
            }
            check(temporary.length() == expectedBytes && digest.digest().joinToString("") { "%02x".format(it) } == checksum) {
                "Контрольная сумма декодера не совпала. Проверьте установленный комплект моделей."
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return target
        } finally {
            temporary.delete()
        }
    }
}
