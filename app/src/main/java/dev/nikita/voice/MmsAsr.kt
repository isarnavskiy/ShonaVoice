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
    fun transcribe(lang: String, recording: () -> FloatArray): String {
        require(lang == "rus" || lang == "sna") { "Неизвестный язык распознавания" }
        val folder = File(modelRoot, "asr")
        val metadata = JSONObject(File(folder, "$lang.tokens.json").readText())
        val tokenCount = metadata.getJSONArray("tokens").length()
        val frameCount: Int
        val scores: FloatArray
        val env = OrtEnvironment.getEnvironment()
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            options.setMemoryPatternOptimization(false)
            options.setCPUArenaAllocator(false)
            env.createSession(File(folder, "$lang.onnx").absolutePath, options).use { session ->
                val audio16k = recording()
                if (audio16k.size < RECEPTIVE_FIELD || audio16k.all { it == 0f }) return ""
                frameCount = 1 + (audio16k.size - RECEPTIVE_FIELD) / STRIDE
                scores = FloatArray(frameCount * tokenCount)
                val coreSize = if (audio16k.size <= SINGLE_WINDOW_SAMPLES) frameCount else CORE_FRAMES
                for (coreStart in 0 until frameCount step coreSize) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val coreEnd = minOf(frameCount, coreStart + coreSize)
                    val windowStart = maxOf(0, coreStart - CONTEXT_FRAMES)
                    val windowEnd = minOf(frameCount, coreEnd + CONTEXT_FRAMES)
                    val sampleStart = windowStart * STRIDE
                    val sampleEnd = if (windowEnd == frameCount) audio16k.size else (windowEnd - 1) * STRIDE + RECEPTIVE_FIELD
                    val sampleCount = sampleEnd - sampleStart
                    val audio = FloatBuffer.wrap(audio16k, sampleStart, sampleCount).slice()
                    val size = sampleCount.toLong()
                    OnnxTensor.createTensor(env, audio, longArrayOf(1, size)).use { signal ->
                        OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(size)), longArrayOf(1)).use { length ->
                            session.run(mapOf("input_values" to signal, "input_lengths" to length)).use { output ->
                                val logits = output[0] as OnnxTensor
                                val shape = logits.info.shape
                                require(shape.size == 3 && shape[0] == 1L &&
                                    shape[1] == (windowEnd - windowStart).toLong() && shape[2] == tokenCount.toLong()) {
                                    "Размер выхода модели распознавания не соответствует аудиозаписи или словарю."
                                }
                                val frames = logits.floatBuffer
                                frames.position((coreStart - windowStart) * tokenCount)
                                frames.get(scores, coreStart * tokenCount, (coreEnd - coreStart) * tokenCount)
                            }
                        }
                    }
                }
            }
        }
        // Run CTC once across the complete sequence so a word spanning two windows is decoded once.
        return CtcDecoder.decode(FloatBuffer.wrap(scores), frameCount, metadata, lang, if (lang == "sna") 0.4 else 0.0)
    }

    private companion object {
        // Seven valid convolutions in MMS produce 400-sample receptive fields at a 320-sample stride (16 kHz).
        const val RECEPTIVE_FIELD = 400
        const val STRIDE = 320
        const val SINGLE_WINDOW_SAMPLES = 12 * 16_000
        const val CORE_FRAMES = 500
        const val CONTEXT_FRAMES = 48
    }
}
