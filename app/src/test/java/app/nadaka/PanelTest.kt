package app.nadaka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelTest {
    @Test fun doorNeedsTwoAgreeingLooks() {
        val f = SceneFinder("door", 0)
        assertEquals(null to false, f.answer(1000, "yes, right, closed"))
        assertEquals("Door on your right, closed." to true, f.answer(3500, "Yes, there is a door on the right, closed."))
    }

    @Test fun disagreeingOrEmptyLooksKeepSearchingThenGiveUp() {
        val f = SceneFinder("exit", 0)
        f.answer(1000, "yes, on the left")
        assertEquals(null to false, f.answer(3500, "yes, on the right")) // changed side: not trusted yet
        assertEquals(null to false, f.answer(6000, "not visible"))
        assertTrue(f.answer(Settings.sceneFindMaxMs + 1, "not visible").second)
    }

    @Test fun liftButtonsTopToBottom() {
        // Two columns of buttons: 4 5 / 2 3 / G 1 (pixel positions as OCR reports them).
        val w = listOf(Word("5", 300, 100, 40), Word("4", 100, 102, 40), Word("3", 300, 200, 40), Word("2", 100, 198, 40),
            Word("1", 300, 300, 40), Word("G", 100, 302, 40), Word("OTIS", 150, 20, 20))
        assertEquals("Lift buttons, top to bottom: 4, 5, 2, 3, ground, 1.", Panel.liftButtons(w))
    }

    @Test fun roomNumberIsTheBigNumber() {
        assertEquals("Number 204.", Panel.roomNumber(listOf(Word("204", 50, 50, 80), Word("12", 10, 300, 12), Word("MEETING", 50, 10, 20))))
        assertNull(Panel.roomNumber(listOf(Word("EXIT", 0, 0, 40))))
        assertTrue(Panel.isRoomQuestion("what's the room number"))
    }
}
