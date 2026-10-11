package com.roboek.vidscript.engine

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class ModelSpec(
    val label: String,       // Veloce / Accurato / Superiore
    val tag: String,         // nome tecnico
    val file: String,
    val sizeTxt: String,
    val ramTxt: String,
    val cpuLevel: Int,
    val cpuTxt: String,
    val accLevel: Int,
    val note: String,
    val badge: String,
    val desc: String,
)

object Models {
    private const val BASE = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"

    val all = listOf(
        ModelSpec(
            "Veloce", "small", "ggml-small-q5_1.bin", "~0,2 GB", "~0,6 GB", 2, "Leggero", 3,
            "Leggero e veloce, adatto a tutti i telefoni. Ideale per il tempo reale.", "Consigliato",
            "Il modello più leggero: usa poca memoria e poca batteria, quindi funziona bene anche sui telefoni meno recenti. " +
                "Riconosce bene il parlato chiaro; con audio disturbato o termini molto specialistici può sbagliare più parole. " +
                "È la scelta consigliata per la trascrizione in tempo reale."
        ),
        ModelSpec(
            "Accurato", "medium", "ggml-medium-q5_0.bin", "~0,5 GB", "~1,5 GB", 4, "Elevato", 4,
            "Più preciso con termini tecnici e audio disturbato.", "",
            "Il modello intermedio: sbaglia meno con audio di qualità media, accenti e termini specialistici. " +
                "Richiede più memoria e scalda di più il dispositivo, quindi la trascrizione di un file richiede più tempo. " +
                "In tempo reale funziona bene solo su un dispositivo potente."
        ),
        ModelSpec(
            "Superiore", "large-v3-turbo", "ggml-large-v3-turbo-q5_0.bin", "~0,6 GB", "~2 GB", 5, "Molto elevato", 5,
            "Qualità massima. Consigliato con almeno 6 GB di RAM.", "",
            "Il modello con la qualità più alta: offre la resa migliore con audio difficile e linguaggio tecnico. " +
                "Carica molto il processore e richiede almeno 6 GB di RAM, quindi conviene per trascrivere file già registrati " +
                "su un dispositivo potente, non per il tempo reale."
        ),
    )

    fun spec(label: String): ModelSpec = all.firstOrNull { it.label == label } ?: all[0]
    fun url(s: ModelSpec) = BASE + s.file
    fun dir(ctx: Context) = File(ctx.filesDir, "models").apply { mkdirs() }
    fun file(ctx: Context, s: ModelSpec) = File(dir(ctx), s.file)
    fun isInstalled(ctx: Context, s: ModelSpec) = file(ctx, s).let { it.exists() && it.length() > 1_000_000 }
    fun installed(ctx: Context) = all.filter { isInstalled(ctx, it) }
    fun delete(ctx: Context, s: ModelSpec) {
        file(ctx, s).delete(); File(dir(ctx), s.file + ".part").delete()
    }
}

data class DlState(val running: Boolean = false, val progress: Float = 0f, val error: String = "")

/** Scarica i modelli (con ripresa) in background. */
object ModelDownloader {
    val state = MutableStateFlow<Map<String, DlState>>(emptyMap())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Job>()

    private fun put(label: String, s: DlState) { state.value = state.value + (label to s) }

    fun isRunning(label: String) = state.value[label]?.running == true
    fun anyRunning() = state.value.values.any { it.running }

    fun start(ctx: Context, spec: ModelSpec) {
        if (isRunning(spec.label) || Models.isInstalled(ctx, spec)) return
        val app = ctx.applicationContext
        put(spec.label, DlState(true, 0f))
        jobs[spec.label] = scope.launch {
            try {
                download(app, spec)
                put(spec.label, DlState(false, 1f))
            } catch (e: CancellationException) {
                put(spec.label, DlState(false, 0f))
            } catch (e: Exception) {
                put(spec.label, DlState(false, 0f, "Download non riuscito: controlla la connessione e riprova."))
            }
        }
    }

    fun cancel(label: String) { jobs[label]?.cancel() }

    private suspend fun download(ctx: Context, spec: ModelSpec) {
        val part = File(Models.dir(ctx), spec.file + ".part")
        val target = Models.file(ctx, spec)
        val have = if (part.exists()) part.length() else 0L
        val conn = (URL(Models.url(spec)).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000; readTimeout = 30000; instanceFollowRedirects = true
            if (have > 0) setRequestProperty("Range", "bytes=$have-")
        }
        try {
            val code = conn.responseCode
            if (code == 416) { part.renameTo(target); return }
            if (code != 200 && code != 206) throw IllegalStateException("HTTP $code")
            val resume = code == 206
            val total = (if (resume) have else 0L) + conn.contentLengthLong
            var done = if (resume) have else 0L
            FileOutputStream(part, resume).use { out ->
                conn.inputStream.use { inp ->
                    val buf = ByteArray(64 * 1024)
                    var lastUpd = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - lastUpd > 300 && total > 0) {
                            lastUpd = now
                            put(spec.label, DlState(true, (done.toFloat() / total).coerceIn(0f, 1f)))
                        }
                    }
                }
            }
            if (conn.contentLengthLong > 0 && done < total) throw IllegalStateException("incompleto")
            if (!part.renameTo(target)) throw IllegalStateException("rename")
        } finally {
            conn.disconnect()
        }
    }
}

/** Mantiene in memoria il modello in uso. */
object EngineCache {
    private var label: String? = null
    private var engine: WhisperEngine? = null

    @Synchronized
    fun get(ctx: Context, spec: ModelSpec): WhisperEngine? {
        if (label == spec.label && engine != null) return engine
        engine?.release(); engine = null; label = null
        if (!Models.isInstalled(ctx, spec)) return null
        val e = WhisperEngine()
        if (!e.load(Models.file(ctx, spec).absolutePath)) return null
        engine = e; label = spec.label
        return e
    }

    @Synchronized
    fun release() { engine?.release(); engine = null; label = null }

    fun threads(live: Boolean): Int {
        val c = Runtime.getRuntime().availableProcessors()
        return if (live) (c / 2).coerceIn(2, 4) else (c - 2).coerceIn(2, 6)
    }
}
