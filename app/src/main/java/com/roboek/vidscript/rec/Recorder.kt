package com.roboek.vidscript.rec

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.roboek.vidscript.engine.Resampler
import java.io.File
import java.nio.ByteBuffer

/**
 * Registra microfono e/o audio delle app (mixati) in AAC, e opzionalmente lo schermo in H.264,
 * dentro un unico file MP4 (.m4a se solo audio). Fornisce anche PCM a 16 kHz per la trascrizione.
 */
class Recorder(
    private val ctx: Context,
    private val video: Boolean,
    private val useMic: Boolean,
    private val useApp: Boolean,
    private val projection: MediaProjection?,
    val outFile: File,
    private val onPcm16k: (ShortArray) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val sr = 44100
    @Volatile private var stopping = false
    private val hasAudio = useMic || (useApp && Build.VERSION.SDK_INT >= 29 && projection != null)

    private var muxer: MediaMuxer? = null
    private val muxLock = Any()
    private var muxStarted = false
    private var audioTrack = -1
    private var videoTrack = -1
    private val pending = ArrayList<Triple<Int, ByteArray, MediaCodec.BufferInfo>>()
    private val firstPts = longArrayOf(-1, -1)
    private var wroteAnything = false

    private var vEnc: MediaCodec? = null
    private var vDisplay: VirtualDisplay? = null
    private var audioThread: Thread? = null
    private var videoThread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start() {
        muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        if (video) setupVideo()
        if (hasAudio) {
            audioThread = Thread({ audioLoop() }, "vs-audio").also { it.start() }
        }
        if (video) videoThread = Thread({ videoLoop() }, "vs-video").also { it.start() }
        if (!hasAudio && !video) onError("Nessuna sorgente selezionata")
    }

    /** Ferma e chiude il file. Ritorna true se il file contiene dati. */
    fun stop(): Boolean {
        stopping = true
        audioThread?.join(8000)
        videoThread?.join(8000)
        try { vDisplay?.release() } catch (_: Exception) {}
        try { vEnc?.stop() } catch (_: Exception) {}
        try { vEnc?.release() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        synchronized(muxLock) {
            try { if (muxStarted) muxer?.stop() } catch (_: Exception) { wroteAnything = false }
            try { muxer?.release() } catch (_: Exception) {}
        }
        return wroteAnything && outFile.exists() && outFile.length() > 0
    }

    // ------------------------------------------------------------------ audio
    @SuppressLint("MissingPermission")
    private fun audioLoop() {
        var mic: AudioRecord? = null
        var app: AudioRecord? = null
        var enc: MediaCodec? = null
        try {
            val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val bufSize = maxOf(minBuf, sr / 2 * 2) * 2
            if (useMic) {
                mic = AudioRecord(MediaRecorder.AudioSource.MIC, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
                if (mic.state != AudioRecord.STATE_INITIALIZED) { onError("Microfono non disponibile"); mic.release(); mic = null }
            }
            if (useApp && Build.VERSION.SDK_INT >= 29 && projection != null) {
                try {
                    val cfg = AudioPlaybackCaptureConfiguration.Builder(projection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build()
                    app = AudioRecord.Builder()
                        .setAudioPlaybackCaptureConfig(cfg)
                        .setAudioFormat(AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sr)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                        .setBufferSizeInBytes(bufSize).build()
                    if (app.state != AudioRecord.STATE_INITIALIZED) { app.release(); app = null }
                } catch (e: Exception) { onError("Audio delle app non disponibile su questo dispositivo"); app = null }
            }
            if (mic == null && app == null) { onError("Nessuna sorgente audio disponibile"); return }

            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sr, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 96000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            mic?.startRecording(); app?.startRecording()

            val rs = Resampler(sr, 1)
            val frame = 2048
            val a = ShortArray(frame); val b = ShortArray(frame)
            var total = 0L
            while (!stopping) {
                var n = frame
                if (mic != null) n = minOf(n, mic.read(a, 0, frame, AudioRecord.READ_BLOCKING).coerceAtLeast(0))
                if (app != null) n = minOf(n, app.read(b, 0, frame, AudioRecord.READ_BLOCKING).coerceAtLeast(0))
                if (n <= 0) continue
                val mix = ShortArray(n)
                for (i in 0 until n) {
                    val v = (if (mic != null) a[i].toInt() else 0) + (if (app != null) b[i].toInt() else 0)
                    mix[i] = v.coerceIn(-32768, 32767).toShort()
                }
                feed(enc, mix, total)
                total += n
                drain(enc, false, 0)
                onPcm16k(rs.process(mix))
            }
            // fine: EOS
            val idx = enc.dequeueInputBuffer(100_000)
            if (idx >= 0) enc.queueInputBuffer(idx, 0, 0, total * 1_000_000L / sr, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            var tries = 0
            while (tries++ < 300 && !drain(enc, false, 10_000)) { }
        } catch (e: Exception) {
            onError("Errore di registrazione audio: ${e.message}")
        } finally {
            try { mic?.stop() } catch (_: Exception) {}
            try { app?.stop() } catch (_: Exception) {}
            mic?.release(); app?.release()
            try { enc?.stop() } catch (_: Exception) {}
            try { enc?.release() } catch (_: Exception) {}
        }
    }

    private fun feed(enc: MediaCodec, pcm: ShortArray, totalSamples: Long) {
        var off = 0
        var t = totalSamples
        while (off < pcm.size) {
            val idx = enc.dequeueInputBuffer(20_000)
            if (idx < 0) { drain(enc, false, 0); continue }
            val buf = enc.getInputBuffer(idx)!!
            buf.clear()
            val n = minOf(pcm.size - off, buf.capacity() / 2)
            for (i in 0 until n) buf.putShort(pcm[off + i])
            enc.queueInputBuffer(idx, 0, n * 2, t * 1_000_000L / sr, 0)
            off += n; t += n
        }
    }

    // ------------------------------------------------------------------ video
    private fun setupVideo() {
        val dm = ctx.resources.displayMetrics
        val big = maxOf(dm.widthPixels, dm.heightPixels).toFloat()
        val s = minOf(1f, 1280f / big)
        val w = ((dm.widthPixels * s).toInt()) and 1.inv()
        val h = ((dm.heightPixels * s).toInt()) and 1.inv()
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 3_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 500_000L) // ripete il fotogramma se lo schermo è fermo
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = enc.createInputSurface()
        enc.start()
        vEnc = enc
        projection?.registerCallback(object : MediaProjection.Callback() {}, Handler(Looper.getMainLooper()))
        vDisplay = projection?.createVirtualDisplay(
            "VidScript", w, h, dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null)
    }

    private fun videoLoop() {
        val enc = vEnc ?: return
        try {
            while (!stopping) drain(enc, true, 10_000)
            enc.signalEndOfInputStream()
            var tries = 0
            while (tries++ < 300 && !drain(enc, true, 10_000)) { }
        } catch (e: Exception) {
            onError("Errore di registrazione video: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ muxer
    /** Svuota l'encoder. Ritorna true quando incontra la fine del flusso. */
    private fun drain(enc: MediaCodec, isVideo: Boolean, timeoutUs: Long): Boolean {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val i = enc.dequeueOutputBuffer(info, timeoutUs)
            when {
                i == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> addTrack(enc.outputFormat, isVideo)
                i >= 0 -> {
                    val b = enc.getOutputBuffer(i)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && b != null) {
                        val k = if (isVideo) 1 else 0
                        if (firstPts[k] < 0) firstPts[k] = info.presentationTimeUs
                        val out = MediaCodec.BufferInfo().apply {
                            set(0, info.size, (info.presentationTimeUs - firstPts[k]).coerceAtLeast(0), info.flags)
                        }
                        b.position(info.offset); b.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size); b.get(bytes)
                        write(if (isVideo) videoTrack else audioTrack, isVideo, bytes, out)
                    }
                    enc.releaseOutputBuffer(i, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
                }
            }
        }
    }

    private fun addTrack(f: MediaFormat, isVideo: Boolean) {
        synchronized(muxLock) {
            val m = muxer ?: return
            val t = m.addTrack(f)
            if (isVideo) videoTrack = t else audioTrack = t
            val need = (if (hasAudio) 1 else 0) + (if (video) 1 else 0)
            val have = (if (audioTrack >= 0) 1 else 0) + (if (videoTrack >= 0) 1 else 0)
            if (have == need && !muxStarted) {
                m.start(); muxStarted = true
                for ((kind, bytes, info) in pending) m.writeSampleData(if (kind == 1) videoTrack else audioTrack, ByteBuffer.wrap(bytes), info)
                if (pending.isNotEmpty()) wroteAnything = true
                pending.clear()
            }
        }
    }

    private fun write(track: Int, isVideo: Boolean, bytes: ByteArray, info: MediaCodec.BufferInfo) {
        synchronized(muxLock) {
            val m = muxer ?: return
            if (!muxStarted) {
                if (pending.size < 3000) pending.add(Triple(if (isVideo) 1 else 0, bytes, info))
                return
            }
            m.writeSampleData(track, ByteBuffer.wrap(bytes), info)
            wroteAnything = true
        }
    }
}
