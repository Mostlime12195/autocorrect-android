package dev.mostlime.autocorrect.engine

/**
 * Deterministic fixes that never go through the model, the unsure / risky gates or suggest-only mode.
 * ONE place for the table, so the Android and Mac copies can be diffed: [CONTRACTIONS] (and [I_WORDS]) below.
 *
 * - lone i / i'm / i'll / i'd / i've -> I, I'm, I'll, I'd, I've
 * - contractions typed without the apostrophe, only where the apostrophe-less form is NOT an English word
 *   (so NOT wont, cant, its, lets, id, ill, hell, shell, well, were, shed, wed)
 *
 * Case: all lowercase, or first letter capital with the rest lowercase ("Dont" -> "Don't"). Anything else (DONT, dOnt)
 * is left to the engine. The caller skips the fix in the casual style, for a word in the user's dictionary and when the
 * user undid this exact fix before (UserData.penalty(typed, fixed) > 0).
 */
object FixedFixes {
    /** lone i and its contractions (typed with an apostrophe) */
    val I_WORDS = setOf("i", "i'm", "i'll", "i'd", "i've")

    /** apostrophe-less form (lowercase) -> contraction (lowercase, except the I in I'm / I've) */
    val CONTRACTIONS: Map<String, String> = linkedMapOf(
        "im" to "I'm", "ive" to "I've",
        "dont" to "don't", "doesnt" to "doesn't", "didnt" to "didn't",
        "isnt" to "isn't", "wasnt" to "wasn't", "arent" to "aren't", "werent" to "weren't",
        "havent" to "haven't", "hasnt" to "hasn't", "hadnt" to "hadn't",
        "couldnt" to "couldn't", "wouldnt" to "wouldn't", "shouldnt" to "shouldn't", "mustnt" to "mustn't",
        "youre" to "you're", "youve" to "you've", "youll" to "you'll",
        "theyre" to "they're", "theyve" to "they've", "theyll" to "they'll",
        "weve" to "we've",
        "wouldve" to "would've", "couldve" to "could've", "shouldve" to "should've",
        "thats" to "that's", "whats" to "what's", "theres" to "there's", "heres" to "here's", "wheres" to "where's",
        "aint" to "ain't",
    )

    /** the fixed form of [typed], or null when no rule applies (or [typed] already is the fixed form) */
    fun fix(typed: String): String? {
        if (typed.isEmpty()) return null
        val lower = typed.lowercase()
        val cased = typed == lower || (typed[0].isUpperCase() && typed.substring(1) == typed.substring(1).lowercase())
        if (!cased) return null
        val to = if (lower in I_WORDS) "I" + lower.substring(1) else CONTRACTIONS[lower]?.let { c ->
            if (c[0] == 'I') c else if (typed[0].isUpperCase()) c[0].uppercaseChar() + c.substring(1) else c
        } ?: return null
        return to.takeIf { it != typed }
    }
}
