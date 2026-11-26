package com.wangzan101.djini.core.cap.transcribe

import com.wangzan101.djini.core.tool.AgentTool

internal object TranscribeGroup {
    val tools: List<AgentTool> = listOf(AudioTranscribe)
}
