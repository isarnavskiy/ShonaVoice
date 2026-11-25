package dev.nikita.voice

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject

/** Run the production decoder on precomputed MMS frame scores from a named evaluation split. */
fun main(args: Array<String>) {
    val vocabulary = JSONObject(File(args[0]).readText())
    val size = vocabulary.getJSONArray("tokens").length()
    val directory = File(args[1])
    val weight = args[2].toDouble()
    val results = JSONObject()
    for (id in args.drop(3)) {
        val buffer = ByteBuffer.wrap(File(directory, "$id.bin").readBytes())
            .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        require(buffer.remaining() % size == 0)
        results.put(id, CtcDecoder.decode(buffer, buffer.remaining() / size, vocabulary, "sna", weight))
    }
    print(results.toString())
}
