package dev.mostlime.autocorrect.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FixedFixesTest {
    @Test fun loneI() {
        assertEquals("I", FixedFixes.fix("i"))
        assertEquals("I'm", FixedFixes.fix("i'm"))
        assertEquals("I'll", FixedFixes.fix("i'll"))
        assertEquals("I'd", FixedFixes.fix("i'd"))
        assertEquals("I've", FixedFixes.fix("i've"))
        assertNull(FixedFixes.fix("I"))
        assertNull(FixedFixes.fix("I'm"))
    }

    @Test fun contractionsKeepTheFirstLettersCase() {
        assertEquals("don't", FixedFixes.fix("dont"))
        assertEquals("Don't", FixedFixes.fix("Dont"))
        assertEquals("I'm", FixedFixes.fix("im"))
        assertEquals("I'm", FixedFixes.fix("Im"))
        assertEquals("I've", FixedFixes.fix("ive"))
        assertEquals("You're", FixedFixes.fix("Youre"))
        assertEquals("ain't", FixedFixes.fix("aint"))
        // other case patterns are left alone
        assertNull(FixedFixes.fix("DONT"))
        assertNull(FixedFixes.fix("dOnt"))
        assertNull(FixedFixes.fix("IM"))
    }

    @Test fun everyEntryIsAnApostropheOnlyChange() {
        assertEquals(32, FixedFixes.CONTRACTIONS.size)
        for ((k, v) in FixedFixes.CONTRACTIONS) {
            assertEquals(k, v.lowercase().replace("'", ""))
            assertTrue("'" in v, v)
            assertEquals(v, FixedFixes.fix(k))
            assertEquals(v[0].uppercaseChar() + v.substring(1), FixedFixes.fix(k[0].uppercaseChar() + k.substring(1)))
        }
    }

    @Test fun realWordsAreNotInTheTable() {
        for (w in listOf("wont", "cant", "its", "lets", "id", "ill", "hell", "shell", "well", "were", "shed", "wed")) {
            assertNull(FixedFixes.fix(w), w)
            assertNull(FixedFixes.fix(w.replaceFirstChar { it.uppercaseChar() }), w)
        }
    }
}
