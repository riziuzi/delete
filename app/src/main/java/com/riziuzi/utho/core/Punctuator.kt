package com.riziuzi.utho.core

/**
 * Adds sentence punctuation to speech-to-text output, which often has none.
 * Each recognised segment (the recogniser splits on pauses) becomes one sentence.
 * Hindi sentences end with "।", English with ".", and questions with "?".
 */
object Punctuator {
    private val hindiQuestionStarts = setOf(
        "क्या", "क्यों", "कैसे", "कब", "कहाँ", "कहां", "कौन", "कौनसा", "कौनसी", "कितना", "कितने", "कितनी",
        "किसने", "किसको", "किसका", "किसकी", "किसके", "किस",
    )
    private val hindiQuestionEnds = setOf("क्या", "ना", "न")
    private val englishQuestionStarts = setOf(
        "what", "why", "how", "when", "where", "who", "whom", "whose", "which",
        "is", "are", "am", "was", "were", "do", "does", "did", "can", "could", "should", "would",
        "will", "shall", "may", "might", "have", "has",
    )
    private val enders = setOf('.', '?', '!', '।')

    fun devanagariShare(s: String): Float {
        var dev = 0
        var letters = 0
        for (c in s) {
            if (c.isLetter() || c in 'ऀ'..'ॿ') {
                letters++
                if (c in 'ऀ'..'ॿ') dev++
            }
        }
        return if (letters == 0) 0f else dev.toFloat() / letters
    }

    fun isHindi(s: String) = devanagariShare(s) >= 0.4f

    fun sentence(raw: String): String {
        var t = raw.trim().replace(Regex("\\s+"), " ")
        if (t.isEmpty()) return ""
        t = t.replace(Regex("\\s+([,.?!।])"), "$1")
        val hindi = isHindi(t)
        if (!hindi) {
            t = t.replaceFirstChar { it.uppercaseChar() }
            t = t.replace(Regex("(?<![\\p{L}'])i(?=['\\s,.?!]|$)"), "I")
            t = t.replace(Regex("([.?!]\\s+)(\\p{Ll})")) { m -> m.groupValues[1] + m.groupValues[2].uppercase() }
        }
        if (t.last() in enders) return t
        val words = t.split(" ")
        val first = words.first().lowercase().trim(',', '.')
        val last = words.last().lowercase().trim(',', '.')
        val question = if (hindi) {
            first in hindiQuestionStarts || last in hindiQuestionEnds
        } else {
            first in englishQuestionStarts
        }
        val end = when {
            question -> "?"
            hindi -> "।"
            else -> "."
        }
        return t + end
    }

    fun paragraph(segments: List<String>): String =
        segments.map { sentence(it) }.filter { it.isNotEmpty() }.joinToString(" ")
}
