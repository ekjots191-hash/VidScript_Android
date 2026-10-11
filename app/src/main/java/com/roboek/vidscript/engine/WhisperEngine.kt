package com.roboek.vidscript.engine

/** Contesto whisper.cpp caricato in memoria (un modello alla volta). */
class WhisperEngine {
    private var handle = 0L

    companion object {
        init { System.loadLibrary("vidscript_whisper") }
    }

    fun load(path: String): Boolean {
        handle = nativeInit(path)
        return handle != 0L
    }

    /** Trascrive campioni mono a 16 kHz in float [-1, 1]. */
    @Synchronized
    fun transcribe(samples: FloatArray, lang: String, threads: Int): String {
        if (handle == 0L) return ""
        return nativeTranscribe(handle, samples, lang, threads, false).trim()
    }

    fun cancel() { if (handle != 0L) nativeCancel(handle) }

    @Synchronized
    fun release() {
        if (handle != 0L) nativeFree(handle)
        handle = 0L
    }

    private external fun nativeInit(path: String): Long
    private external fun nativeFree(handle: Long)
    private external fun nativeCancel(handle: Long)
    private external fun nativeTranscribe(handle: Long, samples: FloatArray, lang: String, threads: Int, translate: Boolean): String
}
