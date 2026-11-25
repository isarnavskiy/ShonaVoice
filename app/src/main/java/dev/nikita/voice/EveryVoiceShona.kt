package dev.nikita.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import java.io.File
import java.nio.LongBuffer

/** Fixed-speaker acoustic graph and its matching HiFi-GAN vocoder from EveryVoice OpenBible Shona. */
internal object EveryVoiceShona {
    fun speak(text: String, directory: File, active: () -> Unit, stopInference: () -> Unit,
              infer: (OrtSession, Map<String, OnnxTensor>, String) -> OrtSession.Result) {
        val config = JSONObject(File(directory, "voice.json").readText())
        val vocab = config.getJSONObject("tokens")
        val rate = config.getInt("sample_rate")
        check(rate == 22050) { "Неподходящая частота озвучивания EveryVoice." }
        val phrases = ShonaProsody.plan(text, preserveSymbols = true) { vocab.has(it.toString()) }
        require(phrases.isNotEmpty()) { "В тексте нет слов для озвучивания." }
        val env = OrtEnvironment.getEnvironment()
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            options.setInterOpNumThreads(1)
            options.setMemoryPatternOptimization(false)
            env.createSession(File(directory, "acoustic.onnx").absolutePath, options).use { acoustic ->
                active()
                env.createSession(File(directory, "vocoder.onnx").absolutePath, options).use { vocoder ->
                    ShonaPlayback.play(rate, active, stopInference) { emit ->
                        val pending = java.util.ArrayDeque(phrases)
                        while (pending.isNotEmpty()) {
                            active()
                            val phrase = pending.removeFirst()
                            val ids = LongArray(phrase.text.length) { vocab.getLong(phrase.text[it].toString()) }
                            val pcm = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())).use { input ->
                                infer(acoustic, mapOf("tokens" to input), "mel").use { encoded ->
                                    val mel = encoded[0] as OnnxTensor
                                    val shape = mel.info.shape
                                    check(shape.size == 3 && shape[0] == 1L && shape[1] == 80L && shape[2] in 1..1000) {
                                        "Акустическая модель EveryVoice вернула неверный формат."
                                    }
                                    if (shape[2] == 1000L) null else {
                                        active()
                                        infer(vocoder, mapOf("mel" to mel), "audio").use { decoded ->
                                            val audio = decoded[0] as OnnxTensor
                                            check(audio.info.shape.contentEquals(longArrayOf(1, 1, shape[2] * 256L))) {
                                                "Неверная длительность звука EveryVoice."
                                            }
                                            val signal = audio.floatBuffer
                                            ShortArray(signal.remaining()) {
                                                (signal.get().coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                                            }
                                        }
                                    }
                                }
                            }
                            if (pcm != null) emit(ShonaProsody.withPause(pcm, rate, phrase.pauseAfterMs))
                            else {
                                // The acoustic export caps mel length. Retry both halves instead of clipping speech.
                                val halves = split(phrase)
                                pending.addFirst(halves.second)
                                pending.addFirst(halves.first)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun split(phrase: ShonaProsody.Phrase): Pair<ShonaProsody.Phrase, ShonaProsody.Phrase> {
        val text = phrase.text
        check(text.length >= 2) { "EveryVoice не смог полностью озвучить символ." }
        val mid = text.length / 2
        val space = text.lastIndexOf(' ', mid)
        val cut = if (space > 0) space else mid
        return ShonaProsody.Phrase(text.substring(0, cut).trimEnd(), 0) to
            ShonaProsody.Phrase(text.substring(cut).trimStart(), phrase.pauseAfterMs)
    }
}
