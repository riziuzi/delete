package com.riziuzi.utho.core

/**
 * Converts Devanagari speech-to-text output into Hinglish (Hindi written in English letters):
 * "आज मैंने पॉलिटी के फंडामेंटल राइट्स पढ़े" -> "aaj maine polity ke fundamental rights padhe".
 * Common words come from a dictionary, English words written in Hindi script are mapped back to their
 * English spelling by sound, and everything else is transliterated with Hindi schwa deletion.
 */
object Hinglish {
    private val words: Map<String, String> = mapOf(
        "है" to "hai", "हैं" to "hain", "था" to "tha", "थी" to "thi", "थे" to "the", "हूँ" to "hoon", "हूं" to "hoon", "हो" to "ho",
        "मैं" to "main", "मैंने" to "maine", "में" to "mein", "मुझे" to "mujhe", "मेरा" to "mera", "मेरी" to "meri", "मेरे" to "mere",
        "हम" to "hum", "हमने" to "humne", "आप" to "aap", "आपने" to "aapne", "तुम" to "tum", "यह" to "yeh", "ये" to "ye", "वह" to "woh", "वो" to "wo",
        "और" to "aur", "या" to "ya", "लेकिन" to "lekin", "पर" to "par", "फिर" to "phir", "भी" to "bhi", "तो" to "to", "ही" to "hi",
        "नहीं" to "nahi", "नही" to "nahi", "ना" to "na", "न" to "na", "हां" to "haan", "हाँ" to "haan", "जी" to "ji",
        "का" to "ka", "की" to "ki", "के" to "ke", "को" to "ko", "से" to "se", "ने" to "ne", "पे" to "pe", "तक" to "tak",
        "क्या" to "kya", "क्यों" to "kyon", "कैसे" to "kaise", "कैसा" to "kaisa", "कैसी" to "kaisi", "कब" to "kab", "कहाँ" to "kahan", "कहां" to "kahan",
        "कौन" to "kaun", "कितना" to "kitna", "कितने" to "kitne", "कितनी" to "kitni",
        "आज" to "aaj", "कल" to "kal", "अभी" to "abhi", "बहुत" to "bahut", "थोड़ा" to "thoda", "थोड़ी" to "thodi", "ज़्यादा" to "zyada", "ज्यादा" to "zyada",
        "कम" to "kam", "अच्छा" to "accha", "अच्छी" to "acchi", "अच्छे" to "acche", "ठीक" to "theek", "बढ़िया" to "badhiya", "बढिया" to "badhiya",
        "खराब" to "kharab", "ख़राब" to "kharab", "बुरा" to "bura", "बेकार" to "bekaar", "मस्त" to "mast", "शानदार" to "shaandaar",
        "पढ़ा" to "padha", "पढ़ी" to "padhi", "पढ़े" to "padhe", "पढ़ाई" to "padhai", "पढ़ना" to "padhna", "पढ़" to "padh",
        "किया" to "kiya", "किए" to "kiye", "किये" to "kiye", "करना" to "karna", "कर" to "kar", "करके" to "karke", "करूंगा" to "karunga", "करूंगी" to "karungi",
        "गया" to "gaya", "गई" to "gayi", "गए" to "gaye", "रहा" to "raha", "रही" to "rahi", "रहे" to "rahe", "हुआ" to "hua", "हुई" to "hui", "हुए" to "hue",
        "लगा" to "laga", "लगी" to "lagi", "लगे" to "lage", "घंटे" to "ghante", "घंटा" to "ghanta", "मिनट" to "minute", "दिन" to "din", "रात" to "raat",
        "सुबह" to "subah", "शाम" to "shaam", "दोपहर" to "dopahar", "एक" to "ek", "दो" to "do", "तीन" to "teen", "चार" to "chaar", "पांच" to "paanch", "पाँच" to "paanch",
        "मूड" to "mood", "सवाल" to "sawaal", "जवाब" to "jawaab", "थका" to "thaka", "थकी" to "thaki", "थके" to "thake", "नींद" to "neend",
        "पूरा" to "poora", "पूरी" to "poori", "बाकी" to "baaki", "सब" to "sab", "कुछ" to "kuch", "वाला" to "wala", "वाली" to "wali", "वाले" to "wale",
        "साथ" to "saath", "बाद" to "baad", "पहले" to "pehle", "याद" to "yaad", "नोट्स" to "notes", "रिवीजन" to "revision", "मेहनत" to "mehnat",
        "समझ" to "samajh", "समझा" to "samjha", "राइट्स" to "rights", "राइट" to "right", "न्यूज़पेपर" to "newspaper", "न्यूजपेपर" to "newspaper",
        "मुश्किल" to "mushkil", "आसान" to "aasaan", "किताब" to "kitaab", "दोस्त" to "dost", "उदास" to "udaas", "परेशान" to "pareshaan", "टेंशन" to "tension",
    )

    /** English words as the Hindi recogniser writes them ("क्वेश्चन" -> "question"). */
    private val loanwords: Map<String, String> = mapOf(
        "क्वेश्चन" to "question", "क्वेश्चंस" to "questions", "क्वेश्चन्स" to "questions", "एंपायर" to "empire", "एम्पायर" to "empire",
        "चेंज" to "change", "रेट" to "rate", "ऑफ" to "off", "टेक्नोलॉजी" to "technology", "टेक्नॉलजी" to "technology", "केस" to "case",
        "स्टडी" to "study", "स्टडीज" to "studies", "रीजनिंग" to "reasoning", "सीसैट" to "CSAT", "सीसेट" to "CSAT",
        "एनवायरमेंट" to "environment", "एनवायरनमेंट" to "environment", "एंड" to "and", "मिशन" to "mission", "एस्से" to "essay",
        "एस्सै" to "essay", "मैथ्स" to "maths", "मैथ" to "maths", "पॉलिटी" to "polity", "हिस्ट्री" to "history",
        "ज्योग्राफी" to "geography", "इकोनॉमी" to "economy", "इकोनॉमिक्स" to "economics", "एथिक्स" to "ethics", "साइंस" to "science",
        "फिजिक्स" to "physics", "केमिस्ट्री" to "chemistry", "बायोलॉजी" to "biology", "इंग्लिश" to "English", "करंट" to "current",
        "अफेयर्स" to "affairs", "चैप्टर" to "chapter", "चैप्टर्स" to "chapters", "टॉपिक" to "topic", "टॉपिक्स" to "topics",
        "सिलेबस" to "syllabus", "मॉक" to "mock", "टेस्ट" to "test", "प्रैक्टिस" to "practice", "लेक्चर" to "lecture", "वीडियो" to "video",
        "क्लास" to "class", "बुक" to "book", "नोट" to "note", "रिविजन" to "revision", "प्रीलिम्स" to "prelims", "मेंस" to "mains",
        "मेन्स" to "mains", "आंसर" to "answer", "आंसर्स" to "answers", "राइटिंग" to "writing", "डाउट" to "doubt", "डाउट्स" to "doubts",
        "सॉल्व" to "solve", "सॉल्व्ड" to "solved", "फोकस" to "focus", "ब्रेक" to "break", "प्लान" to "plan", "टारगेट" to "target",
        "गोल" to "goal", "फंडामेंटल" to "fundamental", "आर्टिकल" to "article", "आर्टिकल्स" to "articles", "अमेंडमेंट" to "amendment",
        "बजट" to "budget", "इन्फ्लेशन" to "inflation", "इंफ्लेशन" to "inflation", "मानसून" to "monsoon", "लक्ष्मीकांत" to "Laxmikanth",
        "एनसीईआरटी" to "NCERT", "पार्लियामेंट" to "parliament", "कॉन्स्टिट्यूशन" to "constitution", "रेपो" to "repo", "जीडीपी" to "GDP",
        "जीएसटी" to "GST", "बैंक" to "bank", "क्लाइमेट" to "climate", "बायोडायवर्सिटी" to "biodiversity", "पॉल्यूशन" to "pollution",
        "सैटेलाइट" to "satellite", "स्पेस" to "space", "न्यूक्लियर" to "nuclear", "एप्टीट्यूड" to "aptitude", "इंटीग्रिटी" to "integrity",
        "पजल" to "puzzle", "सीरीज" to "series", "कॉम्प्रिहेंशन" to "comprehension", "ग्रामर" to "grammar", "वोकैबुलरी" to "vocabulary",
        "परसेंटेज" to "percentage", "प्रॉफिट" to "profit", "लॉस" to "loss", "रेशियो" to "ratio", "प्रोबेबिलिटी" to "probability",
        "एवरेज" to "average", "स्पीड" to "speed", "डिस्टेंस" to "distance", "एरिया" to "area", "अलजेब्रा" to "algebra",
        "ज्योमेट्री" to "geometry", "ट्रिगोनोमेट्री" to "trigonometry", "इक्वेशन" to "equation", "गुड" to "good", "बैड" to "bad",
        "ओके" to "okay", "टायर्ड" to "tired", "बोरिंग" to "boring", "बोर" to "bore", "हैप्पी" to "happy", "स्ट्रेस" to "stress",
        "प्रोडक्टिव" to "productive", "ग्रेट" to "great", "बेस्ट" to "best", "पीवाईक्यू" to "PYQ", "पीवायक्यू" to "PYQ",
        "एमसीक्यू" to "MCQ", "न्यूज" to "news", "एडिटोरियल" to "editorial", "पीआईबी" to "PIB", "ऑप्शनल" to "optional",
        "एंथ्रोपोलॉजी" to "anthropology", "सोशियोलॉजी" to "sociology", "पब्लिक" to "public", "एडमिनिस्ट्रेशन" to "administration",
        "गवर्नेंस" to "governance", "इंटरनेशनल" to "international", "रिलेशंस" to "relations", "सिक्योरिटी" to "security",
        "डिजास्टर" to "disaster", "मैनेजमेंट" to "management", "एग्रीकल्चर" to "agriculture", "इंडस्ट्री" to "industry",
        "इंफ्रास्ट्रक्चर" to "infrastructure", "मैप" to "map", "मैप्स" to "maps", "एटलस" to "atlas", "मूवमेंट" to "movement",
        "मॉडर्न" to "modern", "एंशिएंट" to "ancient", "मेडिवल" to "medieval", "मिडिवल" to "medieval", "आर्ट" to "art", "कल्चर" to "culture",
        "पेपर" to "paper", "सेशन" to "session", "मिनिट" to "minute", "रिपीट" to "repeat", "कंप्लीट" to "complete", "कम्पलीट" to "complete",
        "स्टार्ट" to "start", "फिनिश" to "finish", "डन" to "done", "पेंडिंग" to "pending", "बैकलॉग" to "backlog", "शेड्यूल" to "schedule",
        "टाइम" to "time", "टाइमटेबल" to "timetable", "लाइब्रेरी" to "library", "कोचिंग" to "coaching", "कॉन्सेप्ट" to "concept",
        "कॉन्सेप्ट्स" to "concepts", "क्लियर" to "clear", "कन्फ्यूज" to "confuse", "कन्फ्यूज्ड" to "confused", "मोटिवेटेड" to "motivated",
        "मोटिवेशन" to "motivation", "फ्रेश" to "fresh", "डल" to "dull", "रेडी" to "ready", "थ्योरी" to "theory", "फॉर्मूला" to "formula",
        "टेक" to "tech", "ठाक" to "thaak", "बोरियत" to "boriyat", "थक" to "thak", "मॉनसून" to "monsoon", "रिवाइज" to "revise",
        "रिवाइज़" to "revise", "प्रैक्टिकल" to "practical", "सब्जेक्ट" to "subject", "सब्जेक्ट्स" to "subjects",
    )

    private fun norm(w: String) = w.replace('ँ', 'ं').replace("\u093C", "")
    private val lookup: Map<String, String> = buildMap {
        loanwords.forEach { (k, v) -> put(norm(k), v) }
        words.forEach { (k, v) -> put(norm(k), v) }
    }

    /** English words aspirants say; Devanagari words that sound exactly like one are written in English. */
    private val vocabulary = (
        "maths polity history geography economy economics environment ecology science technology ethics essay csat current affairs " +
            "reasoning english physics chemistry biology quant optional revision practice chapter notes test mock question questions answer " +
            "answers video lecture class book books topic topics syllabus newspaper editorial prelims mains paper doubt doubts solve solved " +
            "focus break good bad okay tired boring happy sad stress tension productive great best worst plan target goal fundamental rights " +
            "parliament article articles constitution amendment budget inflation monsoon laxmikanth spectrum ncert president governor " +
            "judiciary election cabinet emergency schedule panchayat federalism preamble revolt congress gandhi mughal british vedic " +
            "maurya gupta river rivers mountain climate soil map earthquake volcano ocean cyclone plateau desert glacier atmosphere himalaya " +
            "continent mineral gdp bank tax gst repo deficit poverty trade export import market sebi subsidy agriculture equation algebra " +
            "geometry triangle percentage profit loss ratio probability average speed distance area integral calculus matrix integrity " +
            "aptitude values attitude satellite space nuclear vaccine virus robot missile biodiversity pollution wildlife species ecosystem " +
            "carbon wetland tiger puzzle series syllogism comprehension grammar vocabulary tense idiom force motion energy wave reaction " +
            "organic acid element compound electron cell genetics plant animal heart blood evolution award capital sports pyq mcq hour hours"
        ).split(" ")
    private val byKey: Map<String, String> = buildMap {
        for (w in vocabulary) {
            val k = Phonetic.skeleton(w)
            if (k.length >= 3 && !containsKey(k)) put(k, w)
        }
    }

    private val nukta = mapOf("ज़" to "z", "फ़" to "f", "क़" to "k", "ख़" to "kh", "ग़" to "g", "ड़" to "d", "ढ़" to "dh")
    private val consonants = mapOf(
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "n", 'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh", 'ञ' to "n",
        'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n", 'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
        'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh", 'म' to "m", 'य' to "y", 'र' to "r", 'ल' to "l", 'ळ' to "l", 'व' to "v",
        'श' to "sh", 'ष' to "sh", 'स' to "s", 'ह' to "h",
    )
    private const val LONG_A = "A"
    private val matras = mapOf(
        'ा' to LONG_A, 'ि' to "i", 'ी' to "i", 'ु' to "u", 'ू' to "u", 'ृ' to "ri",
        'े' to "e", 'ै' to "ai", 'ो' to "o", 'ौ' to "au", 'ॉ' to "o", 'ॅ' to "e",
    )
    private val independent = mapOf(
        'अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "i", 'उ' to "u", 'ऊ' to "u", 'ऋ' to "ri",
        'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au", 'ऑ' to "o",
    )

    /** One consonant (or none) with its vowel: "a" inherent, "" virama/deleted, else a vowel sound. */
    private class Unit(val cons: String?, var vowel: String, var nasal: Boolean = false, val virama: Boolean = false)

    private fun units(w: String): List<Unit> {
        val out = mutableListOf<Unit>()
        var i = 0
        while (i < w.length) {
            val c = w[i]
            val two = if (i + 1 < w.length) w.substring(i, i + 2) else ""
            val cons = nukta[two]?.also { i += 2 } ?: consonants[c]?.also { i++ }
            if (cons != null) {
                val n = w.getOrNull(i)
                when {
                    n == '्' -> {
                        out += Unit(cons, "", virama = true)
                        i++
                    }
                    n != null && matras.containsKey(n) -> {
                        out += Unit(cons, matras.getValue(n))
                        i++
                    }
                    else -> out += Unit(cons, "a")
                }
                continue
            }
            when {
                independent.containsKey(c) -> out += Unit(null, independent.getValue(c))
                c == 'ं' || c == 'ँ' -> out.lastOrNull()?.nasal = true
                c == 'ः' -> out += Unit(null, "h")
                c == '़' -> {}
                else -> out += Unit(null, c.toString())
            }
            i++
        }
        return out
    }

    /** Hindi drops the inherent "a" at word end and in the middle of C-a-C-V patterns (kar-na, sam-jha). */
    private fun deleteSchwa(u: List<Unit>) {
        val n = u.size
        if (n > 1) {
            val last = u[n - 1]
            val afterCluster = u[n - 2].virama
            if (last.cons != null && last.vowel == "a" && !last.nasal && !afterCluster) last.vowel = ""
        }
        var i = n - 2
        while (i >= 1) {
            val cur = u[i]
            val prev = u[i - 1]
            val next = u[i + 1]
            if (cur.cons != null && cur.vowel == "a" && !cur.nasal && prev.vowel.isNotEmpty() && next.cons != null && next.vowel.isNotEmpty()) {
                cur.vowel = ""
                i -= 2
                continue
            }
            i--
        }
    }

    fun word(w: String): String {
        lookup[norm(w)]?.let { return it }
        if (!Phonetic.hasDevanagari(w)) return w
        val k = Phonetic.key(w)
        if (k.length >= 3) byKey[k]?.let { return it }
        val u = units(w)
        deleteSchwa(u)
        val syllables = u.count { it.vowel.isNotEmpty() }
        val sb = StringBuilder()
        u.forEachIndexed { idx, x ->
            x.cons?.let { sb.append(it) }
            sb.append(if (x.vowel == LONG_A) (if (syllables == 1) "aa" else "a") else x.vowel)
            if (x.nasal) {
                val nextCons = u.getOrNull(idx + 1)?.cons
                sb.append(if (nextCons == "p" || nextCons == "ph" || nextCons == "b" || nextCons == "bh" || nextCons == "m" || nextCons == "v") "m" else "n")
            }
        }
        return sb.toString().replace("chchh", "cch")
    }

    /** Converts every Devanagari word; Latin words and punctuation pass through. */
    fun convert(text: String): String {
        if (!Phonetic.hasDevanagari(text)) return text
        return text.replace("।", ".").split(" ").joinToString(" ") { token ->
            val core = token.trimEnd('.', ',', '?', '!')
            val tail = token.substring(core.length)
            core.split("-").joinToString("-") { word(it) } + tail
        }
    }
}

/** Mood picked up from what the user said. 0 = great, 1 = good, 2 = okay, 3 = tired, 4 = bad. */
object Moods {
    fun label(mood: Int, lang: Lang): String = when (mood) {
        0 -> lang.t("Great", "Badhiya")
        1 -> lang.t("Good", "Accha")
        2 -> lang.t("Okay", "Theek")
        3 -> lang.t("Tired", "Thaka hua")
        else -> lang.t("Bad", "Kharab")
    }

    private val great = setOf(
        "great", "awesome", "excellent", "amazing", "fantastic", "best", "superb", "perfect", "productive", "wonderful",
        "badhiya", "badiya", "shandar", "shaandaar", "zabardast", "jabardast", "mast", "kamaal", "kamal", "behtareen",
        "बढ़िया", "बढिया", "शानदार", "जबरदस्त", "ज़बरदस्त", "मस्त", "कमाल", "बेहतरीन", "ग्रेट", "बेस्ट", "ऑसम", "प्रोडक्टिव", "परफेक्ट",
    )
    private val good = setOf(
        "good", "nice", "happy", "better", "motivated", "focused", "confident", "fresh", "accha", "acha", "achha", "acchi", "achhi", "acche", "khush", "behtar",
        "अच्छा", "अच्छी", "अच्छे", "खुश", "बेहतर", "गुड", "नाइस", "हैप्पी", "बेटर", "मोटिवेटेड", "फोकस्ड", "कॉन्फिडेंट", "फ्रेश",
    )
    private val okay = setOf(
        "okay", "ok", "fine", "average", "normal", "alright", "theek", "thik", "theekthaak", "thikthak",
        "ठीक", "ठीकठाक", "सामान्य", "औसत", "ओके", "फाइन", "नॉर्मल", "एवरेज",
    )
    private val tired = setOf(
        "tired", "sleepy", "exhausted", "bored", "boring", "lazy", "drained", "dull", "thak", "thaka", "thaki", "thake", "thakaan", "neend", "bore", "bor", "boriyat", "aalas", "sust",
        "थक", "थका", "थकी", "थके", "थकान", "नींद", "बोर", "आलस", "सुस्त", "टायर्ड", "स्लीपी", "बोरिंग",
    )
    private val bad = setOf(
        "bad", "worst", "terrible", "awful", "sad", "upset", "stressed", "stress", "depressed", "anxious", "frustrated",
        "moodoff", "bura", "buri", "kharab", "bekar", "bekaar", "udaas", "udas", "tension", "pareshan", "pareshaan", "dukhi",
        "मूडऑफ", "बुरा", "बुरी", "खराब", "ख़राब", "बेकार", "उदास", "तनाव", "टेंशन", "परेशान", "दुखी", "बैड", "वर्स्ट", "सैड", "स्ट्रेस", "अपसेट",
    )
    private val negations = setOf("not", "no", "never", "nahi", "nahin", "nhi", "नहीं", "नही")
    private val intensifiers = setOf("very", "really", "so", "too", "super", "bahut", "ekdam", "kaafi", "kafi", "बहुत", "एकदम", "काफी", "काफ़ी", "सुपर")
    /** Words also used as fillers ("ok, then...", "accha, phir..."): only count if nothing stronger is said. */
    private val weak = setOf("ok", "okay", "ओके", "accha", "acha", "achha", "अच्छा")

    /** The mood in [text] (Hinglish, Hindi or English), or null if none was said. Later mentions win. */
    fun detect(text: String): Int? {
        val tokens = (" " + TopicClassifier.normalize(text) + " ")
            .replace(" theek thaak ", " theekthaak ").replace(" thik thak ", " thikthak ").replace(" ठीक ठाक ", " ठीकठाक ")
            .replace(" mood off ", " moodoff ").replace(" मूड ऑफ ", " मूडऑफ ")
            .split(" ").filter { it.isNotBlank() }
        var strong: Int? = null
        var weakMood: Int? = null
        tokens.forEachIndexed { i, t ->
            var m = when (t) {
                in great -> 0
                in good -> 1
                in okay -> 2
                in tired -> 3
                in bad -> 4
                else -> return@forEachIndexed
            }
            val window = (maxOf(0, i - 2)..minOf(tokens.lastIndex, i + 2)).map { tokens[it] }
            val negated = window.any { it in negations } && !(t == "nahi" || t == "नहीं")
            if (negated) {
                m = when (m) {
                    0 -> 2
                    1 -> 4
                    else -> 2
                }
            } else if (m == 1 && tokens.getOrNull(i - 1) in intensifiers) {
                m = 0
            }
            val isWeak = t in weak && (i == 0 || tokens.size > 3 && i < tokens.size - 3 && !negated && tokens.getOrNull(i - 1) !in intensifiers)
            if (isWeak) weakMood = m else strong = m
        }
        return strong ?: weakMood
    }
}
