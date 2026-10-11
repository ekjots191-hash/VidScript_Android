package com.roboek.vidscript

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

const val VERSION_LABEL = "0.9 Beta"

val LANGUAGES = listOf(
    "Italiano" to "it", "English" to "en", "Español" to "es",
    "Français" to "fr", "Deutsch" to "de", "Automatica" to "auto",
)

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("vidscript", Context.MODE_PRIVATE)
    var model: String
        get() = sp.getString("model", "Veloce")!!
        set(v) { sp.edit().putString("model", v).apply() }
    var lang: String
        get() = sp.getString("lang", "it")!!
        set(v) { sp.edit().putString("lang", v).apply() }
    var video: Boolean
        get() = sp.getBoolean("video", false)
        set(v) { sp.edit().putBoolean("video", v).apply() }
    var srcApp: Boolean
        get() = sp.getBoolean("srcApp", false)
        set(v) { sp.edit().putBoolean("srcApp", v).apply() }
    var srcMic: Boolean
        get() = sp.getBoolean("srcMic", true)
        set(v) { sp.edit().putBoolean("srcMic", v).apply() }
    var live: Boolean
        get() = sp.getBoolean("live", false)
        set(v) { sp.edit().putBoolean("live", v).apply() }
    var recFolder: String?
        get() = sp.getString("recFolder", null)
        set(v) { sp.edit().putString("recFolder", v).apply() }
    var outFolder: String?
        get() = sp.getString("outFolder", null)
        set(v) { sp.edit().putString("outFolder", v).apply() }
    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) { sp.edit().putBoolean("onboarded", v).apply() }
}

enum class FileStatus { WAITING, WORKING, DONE, FAILED, SKIPPED }
data class FileItem(val uri: Uri, val name: String, val status: FileStatus = FileStatus.WAITING, val progress: Float = 0f, val note: String = "")

/** Stato condiviso tra servizio e interfaccia. */
object Bus {
    // registrazione
    val recording = MutableStateFlow(false)
    val finishing = MutableStateFlow(false)
    val elapsedSec = MutableStateFlow(0)
    val liveText = MutableStateFlow("")
    val recMessage = MutableStateFlow("")
    val recSaved = MutableStateFlow("")      // percorso/nome dell'ultimo file salvato
    // trascrizione file
    val transcribing = MutableStateFlow(false)
    val items = MutableStateFlow<List<FileItem>>(emptyList())
    val fileText = MutableStateFlow("")
    val trMessage = MutableStateFlow("")
    @Volatile var pendingUris: List<Uri> = emptyList()
    @Volatile var cancelTranscribe = false
    val busy get() = recording.value || finishing.value || transcribing.value
}

object Storage {
    fun displayName(treeUri: String?): String {
        if (treeUri == null) return "Cartella dell'app (Android/data/com.roboek.vidscript/files/VidScript)"
        val last = Uri.parse(treeUri).lastPathSegment ?: return "Cartella scelta"
        return Uri.decode(last).substringAfter(':', last).ifBlank { "Memoria interna" }
    }

    private fun fallbackDir(ctx: Context) = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "VidScript").apply { mkdirs() }

    /** Salva un file (da `src` o da `text`) nella cartella scelta. Ritorna il nome effettivo. */
    fun save(ctx: Context, treeUri: String?, name: String, mime: String, src: File? = null, text: String? = null): String {
        val tree = treeUri?.let { DocumentFile.fromTreeUri(ctx, Uri.parse(it)) }?.takeIf { it.canWrite() }
        if (tree == null) {
            val f = uniqueFile(fallbackDir(ctx), name)
            if (src != null) src.copyTo(f, overwrite = true) else f.writeText(text ?: "")
            return f.name
        }
        val doc = tree.createFile(mime, name) ?: error("Impossibile creare il file nella cartella scelta")
        ctx.contentResolver.openOutputStream(doc.uri)!!.use { out ->
            if (src != null) src.inputStream().use { it.copyTo(out) } else out.write((text ?: "").toByteArray())
        }
        return doc.name ?: name
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        var i = 1
        val base = name.substringBeforeLast('.'); val ext = name.substringAfterLast('.', "")
        while (f.exists()) { f = File(dir, "$base ($i).$ext"); i++ }
        return f
    }
}
