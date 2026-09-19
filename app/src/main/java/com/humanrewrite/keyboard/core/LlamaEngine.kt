package com.humanrewrite.keyboard.core

/** Runs a downloaded GGUF model on the phone through llama.cpp (app/src/main/cpp). One model stays loaded. */
object LlamaEngine {
    private const val CONTEXT_TOKENS = 2048

    private val libraryLoaded = runCatching { System.loadLibrary("humanrewrite") }.isSuccess
    private var handle = 0L
    private var loadedPath: String? = null

    @Synchronized
    fun complete(modelPath: String, prompt: String, maxTokens: Int): String? {
        if (!ensureLoaded(modelPath)) return null
        return nativeComplete(handle, prompt.toByteArray(Charsets.UTF_8), maxTokens)?.toString(Charsets.UTF_8)
    }

    // Loading the GGUF is the slow "cold" part of the first rewrite; callers warm it up ahead of time.
    @Synchronized
    fun ensureLoaded(modelPath: String): Boolean {
        if (!libraryLoaded) return false
        if (loadedPath == modelPath) return true
        release()
        // Capped at 6, not the core count: for small quantized models, memory bandwidth (not
        // compute) becomes the bottleneck beyond ~6 threads, so more cores stop helping.
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        handle = nativeLoad(modelPath, CONTEXT_TOKENS, threads)
        if (handle == 0L) return false
        loadedPath = modelPath
        return true
    }

    @Synchronized
    fun release() {
        if (handle != 0L) nativeFree(handle)
        handle = 0L
        loadedPath = null
    }

    private external fun nativeLoad(path: String, contextTokens: Int, threads: Int): Long
    private external fun nativeComplete(handle: Long, prompt: ByteArray, maxTokens: Int): ByteArray?
    private external fun nativeFree(handle: Long)
}
