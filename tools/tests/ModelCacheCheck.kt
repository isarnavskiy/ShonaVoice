package dev.nikita.voice

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File

/**
 * Run alongside ModelCache.kt with the desktop onnxruntime 1.22.0 JAR on the classpath:
 * kotlinc ModelCache.kt tools/tests/ModelCacheCheck.kt -cp onnxruntime-1.22.0.jar ...
 * The argument is any small ONNX graph, for example app/src/main/resources/beam-cache.onnx.
 */
fun main(args: Array<String>) {
    val graph = File(args.single()).readBytes()
    val opened = mutableMapOf<String, Int>()
    fun opener(key: String): () -> OrtSession = {
        opened[key] = (opened[key] ?: 0) + 1
        OrtEnvironment.getEnvironment().createSession(graph)
    }
    fun closed(session: OrtSession) = runCatching { session.inputNames }.isFailure

    // Caching disabled: every use opens and closes, exactly like the release before caching.
    ModelCache.budgetBytes = 0
    val first = ModelCache.use("a", 10, opener("a")) { it }
    check(closed(first))
    ModelCache.use("a", 10, opener("a")) { }
    check(opened["a"] == 2)

    // A budget of 100 holds "a" and "b" but not "c" as well; the least recently used goes first.
    opened.clear()
    ModelCache.budgetBytes = 100
    val a = ModelCache.use("a", 40, opener("a")) { it }
    val b = ModelCache.use("b", 40, opener("b")) { it }
    ModelCache.use("a", 40, opener("a")) { check(it === a) }
    check(!closed(a) && !closed(b) && opened == mapOf("a" to 1, "b" to 1))
    val c = ModelCache.use("c", 40, opener("c")) { it }
    check(closed(b) && !closed(a) && !closed(c)) { "b was the least recently used model" }

    // A model in use is never closed under a running inference, even by clear().
    ModelCache.use("a", 40, opener("a")) { session ->
        ModelCache.clear()
        check(!closed(session))
        // A nested model that needs the whole budget must not evict the busy one either.
        ModelCache.use("d", 100, opener("d")) { check(!closed(session)) }
    }
    check(closed(a) && closed(c))

    // A model larger than the budget is used once and closed.
    val big = ModelCache.use("big", 101, opener("big")) { it }
    check(closed(big) && !ModelCache.isLoaded("big"))

    // Warm loads only what fits without evicting, and skips under memory pressure.
    ModelCache.clear()
    check(ModelCache.warm("w1", 60, opener("w1")))
    check(!ModelCache.warm("w2", 60, opener("w2")) && opened["w2"] == null)
    check(ModelCache.warm("w1", 60, opener("w1")) && opened["w1"] == 1)
    ModelCache.memoryLow = { true }
    check(!ModelCache.warm("w3", 10, opener("w3")))
    // Low memory releases idle models before the next load.
    ModelCache.use("x", 10, opener("x")) { }
    check(!ModelCache.isLoaded("w1") && ModelCache.isLoaded("x"))
    ModelCache.memoryLow = { false }
    ModelCache.clear()

    val gb = 1024L * 1024 * 1024
    check(ModelCache.budgetFor(4 * gb) in 1_400L * 1024 * 1024..1_700L * 1024 * 1024)
    check(ModelCache.budgetFor(5_500L * 1024 * 1024) >= 2_200L * 1024 * 1024)
    check(ModelCache.budgetFor(2 * gb) in 0L..500L * 1024 * 1024)
    check(ModelCache.budgetFor(16 * gb) == 5_000L * 1024 * 1024)
    check(ModelCache.threads() in 2..6)
    println("ModelCache checks passed")
}
