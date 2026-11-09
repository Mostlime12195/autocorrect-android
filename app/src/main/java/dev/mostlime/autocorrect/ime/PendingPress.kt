package dev.mostlime.autocorrect.ime

import dev.mostlime.autocorrect.engine.Touch

/**
 * A character key press waits here until its gesture is decided, so nothing lands in the editor that has to be taken
 * back: a tap types on release, or as soon as another key goes down (overlapping presses keep their order, like
 * Gboard); a press that turns into a swipe, a long-press popup or a cancelled touch never types.
 * At most one press is waiting: every new key down types the waiting one first.
 */
class PendingPress(private val type: (KeyDef, Touch) -> Unit) {
    private class P(val pointer: Int, val key: KeyDef, val touch: Touch)
    private var p: P? = null

    /** pointer id of the waiting press, -1 if none */
    val pointer get() = p?.pointer ?: -1
    val key: KeyDef? get() = p?.key
    val touch: Touch? get() = p?.touch

    /** a character key went down (call [flush] first if another key went down) */
    fun down(pointer: Int, key: KeyDef, touch: Touch) { flush(); p = P(pointer, key, touch) }

    /** the waiting press is a tap after all (another key went down): type it now */
    fun flush() { val q = p ?: return; p = null; type(q.key, q.touch) }

    /** the pointer went up: a waiting press of it is a tap. true if it typed */
    fun up(pointer: Int): Boolean { if (p?.pointer != pointer) return false; flush(); return true }

    /** the pointer's press became a swipe / long press, or was cancelled: it never types. true if one was waiting */
    fun drop(pointer: Int): Boolean { if (p?.pointer != pointer) return false; p = null; return true }

    fun clear() { p = null }
}
