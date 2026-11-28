package dev.nikita.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Locale

class Nllb(modelRoot: File) {
    private val directory = File(modelRoot, "nllb")
    private val environment = OrtEnvironment.getEnvironment()
    private val tokenizer = File(directory, "sentencepiece.bpe.model").absolutePath

    /** [beam] compares three continuations into Shona. Without it every direction decodes one, which is faster. */
    fun translate(text: String, src: String, dst: String, beam: Boolean = true): String {
        require(setOf(src, dst) == setOf("rus", "sna")) { "Неизвестное направление перевода." }
        require(text.isNotBlank()) { "Нет текста для перевода." }
        require(text.codePointCount(0, text.length) <= 2000) { "Текст превышает 2000 символов." }
        if (text.codePoints().noneMatch { Character.isLetter(it) }) return text.trim()
        val chunks = splitText(text)
        require(chunks.isNotEmpty()) { "Текст не содержит слов для перевода." }
        // Prepare fallback fragments only for sentences containing a link, code or other exact literal.
        // Usually the whole sentence passes validation and keeps its surrounding grammatical context.
        val plans = chunks.associate { it.text to ShonaTranslationText.parts(it.text) }
        val fragments = plans.values.flatten().filter { it.translate }.associate { it.text to splitPlain(it.text) }
        val jobs = linkedMapOf<String, IntArray>()
        for (chunk in chunks) {
            val parts = plans.getValue(chunk.text)
            if (parts.isEmpty() || (parts.any { it.translate } && chunk.pieces.size <= SOURCE_BUDGET)) {
                jobs[chunk.text] = chunk.pieces
            }
            parts.filter { it.translate }.forEach { part ->
                fragments.getValue(part.text).forEach { jobs[it.text] = it.pieces }
            }
        }
        if (jobs.keys.none { word -> word.any { it.isLetter() } }) return text.trim()
        val config = JSONObject(File(directory, "config.json").readText())
        // The paired FLEURS check favored beam search into Shona and greedy decoding into Russian.
        val preferBeam = dst == "sna" && beam
        // The beam derivative keeps the greedy output, so once it exists one decoder serves both directions
        // and only one 476 MB graph needs to stay in memory.
        val decoderFile = if (preferBeam) BeamDecoder.model(directory)
            else BeamDecoder.existing(directory) ?: File(directory, "decoder.onnx")
        val encoded = linkedMapOf<String, Encoded>()
        val translations = mutableMapOf<String, String>()
        try {
            session(File(directory, "encoder.onnx")) { encoder ->
                for ((source, pieces) in jobs) {
                    checkRunning()
                    if (source.any { it.isLetter() }) encoded[source] = encode(encoder, pieces, config.getLong(src))
                    else translations[source] = source
                }
            }
            val pieceCount = config.getLong("piece_count")
            return session(decoderFile) { decoder ->
                run {
                    ModelCache.use(SELECTOR_KEY, SELECTOR_BYTES, ::openSelector) { selector ->
                        fun predict(source: String): String = translations.getOrPut(source) {
                            val alternatives = generate(decoder, selector, encoded.getValue(source), config.getLong(dst), pieceCount, preferBeam)
                            val numbers = ShonaTranslationText.numbers(source)
                            var first = ""
                            for (candidate in alternatives) {
                                val pieces = candidate.filter { it in 4L..pieceCount }.map { (it - 1L).toInt() }.toIntArray()
                                val decoded = NativeSentencePiece.decode(tokenizer, pieces).trim()
                                if (decoded.isEmpty()) continue
                                if (first.isEmpty()) first = decoded
                                if (ShonaTranslationText.preserves(source, decoded) && ShonaTranslationText.numbers(decoded) == numbers) {
                                    return@getOrPut decoded
                                }
                            }
                            check(first.isNotEmpty()) { "Модель вернула пустой перевод." }
                            first
                        }
                        for ((source, parts) in plans) {
                            checkRunning()
                            if (parts.isEmpty()) { predict(source); continue }
                            if (parts.none { it.translate }) { translations[source] = source; continue }
                            val whole = if (source in encoded) predict(source) else null
                            if (whole != null && ShonaTranslationText.preserves(source, whole)) continue
                            translations[source] = parts.joinToString("") { part ->
                                if (!part.translate) part.text else {
                                    val partial = fragments.getValue(part.text).joinToString(" ") { predict(it.text) }
                                    ShonaTranslationText.inlineTranslation(part.text, partial)
                                }
                            }
                        }
                    }
                }
                val byParagraph = chunks.groupBy { it.paragraph }
                (chunks.first().paragraph..chunks.last().paragraph).joinToString("\n") { index ->
                    byParagraph[index]?.joinToString(" ") { translations.getValue(it.text) }.orEmpty()
                }
            }
        } finally {
            encoded.values.forEach { it.close() }
        }
    }

    private data class Chunk(val paragraph: Int, val text: String, val pieces: IntArray)

    private fun splitPlain(text: String): List<Chunk> = mutableListOf<Chunk>().also { splitSentence(text, 0, it) }

    private fun splitText(source: String): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        val seen = mutableMapOf<String, List<Chunk>>()
        for ((index, paragraph) in source.lineSequence().withIndex()) {
            fun append(part: String) {
                val sentence = part.trim()
                if (sentence.isEmpty()) return
                val segments = seen.getOrPut(sentence) {
                    if (ShonaTranslationText.parts(sentence).isEmpty()) splitPlain(sentence)
                    else listOf(Chunk(index, sentence, NativeSentencePiece.encode(tokenizer, sentence)))
                }
                chunks.addAll(segments.map { it.copy(paragraph = index) })
            }
            var start = 0
            for (end in SENTENCE_END.findAll(paragraph)) {
                if (end.value == ".") {
                    val word = LAST_WORD.find(paragraph.substring(start, end.range.first))?.value.orEmpty()
                    if (word.lowercase(Locale.ROOT) in ABBREVIATIONS || (word.length == 1 && word[0].isUpperCase())) continue
                }
                append(paragraph.substring(start, end.range.last + 1))
                start = end.range.last + 1
            }
            append(paragraph.substring(start))
        }
        return chunks
    }

    private fun splitSentence(text: String, paragraph: Int, result: MutableList<Chunk>) {
        if (text.isEmpty()) return
        val tokens = NativeSentencePiece.encode(tokenizer, text)
        if (tokens.size <= SOURCE_BUDGET) {
            if (tokens.isNotEmpty()) result.add(Chunk(paragraph, text, tokens))
            return
        }
        // Around the middle prefer a clause boundary, then whitespace. For a single
        // unbroken word split between code points; every input character is retained.
        val midpoint = text.offsetByCodePoints(0, text.codePointCount(0, text.length) / 2)
        val middle = (text.length / 4).coerceAtLeast(1)..minOf(text.lastIndex, text.length * 3 / 4)
        val edge = middle.filter { text[it - 1] in ",;:!?。！？；" && text[it].isWhitespace() }
            .minByOrNull { kotlin.math.abs(it - midpoint) }
            ?: middle.filter { text[it].isWhitespace() }.minByOrNull { kotlin.math.abs(it - midpoint) }
            ?: midpoint
        splitSentence(text.substring(0, edge).trim(), paragraph, result)
        splitSentence(text.substring(edge).trim(), paragraph, result)
    }

    private data class Encoded(val hidden: OnnxTensor, val mask: OnnxTensor) : AutoCloseable {
        override fun close() {
            hidden.close()
            mask.close()
        }
    }

    private fun encode(encoder: OrtSession, pieces: IntArray, language: Long): Encoded {
        val ids = LongArray(pieces.size + 2)
        ids[0] = language
        for (i in pieces.indices) ids[i + 1] = if (pieces[i] == 0) 3L else pieces[i].toLong() + 1L
        ids[ids.lastIndex] = EOS
        val shape = longArrayOf(1, ids.size.toLong())
        val mask = longTensor(LongArray(ids.size) { 1L }, shape)
        try {
            val hidden = longTensor(ids, shape).use { input ->
                encoder.run(mapOf("input_ids" to input, "attention_mask" to mask), setOf("last_hidden_state")).use { output ->
                    val state = output.tensor("last_hidden_state")
                    OnnxTensor.createTensor(environment, state.floatBuffer, state.info.shape)
                }
            }
            return Encoded(hidden, mask)
        } catch (error: Throwable) {
            mask.close()
            throw error
        }
    }

    private data class Hypothesis(val tokens: List<Long>, val score: Double)
    private data class Candidate(val parent: Int, val token: Long, val score: Double)
    private data class Final(val tokens: List<Long>, val normalized: Double)

    private fun generate(
        decoder: OrtSession, selector: OrtSession, state: Encoded, language: Long, pieceCount: Long, preferBeam: Boolean
    ): List<List<Long>> {
        val past = decoder.inputNames.filter { it.startsWith(PAST) }.associateWith { "present." + it.removePrefix(PAST) }
        val self = past.filterKeys { ".decoder." in it }
        val cross = past.filterKeys { ".encoder." in it }
        val supportsScores = decoder.outputNames.containsAll(setOf("beam_tokens", "beam_logprobs"))
        val hasScores = supportsScores && (preferBeam || "next_token" !in decoder.outputNames)
        val width = if (preferBeam) BEAMS else 1
        check(hasScores || "next_token" in decoder.outputNames) { "Этот декодер не поддерживает требуемый формат NLLB." }
        val prediction = if (hasScores) setOf("beam_tokens", "beam_logprobs") else setOf("next_token")
        val outputs = self.values.toSet() + prediction
        val empty = OnnxTensor.createTensor(environment, FloatBuffer.allocate(0), longArrayOf(1, 16, 0, 64))
        var first: OrtSession.Result? = null
        var previous: OrtSession.Result? = null
        val crossOwners = mutableListOf<OrtSession.Result>()
        val crossFeeds = mutableMapOf<String, OnnxTensor>()
        var expanded: Encoded? = null
        var crossBatch = 0
        var parents = longArrayOf(0)
        val completed = mutableListOf<Final>()
        var active = listOf(Hypothesis(emptyList(), 0.0))
        val limit = (3 * state.mask.info.shape[1].toInt() + 32).coerceIn(64, 512)
        try {
            decode@ for (step in 0 until limit) {
                checkRunning()
                val initial = first
                val cached = initial != null
                val batch = active.size
                val feeds = mutableMapOf<String, OnnxTensor>()
                val selfOwners = mutableListOf<OrtSession.Result>()
                val result = try {
                    if (initial == null) {
                        past.keys.forEach { feeds[it] = empty }
                    } else {
                        if (batch != crossBatch) {
                            expanded?.close()
                            expanded = if (batch > 1) repeatSource(state, batch) else null
                            crossOwners.forEach { it.close() }
                            crossOwners.clear()
                            // Every beam sees the identical source. Expand cross-attention once.
                            selectCaches(selector, initial, cross, LongArray(batch), crossFeeds, crossOwners)
                            crossBatch = batch
                        }
                        feeds.putAll(crossFeeds)
                        selectCaches(selector, checkNotNull(previous), self, parents, feeds, selfOwners)
                    }
                    val source = expanded ?: state
                    feeds["encoder_hidden_states"] = source.hidden
                    feeds["encoder_attention_mask"] = source.mask
                    val ids = if (cached) active.map { it.tokens.last() }.toLongArray() else longArrayOf(EOS, language)
                    val shape = longArrayOf(batch.toLong(), if (cached) 1L else 2L)
                    longTensor(ids, shape).use { input ->
                        OnnxTensor.createTensor(environment, booleanArrayOf(cached)).use { branch ->
                            feeds["input_ids"] = input
                            feeds["use_cache_branch"] = branch
                            decoder.run(feeds, if (cached) outputs else outputs + cross.values)
                        }
                    }
                } finally {
                    selfOwners.forEach { it.close() }
                }
                if (previous !== first) previous?.close()
                if (first == null) first = result
                previous = result
                if (!hasScores) {
                    val token = result.tensor("next_token").longBuffer.get(0)
                    val path = active.single()
                    if (token == EOS) return listOf(path.tokens)
                    active = listOf(Hypothesis(path.tokens + token, 0.0))
                    continue@decode
                }
                val tokens = result.tensor("beam_tokens").longBuffer
                val scores = result.tensor("beam_logprobs").floatBuffer
                val topK = tokens.remaining() / batch
                val candidates = mutableListOf<Candidate>()
                for ((parent, path) in active.withIndex()) {
                    for (i in 0 until topK) {
                        val index = parent * topK + i
                        val token = tokens.get(index)
                        if (token == EOS || token in 4L..pieceCount) {
                            candidates.add(Candidate(parent, token, path.score + scores.get(index)))
                        }
                    }
                }
                val ranked = candidates.sortedByDescending { it.score }
                for (candidate in ranked.take(width).filter { it.token == EOS }) {
                    val words = active[candidate.parent].tokens
                    if (words.isNotEmpty()) completed.add(Final(words, candidate.score / (words.size + 1)))
                }
                completed.sortByDescending { it.normalized }
                while (completed.size > width) completed.removeAt(completed.lastIndex)
                val children = ranked.filter { it.token != EOS }.take(width)
                parents = children.map { it.parent.toLong() }.toLongArray()
                active = children.map { Hypothesis(active[it.parent].tokens + it.token, it.score) }
                val best = active.firstOrNull()
                val done = completed.size == width && best != null &&
                    completed.last().normalized >= best.score / best.tokens.size
                if (best == null || done) break@decode
            }
            check(completed.isNotEmpty()) { "Модель не завершила перевод предложения. Разделите это предложение на части." }
            return completed.map { it.tokens }
        } finally {
            if (previous !== first) previous?.close()
            first?.close()
            expanded?.close()
            crossOwners.forEach { it.close() }
            empty.close()
        }
    }

    private fun repeatSource(source: Encoded, batch: Int): Encoded {
        val original = source.hidden.floatBuffer
        val repeated = java.nio.ByteBuffer.allocateDirect(batch * original.remaining() * 4)
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
        repeat(batch) { repeated.put(original.duplicate()) }
        (repeated as java.nio.Buffer).flip()
        val shape = source.hidden.info.shape.clone().also { it[0] = batch.toLong() }
        val hidden = OnnxTensor.createTensor(environment, repeated, shape)
        try {
            val length = source.mask.info.shape[1].toInt()
            return Encoded(hidden, longTensor(LongArray(batch * length) { 1L }, longArrayOf(batch.toLong(), length.toLong())))
        } catch (error: Throwable) {
            hidden.close()
            throw error
        }
    }

    private fun selectCaches(
        selector: OrtSession, result: OrtSession.Result, names: Map<String, String>, parents: LongArray,
        feeds: MutableMap<String, OnnxTensor>, owners: MutableList<OrtSession.Result>
    ) {
        longTensor(parents, longArrayOf(parents.size.toLong())).use { rows ->
            val sameOrder = parents.indices.all { parents[it] == it.toLong() }
            for ((inputName, outputName) in names) {
                val cache = result.tensor(outputName)
                if (sameOrder && cache.info.shape[0] == parents.size.toLong()) {
                    feeds[inputName] = cache
                } else {
                    // ORT Gather keeps key/value caches in native buffers across decoding steps.
                    val selection = selector.run(mapOf("cache" to cache, "rows" to rows))
                    owners.add(selection)
                    feeds[inputName] = selection.tensor("selected")
                }
            }
        }
    }

    private fun checkRunning() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
    }

    private fun longTensor(data: LongArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(environment, LongBuffer.wrap(data), shape)

    private fun OrtSession.Result.tensor(name: String): OnnxTensor = get(name).get() as OnnxTensor

    private fun configure(options: OrtSession.SessionOptions) {
        options.setIntraOpNumThreads(ModelCache.threads())
        options.setInterOpNumThreads(1)
        options.setMemoryPatternOptimization(false)
        options.setCPUArenaAllocator(false)
        // EXTENDED fuses the dynamically quantized MatMul chains (DynamicQuantizeLinear + MatMulInteger
        // + scale) into one MatMulIntegerToFloat kernel, which BASIC leaves as separate operators.
        // The session stays cached, so its longer one-time optimization is paid once per launch.
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.EXTENDED_OPT)
    }

    // Kept loaded between requests: reading the 419 MB encoder and 476 MB decoder dominated short translations.
    private fun <T> session(file: File, body: (OrtSession) -> T): T = ModelCache.use(file, file.length(), ::configure, body)

    private fun openSelector(): OrtSession {
        val graph = checkNotNull(javaClass.getResourceAsStream("/beam-cache.onnx")).use { it.readBytes() }
        return OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(1)
            options.setInterOpNumThreads(1)
            options.setCPUArenaAllocator(false)
            options.setMemoryPatternOptimization(false)
            environment.createSession(graph, options)
        }
    }

    /** Load the graphs for the next translation from [src] to [dst] if memory allows. */
    fun warm(src: String, dst: String, beam: Boolean = true): Boolean {
        val decoder = if (dst == "sna" && beam) BeamDecoder.existing(directory) ?: return false
            else BeamDecoder.existing(directory) ?: File(directory, "decoder.onnx")
        val encoder = File(directory, "encoder.onnx")
        return ModelCache.warm(encoder, encoder.length(), ::configure) &&
            ModelCache.warm(decoder, decoder.length(), ::configure) &&
            ModelCache.warm(SELECTOR_KEY, SELECTOR_BYTES, ::openSelector)
    }

    private companion object {
        val SENTENCE_END = Regex("""[.!?…]+[»”"')\]]*(?=\s|$)""")
        val LAST_WORD = Regex("""\p{L}+$""")
        val ABBREVIATIONS = setOf("г", "гг", "ул", "д", "стр", "ред", "подп", "пп", "абз", "прил", "ч", "проф",
            "им", "см", "рис", "руб", "тыс", "млн", "млрд", "т", "е", "н", "др", "пр", "п", "ст", "mr", "mrs", "dr")
        const val EOS = 2L
        const val BEAMS = 3
        const val SOURCE_BUDGET = 192
        const val PAST = "past_key_values."
        const val SELECTOR_KEY = "resource:/beam-cache.onnx"
        const val SELECTOR_BYTES = 4096L
    }
}
