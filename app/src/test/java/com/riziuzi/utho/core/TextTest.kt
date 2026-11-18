package com.riziuzi.utho.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PunctuatorTest {
    @Test fun hindiStatementGetsPurnaViram() =
        assertEquals("आज मैंने संविधान पढ़ा।", Punctuator.sentence("आज मैंने संविधान पढ़ा"))

    @Test fun hindiQuestion() =
        assertEquals("क्या मैंने आज रिवीजन किया?", Punctuator.sentence("क्या मैंने आज रिवीजन किया"))

    @Test fun hindiTagQuestion() =
        assertEquals("यह अध्याय कल पूरा करना है ना?", Punctuator.sentence("यह अध्याय कल पूरा करना है ना"))

    @Test fun englishStatementCapitalised() =
        assertEquals("I read the polity chapter on fundamental rights.", Punctuator.sentence("i read the polity chapter on fundamental rights"))

    @Test fun englishQuestion() =
        assertEquals("What is article 21?", Punctuator.sentence("what is article 21"))

    @Test fun keepsExistingPunctuationAndFixesSpacing() =
        assertEquals("Done with maths. Now history?", Punctuator.sentence("done with maths .  now history ?"))

    @Test fun hinglishInLatinIsTreatedAsEnglish() =
        assertEquals("Aaj maine laxmikanth padha.", Punctuator.sentence("aaj maine laxmikanth padha"))

    @Test fun mixedMostlyHindi() =
        assertEquals("मैंने fundamental rights वाला chapter पढ़ा और नोट्स बनाए।", Punctuator.sentence("मैंने fundamental rights वाला chapter पढ़ा और नोट्स बनाए"))

    @Test fun paragraphJoinsSegments() =
        assertEquals(
            "पहले इतिहास पढ़ा। फिर भूगोल के नक्शे देखे।",
            Punctuator.paragraph(listOf("पहले इतिहास पढ़ा", " ", "फिर भूगोल के नक्शे देखे")),
        )

    @Test fun emptyStaysEmpty() = assertEquals("", Punctuator.sentence("   "))
}

class TopicClassifierTest {
    private val topics = listOf("Maths", "Polity", "History", "Geography", "Economics", "Essay")
    private val c = TopicClassifier(topics)

    private fun best(text: String) = c.classify(text).best

    @Test fun englishPolity() = assertEquals("Polity", best("Read Laxmikanth chapter on fundamental rights and writs"))
    @Test fun hindiPolity() = assertEquals("Polity", best("मैंने संविधान के अनुच्छेद 21 के बारे में पढ़ा"))
    @Test fun hinglishMaths() = assertEquals("Maths", best("profit and loss ke questions solve kiye"))
    @Test fun hindiHistory() = assertEquals("History", best("मुगल साम्राज्य और अकबर की नीतियाँ पढ़ीं"))
    @Test fun geography() = assertEquals("Geography", best("monsoon and the rivers of the Himalaya"))
    @Test fun economics() = assertEquals("Economics", best("inflation, repo rate and RBI monetary policy"))
    @Test fun hindiEssay() = assertEquals("Essay", best("आज एक निबंध लिखा"))
    @Test fun saysTopicName() = assertEquals("Geography", best("geography revision"))

    @Test fun nothingMatches() {
        val r = c.classify("kuch khaas nahi kiya")
        assertNull(r.best)
        assertFalse(r.confident)
    }

    @Test fun confidentWhenClear() = assertTrue(c.classify("Laxmikanth fundamental rights writs parliament").confident)

    @Test fun hindiTopicNamesUseBuiltInWords() {
        val hi = TopicClassifier(listOf("राजव्यवस्था", "इतिहास", "भूगोल"))
        assertEquals("राजव्यवस्था", hi.classify("parliament and lok sabha session").best)
        assertEquals("भूगोल", hi.classify("नदियाँ और पर्वत").best)
    }

    @Test fun learnsFromCorrections() {
        val withExamples = TopicClassifier(
            listOf("Polity", "Optional"),
            mapOf("Optional" to listOf("anthropology tribes and kinship systems")),
        )
        assertEquals("Optional", withExamples.classify("kinship and tribes theory").best)
    }

    @Test fun customTopicByName() {
        val custom = TopicClassifier(listOf("Polity", "Anthropology"))
        assertEquals("Anthropology", custom.classify("anthropology paper 1").best)
    }

    @Test fun scienceTechTopicMapsToScience() {
        val t = TopicClassifier(listOf("Science & Tech", "General awareness", "Economy"))
        assertEquals("Science & Tech", t.classify("ISRO satellite launch and space technology").best)
        assertEquals("General awareness", t.classify("capitals and important awards").best)
    }
}
