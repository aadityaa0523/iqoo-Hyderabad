package app.nadaka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LangTest {
    private val hi = SpeechLang.HI
    private val te = SpeechLang.TE

    @Test fun dropAndStairs() {
        assertEquals("रुकिए। आगे नीचे उतार है, 2 मीटर पर।", Say.tr("Stop. Drop ahead, 2 metres.", hi))
        assertEquals("ఆగండి. ముందు కిందకి మెట్లు ఉన్నాయి, 1.5 మీటర్ల దూరంలో. కనీసం 4 మెట్లు.",
            Say.tr("Stop. Stairs down ahead, 1.5 metres. At least 4 steps.", te))
        assertEquals("आगे ऊपर सीढ़ियाँ हैं, 2 मीटर पर। लगभग 8 सीढ़ियाँ।", Say.tr("Stairs going up ahead, 2 metres. About 8 steps.", hi))
    }

    @Test fun approachingUsesHindiGenderAndIndianNames() {
        assertEquals("बाइक पास आ रही है, 3 मीटर पर, 9 बजे की दिशा में।", Say.tr("bike approaching, 3 metres, 9 o'clock.", hi))
        assertEquals("ट्रक पास आ रहा है, 5 मीटर पर।", Say.tr("truck approaching, 5 metres.", hi))
        assertEquals("లారీ దగ్గరికి వస్తోంది, 5 మీటర్ల దూరంలో.", Say.tr("truck approaching, 5 metres.", te))
        assertEquals("bike", displayName("motorcycle"))
        assertEquals("traffic signal", displayName("traffic light"))
    }

    @Test fun severalAlertsTogetherAndDecimals() {
        assertEquals("रुकिए। उतार। सिर बचाइए।", Say.tr("Stop. Drop. Head.", hi))
        assertEquals("आगे रुकावट, 1.5 मीटर पर।", Say.tr("Obstacle ahead, 1.5 metres.", hi))
    }

    @Test fun unknownSentencesStayEnglish() {
        assertNull(Say.tr("A laptop on a desk, screen showing code.", hi))
        assertNull(Say.tr("Stop. Drop. Something new.", te)) // all or nothing
        assertNull(Say.tr("Stop. Drop ahead.", SpeechLang.EN))
        assertTrue(Say.isIndic("ఆగండి"))
    }

    @Test fun doorFinderAndBus() {
        assertEquals("Door on your right, closed.".let { Say.tr(it, hi) }, "दरवाज़ा आपकी दाईं ओर, बंद।")
        assertEquals("బస్ నంబర్ 218.", Say.tr("Bus 218.", te))
    }
}
