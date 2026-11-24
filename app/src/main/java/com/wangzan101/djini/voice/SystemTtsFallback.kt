package com.wangzan101.djini.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

/**
 * Zero-cost on-device Android [TextToSpeech] fallback so Live Voice Mode works out of the box
 * with no paid TTS API key required.
 */
class SystemTtsFallback(context: Context) {
    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pendingSpeak: Pair<String, () -> Unit>? = null
    private var onComplete: (() -> Unit)? = null

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private fun ensureInit() {
        if (tts != null) return
        tts = TextToSpeech(appContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.language = Locale.getDefault()
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _speaking.value = true
                    }

                    override fun onDone(utteranceId: String?) {
                        _speaking.value = false
                        val cb = onComplete
                        onComplete = null
                        cb?.invoke()
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        _speaking.value = false
                        val cb = onComplete
                        onComplete = null
                        cb?.invoke()
                    }
                })
                pendingSpeak?.let { (text, cb) ->
                    pendingSpeak = null
                    speak(text, cb)
                }
            } else {
                pendingSpeak?.second?.invoke()
                pendingSpeak = null
            }
        }
    }

    fun speak(rawText: String, onDone: () -> Unit = {}) {
        val clean = RamblerSpeechCleaner.forSpeech(rawText)
        if (clean.isBlank()) {
            onDone()
            return
        }
        ensureInit()
        if (!ready) {
            pendingSpeak = clean to onDone
            return
        }
        onComplete = onDone
        _speaking.value = true
        val id = UUID.randomUUID().toString()
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
    }

    fun stop() {
        pendingSpeak = null
        onComplete = null
        _speaking.value = false
        runCatching { tts?.stop() }
    }

    fun shutdown() {
        stop()
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }
}
