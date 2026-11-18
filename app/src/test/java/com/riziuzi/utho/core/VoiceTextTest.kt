package com.riziuzi.utho.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the Hindi recogniser writes is Devanagari, even for English words. These cover that path end to end. */
class HinglishTest {
    private fun h(s: String) = Hinglish.convert(s)

    @Test fun mixedSentence() =
        assertEquals("aaj maine polity ke fundamental rights padhe aur mood accha hai", h("आज मैंने पॉलिटी के फंडामेंटल राइट्स पढ़े और मूड अच्छा है"))

    @Test fun englishLoanwordsComeBackInEnglish() {
        assertEquals("aaj maine maths ke question solve kiye", h("आज मैंने मैथ्स के क्वेश्चन सॉल्व किए"))
        assertEquals("CSAT ke reasoning question kiye", h("सीसैट के रीजनिंग क्वेश्चन किए"))
        assertEquals("science and technology mein space mission padhe", h("साइंस एंड टेक्नोलॉजी में स्पेस मिशन पढ़े"))
    }

    @Test fun hindiWordsAreTransliterated() {
        assertEquals("samvidhan ke anucched 14 se 18 dohrae", h("संविधान के अनुच्छेद 14 से 18 दोहराए"))
        assertEquals("geography mein monsoon aur nadiyan padhi", h("ज्योग्राफी में मानसून और नदियां पढ़ी"))
    }

    @Test fun punctuationAndHyphens() {
        assertEquals("theek-thaak raha.", h("ठीक-ठाक रहा।"))
        assertEquals("kya maine revision kiya?", h("क्या मैंने रिवीजन किया?"))
    }

    @Test fun nuktaVariantsMatch() = assertEquals(h("न्यूज़पेपर"), h("न्यूजपेपर"))

    @Test fun latinTextIsUntouched() = assertEquals("Read Laxmikanth today", h("Read Laxmikanth today"))
}

class MoodTest {
    private fun m(s: String) = Moods.detect(s)

    @Test fun plainMoods() {
        assertEquals(1, m("mood accha hai"))
        assertEquals(2, m("mood theek hai"))
        assertEquals(3, m("aaj mehnat ki lekin thak gaya hoon"))
        assertEquals(4, m("bekar din tha"))
        assertEquals(0, m("I feel great today"))
    }

    @Test fun intensifierLiftsGood() = assertEquals(0, m("bahut accha laga"))
    @Test fun bahutBadhiya() = assertEquals(0, m("do ghante padhai ki bahut badhiya laga"))

    @Test fun negation() {
        assertEquals(4, m("mood accha nahi hai"))
        assertEquals(4, m("not good"))
        assertEquals(4, m("मूड अच्छा नहीं है"))
    }

    @Test fun phrases() {
        assertEquals(4, m("mood off hai kuch nahi hua"))
        assertEquals(2, m("theek-thaak raha aaj"))
    }

    @Test fun fillerAcchaDoesNotWinOverRealMood() = assertEquals(3, m("accha to maine polity padhi aur phir bore ho gaya"))

    @Test fun devanagariToo() = assertEquals(3, m("थका हुआ हूं"))

    @Test fun nothingSaid() {
        assertNull(m("polity padhi"))
        assertNull(m(""))
    }

    @Test fun labels() {
        assertEquals("Good", Moods.label(1, Lang.EN))
        assertEquals("Thaka hua", Moods.label(3, Lang.HI))
    }
}

class PhoneticTest {
    @Test fun loanwordSoundsLikeEnglish() {
        assertEquals(Phonetic.key("polity"), Phonetic.key("पॉलिटी"))
        assertEquals(Phonetic.key("history"), Phonetic.key("हिस्ट्री"))
    }

    @Test fun closeNeedsLongKeys() {
        assertTrue(Phonetic.close(Phonetic.key("ज्योग्राफी"), Phonetic.key("geography")))
        assertFalse(Phonetic.close("gnt", "mnt"))
    }
}

/** UPSC subjects, spoken the way the Hindi recogniser writes them. */
class SpokenTopicTest {
    private val c = TopicClassifier(
        listOf("Polity", "History", "Geography", "Economy", "Environment", "Science & Tech", "Ethics", "Essay", "CSAT", "Current affairs", "Maths"),
    )

    /** Same input the app uses: original Devanagari plus its Hinglish. */
    private fun best(spoken: String) = c.classify("$spoken ${Hinglish.convert(spoken)}").best

    @Test fun polity() = assertEquals("Polity", best("आज मैंने पॉलिटी के फंडामेंटल राइट्स पढ़े और मूड अच्छा है"))
    @Test fun maths() = assertEquals("Maths", best("आज मैंने मैथ्स के क्वेश्चन सॉल्व किए"))
    @Test fun history() = assertEquals("History", best("मैंने हिस्ट्री में मुगल एंपायर पढ़ा"))
    @Test fun geography() = assertEquals("Geography", best("ज्योग्राफी में मानसून और नदियां पढ़ी"))
    @Test fun economy() = assertEquals("Economy", best("इकोनॉमी में इन्फ्लेशन और रेपो रेट पढ़ा"))
    @Test fun essay() = assertEquals("Essay", best("एस्से लिखा क्लाइमेट चेंज पर"))
    @Test fun csat() = assertEquals("CSAT", best("सीसैट के रीजनिंग क्वेश्चन किए"))
    @Test fun currentAffairs() = assertEquals("Current affairs", best("करंट अफेयर्स का न्यूज़पेपर पढ़ा"))
    @Test fun environment() = assertEquals("Environment", best("एनवायरमेंट में बायोडायवर्सिटी और क्लाइमेट चेंज"))
    @Test fun ethics() = assertEquals("Ethics", best("एथिक्स के केस स्टडी लिखे"))
    @Test fun scienceTech() = assertEquals("Science & Tech", best("साइंस एंड टेक्नोलॉजी में स्पेस मिशन पढ़े"))
    @Test fun pureHindiPolity() = assertEquals("Polity", best("संविधान के अनुच्छेद 14 से 18 दोहराए"))

    /** Everyday Hindi words that sound a bit like subject words must not pick a subject. */
    @Test fun commonWordsDoNotMisfire() {
        assertNull(best("दो घंटे पढ़ाई की बहुत बढ़िया लगा"))
        assertNull(best("आज मेहनत की लेकिन थक गया हूं"))
        assertNull(best("पांच मिनट का ब्रेक लिया"))
        assertNull(best("मूड ऑफ है कुछ नहीं हुआ"))
    }

    @Test fun typedHinglish() = assertEquals("Polity", best("aaj samvidhan ke fundamental rights padhe"))
}
