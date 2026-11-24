package com.wangzan101.djini.voice

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Hands-free back-and-forth Live Voice loop:
 * Listen (auto-VAD via SystemSpeech/VoiceInput) -> Rambler-style cleanup -> Auto-Send to Djini ->
 * Auto-Speak reply (cloud VoicePlayer if configured, or free on-device SystemTtsFallback) ->
 * Auto-reopen mic.
 */
class LiveVoiceLoop(
    context: Context,
    private val voice: VoiceSource,
    private val scope: CoroutineScope,
    private val onSendTranscript: (String) -> Unit,
    private val onError: (String) -> Unit = {},
) {
    sealed interface Phase {
        data object Off : Phase
        data class Listening(val partial: String = "", val level: Float = 0f) : Phase
        data object Thinking : Phase
        data object Speaking : Phase
    }

    private val tts = SystemTtsFallback(context)
    private val _phase = MutableStateFlow<Phase>(Phase.Off)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private var session: SpeechSession? = null
    private var collectorJob: Job? = null
    private var lastSpokenKey: String? = null

    val isActive: Boolean get() = _phase.value != Phase.Off

    fun toggle() {
        when (_phase.value) {
            Phase.Off -> startListening()
            Phase.Speaking -> {
                // 1-tap barge-in: stop speaking and immediately listen again
                tts.stop()
                startListening()
            }
            is Phase.Listening, Phase.Thinking -> stop()
        }
    }

    fun stop() {
        collectorJob?.cancel()
        collectorJob = null
        session?.cancel()
        session = null
        tts.stop()
        _phase.value = Phase.Off
    }

    fun startListening() {
        collectorJob?.cancel()
        session?.cancel()
        tts.stop()
        val s = voice.start()
        session = s
        _phase.value = Phase.Listening()
        collectorJob = scope.launch(Dispatchers.Main) {
            s.events.collect { ev ->
                when (ev) {
                    is SpeechEvent.Partial -> {
                        val cur = _phase.value as? Phase.Listening
                        if (cur != null) {
                            _phase.value = cur.copy(
                                partial = listOf(ev.stable, ev.tail).filter { it.isNotBlank() }.joinToString(" "),
                            )
                        }
                    }
                    is SpeechEvent.Level -> {
                        val cur = _phase.value as? Phase.Listening ?: return@collect
                        _phase.value = cur.copy(level = ev.value)
                    }
                    is SpeechEvent.Final -> {
                        val cleaned = RamblerSpeechCleaner.clean(ev.text)
                        if (cleaned.isNotBlank()) {
                            _phase.value = Phase.Thinking
                            onSendTranscript(cleaned)
                        } else if (isActive) {
                            startListening()
                        }
                    }
                    is SpeechEvent.Failed -> {
                        val why = ev.why
                        if (why == null && isActive) {
                            // Silence / nothing heard: gently re-listen
                            startListening()
                        } else {
                            stop()
                            onError(why?.toString() ?: "Voice input failed")
                        }
                    }
                    SpeechEvent.Converting -> Unit
                }
            }
        }
    }

    /** Called when Djini finishes an agent turn while Live Mode is in [Phase.Thinking]. */
    fun onAgentReply(replyKey: String, answerText: String) {
        if (_phase.value != Phase.Thinking) return
        if (replyKey == lastSpokenKey) return
        lastSpokenKey = replyKey
        _phase.value = Phase.Speaking
        tts.speak(answerText) {
            scope.launch(Dispatchers.Main) {
                if (_phase.value == Phase.Speaking) {
                    startListening()
                }
            }
        }
    }

    fun release() {
        stop()
        tts.shutdown()
    }
}
