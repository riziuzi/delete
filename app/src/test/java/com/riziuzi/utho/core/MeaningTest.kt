package com.riziuzi.utho.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class LearningTest {
    @Test fun everydayWordsAreNotLearned() =
        assertEquals(listOf("political", "parties", "pressure", "groups"), Learning.contentWords("Aaj maine political parties aur pressure groups padhe"))

    @Test fun aWordNeedsTwoNotesMostlyInOneSubject() {
        val counts = mapOf(
            "Polity" to mapOf("lbsnaa" to 1, "parties" to 3, "laxmikanth" to 2),
            "Ethics" to mapOf("parties" to 2, "lbsnaa" to 1),
        )
        val learned = Learning.learnedWords(counts)
        assertEquals(listOf("laxmikanth"), learned["Polity"]) // parties: 3 of 5 is not "mostly"; lbsnaa: only once
        assertNull(learned["Ethics"])
    }

    @Test fun learnedWordsCountForTheSubject() {
        val c = TopicClassifier(listOf("Polity", "Optional"), learned = mapOf("Optional" to listOf("kinship")))
        assertEquals("Optional", c.classify("kinship systems padhe").best)
    }
}

/**
 * The on-device meaning model with the real table from assets (skipped if the table isn't there).
 * Three phrase sets: the ones the word lists were built from, new phrasing, and phrasing with no list words at all.
 */
class SubjectMeaningTest {
    private val upsc = listOf("Polity", "History", "Geography", "Economy", "Environment", "Science & Tech", "Ethics", "Essay", "CSAT", "Current affairs")

    private val meaning: Meaning? by lazy {
        listOfNotNull(System.getProperty("meaning.dir"), "src/main/assets/meaning", "app/src/main/assets/meaning")
            .map { File(it) }.firstOrNull { File(it, "vectors.bin").exists() }
            ?.let { Meaning.load(File(it, "words.txt").inputStream(), File(it, "vectors.bin").inputStream()) }
    }

    private fun classifier(): TopicClassifier {
        assumeTrue("meaning table not built", meaning != null)
        return TopicClassifier(upsc, meaning = meaning)
    }

    private fun score(c: TopicClassifier, cases: List<Pair<String?, String>>): Int =
        cases.count { (want, s) -> c.classify("$s ${Hinglish.convert(s)}").best == want }

    @Test fun politicalPartiesIsPolity() {
        val c = classifier()
        assertEquals("Polity", c.classify("I studied political parties").best)
        assertEquals("Polity", c.classify("राजनीतिक दल पढ़े rajnitik dal padhe").best)
        assertEquals("Polity", c.classify("the speaker and the house").best)
    }

    @Test fun everydayTalkGetsNoSubject() {
        val c = classifier()
        for (s in listOf("made tea", "played cricket", "nothing much", "do ghante padhai ki", "mood accha hai", "birthday party mein gaya")) {
            assertNull(s, c.classify(s).best)
        }
    }

    @Test fun builtPhrases() = assertTrue(score(classifier(), BUILT) >= BUILT.size - 1)

    @Test fun newPhrasing() = assertTrue(score(classifier(), HELD_OUT) >= HELD_OUT.size - 2)

    @Test fun phrasingWithNoListWords() {
        val c = classifier()
        val words = TopicClassifier(upsc)
        val hybrid = score(c, UNSEEN)
        assertTrue("hybrid $hybrid", hybrid >= UNSEEN.size - 2)
        assertTrue(hybrid > score(words, UNSEEN))
    }

    companion object {
        val BUILT: List<Pair<String?, String>> = listOf(

 "Polity" to "I studied political parties", "Polity" to "political parties padhi", "Polity" to "मैंने पॉलिटिकल पार्टीज पढ़ी",
 "Polity" to "राजनीतिक दल पढ़े", "Polity" to "anti defection law padha", "Polity" to "election commission aur voting",
 "Polity" to "panchayati raj and local government", "Polity" to "fundamental duties revise kiye", "Polity" to "judicial review and basic structure",
 "Polity" to "centre state relations", "Polity" to "राष्ट्रपति की शक्तियां पढ़ी", "Polity" to "पंचायती राज पढ़ा", "Polity" to "laxmikanth ke 3 chapter",
 "Polity" to "parliamentary committees padhi", "Polity" to "governor ki powers", "Polity" to "RTI and governance",
 "History" to "ashoka aur maurya empire", "History" to "भक्ति आंदोलन और सूफी", "History" to "quit india movement padha",
 "History" to "french revolution padhi", "History" to "world war 2", "History" to "indus valley civilization",
 "History" to "delhi sultanate aur vijayanagar", "History" to "1857 ki kranti", "History" to "art and culture mein classical dance",
 "History" to "मराठा और शिवाजी", "History" to "british rule aur governor generals", "History" to "स्वतंत्रता संग्राम पढ़ा",
 "Geography" to "plate tectonics aur earthquake", "Geography" to "indian rivers padhi", "Geography" to "el nino and monsoon",
 "Geography" to "मानचित्र पर नदियां", "Geography" to "western ghats aur deccan plateau", "Geography" to "ocean currents aur tides",
 "Geography" to "population and urbanisation", "Geography" to "crops and irrigation in india", "Geography" to "jet stream padha",
 "Geography" to "भूकंप और ज्वालामुखी", "Geography" to "gc leong ke chapters", "Geography" to "interior of earth",
 "Economy" to "inflation aur repo rate", "Economy" to "budget aur fiscal deficit", "Economy" to "banking aur NPA",
 "Economy" to "balance of payments", "Economy" to "मौद्रिक नीति पढ़ी", "Economy" to "niti aayog aur five year plans",
 "Economy" to "gdp and national income", "Economy" to "msp aur food security", "Economy" to "ramesh singh ka chapter",
 "Economy" to "foreign direct investment", "Economy" to "इकोनॉमी में बैंकिंग",
 "Environment" to "climate change aur paris agreement", "Environment" to "biodiversity hotspots", "Environment" to "ozone depletion",
 "Environment" to "national parks aur tiger reserves", "Environment" to "renewable energy", "Environment" to "wetlands and ramsar sites",
 "Environment" to "pollution aur waste management", "Environment" to "पर्यावरण संरक्षण", "Environment" to "mangroves aur coral reefs",
 "Science & Tech" to "chandrayaan aur isro missions", "Science & Tech" to "biotechnology aur gene editing", "Science & Tech" to "artificial intelligence",
 "Science & Tech" to "nuclear energy", "Science & Tech" to "vaccines and immunity", "Science & Tech" to "semiconductor aur 5g",
 "Science & Tech" to "अंतरिक्ष मिशन पढ़े", "Science & Tech" to "cyber security",
 "Ethics" to "ethics case study likhi", "Ethics" to "emotional intelligence", "Ethics" to "integrity aur probity",
 "Ethics" to "moral thinkers aur philosophers", "Ethics" to "lexicon padha ethics ka", "Ethics" to "ethical dilemma",
 "Ethics" to "corruption aur transparency", "Ethics" to "नैतिकता पढ़ी",
 "Essay" to "essay likha", "Essay" to "निबंध लिखा", "Essay" to "essay ka introduction",
 "CSAT" to "comprehension passages", "CSAT" to "मैथ्स पढ़ लिया", "CSAT" to "reasoning puzzles", "CSAT" to "percentage aur ratio ke questions",
 "Current affairs" to "aaj ka newspaper padha", "Current affairs" to "the hindu editorial", "Current affairs" to "monthly current affairs magazine",
 "Current affairs" to "pib aur yojana", "Current affairs" to "करंट अफेयर्स पढ़े",
 null to "do ghante padhai ki", null to "mood accha hai", null to "thak gaya hoon", null to "kuch nahi kiya",
 null to "दो घंटे पढ़ाई की", null to "आज मेहनत की", null to "पांच मिनट का ब्रेक", null to "birthday party mein gaya",
        )
        val HELD_OUT: List<Pair<String?, String>> = listOf(

 "Polity" to "studied how bills become laws", "Polity" to "governor vs chief minister powers", "Polity" to "how the cabinet works",
 "Polity" to "right to equality", "Polity" to "राज्यसभा के चुनाव", "Polity" to "मौलिक अधिकार", "Polity" to "role of opposition in democracy",
 "Polity" to "studied political parties and pressure groups", "Polity" to "vote and elections in india", "Polity" to "courts and judges",
 "History" to "ancient temples and architecture", "History" to "medieval kingdoms", "History" to "colonial rule in india",
 "History" to "harappan cities", "History" to "kings and dynasties", "History" to "british policies in india", "History" to "राजाओं का इतिहास",
 "History" to "emperors and their wars", "History" to "gandhi and nehru", "History" to "ancient india",
 "Geography" to "types of soils", "Geography" to "monsoon winds", "Geography" to "landforms made by rivers", "Geography" to "climate zones of the world",
 "Geography" to "mountain ranges", "Geography" to "जलवायु और मौसम", "Geography" to "tectonic plates", "Geography" to "deserts and oceans",
 "Economy" to "money supply and banks", "Economy" to "government budget", "Economy" to "prices rising", "Economy" to "unemployment rate",
 "Economy" to "taxes and gst", "Economy" to "share market basics", "Economy" to "महंगाई और बेरोजगारी", "Economy" to "fiscal deficit",
 "Environment" to "forests and wildlife", "Environment" to "endangered animals", "Environment" to "air pollution in delhi",
 "Environment" to "global warming effects", "Environment" to "protected areas", "Environment" to "जंगल और जानवर", "Environment" to "ozone layer depletion",
 "Science & Tech" to "space missions", "Science & Tech" to "how vaccines work", "Science & Tech" to "computers and internet",
 "Science & Tech" to "robots and ai", "Science & Tech" to "rocket launch", "Science & Tech" to "chandrayaan mission", "Science & Tech" to "dna and genes",
 "Ethics" to "honesty in public life", "Ethics" to "moral values", "Ethics" to "case studies on corruption", "Ethics" to "empathy and compassion",
 "CSAT" to "logical puzzles", "CSAT" to "reading comprehension", "CSAT" to "data interpretation", "CSAT" to "maths problems",
 "Current affairs" to "today's news", "Current affairs" to "editorials", "Current affairs" to "government schemes in news",
 "Essay" to "wrote an essay", "Essay" to "essay practice",
 null to "took a break", null to "had lunch", null to "slept for an hour", null to "kuch nahi padha", null to "feeling tired",
 null to "phone use kiya", null to "dost se baat ki", null to "watched a movie", null to "went for a walk", null to "मैं थक गया",
        )
        val UNSEEN: List<Pair<String?, String>> = listOf(

 "Polity" to "how a bill is passed", "Polity" to "the speaker and the house", "Polity" to "ministers and their responsibility",
 "History" to "kings of the south", "History" to "the mutiny against the company", "History" to "old civilisations and their cities",
 "Geography" to "why it rains", "Geography" to "seas and islands", "Geography" to "hills and valleys",
 "Economy" to "why prices go up", "Economy" to "how banks lend money", "Economy" to "jobs and wages",
 "Environment" to "saving tigers and elephants", "Environment" to "trees and forests", "Environment" to "smoke and dirty air",
 "Science & Tech" to "satellites in orbit", "Science & Tech" to "how computers think", "Science & Tech" to "germs and medicines",
 "Ethics" to "being honest at work", "Ethics" to "right and wrong choices", "Ethics" to "kindness and fairness",
 null to "made tea", null to "talked to mom", null to "nothing much", null to "played cricket", null to "watched youtube",
        )
    }
}
