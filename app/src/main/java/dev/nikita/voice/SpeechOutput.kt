package dev.nikita.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong

private val numericSymbol = Regex("\\p{N}")

internal fun checkShonaVoiceText(text: String) {
    require(!numericSymbol.containsMatchIn(text)) {
        "Голос шона может пропустить цифры. Сверьте числа на экране. Для озвучивания запишите их словами."
    }
}

/** The caller runs speak on its serial inference worker. stop/close may run on the UI thread. */
class SpeechOutput(@Suppress("UNUSED_PARAMETER") context: Context, private val modelRoot: File) {
    private val generation = AtomicLong()
    @Volatile private var closed = false
    private val runLock = Any()
    private var activeRun: OrtSession.RunOptions? = null

    fun speak(text: String, lang: String) {
        val token = generation.get()
        checkActive(token)
        require(text.isNotBlank()) { "Нет текста для озвучивания." }
        val prepared = when (lang) {
            "rus" -> RussianSpeechText.normalize(text)
            "sna" -> text.also { checkShonaVoiceText(it) }
            else -> error("Неизвестный язык озвучивания.")
        }
        speakLocal(prepared, lang, token)
    }

    fun stop() {
        generation.incrementAndGet()
        synchronized(runLock) { activeRun?.setTerminate(true) }
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

    private fun speakLocal(text: String, lang: String, token: Long) {
        val voice = File(modelRoot, "tts/$lang")
        val settings = File(voice, "voice.json")
        val graph = File(voice, "model.onnx")
        check(settings.isFile && graph.isFile) { "Повреждён встроенный пакет озвучивания." }
        val descriptor = JSONObject(settings.readText())
        val vocab = descriptor.getJSONObject("tokens")
        val sampleRate = descriptor.getInt("sample_rate")
        check(sampleRate == 16000 && descriptor.getBoolean("add_blank")) { "Неподходящий формат встроенного голоса." }
        val normalized = text.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
            .filter { vocab.has(it.toString()) }.trim()
        require(normalized.isNotEmpty()) { "В переводе нет символов, доступных выбранному голосу." }

        val environment = OrtEnvironment.getEnvironment()
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            options.setMemoryPatternOptimization(false)
            environment.createSession(graph.absolutePath, options).use { session ->
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
                    val ids = LongArray(2 * clause.length + 1)
                    clause.forEachIndexed { index, c -> ids[2 * index + 1] = vocab.getLong(c.toString()) }
                    val pcm = synthesize(environment, session, ids, token)
                    playSamples(pcm, sampleRate, token)
                }
            }
        }
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
            OrtSession.RunOptions().use { run ->
                synchronized(runLock) {
                    checkActive(token)
                    activeRun = run
                }
                try {
                    session.run(feed, setOf("y"), run).use { outputs ->
                        val samples = (outputs[0] as OnnxTensor).floatBuffer
                        check(samples.remaining() > 0) { "Синтезатор вернул пустой звук." }
                        return ShortArray(samples.remaining()) {
                            (samples.get().coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                        }
                    }
                } finally {
                    synchronized(runLock) { activeRun = null }
                }
            }
        } finally {
            feed.values.forEach { it.close() }
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
