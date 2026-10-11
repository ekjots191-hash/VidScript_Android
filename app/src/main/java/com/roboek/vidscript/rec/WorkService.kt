package com.roboek.vidscript.rec

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.roboek.vidscript.*
import com.roboek.vidscript.R
import com.roboek.vidscript.engine.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Servizio in primo piano: tiene viva la registrazione e la trascrizione anche con lo schermo spento. */
class WorkService : Service() {
    companion object {
        const val ACT_REC = "rec"
        const val ACT_STOP = "stop"
        const val ACT_TRANSCRIBE = "transcribe"
        private const val CH = "vidscript"
        private const val NOTIF = 7

        fun startRecording(ctx: Context, video: Boolean, mic: Boolean, app: Boolean, live: Boolean, name: String,
                           resultCode: Int, data: Intent?) {
            val i = Intent(ctx, WorkService::class.java).setAction(ACT_REC)
                .putExtra("video", video).putExtra("mic", mic).putExtra("app", app).putExtra("live", live)
                .putExtra("name", name).putExtra("rc", resultCode).putExtra("data", data)
            ctx.startForegroundService(i)
        }

        fun startTranscribe(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, WorkService::class.java).setAction(ACT_TRANSCRIBE))
        }

        fun stopRecording(ctx: Context) {
            ctx.startService(Intent(ctx, WorkService::class.java).setAction(ACT_STOP))
        }
    }

    private var recorder: Recorder? = null
    private var wake: PowerManager.WakeLock? = null
    private var liveExec = Executors.newSingleThreadExecutor()
    @Volatile private var liveBuf = StringBuilder()
    private var startedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACT_REC -> if (!Bus.busy) startRec(intent) else stopSelfSafe()
            ACT_STOP -> Thread { stopRec() }.start()
            ACT_TRANSCRIBE -> if (!Bus.busy) startTranscription() else stopSelfSafe()
        }
        return START_NOT_STICKY
    }

    private fun stopSelfSafe() { stopSelf() }

    private fun foreground(text: String, types: Int) {
        val ch = NotificationChannel(CH, "VidScript", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, WorkService::class.java).setAction(ACT_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_notif).setContentTitle("VidScript").setContentText(text)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
        if (types and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC == 0)
            b.addAction(0, "Ferma", stop)
        else b.addAction(0, "Annulla", PendingIntent.getService(this, 2,
            Intent(this, WorkService::class.java).setAction(ACT_STOP), PendingIntent.FLAG_IMMUTABLE))
        ServiceCompat.startForeground(this, NOTIF, b.build(), types)
        if (wake == null) {
            wake = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vidscript:work").apply { acquire(6 * 60 * 60 * 1000L) }
        }
    }

    private fun release() {
        try { wake?.release() } catch (_: Exception) {}
        wake = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ------------------------------------------------------------ registrazione
    private fun startRec(i: Intent) {
        val video = i.getBooleanExtra("video", false)
        val mic = i.getBooleanExtra("mic", false)
        val app = i.getBooleanExtra("app", false)
        val live = i.getBooleanExtra("live", false)
        liveExec = Executors.newSingleThreadExecutor()
        val needProj = video || app
        var types = 0
        if (mic) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (needProj) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (types == 0) types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        try {
            foreground("Registrazione in corso", types)
        } catch (e: Exception) {
            Bus.recMessage.value = "Android non permette di avviare la registrazione: ${e.message}"
            release(); return
        }

        var proj: MediaProjection? = null
        if (needProj) {
            val rc = i.getIntExtra("rc", 0)
            @Suppress("DEPRECATION") val data = i.getParcelableExtra<Intent>("data")
            if (data == null) { Bus.recMessage.value = "Permesso di acquisizione schermo mancante"; release(); return }
            proj = (getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).getMediaProjection(rc, data)
        }

        val spec = Models.spec(Prefs(this).model)
        val lang = Prefs(this).lang
        var engine: WhisperEngine? = null
        val chunker = Chunker(live = true)
        Bus.liveText.value = ""
        liveBuf = StringBuilder()

        if (live) {
            Bus.recMessage.value = "Carico il modello…"
            engine = EngineCache.get(this, spec)
            if (engine == null) Bus.recMessage.value = "Modello non disponibile: registro senza trascrizione in tempo reale."
        }
        val liveEngine = engine

        fun submit(chunk: ShortArray) {
            val e = liveEngine ?: return
            if (Chunker.isSilent(chunk)) return
            liveExec.submit {
                val t = e.transcribe(Chunker.toFloat(chunk), lang, EngineCache.threads(true))
                if (t.isNotBlank()) {
                    liveBuf.append(t).append('\n')
                    Bus.liveText.value = liveBuf.toString()
                }
            }
        }

        val ext = if (video) "mp4" else "m4a"
        val tmp = File(cacheDir, "rec_${System.currentTimeMillis()}.$ext")
        val r = Recorder(this, video, mic, app, proj, tmp,
            onPcm16k = { pcm ->
                if (liveEngine != null) {
                    chunker.add(pcm)
                    while (true) submit(chunker.poll() ?: break)
                }
            },
            onError = { msg -> Bus.recMessage.value = msg })
        recorder = r
        pendingName = i.getStringExtra("name") ?: "Registrazione"
        pendingChunker = chunker
        pendingSubmit = ::submit
        pendingLive = liveEngine != null
        try {
            r.start()
        } catch (e: Exception) {
            Bus.recMessage.value = "Impossibile avviare la registrazione: ${e.message}"
            release(); return
        }
        startedAt = System.currentTimeMillis()
        Bus.recSaved.value = ""
        Bus.recording.value = true
        if (Bus.recMessage.value.startsWith("Carico")) Bus.recMessage.value = ""
        Thread {
            while (Bus.recording.value) {
                Bus.elapsedSec.value = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
                Thread.sleep(500)
            }
        }.start()
    }

    private var pendingName = ""
    private var pendingChunker: Chunker? = null
    private var pendingSubmit: ((ShortArray) -> Unit)? = null
    private var pendingLive = false

    private fun stopRec() {
        val r = recorder
        if (r == null) {
            if (Bus.transcribing.value) { Bus.cancelTranscribe = true; EngineCache.get(this, Models.spec(Prefs(this).model))?.cancel() }
            return
        }
        recorder = null
        Bus.recording.value = false
        Bus.finishing.value = true
        Bus.recMessage.value = "Salvataggio in corso…"
        val ok = r.stop()
        pendingChunker?.flush()?.let { pendingSubmit?.invoke(it) }
        if (pendingLive) {
            Bus.recMessage.value = "Completo la trascrizione…"
            liveExec.shutdown()
            liveExec.awaitTermination(30, TimeUnit.MINUTES)
        }
        val prefs = Prefs(this)
        try {
            if (ok) {
                val ext = r.outFile.extension
                val mime = if (ext == "mp4") "video/mp4" else "audio/mp4"
                val saved = Storage.save(this, prefs.recFolder, "$pendingName.$ext", mime, src = r.outFile)
                var msg = "Salvato: $saved"
                if (pendingLive && liveBuf.isNotBlank()) {
                    Storage.save(this, prefs.recFolder, "$pendingName.txt", "text/plain", text = liveBuf.toString())
                    msg += " + trascrizione (.txt)"
                }
                Bus.recSaved.value = msg
                Bus.recMessage.value = ""
            } else {
                if (Bus.recMessage.value.isBlank() || Bus.recMessage.value.startsWith("Salva") || Bus.recMessage.value.startsWith("Completo"))
                    Bus.recMessage.value = "Registrazione vuota: nulla da salvare."
            }
        } catch (e: Exception) {
            Bus.recMessage.value = "Salvataggio non riuscito: ${e.message}"
        } finally {
            r.outFile.delete()
            Bus.finishing.value = false
            release()
        }
    }

    // ------------------------------------------------------------ trascrizione di file
    private fun startTranscription() {
        try { foreground("Trascrizione in corso", ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) }
        catch (e: Exception) { Bus.trMessage.value = "Impossibile avviare: ${e.message}"; release(); return }
        val uris = Bus.pendingUris
        val prefs = Prefs(this)
        Bus.cancelTranscribe = false
        Bus.transcribing.value = true
        Bus.fileText.value = ""
        Bus.items.value = uris.map { FileItem(it, nameOf(it)) }
        Bus.trMessage.value = "Carico il modello…"
        Thread {
            val engine = EngineCache.get(this, Models.spec(prefs.model))
            if (engine == null) {
                Bus.trMessage.value = "Nessun modello disponibile. Scaricane uno da «Gestisci modelli»."
                Bus.transcribing.value = false; release(); return@Thread
            }
            Bus.trMessage.value = ""
            val total = StringBuilder()
            for ((idx, uri) in uris.withIndex()) {
                if (Bus.cancelTranscribe) break
                val name = nameOf(uri)
                update(idx) { it.copy(status = FileStatus.WORKING) }
                val sb = StringBuilder()
                try {
                    val chunker = Chunker()
                    fun run(chunk: ShortArray) {
                        if (Chunker.isSilent(chunk)) return
                        val t = engine.transcribe(Chunker.toFloat(chunk), prefs.lang, EngineCache.threads(false))
                        if (t.isNotBlank()) { sb.append(t).append('\n'); Bus.fileText.value = total.toString() + header(name) + sb }
                    }
                    val hasAudio = AudioDecoder.decode(this, uri, { Bus.cancelTranscribe },
                        { p -> update(idx) { it.copy(progress = p) } },
                        { pcm ->
                            chunker.add(pcm)
                            while (!Bus.cancelTranscribe) run(chunker.poll() ?: break)
                        })
                    if (!hasAudio) { update(idx) { it.copy(status = FileStatus.SKIPPED, note = "Nessuna traccia audio") }; continue }
                    if (Bus.cancelTranscribe) { update(idx) { it.copy(status = FileStatus.FAILED, note = "Annullato") }; break }
                    chunker.flush()?.let { run(it) }
                    val out = name.substringBeforeLast('.') + ".txt"
                    val saved = Storage.save(this, prefs.outFolder, out, "text/plain", text = sb.toString())
                    total.append(header(name)).append(sb).append('\n')
                    Bus.fileText.value = total.toString()
                    update(idx) { it.copy(status = FileStatus.DONE, progress = 1f, note = saved) }
                } catch (e: Exception) {
                    update(idx) { it.copy(status = FileStatus.FAILED, note = e.message ?: "Errore") }
                }
            }
            Bus.transcribing.value = false
            release()
        }.start()
    }

    private fun header(n: String) = if (Bus.items.value.size > 1) "— $n —\n" else ""

    private fun update(idx: Int, f: (FileItem) -> FileItem) {
        Bus.items.value = Bus.items.value.toMutableList().also { if (idx in it.indices) it[idx] = f(it[idx]) }
    }

    private fun nameOf(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) return it.getString(0)
            }
        } catch (_: Exception) {}
        return uri.lastPathSegment ?: "file"
    }

    override fun onDestroy() {
        try { wake?.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}
