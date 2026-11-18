package com.riziuzi.utho.core

import kotlin.math.min
import kotlin.math.sqrt

/** Built-in words for a common subject, in English, Hindi and romanised Hindi. */
data class Lexicon(val aliases: List<String>, val keywords: List<String>)

/**
 * Picks which of the user's topics a short spoken note is about. Runs fully on the phone, instantly.
 *
 * Score per topic =
 *   3 x (topic name or a known alias of it is said)
 * + 1 x each built-in keyword of that subject that is said (max 5)
 * + 5 x similarity to notes the user previously filed under that topic (learned from corrections).
 */
class TopicClassifier(
    private val topics: List<String>,
    private val examples: Map<String, List<String>> = emptyMap(),
) {
    data class Scored(val topic: String, val score: Double)

    /** [best] is null when nothing matched. [confident] = clearly ahead of the runner-up. */
    data class Result(val best: String?, val ranked: List<Scored>, val confident: Boolean)

    private val prepared = topics.map { topic ->
        val norm = normalize(topic)
        val lex = LEXICON.filter { lx -> lx.aliases.any { aliasMatches(normalize(it), norm) } }
        val names = (listOf(norm) + lex.flatMap { it.aliases }.map { normalize(it) }).filter { it.isNotBlank() }.distinct()
        val keywords = lex.flatMap { it.keywords }.map { normalize(it) }.filter { it.isNotBlank() }.distinct()
        val ex = (examples[topic] ?: emptyList()).map { trigrams(normalize(it)) }
        Prepared(topic, names, keywords, ex)
    }

    private class Prepared(
        val topic: String,
        val names: List<String>,
        val keywords: List<String>,
        val examples: List<Map<String, Int>>,
    )

    fun classify(text: String): Result {
        val norm = normalize(text)
        if (norm.isBlank() || topics.isEmpty()) return Result(null, topics.map { Scored(it, 0.0) }, false)
        val tokens = norm.split(" ")
        val padded = " $norm "
        val grams = trigrams(norm)

        val ranked = prepared.map { p ->
            val nameHits = p.names.count { matches(it, tokens, padded) }
            val keywordHits = p.keywords.count { matches(it, tokens, padded) }
            val sim = p.examples.maxOfOrNull { cosine(grams, it) } ?: 0.0
            val exampleScore = if (sim >= 0.3) 5.0 * sim else 0.0
            Scored(p.topic, 3.0 * min(nameHits, 2) + min(keywordHits, 5) + exampleScore)
        }.sortedByDescending { it.score }

        val top = ranked.first()
        val second = ranked.getOrNull(1)?.score ?: 0.0
        if (top.score <= 0.0) return Result(null, ranked, false)
        val confident = top.score >= 1.0 && top.score >= second + 1.0
        return Result(top.topic, ranked, confident)
    }

    companion object {
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
            if (term.contains(' ')) return padded.contains(" $term ")
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
                    "constitution", "संविधान", "samvidhan", "parliament", "संसद", "sansad", "article", "अनुच्छेद", "anuched",
                    "fundamental", "मौलिक", "rights", "अधिकार", "adhikar", "president", "राष्ट्रपति", "governor", "राज्यपाल",
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
                aliases = listOf("essay", "निबंध", "nibandh"),
                keywords = listOf("essay", "निबंध", "nibandh", "introduction", "conclusion", "quote", "उद्धरण", "भूमिका", "निष्कर्ष"),
            ),
            Lexicon(
                aliases = listOf("ethics", "नीतिशास्त्र", "gs4", "नैतिकता"),
                keywords = listOf(
                    "ethics", "नैतिकता", "naitikta", "integrity", "सत्यनिष्ठा", "case study", "केस स्टडी", "emotional intelligence",
                    "भावनात्मक बुद्धिमत्ता", "values", "मूल्य", "attitude", "अभिवृत्ति", "probity", "ईमानदारी", "aptitude",
                ),
            ),
            Lexicon(
                aliases = listOf("science", "विज्ञान", "vigyan", "science and technology", "science tech", "s t", "general science", "सामान्य विज्ञान"),
                keywords = listOf(
                    "physics", "भौतिकी", "chemistry", "रसायन", "biology", "जीव", "cell", "कोशिका", "dna", "space", "अंतरिक्ष",
                    "isro", "इसरो", "satellite", "उपग्रह", "technology", "तकनीक", "प्रौद्योगिकी", "nuclear", "परमाणु", "vaccine",
                    "टीका", "virus", "वायरस", "robot", "artificial intelligence", "biotechnology", "जैव प्रौद्योगिकी", "missile", "मिसाइल",
                ),
            ),
            Lexicon(
                aliases = listOf("environment", "पर्यावरण", "paryavaran", "ecology", "पारिस्थितिकी"),
                keywords = listOf(
                    "biodiversity", "जैव विविधता", "climate change", "जलवायु परिवर्तन", "pollution", "प्रदूषण", "wildlife",
                    "वन्यजीव", "national park", "राष्ट्रीय उद्यान", "species", "प्रजाति", "ecosystem", "पारिस्थितिकी तंत्र",
                    "carbon", "कार्बन", "wetland", "आर्द्रभूमि", "tiger reserve", "बाघ", "forest", "वन", "जंगल", "sanctuary", "अभयारण्य",
                ),
            ),
            Lexicon(
                aliases = listOf("current affairs", "करेंट अफेयर्स", "समसामयिकी", "current events", "news", "समाचार", "ca"),
                keywords = listOf(
                    "newspaper", "अखबार", "the hindu", "indian express", "editorial", "संपादकीय", "pib", "खबर", "news",
                    "monthly magazine", "yojana", "योजना", "kurukshetra", "कुरुक्षेत्र", "scheme", "dainik", "दैनिक",
                ),
            ),
            Lexicon(
                aliases = listOf("csat", "reasoning", "रीजनिंग", "तर्कशक्ति", "logical reasoning", "mental ability"),
                keywords = listOf(
                    "reasoning", "तर्क", "comprehension", "puzzle", "पहेली", "syllogism", "blood relation", "रक्त संबंध",
                    "series", "श्रृंखला", "coding decoding", "direction", "दिशा", "seating arrangement", "data interpretation",
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
                aliases = listOf("physics", "भौतिकी", "bhautiki"),
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
                aliases = listOf("general awareness", "general knowledge", "gk", "सामान्य ज्ञान", "static gk", "state gk", "banking awareness"),
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
                keywords = listOf("revision", "revise", "रिवीजन", "दोहराया", "दोहराव", "short notes"),
            ),
            Lexicon(
                aliases = listOf("practice", "mock test", "test series", "अभ्यास", "टेस्ट"),
                keywords = listOf("mock", "test", "टेस्ट", "pyq", "previous year", "पिछले साल", "practice", "अभ्यास", "mcq"),
            ),
        )
    }
}
