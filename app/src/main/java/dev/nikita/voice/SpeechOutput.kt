package dev.nikita.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong

/** The caller runs speak on its serial inference worker. stop/close may run on the UI thread. */
class SpeechOutput(@Suppress("UNUSED_PARAMETER") context: Context, private val modelRoot: File) {
    private val generation = AtomicLong()
    @Volatile private var closed = false
    private val runLock = Any()
    private var activeRun: OrtSession.RunOptions? = null

    fun speak(text: String, lang: String, voiceId: String = "") {
        val token = generation.get()
        checkActive(token)
        require(text.isNotBlank()) { "Нет текста для озвучивания." }
        val prepared = when (lang) {
            "rus" -> RussianSpeechText.normalize(text)
            "sna" -> ShonaSpeechText.normalize(text)
            else -> error("Неизвестный язык озвучивания.")
        }
        val voice = if (lang == "sna") ShonaVoice.select(modelRoot, voiceId) else null
        if (voice == ShonaVoice.EVERYVOICE) {
            EveryVoiceShona.speak(prepared, voice.directory(modelRoot), { checkActive(token) }, ::stopInference) {
                session, inputs, output -> infer(session, inputs, output, token)
            }
        } else {
            speakLocal(prepared, voice?.directory(modelRoot) ?: File(modelRoot, "tts/rus"), voice != null, token)
        }
    }

    fun stop() {
        generation.incrementAndGet()
        stopInference()
    }

    private fun stopInference() {
        synchronized(runLock) {
            try {
                activeRun?.setTerminate(true)
            } catch (e: OrtException) {
                // The generation check still discards cancelled audio if a provider cannot interrupt a run.
                Log.w("ShonaVoice", "Could not interrupt ONNX speech inference", e)
            }
        }
    }

    fun close() {
        closed = true
        stop()
    }

    private fun checkActive(token: Long) {
        if (closed || generation.get() != token || Thread.currentThread().isInterrupted) {
            throw CancellationException("Озвучивание остановлено.")
        }
    }

    private fun speakLocal(text: String, voice: File, shona: Boolean, token: Long) {
        val settings = File(voice, "voice.json")
        val graph = File(voice, "model.onnx")
        check(settings.isFile && graph.isFile) { "Повреждён встроенный пакет озвучивания." }
        val descriptor = JSONObject(settings.readText())
        val vocab = descriptor.getJSONObject("tokens")
        val sampleRate = descriptor.getInt("sample_rate")
        check(sampleRate == 16000 && descriptor.getBoolean("add_blank")) { "Неподходящий формат встроенного голоса." }

        val environment = OrtEnvironment.getEnvironment()
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            options.setMemoryPatternOptimization(false)
            environment.createSession(graph.absolutePath, options).use { session ->
                if (shona) {
                    val phrases = ShonaProsody.plan(text) { vocab.has(it.toString()) }
                    require(phrases.isNotEmpty()) { "В переводе нет символов, доступных выбранному голосу." }
                    ShonaPlayback.play(sampleRate, { checkActive(token) }, ::stopInference) { emit ->
                        for (phrase in phrases) {
                            checkActive(token)
                            val pcm = synthesize(environment, session, idsFor(phrase.text, vocab), token)
                            emit(ShonaProsody.withPause(pcm, sampleRate, phrase.pauseAfterMs))
                        }
                    }
                    return
                }
                val normalized = text.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
                    .filter { vocab.has(it.toString()) }.trim()
                require(normalized.isNotEmpty()) { "В переводе нет символов, доступных выбранному голосу." }
                // Short clauses also bound the VITS decoder's activation buffers.
                var start = 0
                while (start < normalized.length) {
                    checkActive(token)
                    val limit = (start + 90).coerceAtMost(normalized.length)
                    val boundary = if (limit < normalized.length) normalized.lastIndexOf(' ', limit) else limit
                    val end = if (boundary > start) boundary else limit
                    val clause = normalized.substring(start, end).trim()
                    start = end
                    while (start < normalized.length && normalized[start] == ' ') start++
                    if (clause.isEmpty()) continue
                    val pcm = synthesize(environment, session, idsFor(clause, vocab), token)
                    playSamples(pcm, sampleRate, token)
                }
            }
        }
    }

    private fun idsFor(clause: String, vocab: JSONObject): LongArray {
        val ids = LongArray(2 * clause.length + 1)
        clause.forEachIndexed { i, c -> ids[2 * i + 1] = vocab.getLong(c.toString()) }
        return ids
    }

    private fun synthesize(env: OrtEnvironment, session: OrtSession, ids: LongArray, token: Long): ShortArray {
        val feed = mutableMapOf<String, OnnxTensor>()
        fun scale(value: Float) = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(value)), longArrayOf(1))
        val n = ids.size.toLong()
        try {
            feed["x"] = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, n))
            feed["x_length"] = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(n)), longArrayOf(1))
            feed["noise_scale"] = scale(0.667f)
            feed["length_scale"] = scale(1f)
            feed["noise_scale_w"] = scale(0.8f)
            infer(session, feed, "y", token).use { outputs ->
                val samples = (outputs[0] as OnnxTensor).floatBuffer
                check(samples.remaining() > 0) { "Синтезатор вернул пустой звук." }
                return ShortArray(samples.remaining()) {
                    (samples.get().coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                }
            }
        } finally {
            feed.values.forEach { it.close() }
        }
    }

    private fun infer(session: OrtSession, feed: Map<String, OnnxTensor>, output: String, token: Long): OrtSession.Result {
        OrtSession.RunOptions().use { run ->
            synchronized(runLock) {
                checkActive(token)
                activeRun = run
            }
            try {
                return session.run(feed, setOf(output), run)
            } finally {
                synchronized(runLock) { activeRun = null }
            }
        }
    }

    private fun playSamples(samples: ShortArray, sampleRate: Int, token: Long) {
        checkActive(token)
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val format = AudioFormat.Builder().setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
        val player = AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(samples.size * 2).build()
        try {
            check(player.write(samples, 0, samples.size) == samples.size) { "Не удалось записать звук для воспроизведения." }
            checkActive(token)
            player.play()
            val deadline = SystemClock.elapsedRealtime() + 1000L * samples.size / sampleRate + 5000L
            while (player.playbackHeadPosition < samples.size && player.playState == AudioTrack.PLAYSTATE_PLAYING) {
                checkActive(token)
                check(SystemClock.elapsedRealtime() < deadline) { "Не удалось завершить воспроизведение." }
                Thread.sleep(25)
            }
        } finally {
            player.release()
        }
    }
}
