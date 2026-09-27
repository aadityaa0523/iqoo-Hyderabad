package app.nadaka

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickLaunchTest {
    @Test fun threeQuickPressesOpen() {
        val t = LongArray(3) { -1_000_000L }
        assertFalse(TriplePress.add(t, 1000)); assertFalse(TriplePress.add(t, 1300))
        assertTrue(TriplePress.add(t, 1700))
        assertFalse(TriplePress.add(t, 1900)) // a fourth press right after doesn't open it again
    }

    @Test fun slowPressesAreJustVolume() {
        val t = LongArray(3) { -1_000_000L }
        TriplePress.add(t, 0); TriplePress.add(t, 900)
        assertFalse(TriplePress.add(t, 2000)) // 2 s for three presses: normal volume changes
    }
}
