package dev.nikita.voice

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/**
 * Keeps ONNX sessions loaded between requests instead of re-reading hundreds of megabytes of
 * weights for every recording, translation and spoken reply.
 *
 * Sessions are only acquired on the serial inference worker. [clear] may run on any thread:
 * a session that is in use is closed by its last [use] call, never underneath a running model.
 * The byte budget follows the phone's RAM. A model larger than the remaining budget evicts the
 * least recently used idle models; a model larger than the whole budget is closed after use,
 * exactly as before caching existed.
 */
@OptIn(ExperimentalContracts::class)
internal object ModelCache {
    @PublishedApi internal class Entry(val key: String, val session: OrtSession, val bytes: Long) {
        var leases = 0
        var retired = false
    }

    // Access order: the first entry is the least recently used one.
    private val entries = LinkedHashMap<String, Entry>(8, 0.75f, true)

    private val loadNanos = java.util.concurrent.atomic.AtomicLong()

    /** Milliseconds spent reading models since the previous call. Shown to the user per step. */
    fun takeLoadMillis(): Long = loadNanos.getAndSet(0) / 1_000_000

    /** Bytes of model weights that may stay resident. 0 disables caching. */
    @Volatile var budgetBytes: Long = 0

    /** Returns true when the system reports low memory; cached idle models are then released first. */
    @Volatile var memoryLow: () -> Boolean = { false }

    /** Intra-op threads for the heavier graphs: half of the cores, between 2 and 4. */
    fun threads(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)

    /** Budget for a phone with [totalBytes] of RAM. On 6 GB it holds a whole voice turn (about 2.1 GB). */
    fun budgetFor(totalBytes: Long): Long =
        (totalBytes * 55 / 100 - 700L * MB).coerceIn(0L, 3_500L * MB)

    /** Size on disk of a graph plus the external weight files stored beside it. */
    fun weightBytes(graph: File, vararg external: File): Long = graph.length() + external.sumOf { it.length() }

    inline fun <T> use(key: String, bytes: Long, noinline open: () -> OrtSession, body: (OrtSession) -> T): T {
        contract { callsInPlace(body, InvocationKind.EXACTLY_ONCE) }
        val entry = acquire(key, bytes, open)
        try {
            return body(entry.session)
        } finally {
            release(entry)
        }
    }

    /** Session from a file with the options built by [configure]. */
    inline fun <T> use(file: File, bytes: Long, noinline configure: (OrtSession.SessionOptions) -> Unit,
                       body: (OrtSession) -> T): T {
        contract { callsInPlace(body, InvocationKind.EXACTLY_ONCE) }
        return use(file.canonicalPath, bytes, { open(file, configure) }, body)
    }

    fun open(file: File, configure: (OrtSession.SessionOptions) -> Unit): OrtSession =
        OrtSession.SessionOptions().use { options ->
            configure(options)
            OrtEnvironment.getEnvironment().createSession(file.absolutePath, options)
        }

    /**
     * Load a model ahead of time when it fits without evicting anything. Returns false when it was
     * skipped. Used to prepare the next stage of a voice turn while the phone is otherwise idle.
     */
    fun warm(key: String, bytes: Long, open: () -> OrtSession): Boolean {
        synchronized(this) {
            if (entries.containsKey(key)) return true
            if (bytes > budgetBytes - residentBytes() || memoryLow()) return false
        }
        release(acquire(key, bytes, open))
        return true
    }

    fun warm(file: File, bytes: Long = file.length(), configure: (OrtSession.SessionOptions) -> Unit): Boolean =
        warm(file.canonicalPath, bytes) { open(file, configure) }

    fun isLoaded(key: String): Boolean = synchronized(this) { entries.containsKey(key) }

    /** Release every idle model now and every busy one as soon as its current run finishes. */
    fun clear() {
        val idle = mutableListOf<Entry>()
        synchronized(this) {
            for (entry in entries.values) {
                entry.retired = true
                if (entry.leases == 0) idle.add(entry)
            }
            entries.clear()
        }
        idle.forEach { it.session.close() }
    }

    private fun residentBytes(): Long = entries.values.sumOf { it.bytes }

    @PublishedApi internal fun acquire(key: String, bytes: Long, open: () -> OrtSession): Entry {
        val evicted = mutableListOf<Entry>()
        synchronized(this) {
            entries[key]?.let {
                it.leases++
                return it
            }
            // Make room before loading so that two large graphs are not resident at the peak.
            if (memoryLow()) evictIdle(Long.MAX_VALUE, evicted)
            evictIdle(bytes - (budgetBytes - residentBytes()), evicted)
        }
        evicted.forEach { it.session.close() }
        // Loading takes seconds. Keep the lock free so clear() from the UI thread never waits on it.
        val loadStarted = System.nanoTime()
        val session = open()
        loadNanos.addAndGet(System.nanoTime() - loadStarted)
        synchronized(this) {
            entries[key]?.let {
                // Only the worker loads models, so this is defensive: keep the first copy.
                session.close()
                it.leases++
                return it
            }
            val entry = Entry(key, session, bytes)
            entry.leases = 1
            if (bytes <= budgetBytes) entries[key] = entry else entry.retired = true
            return entry
        }
    }

    @PublishedApi internal fun release(entry: Entry) {
        val close = synchronized(this) {
            entry.leases--
            entry.leases == 0 && entry.retired
        }
        if (close) entry.session.close()
    }

    /** Called with the lock held. Moves idle entries, oldest first, into [out] until [needed] bytes are free. */
    private fun evictIdle(needed: Long, out: MutableList<Entry>) {
        if (needed <= 0) return
        var freed = 0L
        val iterator = entries.values.iterator()
        while (iterator.hasNext() && freed < needed) {
            val entry = iterator.next()
            if (entry.leases > 0) continue
            iterator.remove()
            entry.retired = true
            out.add(entry)
            freed += entry.bytes
        }
    }

    private const val MB = 1024L * 1024L
}
