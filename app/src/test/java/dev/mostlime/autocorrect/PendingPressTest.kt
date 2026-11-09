package dev.mostlime.autocorrect

import dev.mostlime.autocorrect.engine.Touch
import dev.mostlime.autocorrect.ime.KeyDef
import dev.mostlime.autocorrect.ime.KeyType
import dev.mostlime.autocorrect.ime.PendingPress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The view's tap / swipe / long-press gate: character keys type only once the press is known to be a tap. */
class PendingPressTest {
    private val typed = StringBuilder()
    private val gate = PendingPress { k, _ -> typed.append(k.text) }
    private fun key(c: Char) = KeyDef(c.toString(), c.toString(), KeyType.CHAR, 0f, 1f, 0)
    private fun down(pointer: Int, c: Char) { gate.flush(); gate.down(pointer, key(c), Touch.APOS) }   // as the view does

    @Test fun tapTypesOnReleaseNotOnPress() {
        down(0, 'a')
        assertEquals("", typed.toString())
        assertTrue(gate.up(0))
        assertEquals("a", typed.toString())
        assertFalse(gate.up(0))                   // a second release never types again
        assertEquals("a", typed.toString())
    }

    @Test fun overlappingPressesKeepTheirOrder() {
        // second finger down before the first is up, and the second finger lifts first (rolling fast typing)
        down(0, 't'); down(1, 'h')
        assertEquals("t", typed.toString())       // the next press decides the first one: it was a tap
        gate.up(1); gate.up(0)
        assertEquals("th", typed.toString())
        // the usual overlap: first finger lifts first
        down(0, 'e'); down(1, ' ')
        gate.up(0); gate.up(1)
        assertEquals("the ", typed.toString())
        // three fingers down at once, released in reverse
        down(0, 'c'); down(1, 'a'); down(2, 't')
        gate.up(2); gate.up(1); gate.up(0)
        assertEquals("the cat", typed.toString())
    }

    @Test fun swipeOrLongPressNeverTypesTheKey() {
        down(0, 's')
        assertTrue(gate.drop(0))                  // became a swipe / opened a long-press popup
        assertFalse(gate.up(0))
        assertEquals("", typed.toString())
        down(0, 'e')
        assertTrue(gate.drop(0))
        down(1, 'x')                              // the next key down doesn't bring a dropped press back
        gate.up(1)
        assertEquals("x", typed.toString())
    }

    @Test fun cancelledTouchNeverTypes() {
        down(3, 'q')
        gate.drop(3)
        gate.clear()
        assertEquals(-1, gate.pointer)
        assertEquals("", typed.toString())
    }

    @Test fun dropOnlyAffectsItsOwnPointer() {
        down(0, 'a'); down(1, 'b')                // a typed when b went down
        assertFalse(gate.drop(0))
        assertEquals(1, gate.pointer)
        gate.up(1)
        assertEquals("ab", typed.toString())
    }
}
