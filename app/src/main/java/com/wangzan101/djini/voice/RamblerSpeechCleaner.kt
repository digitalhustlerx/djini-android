package com.wangzan101.djini.voice

/**
 * On-device, zero-cost Rambler-style speech transcript cleaner:
 * - Strips spoken filler words ("um", "uh", "erm", "like" as filler, etc.)
 * - Resolves mid-sentence self-corrections ("at 3pm, actually 2pm" -> "at 2pm",
 *   "open instagram no wait open youtube" -> "open youtube")
 * - Collapses accidental word stutters ("the the" -> "the")
 * - Normalizes whitespace, capitalization, and terminal punctuation.
 */
object RamblerSpeechCleaner {
    private val FILLER_REGEX = Regex(
        """\b(?:um+|uh+|er+m?|ah+|hmm+|you know|sort of|kind of)\b[,.]?\s*""",
        RegexOption.IGNORE_CASE,
    )
    private val STUTTER_REGEX = Regex("""\b(\w+)\s+\1\b""", RegexOption.IGNORE_CASE)
    private val MULTI_SPACE = Regex("""\s{2,}""")

    // Self-correction pivots: "X, actually Y" or "X no wait Y" or "X sorry I mean Y"
    private val CORRECTION_PIVOTS = listOf(
        Regex("""^(.*?)\b(?:no wait|wait no|sorry i mean|i mean|actually|scratch that|never mind)\b[,:]?\s+(.+)$""", RegexOption.IGNORE_CASE),
    )

    fun clean(raw: String): String {
        var text = raw.trim()
        if (text.isEmpty()) return ""

        // 1. Strip filler words
        text = FILLER_REGEX.replace(text, " ")

        // 2. Resolve mid-sentence self-corrections
        for (pivot in CORRECTION_PIVOTS) {
            val m = pivot.find(text) ?: continue
            val before = m.groupValues[1].trim()
            val after = m.groupValues[2].trim()
            if (after.isNotEmpty()) {
                // If 'after' is a full clause or replacement, prefer 'after';
                // if 'before' has a leading verb/preposition and 'after' is short (1-2 words),
                // keep the prefix up to the last word before the pivot.
                val afterWords = after.split(Regex("\\s+"))
                val beforeWords = before.split(Regex("\\s+")).filter { it.isNotEmpty() }
                text = if (afterWords.size <= 2 && beforeWords.size >= 3) {
                    (beforeWords.dropLast(afterWords.size.coerceAtMost(beforeWords.size - 1)) + afterWords).joinToString(" ")
                } else {
                    after
                }
            }
        }

        // 3. Collapse word stutters ("open open" -> "open")
        text = STUTTER_REGEX.replace(text, "$1")

        // 4. Clean spacing & punctuation
        text = MULTI_SPACE.replace(text, " ").trim().trimStart(',', '.', ';', ':').trim()
        if (text.isEmpty()) return ""

        // 5. Capitalize first character
        text = text.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        return text
    }

    /** Strips markdown syntax so Android TTS reads replies naturally without saying "asterisk". */
    fun forSpeech(markdown: String): String {
        return markdown
            .replace(Regex("```[\\s\\S]*?```"), " code block omitted. ")
            .replace(Regex("`([^`]+)`"), "$1")
            .replace(Regex("\\[([^\\]]+)\\]\\([^)]+\\)"), "$1")
            .replace(Regex("[*_~#>]+"), "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }
}
