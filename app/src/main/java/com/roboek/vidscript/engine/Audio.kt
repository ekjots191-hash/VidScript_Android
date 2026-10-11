package com.roboek.vidscript.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/** Converte PCM 16 bit (qualsiasi frequenza/canali) in mono a 16 kHz. */
class Resampler(var inRate: Int, var channels: Int) {
    private var pos = 0.0
    private var prev = 0f

    fun process(src: ShortArray): ShortArray {
        val ch = channels.coerceAtLeast(1)
        val frames = src.size / ch
        if (frames == 0) return ShortArray(0)
        val mono = FloatArray(frames)
        for (i in 0 until frames) {
            var s = 0
            for (c in 0 until ch) s += src[i * ch + c]
            mono[i] = s.toFloat() / ch
        }
        val step = inRate / 16000.0
        var p = pos
        val out = ShortArray(((frames - p) / step).toInt() + 2)
        var n = 0
        while (p < frames - 1) {
            val i = floor(p).toInt()
            val fr = (p - i).toFloat()
            val a = if (i < 0) prev else mono[i]
            val b = mono[i + 1]
            out[n++] = (a + (b - a) * fr).toInt().coerceIn(-32768, 32767).toShort()
            p += step
        }
        pos = p - frames
        prev = mono[frames - 1]
        return out.copyOf(n)
    }
}

/** Accumula PCM 16 kHz e lo divide in blocchi adatti a Whisper (tagliando nei punti più silenziosi). */
class Chunker(private val maxSec: Float = 28f, private val live: Boolean = false) {
    private var buf = ShortArray(16000 * 40)
    private var n = 0

    fun add(s: ShortArray) {
        if (n + s.size > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, n + s.size))
        System.arraycopy(s, 0, buf, n, s.size)
        n += s.size
    }

    fun poll(): ShortArray? {
        val max = (maxSec * 16000).toInt()
        if (n >= max) return take(quietestCut(max))
        if (live && n >= 16000 * 4 && rms(buf, n - 11200, n) < 250.0) return take(n)
        return null
    }

    fun flush(): ShortArray? = if (n > 0) take(n) else null

    private fun quietestCut(max: Int): Int {
        val win = 1600 // 100 ms
        var best = max
        var bestRms = Double.MAX_VALUE
        var start = max - 16000 * 4
        while (start + win <= max) {
            val r = rms(buf, start, start + win)
            if (r < bestRms) { bestRms = r; best = start + win / 2 }
            start += win
        }
        return best
    }

    private fun take(k: Int): ShortArray {
        val out = buf.copyOf(k)
        System.arraycopy(buf, k, buf, 0, n - k)
        n -= k
        return out
    }

    companion object {
        fun rms(a: ShortArray, from: Int, to: Int): Double {
            val f = from.coerceAtLeast(0); val t = to.coerceAtMost(a.size)
            if (t <= f) return 0.0
            var s = 0.0
            for (i in f until t) { val v = a[i].toDouble(); s += v * v }
            return sqrt(s / (t - f))
        }
        fun isSilent(a: ShortArray) = rms(a, 0, a.size) < 120.0
        fun toFloat(a: ShortArray) = FloatArray(a.size) { a[it] / 32768f }
    }
}

object AudioDecoder {
    /** Decodifica la traccia audio di un file (audio o video) a mono 16 kHz, a blocchi. Ritorna false se non c'è audio. */
    fun decode(
        ctx: Context, uri: Uri,
        cancelled: () -> Boolean,
        onProgress: (Float) -> Unit,
        onPcm: (ShortArray) -> Unit,
    ): Boolean {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, uri, null)
            var track = -1
            for (i in 0 until ex.trackCount) {
                if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; break }
            }
            if (track < 0) return false
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            val rs = Resampler(
                if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100,
                if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2,
            )
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(fmt, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            while (!outDone) {
                if (cancelled()) return true
                if (!inDone) {
                    val idx = codec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val b = codec.getInputBuffer(idx)!!
                        val size = ex.readSampleData(b, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            codec.queueInputBuffer(idx, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    oi >= 0 -> {
                        if (info.size > 0) {
                            val ob = codec.getOutputBuffer(oi)!!
                            ob.position(info.offset); ob.limit(info.offset + info.size)
                            val sb = ob.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val shorts = ShortArray(sb.remaining())
                            sb.get(shorts)
                            val mono = rs.process(shorts)
                            if (mono.isNotEmpty()) onPcm(mono)
                            if (durUs > 0) onProgress((info.presentationTimeUs.toFloat() / durUs).coerceIn(0f, 1f))
                        }
                        codec.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    }
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rs.inRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) rs.channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }
            }
            return true
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            ex.release()
        }
    }
}
