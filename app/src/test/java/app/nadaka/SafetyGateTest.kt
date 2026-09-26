package app.nadaka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyGateTest {
    private val safety = listOf(
        "is it safe to cross", "can I walk", "Should I go now?", "is the path clear", "anything in my way",
        "is there a car coming", "ok to move?", "can i keep walking", "is the road clear",
        "kya main chal sakta hoon", "safe hai kya", "rasta saaf hai", "aage kuch hai",
        "क्या मैं चल सकता हूँ", "सड़क पार कर सकता हूँ", "रास्ता साफ है",
        "నేను దాటవచ్చా", "ముందు ఏమైనా ఉందా",
    )
    private val notSafety = listOf("what's ahead", "read this note", "call mom", "find my keys", "tell me less", "", "how are you")

    @Test fun catchesSafetyQuestionsInThreeLanguages() {
        safety.forEach { assertTrue("missed: $it", SafetyGate.isSafetyQuestion(it)) }
    }

    @Test fun ordinaryRequestsAreNotSafetyQuestions() {
        notSafety.forEach { assertFalse("false alarm: $it", SafetyGate.isSafetyQuestion(it)) }
    }

    @Test fun safetyWinsOverOtherIntents() {
        assertEquals(Ask.SAFETY, intentOf("what's ahead, can I cross?"))
        assertEquals(Ask.DESCRIBE, intentOf("what's ahead"))
        assertEquals(Ask.READ, intentOf("read this note"))
        assertEquals(Ask.QUIET, intentOf("tell me less"))
    }

    @Test fun answerNeverGreenLights() {
        listOf(Answers.safety(emptyList()), Answers.safety(listOf("a drop-off ahead, 1 metre"))).forEach { a ->
            val t = a.lowercase()
            listOf("yes", "you can go", "it is safe", "it's safe", "go ahead", "path is clear", "all clear").forEach {
                assertFalse("green light '$it' in: $a", it in t)
            }
            assertTrue(t.contains("cane"))
        }
    }

    @Test fun hazardThatFlickeredWhileAskingIsStillReported() {
        val m = HazardMemory()
        m.record(0, Hazards(dropAtM = 1.2f), emptyList(), Health.OK)
        m.record(1000, Hazards(), emptyList(), Health.OK) // gone this frame
        assertTrue(Answers.safety(m.recent()).contains("drop-off"))
        m.record(1000 + Settings.hazardMemoryMs + 1, Hazards(), emptyList(), Health.OK)
        assertEquals(emptyList<String>(), m.recent())
    }

    @Test fun gemmaGreenLightsAreCaught() {
        listOf("The path is clear, you can go ahead.", "It's safe to cross now.", "There are no obstacles.", "The way looks clear.")
            .forEach { assertTrue("missed: $it", SafetyGate.greenLight(it)) }
        listOf("A chair at 12 o'clock, about 2 metres.", "A sign reading Exit, 1 o'clock.")
            .forEach { assertFalse("false alarm: $it", SafetyGate.greenLight(it)) }
    }

    @Test fun signIntent() {
        assertEquals(Ask.SIGN, intentOf("read the sign"))
        assertEquals(Ask.SIGN, intentOf("what does it say"))
        assertEquals(Ask.READ, intentOf("read this note"))
    }

    @Test fun commonRecognizerSlipsStillWork() {
        assertEquals(Ask.DESCRIBE, intentOf("What's a head?"))
        assertEquals(Ask.DESCRIBE, intentOf("watts ahead"))
        assertEquals(Ask.READ, intentOf("red this"))
        assertEquals(Ask.SAFETY, intentOf("is it save to cross"))
        assertEquals(Ask.DESCRIBE, intentOf("what's near me"))
    }

    @Test fun nBestPicksAMeaningfulAlternativeAndSafetyAlwaysWins() {
        assertEquals(Ask.DESCRIBE, bestIntent(listOf("hot tub", "what's ahead")).second)
        assertEquals(Ask.SAFETY, bestIntent(listOf("what's ahead", "can I cross")).second)
        assertEquals(Ask.HELP, bestIntent(listOf("banana")).second)
    }
}
