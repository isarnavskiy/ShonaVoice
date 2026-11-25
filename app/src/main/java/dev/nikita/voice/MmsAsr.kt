package dev.nikita.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/** One fixed language adapter per graph. Both graphs reference one INT8 weight file. */
class MmsAsr(private val modelRoot: File) {
    fun transcribe(audio16k: FloatArray, lang: String): String {
        require(lang == "rus" || lang == "sna") { "Неизвестный язык распознавания" }
        if (audio16k.size < 400 || audio16k.all { it == 0f }) return ""
        val folder = File(modelRoot, "asr")
        val metadata = JSONObject(File(folder, "$lang.tokens.json").readText())
        val tokens = metadata.getJSONArray("tokens")
        val env = OrtEnvironment.getEnvironment()
        return OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            options.setMemoryPatternOptimization(false)
            options.setCPUArenaAllocator(false)
            env.createSession(File(folder, "$lang.onnx").absolutePath, options).use { session ->
                val size = audio16k.size.toLong()
                OnnxTensor.createTensor(env, FloatBuffer.wrap(audio16k), longArrayOf(1, size)).use { signal ->
                    OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(size)), longArrayOf(1)).use { length ->
                        session.run(mapOf("input_values" to signal, "input_lengths" to length)).use { output ->
                            val logits = output[0] as OnnxTensor
                            val shape = logits.info.shape
                            require(shape.size == 3 && shape[0] == 1L && shape[2] == tokens.length().toLong()) {
                                "Версии словаря и модели распознавания различаются"
                            }
                            CtcDecoder.decode(
                                logits.floatBuffer, shape[1].toInt(), metadata, lang,
                                if (lang == "sna") 0.65 else 0.0
                            )
                        }
                    }
                }
            }
        }
    }
}
