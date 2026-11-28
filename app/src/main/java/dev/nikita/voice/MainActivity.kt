package dev.nikita.voice

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

internal class DeviceBridge(private val activity: MainActivity) {
    // WebView calls these narrow entry points on its bridge thread.
    @JavascriptInterface fun status(): String = activity.statusJson()
    @JavascriptInterface fun hasShonaNumberGroups(text: String): Boolean = ShonaSpeechText.hasNumberGroups(text)
    @JavascriptInterface fun translate(id: Int, text: String, from: String, to: String) =
        activity.runOnUiThread { activity.translate(id, text, from, to) }
    @JavascriptInterface fun speak(id: Int, text: String, lang: String) =
        activity.runOnUiThread { activity.speak(id, text, lang) }
    @JavascriptInterface fun speakWithVoice(id: Int, text: String, lang: String, voiceId: String) =
        activity.runOnUiThread { activity.speak(id, text, lang, voiceId) }
    @JavascriptInterface fun record(id: Int, lang: String) =
        activity.runOnUiThread { activity.record(id, lang) }
    @JavascriptInterface fun finish(id: Int) = activity.runOnUiThread { activity.finishRecording(id) }
    @JavascriptInterface fun live(id: Int, from: String, to: String) =
        activity.runOnUiThread { activity.live(id, from, to) }
    @JavascriptInterface fun journalAdd(from: String, to: String, source: String, translation: String, mode: String) =
        activity.journal.add(System.currentTimeMillis(), from, to, source, translation, mode)
    @JavascriptInterface fun journalRead(): String = activity.journal.read().toString()
    @JavascriptInterface fun journalClear() = activity.journal.clear()
    @JavascriptInterface fun journalExport(): String = activity.exportJournal()
    @JavascriptInterface fun cancel(id: Int) = activity.runOnUiThread { activity.cancel(id) }
    @JavascriptInterface fun setFastTranslation(enabled: Boolean) { MainActivity.fastTranslation = enabled }
    @JavascriptInterface fun prepare(from: String, to: String, voiceId: String) =
        activity.runOnUiThread { activity.prepare(from, to, voiceId) }
}

class MainActivity : Activity() {
    companion object {
        // A previous Activity's running native inference must finish before a new one loads weights.
        private val worker = Executors.newSingleThreadExecutor()
        private const val MIC_PERMISSION = 16
        // User requests waiting for the worker. Background preparation yields to them between models.
        private val waiting = AtomicInteger()
        private var preparing = false // Read and written on the UI thread.
        /** One translation candidate instead of three into Shona. Set from the settings screen. */
        @Volatile var fastTranslation = true
    }
    private class Operation(val id: Int) {
        @Volatile var cancelled = false
        var future: Future<*>? = null
        var audio: SpeechOutput? = null
        var inputLanguage: String? = null
        val finishedRecording = CompletableFuture<() -> FloatArray>()
        var capturing = false
        /** Start of the measured step. Voice input restarts it when the recording is handed over. */
        @Volatile var measuredFrom = 0L
        var liveTarget: String? = null
        var live: LiveCapture? = null
    }

    private lateinit var screen: ConversationScreen
    private val recorder = VoiceRecorder()
    private var active: Operation? = null
    private var foreground = false
    private var permissionOp: Operation? = null
    private var nextPreparation: Triple<String, String, String>? = null
    internal val journal by lazy { TranslationJournal(File(filesDir, "journal.jsonl")) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 8.0 exposes dark navigation icons through View; the theme attribute arrived in 8.1.
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        val memory = getSystemService(ActivityManager::class.java)
        ModelCache.budgetBytes = ModelCache.budgetFor(ActivityManager.MemoryInfo().also { memory.getMemoryInfo(it) }.totalMem)
        ModelCache.memoryLow = { ActivityManager.MemoryInfo().also { memory.getMemoryInfo(it) }.lowMemory }
        screen = ConversationScreen(this, DeviceBridge(this))
        setContentView(screen)
    }

    override fun onStart() {
        super.onStart()
        foreground = true
        screen.onResume()
    }

    override fun onStop() {
        foreground = false
        active?.let { end(it, "Операция остановлена.") }
        screen.onPause()
        super.onStop()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Keep models while the user briefly switches apps; free them when Android needs the memory.
        @Suppress("DEPRECATION")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && level != ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            ModelCache.clear()
        }
    }

    override fun onDestroy() {
        screen.removeJavascriptInterface("NikitaPlatform")
        (screen.parent as? android.view.ViewGroup)?.removeView(screen)
        screen.destroy()
        super.onDestroy()
    }

    private fun root(): File? = getExternalFilesDir(null)?.let { File(it, "models") }
    private fun issue(dir: File?) = if (dir != null) modelPackIssue(dir) else "Хранилище моделей недоступно."
    internal fun statusJson(): String {
        val dir = root()
        val problem = issue(dir)
        val voices = if (problem == null) ShonaVoice.available(dir) else emptyList()
        val options = JSONArray()
        for (voice in voices) options.put(JSONObject().put("id", voice.id).put("name", voice.label))
        return JSONObject().put("translation", problem == null).put("asr", problem == null)
            .put("shonaTts", voices.isNotEmpty()).put("shonaVoices", options)
            .put("defaultShonaVoice", voices.firstOrNull()?.id ?: "").put("issue", problem ?: "").toString()
    }
    private fun models(): File {
        val path = root()
        val problem = issue(path)
        check(problem == null && path != null) { problem ?: "Хранилище моделей недоступно." }
        return path
    }
    private fun language(code: String) = when (code) {
        "ru" -> "rus"
        "sn" -> "sna"
        else -> throw IllegalArgumentException("Неизвестный язык.")
    }

    private fun newOperation(id: Int): Operation? {
        if (!foreground || id <= 0) {
            if (!isDestroyed) screen.send(id, "error", "Откройте приложение, чтобы продолжить.")
            return null
        }
        active?.let { end(it, "Операция остановлена.") }
        return Operation(id).also {
            active = it
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun end(op: Operation, message: String? = null, value: String = "") {
        if (active !== op) return
        active = null
        if (op.capturing) recorder.cancel()
        op.live?.cancel()
        if (message != null) {
            op.cancelled = true
            op.finishedRecording.cancel(false)
            op.future?.cancel(true)
        }
        op.audio?.close()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        screen.send(op.id, if (message == null) "result" else "error", message ?: value)
    }

    internal fun cancel(id: Int) {
        active?.takeIf { it.id == id }?.let { end(it, "Операция остановлена.") }
    }

    private fun work(op: Operation, stage: String, action: () -> String) {
        val queued = AtomicBoolean(true)
        fun dequeue() { if (queued.getAndSet(false)) waiting.decrementAndGet() }
        waiting.incrementAndGet()
        // done() also runs for a request cancelled before it started, so the count cannot leak.
        val task = object : FutureTask<Unit>({
            dequeue()
            val started = SystemClock.elapsedRealtime()
            op.measuredFrom = started
            ModelCache.takeLoadMillis()
            try {
                if (!op.cancelled) runAction(op, stage, action)
            } finally {
                Log.i("ShonaVoice", "stage=$stage elapsed_ms=${SystemClock.elapsedRealtime() - started}")
            }
        }) {
            override fun done() = dequeue()
        }
        op.future = task
        worker.execute(task)
    }

    private fun runAction(op: Operation, stage: String, action: () -> String) {
        try {
            val value = action()
            val timing = timingJson(stage, SystemClock.elapsedRealtime() - op.measuredFrom, ModelCache.takeLoadMillis())
            runOnUiThread {
                if (active === op) screen.send(op.id, "timing", timing)
                end(op, value = value)
            }
        } catch (e: Exception) {
            val message = e.localizedMessage?.lineSequence()?.firstOrNull()?.take(250) ?: "Обработка прервана."
            runOnUiThread { end(op, message) }
        } catch (e: LinkageError) {
            runOnUiThread { end(op, "Не удалось загрузить библиотеки моделей на этом телефоне.") }
        } catch (e: OutOfMemoryError) {
            ModelCache.clear()
            runOnUiThread { end(op, "Недостаточно оперативной памяти. Закройте другие приложения и повторите.") }
        }
    }

    private fun timingJson(stage: String, millis: Long, loadMillis: Long): String {
        val memory = ActivityManager.MemoryInfo().also { getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        return JSONObject().put("stage", stage).put("ms", millis).put("loadMs", loadMillis)
            .put("ramMb", memory.totalMem / (1024 * 1024)).put("budgetMb", ModelCache.budgetBytes / (1024 * 1024))
            .put("cores", Runtime.getRuntime().availableProcessors()).put("fast", fastTranslation).toString()
    }

    /**
     * Load the recognizer, translator and voice for the selected direction while the user is reading
     * or typing, so the first recording and translation do not wait for weights. Only models that fit
     * in the memory budget are loaded, and any user request takes priority between models.
     */
    internal fun prepare(from: String, to: String, voiceId: String) {
        if (!foreground) return
        if (preparing) {
            // Run once more for the latest direction when the current preparation ends.
            nextPreparation = Triple(from, to, voiceId)
            return
        }
        val src = runCatching { language(from) }.getOrNull() ?: return
        val dst = runCatching { language(to) }.getOrNull() ?: return
        if (src == dst) return
        val path = root() ?: return
        preparing = true
        worker.execute {
            val started = SystemClock.elapsedRealtime()
            try {
                if (issue(path) != null) return@execute
                val steps = listOf<() -> Boolean>(
                    // The translator first: a recording hides the recognizer's load, nothing hides the translator's.
                    { Nllb(path).warm(src, dst, beam = !fastTranslation) },
                    { MmsAsr(path).warm(src) },
                    { SpeechOutput.warm(path, dst, voiceId) }
                )
                for (step in steps) {
                    if (waiting.get() > 0 || !foreground) break
                    if (!step()) break
                }
            } catch (e: Throwable) {
                // Preparation is an optimization. The real request reports any problem with the models.
                Log.w("ShonaVoice", "Model preparation skipped", e)
            } finally {
                Log.i("ShonaVoice", "stage=prepare elapsed_ms=${SystemClock.elapsedRealtime() - started}")
                runOnUiThread {
                    preparing = false
                    nextPreparation?.let { (f, t, v) ->
                        nextPreparation = null
                        if (!isDestroyed) prepare(f, t, v)
                    }
                }
            }
        }
    }

    internal fun translate(id: Int, text: String, from: String, to: String) {
        val op = newOperation(id) ?: return
        work(op, "translation") {
            require(text.codePointCount(0, text.length) <= 2000 && text.isNotBlank()) { "Введите от 1 до 2000 символов." }
            Nllb(models()).translate(text, language(from), language(to), beam = !fastTranslation)
        }
    }

    internal fun speak(id: Int, text: String, lang: String, voiceId: String = "") {
        val op = newOperation(id) ?: return
        val path = root()
        if (path == null) { end(op, "Хранилище приложения недоступно."); return }
        val engine = SpeechOutput(applicationContext, path)
        op.audio = engine
        work(op, "tts") {
            require(text.isNotBlank() && text.length <= 12000) { "Не удалось озвучить текст такой длины." }
            engine.speak(text, language(lang), voiceId)
            ""
        }
    }

    internal fun record(id: Int, lang: String) {
        val op = newOperation(id) ?: return
        if (lang != "ru" && lang != "sn") { end(op, "Неизвестный язык записи."); return }
        op.inputLanguage = language(lang)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (permissionOp == null) {
                permissionOp = op
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_PERMISSION)
            } else {
                end(op, "Подтвердите разрешение и снова нажмите микрофон.")
            }
        } else {
            beginRecording(op)
        }
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code != MIC_PERMISSION) return
        val op = permissionOp
        permissionOp = null
        if (op == null || active !== op) return
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED && foreground) {
            if (op.liveTarget != null) beginLive(op) else beginRecording(op)
        }
        else end(op, "Разрешите доступ к микрофону в настройках приложения.")
    }

    private fun beginRecording(op: Operation) {
        try {
            recorder.start(
                onStarted = { runOnUiThread { if (active === op && op.capturing) screen.send(op.id, "recording") } },
                onLimit = { runOnUiThread { if (active === op && op.capturing) finishRecording(op.id) } }
            )
            op.capturing = true
            // Load the language model while the microphone is collecting this turn.
            // Consume the complete recording only after manual release or the time limit.
            work(op, "record_and_asr") {
                MmsAsr(models()).transcribe(op.inputLanguage!!) {
                    val pcm = op.finishedRecording.get().invoke()
                    // Report recognition time without the time spent speaking.
                    op.measuredFrom = SystemClock.elapsedRealtime()
                    val clip = encodeWav(pcm)
                    runOnUiThread { if (active === op) screen.send(op.id, "audio", clip) }
                    pcm
                }.trim().also {
                    require(it.isNotBlank()) { "Не удалось разобрать речь. Повторите запись или введите текст." }
                }
            }
        } catch (e: Exception) {
            end(op, e.localizedMessage ?: "Не удалось открыть микрофон.")
        }
    }

    internal fun finishRecording(id: Int) {
        val op = active?.takeIf { it.id == id && it.inputLanguage != null } ?: return
        if (op.liveTarget != null) {
            val live = op.live
            if (live == null) end(op, "Непрерывный перевод остановлен.") else {
                live.stop()
                screen.send(id, "live-state", "stopping")
            }
            return
        }
        if (!op.capturing) {
            if (permissionOp === op) end(op, "После разрешения доступа нажмите микрофон ещё раз.")
            return
        }
        val capture = recorder.stopCapture()
        op.capturing = false
        screen.send(id, "finishing")
        op.finishedRecording.complete(capture)
    }

    /**
     * Hands-free translation: one tap keeps the microphone open, every phrase is recognized and
     * translated while the next one is being recorded, and each result is added to the journal.
     */
    internal fun live(id: Int, from: String, to: String) {
        val op = newOperation(id) ?: return
        val src = runCatching { language(from) }.getOrNull()
        val dst = runCatching { language(to) }.getOrNull()
        if (src == null || dst == null || src == dst) { end(op, "Неизвестное направление перевода."); return }
        op.inputLanguage = src
        op.liveTarget = dst
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (permissionOp == null) {
                permissionOp = op
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_PERMISSION)
            } else end(op, "Подтвердите разрешение и снова включите непрерывный перевод.")
        } else beginLive(op)
    }

    private fun beginLive(op: Operation) {
        val path = try { models() } catch (e: Exception) { end(op, e.localizedMessage ?: "Модели недоступны."); return }
        val src = op.inputLanguage!!
        val dst = op.liveTarget!!
        val pending = AtomicInteger()
        val capture = LiveCapture(
            onSegment = { pcm, rate -> processSegment(op, path, src, dst, pcm, rate, pending) },
            onSpeech = { speaking ->
                runOnUiThread { if (active === op) screen.send(op.id, "live-state", if (speaking) "speech" else "listening") }
            },
            onClosed = { failure ->
                // Runs after the last phrase was queued; the worker finishes it first.
                worker.execute {
                    runOnUiThread {
                        if (failure != null) end(op, failure.localizedMessage ?: "Микрофон недоступен.")
                        else end(op, value = "")
                    }
                }
            }
        )
        op.live = capture
        capture.start()
        screen.send(op.id, "live-state", "listening")
        // Load both models while the first phrase is being spoken.
        worker.execute {
            runCatching {
                if (!op.cancelled) Nllb(path).warm(src, dst, beam = !fastTranslation)
                if (!op.cancelled) MmsAsr(path).warm(src)
            }
        }
    }

    private fun processSegment(op: Operation, path: File, src: String, dst: String, pcm: ShortArray, rate: Int,
                               pending: AtomicInteger) {
        pending.incrementAndGet()
        runOnUiThread { if (active === op) screen.send(op.id, "live-queue", pending.get().toString()) }
        worker.execute {
            try {
                if (op.cancelled) return@execute
                ModelCache.takeLoadMillis()
                val started = SystemClock.elapsedRealtime()
                val audio = Resampler.to16k(pcm, pcm.size, rate)
                val text = MmsAsr(path).transcribe(src) { audio }.trim()
                val recognized = SystemClock.elapsedRealtime()
                if (text.isEmpty() || op.cancelled) return@execute
                val translation = Nllb(path).translate(text, src, dst, beam = !fastTranslation).trim()
                val done = SystemClock.elapsedRealtime()
                val at = System.currentTimeMillis()
                journal.add(at, code(src), code(dst), text, translation, "live")
                val event = JSONObject().put("source", text).put("translation", translation).put("at", at)
                    .put("seconds", audio.size / 16_000.0).put("asrMs", recognized - started)
                    .put("mtMs", done - recognized).put("loadMs", ModelCache.takeLoadMillis()).toString()
                runOnUiThread { if (active === op) screen.send(op.id, "live", event) }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                val text = e.localizedMessage?.lineSequence()?.firstOrNull()?.take(200) ?: "Фрагмент не обработан."
                runOnUiThread { if (active === op) screen.send(op.id, "live-error", text) }
            } catch (e: OutOfMemoryError) {
                ModelCache.clear()
                runOnUiThread { if (active === op) screen.send(op.id, "live-error", "Недостаточно памяти для фрагмента.") }
            } finally {
                val left = pending.decrementAndGet()
                runOnUiThread { if (active === op) screen.send(op.id, "live-queue", left.toString()) }
            }
        }
    }

    private fun code(lang: String) = if (lang == "rus") "ru" else "sn"

    /** Saves the journal as a text file in Downloads/ShonaVoice. Returns a message for the user. */
    internal fun exportJournal(): String {
        val text = journal.exportText()
        val name = "ShonaVoice-journal-" + java.text.SimpleDateFormat("yyyy-MM-dd-HHmm", java.util.Locale.ROOT)
            .format(java.util.Date()) + ".txt"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return try {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/ShonaVoice")
                }
                val uri = checkNotNull(contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
                checkNotNull(contentResolver.openOutputStream(uri)).use { it.write(text.toByteArray(Charsets.UTF_8)) }
                "Журнал сохранён: Загрузки/ShonaVoice/$name"
            } catch (e: Exception) {
                shareText(text)
                "Не удалось сохранить файл, открываю «Поделиться»."
            }
        }
        shareText(text)
        return "Выберите, куда отправить журнал."
    }

    private fun shareText(text: String) = runOnUiThread {
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_SUBJECT, "Журнал переводов Shona Voice")
            .putExtra(android.content.Intent.EXTRA_TEXT, text.take(400_000))
        startActivity(android.content.Intent.createChooser(intent, "Журнал переводов"))
    }

    /** In-memory preview of the resampled recording. The recognizer additionally trims long silence at its ends. */
    private fun encodeWav(samples: FloatArray): String {
        val size = samples.size * 2
        val wav = ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(s: String) { wav.put(s.toByteArray(Charsets.US_ASCII)) }
        tag("RIFF"); wav.putInt(36 + size); tag("WAVE"); tag("fmt ")
        wav.putInt(16); wav.putShort(1); wav.putShort(1); wav.putInt(16000); wav.putInt(32000)
        wav.putShort(2); wav.putShort(16); tag("data"); wav.putInt(size)
        for (sample in samples) wav.putShort((sample.coerceIn(-1f, 1f) * 32768f).roundToInt()
            .coerceIn(-32768, 32767).toShort())
        return Base64.encodeToString(wav.array(), Base64.NO_WRAP)
    }
}
