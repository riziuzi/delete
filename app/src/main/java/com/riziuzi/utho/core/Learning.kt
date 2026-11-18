package com.riziuzi.utho.core

/**
 * The word list that grows with use. Every note you save (or fix) under a subject counts its words for that
 * subject. A word becomes one of that subject's own words once it has shown up there at least twice and
 * mostly there (3 out of 4 times), so everyday words like "padha" or "chapter" never qualify.
 */
object Learning {
    const val MIN_COUNT = 2
    const val MIN_SHARE = 0.75

    private val STOP = (
        "the a an and or but of to in on at for with from by is are was were be been am i me my we you he she it they this that " +
            "these those today yesterday tomorrow studied study studying read reading did do done some few many more less very " +
            "chapter chapters topic topics notes questions question revision revise class lecture video book books page pages " +
            "hour hours minute minutes time good bad okay ok tired mood feeling feel felt also then just only again " +
            "aaj kal maine mene humne hum main mai mein me ke ki ka ko se ne par pe aur ya bhi to hi hai hain tha thi the ho " +
            "kiya kiye ki kar karke kara padha padhi padhe padhai padh liya liye diya diye gaya gayi gaye raha rahi rahe hua hui " +
            "kuch sab bahut thoda zyada abhi phir ek do teen char paanch ghanta ghante wala wali wale accha theek badhiya " +
            "nahi na haan ji bas fir isme usme yeh woh wo ab"
        ).split(" ").toSet()

    /** Words in a note worth learning from. */
    fun contentWords(text: String): List<String> =
        TopicClassifier.normalize(text).split(" ").filter { w ->
            w.length >= 3 && w !in STOP && w.none { it.isDigit() } && !(Phonetic.hasDevanagari(w) && Hinglish.convert(w) in STOP)
        }.distinct()

    /** Each subject's learned words, from per-subject word counts. */
    fun learnedWords(counts: Map<String, Map<String, Int>>): Map<String, List<String>> {
        val total = HashMap<String, Int>()
        counts.values.forEach { m -> m.forEach { (w, c) -> total[w] = (total[w] ?: 0) + c } }
        return counts.mapValues { (_, m) ->
            m.filter { (w, c) -> c >= MIN_COUNT && c >= MIN_SHARE * (total[w] ?: c) }.entries
                .sortedByDescending { it.value }.map { it.key }
        }.filterValues { it.isNotEmpty() }
    }
}
