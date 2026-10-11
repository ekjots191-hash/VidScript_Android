package com.roboek.vidscript.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.roboek.vidscript.*
import com.roboek.vidscript.R
import com.roboek.vidscript.engine.ModelDownloader
import com.roboek.vidscript.engine.Models
import com.roboek.vidscript.rec.WorkService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MainScreen(vm: AppModel) {
    val ctx = LocalContext.current
    val recording by Bus.recording.collectAsStateWithLifecycle()
    val finishing by Bus.finishing.collectAsStateWithLifecycle()
    val transcribing by Bus.transcribing.collectAsStateWithLifecycle()
    val dl by ModelDownloader.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val busy = recording || finishing || transcribing

    LaunchedEffect(dl) { vm.bumpModels(); vm.ensureValidModel() }

    BoxWithConstraints(Modifier.fillMaxSize().background(C.bg).safeDrawingPadding()) {
        val wide = maxWidth >= 720.dp
        Column(Modifier.fillMaxSize().padding(horizontal = if (wide) 24.dp else 16.dp, vertical = 12.dp)) {
            Header(vm)
            Spacer(Modifier.height(12.dp))
            Box(Modifier.widthIn(max = 420.dp)) {
                Segmented(listOf("Registra", "Trascrivi file"), tab, { if (!busy || it == tab) tab = it })
            }
            Spacer(Modifier.height(14.dp))
            if (wide) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Card(Modifier.width(420.dp).fillMaxHeight()) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            if (tab == 0) RecordControls(vm) else FileControls(vm)
                        }
                    }
                    OutputCard(tab, Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Card(Modifier.fillMaxWidth()) { if (tab == 0) RecordControls(vm) else FileControls(vm) }
                    OutputCard(tab, Modifier.fillMaxWidth().height(320.dp))
                }
            }
        }
    }
}

@Composable
private fun Header(vm: AppModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Logo(40.dp)
        Spacer(Modifier.width(12.dp))
        Text("VidScript", color = C.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(10.dp))
        Badge("$VERSION_LABEL")
        Spacer(Modifier.weight(1f))
        GhostButton("? Guida", { vm.showGuide = true })
    }
}

private fun mmss(s: Int) = "%02d:%02d".format(s / 60, s % 60)

// ============================================================ REGISTRA
@Composable
private fun RecordControls(vm: AppModel) {
    val ctx = LocalContext.current
    val recording by Bus.recording.collectAsStateWithLifecycle()
    val finishing by Bus.finishing.collectAsStateWithLifecycle()
    val transcribing by Bus.transcribing.collectAsStateWithLifecycle()
    val elapsed by Bus.elapsedSec.collectAsStateWithLifecycle()
    val msg by Bus.recMessage.collectAsStateWithLifecycle()
    val saved by Bus.recSaved.collectAsStateWithLifecycle()
    val locked = recording || finishing || transcribing
    val canApp = Build.VERSION.SDK_INT >= 29

    fun toast(t: String) = Toast.makeText(ctx, t, Toast.LENGTH_LONG).show()
    fun fileName(): String {
        val raw = vm.name.trim().ifBlank { "Registrazione_" + SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.ITALY).format(Date()) }
        return raw.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }
    fun begin(rc: Int, data: Intent?) {
        WorkService.startRecording(ctx, vm.video, vm.srcMic, vm.srcApp && canApp, vm.live, fileName(), rc, data)
    }

    val projLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK && r.data != null) begin(r.resultCode, r.data)
        else toast("Senza il permesso di acquisizione non posso registrare lo schermo o l'audio delle app.")
    }
    fun proceed() {
        if (vm.video || (vm.srcApp && canApp)) {
            val mpm = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projLauncher.launch(mpm.createScreenCaptureIntent())
        } else begin(0, null)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (vm.srcMic && res[Manifest.permission.RECORD_AUDIO] == false) toast("Serve il permesso del microfono per registrare.")
        else proceed()
    }
    fun check() {
        if (!vm.video && !vm.srcMic && !(vm.srcApp && canApp)) { toast("Scegli almeno una sorgente audio."); return }
        if (vm.live && !vm.hasModel()) { toast("Scarica un modello per la trascrizione in tempo reale."); vm.showModels = true; return }
        val need = mutableListOf<String>()
        if (vm.srcMic && ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isNotEmpty()) permLauncher.launch(need.toTypedArray()) else proceed()
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            vm.updateRecFolder(uri.toString())
        }
    }

    Label("Cosa registrare")
    Segmented(listOf("Solo audio", "Video + audio"), if (vm.video) 1 else 0, { vm.updateVideo(it == 1) }, enabled = !locked)
    Spacer(Modifier.height(14.dp))
    Label("Sorgenti audio")
    CheckRow("Audio delle app del dispositivo", vm.srcApp && canApp, { vm.updateSrcApp(it) }, enabled = !locked && canApp,
        sub = if (canApp) "Solo le app che consentono la cattura audio" else "Richiede Android 10 o successivo")
    CheckRow("Audio del microfono", vm.srcMic, { vm.updateSrcMic(it) }, enabled = !locked)
    CheckRow("Trascrizione in tempo reale", vm.live, { vm.updateLive(it) }, enabled = !locked)
    Spacer(Modifier.height(12.dp))
    ModelPicker(vm, locked)
    Spacer(Modifier.height(12.dp))
    Label("Lingua parlata")
    Dropdown(LANGUAGES.firstOrNull { it.second == vm.lang }?.first ?: "Italiano", LANGUAGES.map { it.first },
        { n -> vm.updateLang(LANGUAGES.first { it.first == n }.second) }, enabled = !locked)
    Spacer(Modifier.height(12.dp))
    Label("Cartella di salvataggio")
    FolderRow(Storage.displayName(vm.recFolder), { folderLauncher.launch(null) }, !locked)
    Spacer(Modifier.height(12.dp))
    Label("Nome del file (facoltativo)")
    OutlinedTextField(vm.name, { vm.name = it }, singleLine = true, enabled = !locked, modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Registrazione_data_ora", color = C.faint) },
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = C.text, unfocusedTextColor = C.text,
            focusedBorderColor = C.accent, unfocusedBorderColor = C.border, cursorColor = C.accent,
            focusedContainerColor = C.field, unfocusedContainerColor = C.field, disabledContainerColor = C.field,
            disabledTextColor = C.disabled, disabledBorderColor = C.border))
    Spacer(Modifier.height(18.dp))
    when {
        recording -> PrimaryButton("■  Ferma e salva  ·  ${mmss(elapsed)}", { WorkService.stopRecording(ctx) }, Modifier.fillMaxWidth(), danger = true)
        finishing -> PrimaryButton("Salvataggio…", {}, Modifier.fillMaxWidth(), enabled = false)
        else -> PrimaryButton("●  Avvia registrazione", { check() }, Modifier.fillMaxWidth(), enabled = !transcribing)
    }
    if (msg.isNotBlank()) Text(msg, color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
    if (saved.isNotBlank()) Text(saved, color = C.ok, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
}

@Composable
private fun ModelPicker(vm: AppModel, locked: Boolean) {
    val installed = vm.installed()
    Label("Qualità")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Dropdown(vm.model, installed.map { it.label }, { vm.updateModel(it) }, Modifier.weight(1f),
            enabled = !locked, empty = "Nessun modello")
        GhostButton("Gestisci modelli", { vm.showModels = true })
    }
}

@Composable
private fun FolderRow(text: String, onPick: () -> Unit, enabled: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, color = C.text, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 2)
        GhostButton("Scegli", onPick, enabled = enabled)
    }
}

// ============================================================ TRASCRIVI FILE
@Composable
private fun FileControls(vm: AppModel) {
    val ctx = LocalContext.current
    val recording by Bus.recording.collectAsStateWithLifecycle()
    val finishing by Bus.finishing.collectAsStateWithLifecycle()
    val transcribing by Bus.transcribing.collectAsStateWithLifecycle()
    val items by Bus.items.collectAsStateWithLifecycle()
    val msg by Bus.trMessage.collectAsStateWithLifecycle()
    val locked = recording || finishing || transcribing
    var chosen by remember { mutableStateOf<List<Uri>>(emptyList()) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { try { ctx.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {} }
            chosen = uris
            Bus.items.value = uris.map { FileItem(it, it.lastPathSegment?.substringAfterLast('/') ?: "file") }
            Bus.fileText.value = ""
        }
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            vm.updateOutFolder(uri.toString())
        }
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    Label("File audio o video")
    GhostButton("Scegli file…", { picker.launch(arrayOf("audio/*", "video/*")) }, Modifier.fillMaxWidth(), enabled = !locked)
    Spacer(Modifier.height(10.dp))
    if (items.isEmpty()) Text("Nessun file scelto.", color = C.faint, fontSize = 13.sp)
    items.forEach { it -> FileRow(it) }
    Spacer(Modifier.height(12.dp))
    ModelPicker(vm, locked)
    Spacer(Modifier.height(12.dp))
    Label("Lingua parlata")
    Dropdown(LANGUAGES.firstOrNull { it.second == vm.lang }?.first ?: "Italiano", LANGUAGES.map { it.first },
        { n -> vm.updateLang(LANGUAGES.first { it.first == n }.second) }, enabled = !locked)
    Spacer(Modifier.height(12.dp))
    Label("Cartella dei testi (.txt)")
    FolderRow(Storage.displayName(vm.outFolder), { folderLauncher.launch(null) }, !locked)
    Spacer(Modifier.height(18.dp))
    if (transcribing) {
        PrimaryButton("■  Annulla", { WorkService.stopRecording(ctx) }, Modifier.fillMaxWidth(), danger = true)
    } else {
        PrimaryButton("Trascrivi", {
            if (!vm.hasModel()) { Toast.makeText(ctx, "Scarica prima un modello.", Toast.LENGTH_LONG).show(); vm.showModels = true; return@PrimaryButton }
            if (chosen.isEmpty()) { Toast.makeText(ctx, "Scegli almeno un file.", Toast.LENGTH_SHORT).show(); return@PrimaryButton }
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            Bus.pendingUris = chosen
            WorkService.startTranscribe(ctx)
        }, Modifier.fillMaxWidth(), enabled = !locked)
    }
    if (msg.isNotBlank()) Text(msg, color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
}

@Composable
private fun FileRow(f: FileItem) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(f.name, color = C.text, fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f))
            val (t, c) = when (f.status) {
                FileStatus.WAITING -> "In attesa" to C.faint
                FileStatus.WORKING -> "${(f.progress * 100).toInt()}%" to C.accentText
                FileStatus.DONE -> "Fatto ✓" to C.ok
                FileStatus.FAILED -> "Errore" to C.rec
                FileStatus.SKIPPED -> "Saltato" to C.muted
            }
            Text(t, color = c, fontSize = 13.sp)
        }
        if (f.status == FileStatus.WORKING)
            LinearProgressIndicator(progress = { f.progress }, Modifier.fillMaxWidth().padding(top = 4.dp), color = C.accent, trackColor = C.track)
        if (f.note.isNotBlank()) Text(f.note, color = C.faint, fontSize = 12.sp)
    }
}

// ============================================================ USCITA
@Composable
private fun OutputCard(tab: Int, modifier: Modifier) {
    val live by Bus.liveText.collectAsStateWithLifecycle()
    val files by Bus.fileText.collectAsStateWithLifecycle()
    val text = if (tab == 0) live else files
    val clip = LocalClipboardManager.current
    val ctx = LocalContext.current
    Card(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (tab == 0) "Trascrizione in tempo reale" else "Testo trascritto", color = C.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            GhostButton("Copia", { clip.setText(AnnotatedString(text)); Toast.makeText(ctx, "Copiato", Toast.LENGTH_SHORT).show() }, enabled = text.isNotBlank())
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(C.field).padding(14.dp)) {
            val s = rememberScrollState()
            LaunchedEffect(text) { s.animateScrollTo(s.maxValue) }
            SelectionContainer {
                Text(
                    text.ifBlank { if (tab == 0) "Il testo comparirà qui mentre registri (se hai attivato la trascrizione in tempo reale)." else "Il testo dei file trascritti comparirà qui." },
                    color = if (text.isBlank()) C.faint else C.text, fontSize = 15.sp, lineHeight = 22.sp,
                    modifier = Modifier.fillMaxSize().verticalScroll(s))
            }
        }
    }
}

@Composable
fun SplashOverlay() {
    Box(Modifier.fillMaxSize().background(C.bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Logo(84.dp)
                Spacer(Modifier.width(18.dp))
                Text("VidScript", color = C.text, fontSize = 40.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("by", color = C.faint, fontSize = 14.sp)
                Spacer(Modifier.width(8.dp))
                Image(painterResource(R.drawable.roboek), "Roboek", Modifier.height(20.dp))
            }
            Spacer(Modifier.height(28.dp))
            LinearProgressIndicator(Modifier.width(180.dp), color = C.accent, trackColor = C.track)
            Spacer(Modifier.height(10.dp))
            Text("versione $VERSION_LABEL", color = C.faint, fontSize = 12.sp)
        }
    }
}
