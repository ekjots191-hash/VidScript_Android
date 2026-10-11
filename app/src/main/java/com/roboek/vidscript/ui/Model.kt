package com.roboek.vidscript.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.roboek.vidscript.Prefs
import com.roboek.vidscript.engine.EngineCache
import com.roboek.vidscript.engine.ModelSpec
import com.roboek.vidscript.engine.Models

class AppModel(app: Application) : AndroidViewModel(app) {
    private val p = Prefs(app)
    var model by mutableStateOf(p.model)
        private set
    var lang by mutableStateOf(p.lang)
        private set
    var video by mutableStateOf(p.video)
        private set
    var srcApp by mutableStateOf(p.srcApp)
        private set
    var srcMic by mutableStateOf(p.srcMic)
        private set
    var live by mutableStateOf(p.live)
        private set
    var recFolder by mutableStateOf(p.recFolder)
        private set
    var outFolder by mutableStateOf(p.outFolder)
        private set
    var name by mutableStateOf("")
    var showGuide by mutableStateOf(!p.onboarded)
    var showModels by mutableStateOf(false)
    var splash by mutableStateOf(true)
    var modelsVersion by mutableIntStateOf(0)
        private set

    fun setModel(v: String) { model = v; p.model = v; EngineCache.release() }
    fun setLang(v: String) { lang = v; p.lang = v }
    fun setVideo(v: Boolean) { video = v; p.video = v }
    fun setSrcApp(v: Boolean) { srcApp = v; p.srcApp = v }
    fun setSrcMic(v: Boolean) { srcMic = v; p.srcMic = v }
    fun setLive(v: Boolean) { live = v; p.live = v }
    fun setRecFolder(v: String?) { recFolder = v; p.recFolder = v }
    fun setOutFolder(v: String?) { outFolder = v; p.outFolder = v }
    fun finishGuide() { p.onboarded = true; showGuide = false }
    fun bumpModels() { modelsVersion++ }

    fun installed(): List<ModelSpec> { modelsVersion; return Models.installed(getApplication()) }
    fun hasModel() = installed().isNotEmpty()

    /** Se il modello scelto non è installato ma ne esiste un altro, passa a quello. */
    fun ensureValidModel() {
        val inst = installed()
        if (inst.isNotEmpty() && inst.none { it.label == model }) setModel(inst[0].label)
    }
}
