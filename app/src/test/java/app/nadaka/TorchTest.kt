package app.nadaka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TorchTest {
    @Test fun onlyAfterASecondOfDarkness() {
        val t = TorchPolicy()
        assertNull(t.update(0, 20f))
        assertNull(t.update(500, 20f))
        assertNull(t.update(600, 90f))   // a dark flicker, then light: nothing
        assertNull(t.update(700, 20f))
        assertEquals(true, t.update(1800, 20f))
    }

    @Test fun turnsOffWhenTheRoomIsLitAgain() {
        val t = TorchPolicy()
        t.update(0, 20f); t.update(1000, 20f)                     // on at 1 s
        assertNull(t.update(5000, 120f))                          // lit by the torch itself: can't judge yet
        assertEquals(false, t.update(9000, 120f))                 // probe: torch off for a look
        assertNull(t.update(9300, 110f))                          // settling
        assertNull(t.update(9800, 110f))                          // the room alone is bright: stays off
        assertTrue(!t.on)
    }

    @Test fun backOnWhenStillDark() {
        val t = TorchPolicy()
        t.update(0, 20f); t.update(1000, 20f)
        t.update(9000, 120f)                                      // probe starts
        assertEquals(true, t.update(9800, 15f))                   // still dark without it: back on
        assertTrue(t.on)
    }

    @Test fun leavingTheAppForgets() {
        val t = TorchPolicy()
        t.update(0, 20f); t.update(1000, 20f)
        t.reset()
        assertTrue(!t.on)
        assertNull(t.update(1100, 150f))                          // bright on return: stays off
    }
}
