package dev.mostlime.autocorrect

import dev.mostlime.autocorrect.ime.ClipStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ClipStoreTest {
    @Test fun keepsTenUnpinnedPlusPinnedNewestFirst() {
        val s = ClipStore(null)
        s.add("keep me", 0); s.togglePin(s.items[0])
        for (i in 1..15) s.add("clip $i", i.toLong())
        assertEquals("clip 15", s.items[0].text)
        assertEquals(10, s.items.count { !it.pinned })
        assertTrue(s.items.any { it.text == "keep me" && it.pinned })
        s.clear()
        assertEquals(listOf("keep me"), s.items.map { it.text })
    }

    @Test fun offerExpiresAndIsConsumed() {
        val s = ClipStore(null)
        s.add("hello", 1000)
        assertEquals("hello", s.recentOffer(2000)?.text)
        assertNull(s.recentOffer(1000 + ClipStore.OFFER_MS + 1))
        s.consumeOffer()
        assertNull(s.recentOffer(2000))
    }

    @Test fun duplicatesMoveToTopAndPersist() {
        val f = File.createTempFile("clips", ".json"); f.delete()
        val s = ClipStore(f)
        s.add("a", 1); s.add("b", 2); s.add("a", 3)
        assertEquals(listOf("a", "b"), s.items.map { it.text })
        assertEquals(listOf("a", "b"), ClipStore(f).items.map { it.text })
        f.delete()
    }
}
