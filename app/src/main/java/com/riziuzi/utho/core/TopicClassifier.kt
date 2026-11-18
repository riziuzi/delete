package com.riziuzi.utho.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Built-in words for a common subject, in English, Hindi and romanised Hindi. */
data class Lexicon(val aliases: List<String>, val keywords: List<String>)

/**
 * Picks which of the user's subjects a short note is about. Runs fully on the phone, instantly. Three parts:
 *
 *  1. Word list: subject names and built-in words (English, Hindi, Hinglish; also English words the Hindi
 *     recogniser writes in Hindi script, by sound). 3 x name said + 1 x each word, 2 x each phrase (max 6).
 *  2. Learned words ([learned]): words that kept turning up in your own notes for a subject (see [Learning]).
 *  3. Meaning ([meaning]): the on-device AI word vectors. Compares the note's meaning with each subject
 *     (its words plus your past notes in it), so new phrasing still finds the right subject.
 * Plus similarity to notes you previously filed under each subject.
 */
class TopicClassifier(
    private val topics: List<String>,
    private val examples: Map<String, List<String>> = emptyMap(),
    private val meaning: Meaning? = null,
    private val learned: Map<String, List<String>> = emptyMap(),
) {
    data class Scored(val topic: String, val score: Double)

    /** [best] is null when nothing matched. [confident] = clearly ahead of the runner-up. */
    data class Result(val best: String?, val ranked: List<Scored>, val confident: Boolean)

    private val prepared = topics.map { topic ->
        val norm = normalize(topic)
        val lex = LEXICON.filter { lx -> lx.aliases.any { aliasMatches(normalize(it), norm) } }
        val names = (listOf(norm) + lex.flatMap { it.aliases }.map { normalize(it) }).filter { it.isNotBlank() }.distinct()
        val keywords = (lex.flatMap { it.keywords }.map { normalize(it) } + (learned[topic] ?: emptyList())).filter { it.isNotBlank() }.distinct()
        val ex = (examples[topic] ?: emptyList()).map { trigrams(normalize(it)) }
        // What the subject means: its name, aliases and words, plus the notes filed under it.
        val proto = meaning?.embed((listOf(topic) + names + names + keywords).joinToString(" "))
        val exVecs = meaning?.let { m -> (examples[topic] ?: emptyList()).mapNotNull { m.embed(it) } } ?: emptyList()
        Prepared(topic, names, names.map { soundKeys(it) }, keywords, keywords.map { soundKeys(it) }, ex, proto, exVecs)
    }

    /** How close each subject already is to everyday study talk; taken off so no subject gets notes by default. */
    private val bias: List<Double> = meaning?.embed(NEUTRAL)?.let { n -> prepared.map { p -> p.proto?.let { Meaning.cosine(n, it) } ?: 0.0 } }
        ?: prepared.map { 0.0 }

    private class Prepared(
        val topic: String,
        val names: List<String>,
        val nameKeys: List<List<String>>,
        val keywords: List<String>,
        val keywordKeys: List<List<String>>,
        val examples: List<Map<String, Int>>,
        val proto: FloatArray?,
        val exampleVecs: List<FloatArray>,
    )

    /** Word-list score of each subject (no meaning model). */
    private fun wordScores(norm: String): List<Double> {
        val tokens = norm.split(" ")
        val padded = " $norm "
        val grams = trigrams(norm)
        // Sound-alike keys catch English words written in Hindi script (मैथ्स = maths).
        val textKeys = tokens.map { if (it in STOP_TOKENS || !Phonetic.hasDevanagari(it)) "" else Phonetic.key(it) }
        return prepared.map { p ->
            val nameHits = p.names.indices.count { matches(p.names[it], tokens, padded) || Phonetic.contains(textKeys, p.nameKeys[it], 3) }
            // A matched phrase ("climate change") says more than a single word ("climate"), so it counts double.
            var keywordHits = 0
            for (i in p.keywords.indices) {
                if (matches(p.keywords[i], tokens, padded) || Phonetic.contains(textKeys, p.keywordKeys[i], 4)) {
                    keywordHits += if (p.keywords[i].contains(' ')) 2 else 1
                }
            }
            val sim = p.examples.maxOfOrNull { cosine(grams, it) } ?: 0.0
            val exampleScore = if (sim >= 0.3) 5.0 * sim else 0.0
            3.0 * min(nameHits, 2) + min(keywordHits, 6) + exampleScore
        }
    }

    /** Meaning similarity of the note to each subject (its description, or your closest past note in it). */
    fun meaningScores(text: String): List<Double>? {
        val m = meaning ?: return null
        val v = m.embed(text) ?: return null
        return prepared.map { p ->
            val toSubject = p.proto?.let { Meaning.cosine(v, it) - bias[prepared.indexOf(p)] } ?: 0.0
            val toNotes = p.exampleVecs.maxOfOrNull { Meaning.cosine(v, it) - EXAMPLE_DISCOUNT } ?: -1.0
            max(toSubject, toNotes)
        }
    }

    fun classify(text: String): Result {
        val norm = normalize(text)
        if (norm.isBlank() || topics.isEmpty()) return Result(null, topics.map { Scored(it, 0.0) }, false)
        val words = wordScores(norm)
        val sims = meaningScores(text)
        val topWord = words.max()
        // The word list decides when it's clear. Meaning only settles near-ties, or speaks when no word matched.
        val scores = words.indices.map { i ->
            val inTie = topWord > 0.0 && words[i] >= topWord - 1.0
            words[i] + if (sims != null && inTie) TIE_WEIGHT * sims[i] else 0.0
        }
        val ranked = prepared.indices.map { Scored(prepared[it].topic, scores[it]) }.sortedByDescending { it.score }
        val top = ranked.first()
        val second = ranked.getOrNull(1)?.score ?: 0.0
        if (topWord > 0.0) {
            return Result(top.topic, ranked, top.score >= 1.0 && top.score >= second + 1.0)
        }
        if (sims != null) {
            val order = sims.indices.sortedByDescending { sims[it] }
            val a = sims[order[0]]
            val b = order.getOrNull(1)?.let { sims[it] } ?: 0.0
            if (a >= MEANING_FLOOR && a - b >= MEANING_GAP) {
                val byMeaning = order.map { Scored(prepared[it].topic, sims[it]) }
                return Result(prepared[order[0]].topic, byMeaning, a - b >= 2 * MEANING_GAP)
            }
        }
        return Result(null, ranked, false)
    }

    companion object {
        /** Tuned on the phrase sets in the unit tests (SubjectMeaningTest). */
        var TIE_WEIGHT = 1.0
        var MEANING_FLOOR = 0.45
        var MEANING_GAP = 0.04
        var EXAMPLE_DISCOUNT = 0.05

        /** Everyday study talk with no subject in it (see [bias]). */
        private const val NEUTRAL =
            "aaj maine do ghante padhai ki kuch nahi kiya thak gaya mood accha hai revision chapter questions notes " +
                "today i studied for two hours did some reading took a break feeling okay " +
                "आज मैंने पढ़ाई की कुछ नहीं किया थक गया मूड अच्छा है"
        /** Romanised Hindi words in the lexicon: matched by spelling only, never by sound (ganit ~ घंटे). */
        private val ROMAN_HINDI = setOf(
            "rajvyavastha", "samvidhan", "sansad", "anuched", "adhikar", "itihas", "angrez", "andolan", "bhugol", "nadi", "parvat",
            "mehngai", "arthvyavastha", "arthashastra", "ganit", "nibandh", "naitikta", "vigyan", "paryavaran", "angrezi", "vyakaran",
            "bhautiki", "rasayan", "sawal",
        )

        /** Sound keys for English terms only; Hindi terms are matched by spelling. */
        private fun soundKeys(term: String): List<String> =
            if (Phonetic.hasDevanagari(term) || term in ROMAN_HINDI) emptyList() else Phonetic.keys(term)

        /** Everyday words that sound like a subject name ("सेंस" ~ science). */
        private val STOP_TOKENS = setOf("सेंस", "sense", "स्त्री", "पलट", "पलटा", "ऐसे", "ऐसा", "स्टार", "star")

        fun normalize(s: String): String = s.lowercase()
            .replace('ँ', 'ं')
            .replace("़", "") // nukta: ज़ -> ज, ढ़ -> ढ
            .replace("‌", "").replace("‍", "")
            .replace(Regex("[^\\p{L}\\p{M}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        private fun aliasMatches(alias: String, topic: String): Boolean {
            if (alias.isBlank() || topic.isBlank()) return false
            if (alias == topic) return true
            if (" $topic ".contains(" $alias ")) return true
            if (alias.contains(' ') || alias.length < 4) return false
            return topic.split(" ").any { it.length >= 4 && (it.startsWith(alias) || alias.startsWith(it)) }
        }

        private fun matches(term: String, tokens: List<String>, padded: String): Boolean {
            // Phrases also match with a plural last word ("national park" ~ "national parks").
            if (term.contains(' ')) return padded.contains(" $term ") || padded.contains(" ${term}s ") || padded.contains(" ${term}es ")
            return tokens.any { it == term || (term.length >= 4 && it.startsWith(term)) }
        }

        private fun trigrams(s: String): Map<String, Int> {
            val t = " $s "
            val out = HashMap<String, Int>()
            for (i in 0..t.length - 3) {
                val g = t.substring(i, i + 3)
                out[g] = (out[g] ?: 0) + 1
            }
            return out
        }

        private fun cosine(a: Map<String, Int>, b: Map<String, Int>): Double {
            if (a.isEmpty() || b.isEmpty()) return 0.0
            var dot = 0.0
            for ((k, v) in a) b[k]?.let { dot += v.toDouble() * it }
            val na = sqrt(a.values.sumOf { it.toDouble() * it })
            val nb = sqrt(b.values.sumOf { it.toDouble() * it })
            return if (na == 0.0 || nb == 0.0) 0.0 else dot / (na * nb)
        }

        val LEXICON: List<Lexicon> = listOf(
            Lexicon(
                aliases = listOf("polity", "राजव्यवस्था", "राजनीति", "rajvyavastha", "constitution", "gs2", "governance", "शासन"),
                keywords = listOf(
                    "equality", "right to equality", "समानता", "law", "laws", "कानून", "minister", "ministers", "मंत्री",
                    "political", "politics", "political party", "political parties", "राजनीतिक", "राजनीतिक दल", "राजनीतिक पार्टी",
                    "anti defection", "defection", "दलबदल", "election commission", "चुनाव आयोग", "voting", "मतदान", "representation of people",
                    "local government", "panchayati raj", "पंचायती राज", "municipality", "नगरपालिका", "fundamental duties", "मौलिक कर्तव्य",
                    "directive principles", "legislature", "विधायिका", "executive", "कार्यपालिका", "ordinance", "अध्यादेश", "attorney general",
                    "comptroller", "cag", "finance commission", "वित्त आयोग", "constitutional body", "constitutional bodies", "statutory", "tribunal",
                    "न्यायाधिकरण", "centre state", "centre state relations", "केंद्र राज्य", "union territory", "केंद्र शासित", "separation of powers",
                    "judicial review", "न्यायिक समीक्षा", "basic structure", "मूल ढांचा", "pressure group", "दबाव समूह", "rti", "सूचना का अधिकार",
                    "vidhan sabha", "विधानसभा", "democracy", "लोकतंत्र", "secularism", "धर्मनिरपेक्षता", "sovereignty", "संप्रभुता", "judges",
                    "parliamentary", "संसदीय", "committee", "committees", "समिति", "constitutional", "संवैधानिक", "rajniti", "sansad",
                    "constitution", "संविधान", "samvidhan", "parliament", "संसद", "sansad", "article", "अनुच्छेद", "anuched",
                    "fundamental", "मौलिक", "rights", "राइट्स", "अधिकार", "adhikar", "president", "राष्ट्रपति", "governor", "राज्यपाल",
                    "judiciary", "न्यायपालिका", "supreme court", "सुप्रीम", "high court", "उच्च न्यायालय", "lok sabha", "लोकसभा",
                    "rajya sabha", "राज्यसभा", "amendment", "संशोधन", "preamble", "प्रस्तावना", "panchayat", "पंचायत",
                    "federalism", "संघवाद", "election", "चुनाव", "निर्वाचन", "laxmikanth", "लक्ष्मीकांत", "dpsp", "नीति निदेशक",
                    "emergency", "आपातकाल", "writ", "रिट", "cabinet", "मंत्रिमंडल", "prime minister", "प्रधानमंत्री",
                    "bill", "विधेयक", "speaker", "स्पीकर", "citizenship", "नागरिकता", "schedule", "अनुसूची",
                ),
            ),
            Lexicon(
                aliases = listOf("history", "इतिहास", "itihas", "art and culture", "culture", "संस्कृति"),
                keywords = listOf(
                    "king", "kings", "kingdom", "kingdoms", "emperor", "emperors", "ruler", "rulers", "dynasties", "war", "wars", "battle", "battles", "colonial", "raja", "राजा", "सम्राट", "युद्ध",
                    "ashoka", "अशोक", "chola", "चोल", "vijayanagar", "विजयनगर", "maratha", "मराठा", "shivaji", "शिवाजी", "delhi sultanate",
                    "rajput", "राजपूत", "indus valley", "सिंधु घाटी", "civilization", "civilisation", "सभ्यता", "mahajanapada", "महाजनपद",
                    "freedom fighter", "स्वतंत्रता सेनानी", "nehru", "नेहरू", "subhash", "सुभाष", "bhagat singh", "भगत सिंह", "partition", "विभाजन",
                    "non cooperation", "असहयोग", "civil disobedience", "सविनय अवज्ञा", "quit india", "भारत छोड़ो", "swadeshi", "स्वदेशी",
                    "governor general", "गवर्नर जनरल", "east india company", "ईस्ट इंडिया", "plassey", "प्लासी", "renaissance", "पुनर्जागरण",
                    "social reform", "समाज सुधार", "bhakti", "भक्ति", "sufi", "सूफी", "world history", "विश्व इतिहास", "french revolution",
                    "फ्रांसीसी क्रांति", "industrial revolution", "औद्योगिक क्रांति", "revolution", "क्रांति", "kranti", "world war", "विश्व युद्ध",
                    "cold war", "शीत युद्ध", "colonialism", "उपनिवेशवाद", "classical dance", "dance", "नृत्य", "sculpture", "मूर्तिकला",
                    "stupa", "स्तूप", "cave", "गुफा", "nitin singhania", "british rule", "ब्रिटिश शासन", "mughals", "sangam", "संगम",
                    "national movement", "राष्ट्रीय आंदोलन", "freedom movement", "independence", "आजादी", "स्वतंत्रता संग्राम",
                    "mughal", "मुगल", "akbar", "अकबर", "british", "ब्रिटिश", "angrez", "अंग्रेज", "gandhi", "गांधी",
                    "freedom struggle", "स्वतंत्रता", "आंदोलन", "andolan", "movement", "harappa", "हड़प्पा", "हडप्पा", "vedic", "वैदिक",
                    "maurya", "मौर्य", "gupta", "गुप्त", "revolt", "विद्रोह", "1857", "congress", "कांग्रेस", "medieval",
                    "मध्यकालीन", "ancient", "प्राचीन", "modern india", "आधुनिक भारत", "spectrum", "bipin", "बिपिन", "sultanate",
                    "सल्तनत", "buddhism", "बौद्ध", "jainism", "जैन", "temple", "मंदिर", "viceroy", "वायसराय", "dynasty", "वंश",
                    "empire", "साम्राज्य", "painting", "चित्रकला", "architecture", "स्थापत्य",
                ),
            ),
            Lexicon(
                aliases = listOf("geography", "भूगोल", "bhugol"),
                keywords = listOf(
                    "rain", "weather", "मौसम", "hills", "valley", "valleys", "sea", "seas", "plains", "मैदान",
                    "physical geography", "human geography", "world geography", "crops", "फसल", "irrigation", "सिंचाई", "drainage", "अपवाह",
                    "rocks", "चट्टान", "landforms", "स्थलाकृति", "winds", "पवन", "ocean currents", "currents", "धाराएं", "tides", "ज्वार",
                    "population", "जनसंख्या", "census", "जनगणना", "urbanisation", "urbanization", "नगरीकरण", "settlement", "transport",
                    "परिवहन", "resources", "संसाधन", "coal", "कोयला", "iron ore", "el nino", "la nina", "jet stream", "temperature", "तापमान",
                    "humidity", "आर्द्रता", "clouds", "बादल", "lakes", "झील", "island", "द्वीप", "coast", "तट", "western ghats", "eastern ghats",
                    "deccan", "दक्कन", "thar", "थार", "ganga", "गंगा", "brahmaputra", "ब्रह्मपुत्र", "peninsular", "प्रायद्वीपीय",
                    "interior of earth", "पृथ्वी", "solar system", "सौर मंडल", "atlas", "एटलस", "gc leong", "goh cheng leong", "landslide",
                    "भूस्खलन", "flood", "बाढ़", "drought", "सूखा", "tsunami", "सुनामी", "rivers", "नदियां", "mountains", "rajasthan",
                    "river", "नदी", "nadi", "mountain", "पर्वत", "parvat", "climate", "जलवायु", "monsoon", "मानसून", "soil",
                    "मिट्टी", "मृदा", "map", "मानचित्र", "नक्शा", "plate tectonics", "tectonic", "earthquake", "भूकंप", "volcano",
                    "ज्वालामुखी", "ocean", "महासागर", "latitude", "अक्षांश", "longitude", "देशांतर", "cyclone", "चक्रवात",
                    "rainfall", "वर्षा", "plateau", "पठार", "desert", "मरुस्थल", "glacier", "हिमनद", "atmosphere", "वायुमंडल",
                    "himalaya", "हिमालय", "continent", "महाद्वीप", "mineral", "खनिज", "ncert geography",
                ),
            ),
            Lexicon(
                aliases = listOf("economy", "economics", "अर्थव्यवस्था", "अर्थशास्त्र", "arthvyavastha", "arthashastra", "indian economy"),
                keywords = listOf(
                    "economic", "आर्थिक", "money", "मुद्रा", "banking", "बैंकिंग", "npa", "cpi", "wpi", "financial", "वित्तीय", "finance",
                    "investment", "निवेश", "fdi", "foreign direct investment", "stock market", "share market", "शेयर", "insurance", "बीमा",
                    "msme", "industry", "उद्योग", "industrial policy", "liberalisation", "उदारीकरण", "privatisation", "globalisation",
                    "वैश्वीकरण", "five year plan", "पंचवर्षीय योजना", "niti aayog", "नीति आयोग", "planning", "नियोजन", "balance of payments",
                    "भुगतान संतुलन", "currency", "rupee", "रुपया", "exchange rate", "विनिमय दर", "wto", "imf", "world bank", "विश्व बैंक",
                    "taxation", "income tax", "आयकर", "direct tax", "indirect tax", "revenue", "राजस्व", "expenditure", "public debt", "ऋण",
                    "demand", "मांग", "supply", "आपूर्ति", "national income", "राष्ट्रीय आय", "per capita", "प्रति व्यक्ति", "gnp", "msp",
                    "न्यूनतम समर्थन मूल्य", "food security", "खाद्य सुरक्षा", "pds", "inclusive growth", "समावेशी विकास", "employment",
                    "रोजगार", "mgnrega", "मनरेगा", "upi", "monetary policy", "fiscal policy", "fiscal deficit", "economy",
                    "gdp", "inflation", "मुद्रास्फीति", "महंगाई", "mehngai", "budget", "बजट", "tax", "gst", "rbi", "bank",
                    "बैंक", "monetary", "मौद्रिक", "fiscal", "राजकोषीय", "repo", "रेपो", "deficit", "घाटा", "poverty", "गरीबी",
                    "unemployment", "बेरोजगारी", "trade", "व्यापार", "export", "निर्यात", "import", "आयात", "market", "बाजार",
                    "sebi", "सेबी", "interest rate", "ब्याज दर", "growth", "संवृद्धि", "agriculture", "कृषि", "subsidy", "सब्सिडी",
                    "ramesh singh", "रमेश सिंह", "economic survey", "आर्थिक सर्वेक्षण",
                ),
            ),
            Lexicon(
                aliases = listOf("maths", "math", "mathematics", "गणित", "ganit", "quant", "quantitative", "quantitative aptitude"),
                keywords = listOf(
                    "equation", "समीकरण", "algebra", "बीजगणित", "geometry", "ज्यामिति", "triangle", "त्रिभुज", "percentage",
                    "प्रतिशत", "profit", "लाभ", "loss", "हानि", "ratio", "अनुपात", "probability", "प्रायिकता", "integral",
                    "समाकलन", "calculus", "derivative", "अवकलन", "average", "औसत", "simple interest", "compound interest",
                    "time and work", "speed", "चाल", "distance", "दूरी", "mensuration", "क्षेत्रफल", "area", "trigonometry",
                    "त्रिकोणमिति", "matrix", "आव्यूह", "questions solve", "number system", "संख्या पद्धति",
                ),
            ),
            Lexicon(
                aliases = listOf("essay", "निबंध", "nibandh", "एस्से"),
                keywords = listOf("essay writing", "निबंध लेखन", "essays", "essay", "निबंध", "nibandh", "introduction", "conclusion", "quote", "उद्धरण", "भूमिका", "निष्कर्ष"),
            ),
            Lexicon(
                aliases = listOf("ethics", "नीतिशास्त्र", "gs4", "नैतिकता"),
                keywords = listOf(
                    "right and wrong", "fairness", "kindness", "virtue", "duty",
                    "ethical", "नैतिक", "moral", "morality", "नैतिक मूल्य", "empathy", "सहानुभूति", "compassion", "करुणा", "honesty",
                    "accountability", "जवाबदेही", "transparency", "पारदर्शिता", "corruption", "भ्रष्टाचार", "conscience", "अंतरात्मा",
                    "thinkers", "philosopher", "philosophers", "दार्शनिक", "aristotle", "kant", "dilemma", "दुविधा", "ethical dilemma",
                    "public service", "लोक सेवा", "code of conduct", "आचार संहिता", "leadership", "नेतृत्व", "lexicon", "civil service values",
                    "objectivity", "impartiality", "निष्पक्षता", "tolerance", "सहिष्णुता",
                    "ethics", "नैतिकता", "naitikta", "integrity", "सत्यनिष्ठा", "case study", "केस स्टडी", "emotional intelligence",
                    "भावनात्मक बुद्धिमत्ता", "values", "मूल्य", "attitude", "अभिवृत्ति", "probity", "ईमानदारी", "aptitude",
                ),
            ),
            Lexicon(
                aliases = listOf("science", "विज्ञान", "vigyan", "science and technology", "science tech", "s t", "general science", "सामान्य विज्ञान"),
                keywords = listOf(
                    "internet", "इंटरनेट", "computer", "कंप्यूटर", "cyber", "साइबर", "cyber security", "5g", "blockchain", "quantum", "क्वांटम",
                    "nanotechnology", "नैनो", "drdo", "डीआरडीओ", "chandrayaan", "चंद्रयान", "gaganyaan", "गगनयान", "mars", "मंगलयान",
                    "moon", "चंद्रमा", "rocket", "रॉकेट", "pslv", "gslv", "genetic", "आनुवंशिक", "gene", "gene editing", "crispr",
                    "stem cell", "disease", "रोग", "बीमारी", "medicine", "दवा", "antibiotic", "immunity", "प्रतिरक्षा", "ai", "electronics",
                    "semiconductor", "सेमीकंडक्टर", "telecom", "laser", "nuclear energy", "atomic", "isro missions", "space mission",
                    "मिशन", "अंतरिक्ष मिशन", "drone", "ड्रोन", "supercomputer",
                    "physics", "भौतिकी", "chemistry", "रसायन", "biology", "जीव", "cell", "कोशिका", "dna", "space", "अंतरिक्ष",
                    "isro", "इसरो", "satellite", "उपग्रह", "technology", "तकनीक", "प्रौद्योगिकी", "nuclear", "परमाणु", "vaccine",
                    "टीका", "virus", "वायरस", "robot", "artificial intelligence", "biotechnology", "जैव प्रौद्योगिकी", "missile", "मिसाइल",
                ),
            ),
            Lexicon(
                aliases = listOf("environment", "पर्यावरण", "paryavaran", "ecology", "पारिस्थितिकी"),
                keywords = listOf(
                    "environmental", "पर्यावरणीय", "global warming", "ग्लोबल वार्मिंग", "greenhouse", "ग्रीनहाउस", "ozone", "ओजोन",
                    "conservation", "संरक्षण", "endangered", "संकटग्रस्त", "iucn", "paris agreement", "पेरिस समझौता", "kyoto",
                    "renewable energy", "renewable", "नवीकरणीय", "solar energy", "सौर ऊर्जा", "waste management", "अपशिष्ट", "plastic",
                    "air quality", "वायु गुणवत्ता", "water pollution", "जल प्रदूषण", "mangrove", "मैंग्रोव", "coral reef", "coral", "प्रवाल",
                    "biosphere reserve", "जैवमंडल", "ramsar", "रामसर", "eia", "food chain", "खाद्य श्रृंखला", "invasive species", "cites",
                    "wildlife protection", "emission", "उत्सर्जन", "net zero", "carbon credit", "sustainable development", "सतत विकास",
                    "shankar ias", "hotspot", "hotspots", "national parks", "tiger reserves", "depletion", "cop", "unfccc",
                    "biodiversity", "जैव विविधता", "climate change", "जलवायु परिवर्तन", "pollution", "प्रदूषण", "wildlife",
                    "वन्यजीव", "national park", "राष्ट्रीय उद्यान", "species", "प्रजाति", "ecosystem", "पारिस्थितिकी तंत्र",
                    "carbon", "कार्बन", "wetland", "आर्द्रभूमि", "tiger reserve", "बाघ", "forest", "वन", "जंगल", "sanctuary", "अभयारण्य",
                ),
            ),
            Lexicon(
                aliases = listOf("current affairs", "करेंट अफेयर्स", "समसामयिकी", "current events", "news", "समाचार", "ca"),
                keywords = listOf(
                    "current", "करंट", "affairs", "अफेयर्स", "daily news", "monthly", "मासिक", "magazine", "पत्रिका", "vision ias",
                    "insights", "newspaper reading", "headlines",
                    "newspaper", "अखबार", "the hindu", "indian express", "editorial", "संपादकीय", "pib", "खबर", "news",
                    "monthly magazine", "yojana", "योजना", "kurukshetra", "कुरुक्षेत्र", "scheme", "dainik", "दैनिक",
                ),
            ),
            Lexicon(
                aliases = listOf("csat", "सीसैट", "सी सैट", "सीसेट", "reasoning", "रीजनिंग", "तर्कशक्ति", "logical reasoning", "mental ability"),
                keywords = listOf(
                    "reasoning", "तर्क", "comprehension", "puzzle", "पहेली", "syllogism", "blood relation", "रक्त संबंध",
                    "series", "श्रृंखला", "coding decoding", "direction", "दिशा", "seating arrangement", "data interpretation",
                ),
            ),
            // UPSC CSAT is where maths lives, so maths talk counts as CSAT when "Maths" isn't one of the subjects.
            // Kept apart from the reasoning entry so a "Reasoning" subject doesn't pick up maths words.
            Lexicon(
                aliases = listOf("csat", "सीसैट", "सी सैट", "सीसेट"),
                keywords = listOf(
                    "maths", "math", "mathematics", "मैथ्स", "मैथ", "गणित", "ganit", "quant", "aptitude", "एप्टीट्यूड",
                    "percentage", "profit", "ratio", "average", "probability", "number system", "time and work", "time and distance",
                ),
            ),
            Lexicon(
                aliases = listOf("english", "अंग्रेजी", "angrezi", "english language"),
                keywords = listOf("grammar", "vocabulary", "tense", "idiom", "synonym", "antonym", "comprehension", "cloze", "error spotting"),
            ),
            Lexicon(
                aliases = listOf("hindi", "हिंदी", "हिन्दी", "hindi language"),
                keywords = listOf("व्याकरण", "vyakaran", "संधि", "समास", "मुहावरे", "कविता", "कहानी", "पर्यायवाची", "विलोम"),
            ),
            Lexicon(
                aliases = listOf("physics", "भौतिकी", "bhautiki", "फिजिक्स"),
                keywords = listOf(
                    "force", "बल", "motion", "गति", "newton", "न्यूटन", "electric", "विद्युत", "magnet", "चुंबक", "optics",
                    "प्रकाशिकी", "wave", "तरंग", "thermodynamics", "ऊष्मागतिकी", "momentum", "संवेग", "energy", "ऊर्जा",
                    "kinematics", "gravitation", "गुरुत्वाकर्षण", "current electricity", "circuit", "परिपथ",
                ),
            ),
            Lexicon(
                aliases = listOf("chemistry", "रसायन", "रसायन विज्ञान", "rasayan"),
                keywords = listOf(
                    "reaction", "अभिक्रिया", "organic", "कार्बनिक", "mole", "मोल", "bond", "बंध", "acid", "अम्ल", "base", "क्षार",
                    "periodic table", "आवर्त सारणी", "element", "तत्व", "compound", "यौगिक", "electron", "इलेक्ट्रॉन", "equilibrium", "साम्यावस्था",
                ),
            ),
            Lexicon(
                aliases = listOf("biology", "जीवविज्ञान", "जीव विज्ञान", "botany", "zoology"),
                keywords = listOf(
                    "cell", "कोशिका", "genetics", "आनुवंशिकी", "dna", "plant", "पौधा", "पादप", "animal", "जंतु", "human body",
                    "मानव शरीर", "heart", "हृदय", "blood", "रक्त", "photosynthesis", "प्रकाश संश्लेषण", "evolution", "क्रम विकास",
                    "reproduction", "जनन", "enzyme", "एंजाइम",
                ),
            ),
            Lexicon(
                aliases = listOf("general awareness", "general knowledge", "gk", "जीके", "जी के", "सामान्य ज्ञान", "static gk", "state gk", "banking awareness"),
                keywords = listOf(
                    "capital", "राजधानी", "award", "पुरस्कार", "sports", "खेल", "author", "लेखक", "headquarters", "मुख्यालय",
                    "static gk", "first in india", "दिवस", "important days",
                ),
            ),
            Lexicon(
                aliases = listOf("child development", "बाल विकास", "cdp", "pedagogy", "शिक्षाशास्त्र"),
                keywords = listOf("piaget", "पियाजे", "vygotsky", "learning", "अधिगम", "child", "बालक", "pedagogy", "inclusive", "समावेशी"),
            ),
            Lexicon(
                aliases = listOf("answer writing", "उत्तर लेखन", "mains", "मुख्य परीक्षा"),
                keywords = listOf("answer writing", "उत्तर लेखन", "word limit", "mains answer", "उत्तर लिखा", "answers likhe"),
            ),
            Lexicon(
                aliases = listOf("revision", "रिवीजन", "दोहराव"),
                keywords = listOf("revision", "revise", "रिवीजन", "रिविजन", "दोहराया", "दोहराव", "short notes"),
            ),
            Lexicon(
                aliases = listOf("practice", "mock test", "test series", "अभ्यास", "टेस्ट"),
                keywords = listOf("mock", "test", "टेस्ट", "pyq", "previous year", "पिछले साल", "practice", "अभ्यास", "mcq"),
            ),
        )
    }
}
