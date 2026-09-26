package app.nadaka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ParseTest {
    @Test fun money() {
        val note = "RESERVE BANK OF INDIA\n500\nI PROMISE TO PAY THE BEARER THE SUM OF FIVE HUNDRED RUPEES\n500\n2019"
        assertTrue(Parse.isMoney(note))
        assertEquals(500, Parse.denomination(note))
        assertEquals(200, Parse.denomination("RESERVE BANK OF INDIA 200 TWO HUNDRED RUPEES 200 8AB 123456"))
        assertNull(Parse.denomination("RESERVE BANK OF INDIA"))
    }

    @Test fun medicine() {
        val strip = "DOLO 650\nParacetamol Tablets IP 650 mg\nB.No. DOBS3975\nMFG 01/2025\nEXP 12/2026"
        assertTrue(Parse.isMedicine(strip))
        assertFalse(Parse.isMoney(strip))
        assertEquals("650 milligrams", Parse.strength(strip))
        assertEquals(2026 to 12, Parse.expiry(strip))
        assertEquals(2027 to 3, Parse.expiry("Exp.Date: MAR.2027"))
        assertEquals(2027 to 3, Parse.expiry("EXP 03/27"))
        assertEquals(2027 to 3, Parse.expiry("EXPIRY 03/2027"))
        assertNull(Parse.expiry("MFG 01/2025"))
        assertTrue(Parse.isMedicine("M.R.P. ₹25.00 incl. of all taxes 10 TABLETS"))
    }

    @Test fun expiryIsEndOfMonth() {
        val today = LocalDate.of(2026, 9, 26)
        assertFalse(Parse.isExpired(2026 to 9, today))
        assertTrue(Parse.isExpired(2026 to 8, today))
        assertTrue(Parse.isExpired(2024 to 3, today))
    }
}
