package dev.nikita.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** One bounded queue lets the next clause synthesize while the current one plays on the same track. */
internal object ShonaPlayback {
    /**
     * When set on the calling thread, synthesized audio goes to this sink instead of a new
     * AudioTrack. Simultaneous translation uses it to queue every voice on one continuous player.
     */
    val sink = ThreadLocal<((ShortArray, Int) -> Unit)?>()

    fun play(sampleRate: Int, active: () -> Unit, stopInference: () -> Unit, synthesize: ((ShortArray) -> Unit) -> Unit) {
        sink.get()?.let { target ->
            synthesize { audio ->
                active()
                target(audio, sampleRate)
            }
            return
        }
        pipe(active, synthesize, stopInference) { next -> render(sampleRate, active, next) }
    }

    internal fun pipe(active: () -> Unit, produce: ((ShortArray) -> Unit) -> Unit, stopInference: () -> Unit,
                      consume: (() -> ShortArray?) -> Unit) {
        val chunks = ArrayBlockingQueue<ShortArray>(2)
        val failure = AtomicReference<Throwable?>(null)
        // Only the producer writes done, so the consumer cannot miss a last, concurrently queued clause.
        val done = AtomicBoolean(false)
        val output = thread(name = "Shona Voice playback", isDaemon = true) {
            try {
                consume {
                    var item: ShortArray?
                    do {
                        active()
                        item = chunks.poll(40, TimeUnit.MILLISECONDS)
                    } while (item == null && (!done.get() || chunks.isNotEmpty()))
                    item
                }
            } catch (err: Throwable) {
                failure.compareAndSet(null, err)
                stopInference()
            }
        }
        try {
            produce { audio ->
                do {
                    active()
                    failure.get()?.let { throw it }
                } while (!chunks.offer(audio, 40, TimeUnit.MILLISECONDS))
            }
            done.set(true)
            while (output.isAlive) {
                active()
                output.join(40)
            }
            failure.get()?.let { throw it }
        } catch (err: Throwable) {
            throw failure.get() ?: err
        } finally {
            done.set(true)
            output.interrupt()
        }
    }

    internal fun bufferSizeBytes(sampleRate: Int, minimumBytes: Int): Int {
        check(sampleRate > 0 && minimumBytes > 0) { "Аудиоустройство не поддерживает частоту голоса." }
        // Mono PCM16 uses two bytes per frame. Android requires a whole number of frames.
        val quarterSecondFrames = (sampleRate.toLong() + 3) / 4
        val minimumFrames = (minimumBytes.toLong() + 1) / 2
        val bytes = maxOf(quarterSecondFrames, minimumFrames) * 2
        check(bytes <= Int.MAX_VALUE) { "Слишком большой аудиобуфер." }
        return bytes.toInt()
    }

    private fun render(sampleRate: Int, active: () -> Unit, next: () -> ShortArray?) {
        var samples = next() ?: return
        val mask = AudioFormat.CHANNEL_OUT_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val format = AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(encoding).setChannelMask(mask).build()
        val minimum = AudioTrack.getMinBufferSize(sampleRate, mask, encoding)
        val track = AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(bufferSizeBytes(sampleRate, minimum)).build()
        try {
            var total = 0L
            var playing = false
            do {
                var at = 0
                var lastWrite = SystemClock.elapsedRealtime()
                while (at < samples.size) {
                    active()
                    val n = track.write(samples, at, samples.size - at, AudioTrack.WRITE_NON_BLOCKING)
                    check(n >= 0) { "Не удалось воспроизвести голос." }
                    if (n == 0) {
                        check(SystemClock.elapsedRealtime() - lastWrite < 5000) { "Аудиоустройство не отвечает." }
                        Thread.sleep(8)
                    } else {
                        lastWrite = SystemClock.elapsedRealtime()
                        at += n
                        total += n
                        if (!playing) {
                            track.play()
                            playing = true
                        }
                    }
                }
                val following = next()
                if (following != null) {
                    samples = following
                } else if (total < track.bufferSizeInFrames) {
                    // A very short last clip must also reach the device's startup threshold.
                    samples = ShortArray(track.bufferSizeInFrames - total.toInt())
                } else break
            } while (true)
            var previous = track.playbackHeadPosition.toLong() and 0xffffffffL
            var lastAdvance = SystemClock.elapsedRealtime()
            while (previous < total) {
                active()
                Thread.sleep(15)
                val head = track.playbackHeadPosition.toLong() and 0xffffffffL
                if (head != previous) lastAdvance = SystemClock.elapsedRealtime()
                check(SystemClock.elapsedRealtime() - lastAdvance < 5000) { "Воспроизведение прервано." }
                previous = head
            }
        } finally {
            try {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause()
            } finally {
                track.release()
            }
        }
    }
}
