package com.wangzan101.djini.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class RamblerSpeechCleanerTest {
    @Test fun stripsFillersAndStutters() {
        assertEquals(
            "Open YouTube and search for lofi beats",
            RamblerSpeechCleaner.clean("um uh open open YouTube and, you know, search for lofi beats"),
        )
    }

    @Test fun resolvesMidSentenceCorrection() {
        assertEquals(
            "Open YouTube",
            RamblerSpeechCleaner.clean("open instagram no wait open YouTube"),
        )
        assertEquals(
            "Meet at 2pm",
            RamblerSpeechCleaner.clean("meet at 3pm actually 2pm"),
        )
    }

    @Test fun stripsMarkdownForNaturalSpeech() {
        assertEquals(
            "Hello world! Check Settings for details.",
            RamblerSpeechCleaner.forSpeech("**Hello** `world`! Check [Settings](file:///a) for details."),
        )
    }
}
