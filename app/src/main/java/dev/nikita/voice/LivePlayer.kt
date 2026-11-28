package dev.nikita.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.Process
import android.os.SystemClock
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * One continuous voice for simultaneous translation. Phrases are queued and never overlap.
 * When the queue falls behind the speaker, playback speeds up slightly (pitch preserved) so the
 * delay does not keep growing. [onSpeaking] reports when sound is actually coming out.
 */
internal class LivePlayer(private val onSpeaking: (Boolean) -> Unit) {
    private class Chunk(val pcm: ShortArray, val rate: Int)

    private val queue = LinkedBlockingQueue<Chunk>()
    private val backlog = AtomicLong() // Milliseconds of audio queued but not yet written.
    @Volatile private var closed = false
    @Volatile private var draining = false
    private var worker: Thread? = null

    fun start() {
        worker = thread(name = "Shona Voice simultaneous playback") { run() }
    }

    fun enqueue(pcm: ShortArray, rate: Int) {
        if (closed || pcm.isEmpty()) return
        backlog.addAndGet(pcm.size * 1000L / rate)
        queue.put(Chunk(pcm, rate))
    }

    /** Audio waiting to be spoken, in milliseconds. */
    fun backlogMillis(): Long = backlog.get()

    /** Play everything queued, then stop. Blocks up to [timeoutMs]. */
    fun drain(timeoutMs: Long, active: () -> Boolean) {
        draining = true
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (worker?.isAlive == true && SystemClock.elapsedRealtime() < deadline && active()) worker?.join(50)
        close()
    }

    /** Stop immediately. */
    fun close() {
        closed = true
        queue.clear()
        worker?.interrupt()
    }

    private fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        var track: AudioTrack? = null
        var rate = 0
        var written = 0L
        var speed = 1f
        var speaking = false
        fun setSpeaking(value: Boolean) {
            if (value != speaking) {
                speaking = value
                onSpeaking(value)
            }
        }
        fun played(): Long = (track?.playbackHeadPosition?.toLong() ?: 0L) and 0xffffffffL
        try {
            while (!closed) {
                val chunk = try { queue.poll(40, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { break }
                if (chunk == null) {
                    // Keep the echo guard on until the device has played the last sample, plus a short tail.
                    if (speaking && track != null && played() >= written) {
                        SystemClock.sleep(250)
                        if (queue.isEmpty()) setSpeaking(false)
                    }
                    if (draining && queue.isEmpty() && (track == null || played() >= written)) break
                    continue
                }
                if (track == null || chunk.rate != rate) {
                    track?.let { old ->
                        while (!closed && played() < written) SystemClock.sleep(20)
                        old.release()
                    }
                    rate = chunk.rate
                    track = open(rate)
                    written = 0
                    speed = 1f
                }
                val target = when {
                    backlog.get() > 12_000 -> 1.3f
                    backlog.get() > 6_000 -> 1.15f
                    else -> 1f
                }
                if (target != speed) {
                    runCatching { track.playbackParams = PlaybackParams().setSpeed(target).setPitch(1f) }
                    speed = target
                }
                setSpeaking(true)
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
                var at = 0
                while (at < chunk.pcm.size && !closed) {
                    val n = track.write(chunk.pcm, at, minOf(4096, chunk.pcm.size - at), AudioTrack.WRITE_BLOCKING)
                    if (n < 0) break
                    at += n
                }
                written += at
                if (written < track.bufferSizeInFrames && !closed) {
                    // A short first phrase must still reach the device's startup threshold.
                    val pad = ShortArray(track.bufferSizeInFrames - written.toInt())
                    val n = track.write(pad, 0, pad.size, AudioTrack.WRITE_BLOCKING)
                    if (n > 0) written += n
                }
                backlog.addAndGet(-(chunk.pcm.size * 1000L / chunk.rate))
            }
        } finally {
            backlog.set(0)
            track?.let {
                runCatching { if (it.playState == AudioTrack.PLAYSTATE_PLAYING) it.pause() }
                it.release()
            }
            setSpeaking(false)
        }
    }

    private fun open(rate: Int): AudioTrack {
        val mask = AudioFormat.CHANNEL_OUT_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val format = AudioFormat.Builder().setSampleRate(rate).setEncoding(encoding).setChannelMask(mask).build()
        val minimum = AudioTrack.getMinBufferSize(rate, mask, encoding)
        return AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(ShonaPlayback.bufferSizeBytes(rate, minimum)).build()
    }
}
