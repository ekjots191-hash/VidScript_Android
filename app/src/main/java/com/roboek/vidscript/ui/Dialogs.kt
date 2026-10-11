package com.roboek.vidscript.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.roboek.vidscript.Bus
import com.roboek.vidscript.engine.*

@Composable
private fun PopupFrame(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(
            Modifier.fillMaxWidth(0.96f).widthIn(max = 880.dp).heightIn(max = 760.dp),
            shape = RoundedCornerShape(20.dp), color = C.bg, border = androidx.compose.foundation.BorderStroke(1.dp, C.border),
        ) { Column(Modifier.padding(20.dp), content = content) }
    }
}

// ============================================================ GUIDA
@Composable
fun GuideDialog(vm: AppModel) {
    val ctx = LocalContext.current
    var page by remember { mutableIntStateOf(0) }
    var sel by remember { mutableStateOf(if (Models.spec(vm.model).label == vm.model) vm.model else "Veloce") }
    val dl by ModelDownloader.state.collectAsStateWithLifecycle()
    val spec = Models.spec(sel)
    val installed = Models.isInstalled(ctx, spec)

    PopupFrame({ vm.finishGuide() }) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            when (page) {
                0 -> {
                    Text("Benvenuto in VidScript", color = C.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text("Scegli il modello di trascrizione da scaricare. Potrai cambiarlo o aggiungerne altri in qualsiasi momento da «Gestisci modelli».",
                        color = C.muted, fontSize = 14.sp)
                    Spacer(Modifier.height(14.dp))
                    BoxWithConstraints {
                        val row = maxWidth >= 640.dp
                        val cards: @Composable (ModelSpec) -> Unit = { m -> ModelChoiceCard(m, m.label == sel) { sel = m.label } }
                        if (row) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Models.all.forEach { Box(Modifier.weight(1f)) { cards(it) } }
                        } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { Models.all.forEach { cards(it) } }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Il download usa dati: meglio con il Wi-Fi.", color = C.faint, fontSize = 12.sp)
                }
                1 -> Tour("Registra", "Audio o video, con la trascrizione che scorre mentre registri.",
                    listOf(
                        "Scegli cosa registrare" to "«Solo audio» salva un file audio (.m4a). «Video + audio» registra lo schermo e salva un file .mp4.",
                        "Scegli le sorgenti audio" to "«Audio delle app» cattura i suoni riprodotti dal dispositivo (solo dalle app che lo consentono), «Audio del microfono» quello del microfono. Puoi registrarli insieme.",
                        "Trascrizione in tempo reale" to "Il testo appare mentre registri e il file .txt si salva accanto alla registrazione."),
                    "Scegli la cartella di salvataggio nella scheda Registra. Registra solo ciò per cui hai il permesso.")
                2 -> Tour("Trascrivi file", "Trasforma in testo le registrazioni che hai già.",
                    listOf(
                        "Scegli i file" to "Audio o video già presenti sul dispositivo, anche più di uno alla volta.",
                        "Scegli la cartella" to "Per ogni file ottieni un testo (.txt) con lo stesso nome, nella cartella che preferisci.",
                        "Puoi lasciare l'app" to "La trascrizione continua in background con una notifica. Con file lunghi serve tempo e batteria: meglio con il dispositivo in carica."),
                    "Più il modello è accurato, più tempo ci vuole.")
                else -> {
                    Text("Tutto pronto", color = C.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    val st = dl[sel]
                    when {
                        installed -> Text("Il modello «${spec.label}» è pronto. Puoi iniziare.", color = C.ok, fontSize = 16.sp)
                        st?.running == true -> {
                            Text("Scarico il modello «${spec.label}»… ${(st.progress * 100).toInt()}%", color = C.text, fontSize = 15.sp)
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(progress = { st.progress }, Modifier.fillMaxWidth(), color = C.accent, trackColor = C.track)
                            Spacer(Modifier.height(8.dp))
                            Text("Puoi iniziare a usare l'app: il download continua e il modello sarà disponibile appena finito.", color = C.muted, fontSize = 13.sp)
                        }
                        st?.error?.isNotBlank() == true -> {
                            Text(st.error, color = C.rec, fontSize = 14.sp)
                            Spacer(Modifier.height(8.dp))
                            GhostButton("Riprova", { ModelDownloader.start(ctx, spec) })
                        }
                        else -> Text("Il modello non è ancora scaricato. Puoi scaricarlo da «Gestisci modelli».", color = C.muted, fontSize = 14.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                for (i in 0..3) Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(if (i == page) C.accent else C.track))
            }
            if (page > 0) { GhostButton("Indietro", { page-- }); Spacer(Modifier.width(8.dp)) }
            PrimaryButton(if (page == 3) "Inizia" else "Avanti", {
                if (page == 0) { vm.updateModel(sel); ModelDownloader.start(ctx, spec) }
                if (page == 3) vm.finishGuide() else page++
            }, Modifier.widthIn(min = 120.dp))
        }
    }
}

@Composable
private fun ModelChoiceCard(m: ModelSpec, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (selected) C.accentTint else C.card)
            .border(if (selected) 2.dp else 1.dp, if (selected) C.accent else C.border, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(m.label, color = C.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (m.badge.isNotBlank()) Badge(m.badge)
        }
        Spacer(Modifier.height(10.dp))
        Stat("Download", m.sizeTxt)
        Stat("Memoria RAM utilizzata", m.ramTxt)
        Text("Carico sul processore · ${m.cpuTxt}", color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        Dots(m.cpuLevel, C.rec)
        Text("Precisione", color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        Dots(m.accLevel)
        Text(m.note, color = C.faint, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun Stat(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
        Text(k, color = C.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(v, color = C.text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Tour(title: String, sub: String, points: List<Pair<String, String>>, foot: String) {
    Text(title, color = C.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
    Text(sub, color = C.muted, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
    points.forEachIndexed { i, (h, b) ->
        Row(Modifier.padding(bottom = 12.dp)) {
            Box(Modifier.size(26.dp).clip(RoundedCornerShape(50)).background(C.accentTint), contentAlignment = Alignment.Center) {
                Text("${i + 1}", color = C.accentText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            Column(Modifier.padding(start = 12.dp)) {
                Text(h, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(b, color = C.muted, fontSize = 14.sp, lineHeight = 20.sp)
            }
        }
    }
    Text(foot, color = C.faint, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
}

// ============================================================ GESTISCI MODELLI
@Composable
fun ModelManagerDialog(vm: AppModel) {
    val ctx = LocalContext.current
    val dl by ModelDownloader.state.collectAsStateWithLifecycle()
    val recording by Bus.recording.collectAsStateWithLifecycle()
    val finishing by Bus.finishing.collectAsStateWithLifecycle()
    val transcribing by Bus.transcribing.collectAsStateWithLifecycle()
    val busy = recording || finishing || transcribing
    vm.modelsVersion

    PopupFrame({ vm.showModels = false }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Gestisci modelli", color = C.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            GhostButton("Chiudi", { vm.showModels = false })
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            Text("I modelli sono i programmi di riconoscimento vocale che trasformano l'audio in testo. Si scaricano una sola volta e restano sul dispositivo: " +
                "dopo il download la trascrizione funziona anche senza internet. Puoi scaricarne più di uno, scegliere quale usare e eliminare quelli che non ti servono per liberare spazio.",
                color = C.muted, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(vertical = 12.dp))
            Models.all.forEach { m ->
                val installed = Models.isInstalled(ctx, m)
                val st = dl[m.label]
                val inUse = installed && vm.model == m.label
                Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(14.dp)).background(C.card)
                    .border(1.dp, if (inUse) C.accent else C.border, RoundedCornerShape(14.dp)).padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(m.label, color = C.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(8.dp))
                        if (m.badge.isNotBlank()) Badge(m.badge)
                        Spacer(Modifier.weight(1f))
                        Text(when { inUse -> "In uso"; installed -> "Scaricato"; st?.running == true -> "Download…"; else -> "Non scaricato" },
                            color = if (installed) C.ok else C.faint, fontSize = 13.sp)
                    }
                    Text("Download ${m.sizeTxt}  ·  RAM ${m.ramTxt}  ·  Carico ${m.cpuTxt.lowercase()}", color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    Text(m.desc, color = C.muted, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 8.dp))
                    if (st?.running == true) {
                        LinearProgressIndicator(progress = { st.progress }, Modifier.fillMaxWidth().padding(top = 10.dp), color = C.accent, trackColor = C.track)
                        Text("${(st.progress * 100).toInt()}%", color = C.faint, fontSize = 12.sp)
                    }
                    if (st?.error?.isNotBlank() == true && !installed) Text(st.error, color = C.rec, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when {
                            st?.running == true -> GhostButton("Annulla", { ModelDownloader.cancel(m.label) })
                            installed -> {
                                if (!inUse) PrimaryButton("Usa", { vm.updateModel(m.label) }, Modifier.heightIn(min = 44.dp), enabled = !busy)
                                GhostButton("Elimina", {
                                    if (busy) Toast.makeText(ctx, "Non puoi eliminare un modello mentre registri o trascrivi.", Toast.LENGTH_SHORT).show()
                                    else { EngineCache.release(); Models.delete(ctx, m); vm.bumpModels(); vm.ensureValidModel() }
                                })
                            }
                            else -> PrimaryButton("Scarica", {
                                if (ModelDownloader.anyRunning()) Toast.makeText(ctx, "Un download è già in corso.", Toast.LENGTH_SHORT).show()
                                else ModelDownloader.start(ctx, m)
                            }, Modifier.heightIn(min = 44.dp))
                        }
                    }
                }
            }
            Text("Il download usa dati: meglio con il Wi-Fi.", color = C.faint, fontSize = 12.sp)
        }
    }
}
