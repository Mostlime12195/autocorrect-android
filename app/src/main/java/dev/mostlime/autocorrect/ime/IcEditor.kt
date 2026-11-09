package dev.mostlime.autocorrect.ime

import android.content.Context
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * Editor over an InputConnection (the keyboard's real text field). Typo marks are SuggestionSpans (misspelled +
 * easy-correct) committed with the text, so apps that keep spans show the red underline and their fix menu.
 */
class IcEditor(
    private val ctx: Context,
    private val icp: () -> InputConnection?,
    private val info: () -> EditorInfo?,
    private val sendKey: (Int) -> Unit,
    private val sendChar: (Char) -> Unit,
    /** cursor from onUpdateSelection, for apps that don't give extracted text (-1 = unknown) */
    private val selFallback: () -> Int = { -1 },
) : Editor {
    private val ic get() = icp()
    override fun textBefore(n: Int) = ic?.getTextBeforeCursor(n, 0)?.toString() ?: ""
    override fun textAfter(n: Int) = ic?.getTextAfterCursor(n, 0)?.toString() ?: ""
    override fun cursorPos(): Int {
        val et = ic?.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0) ?: return selFallback()
        return if (et.selectionStart < 0) selFallback() else et.startOffset + et.selectionStart
    }
    /**
     * Replace len chars at cursor + offset. Composing region + commit, then the result is checked against the text
     * before the cursor; if the field didn't take it (React Native inputs can be flaky with regions), the span from the
     * word to the cursor is deleted and typed again with the fix (cursor-relative, no absolute offsets needed).
     */
    override fun replaceRange(offset: Int, len: Int, text: String) {
        val c = ic ?: return
        val cur = cursorPos().takeIf { it >= 0 } ?: return
        val a = cur + offset; val b = a + len
        if (a < 0) return
        if (b <= cur) {
            val span = c.getTextBeforeCursor(cur - a, 0)?.toString() ?: return
            if (span.length != cur - a) return
            val old = span.substring(0, len)
            val after = span.substring(len)
            c.beginBatchEdit()
            try { if (c.setComposingRegion(a, b)) { c.commitText(text, 1); c.setSelection(cur + text.length - len, cur + text.length - len) } }
            finally { c.endBatchEdit() }
            if (c.getTextBeforeCursor(text.length + after.length, 0)?.toString() == text + after) return
            // fallback: the field still shows the old word (or a mix): retype from the word to the cursor
            val now = c.getTextBeforeCursor(old.length + after.length, 0)?.toString()
            if (now == old + after) {
                c.beginBatchEdit()
                try { c.finishComposingText(); c.deleteSurroundingText(old.length + after.length, 0); c.commitText(text + after, 1) }
                finally { c.endBatchEdit() }
            }
            return
        }
        val newCur = when { a >= cur -> cur; else -> a + text.length }
        c.beginBatchEdit()
        try { if (c.setComposingRegion(a, b)) { c.commitText(text, 1); c.setSelection(newCur, newCur) } } finally { c.endBatchEdit() }
    }
    override fun moveCursor(dir: String) {
        sendKey(when (dir) { "L" -> KeyEvent.KEYCODE_DPAD_LEFT; "R" -> KeyEvent.KEYCODE_DPAD_RIGHT; "U" -> KeyEvent.KEYCODE_DPAD_UP; else -> KeyEvent.KEYCODE_DPAD_DOWN })
    }
    /** same text, fresh SuggestionSpans: replace [cursor + offset, + len) and put the cursor back */
    override fun remark(offset: Int, text: String, marks: List<TypoMark>) {
        val c = ic ?: return
        val cur = cursorPos().takeIf { it >= 0 } ?: return
        val a = cur + offset
        if (a < 0) return
        val ss = android.text.SpannableString(text)
        for (m in marks) {
            if (m.start < 0 || m.start + m.len > text.length) continue
            ss.setSpan(android.text.style.SuggestionSpan(ctx, m.suggestions.take(5).toTypedArray(),
                android.text.style.SuggestionSpan.FLAG_MISSPELLED or android.text.style.SuggestionSpan.FLAG_EASY_CORRECT),
                m.start, m.start + m.len, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        c.beginBatchEdit()
        try {
            if (c.setComposingRegion(a, a + text.length)) { c.commitText(ss, 1); c.setSelection(cur, cur) }
        } finally { c.endBatchEdit() }
    }
    override fun hasSelection() = !ic?.getSelectedText(0).isNullOrEmpty()
    override fun setComposing(text: String) { ic?.setComposingText(text, 1) }
    override fun finishComposing() { ic?.finishComposingText() }
    override fun commit(text: String) { ic?.commitText(text, 1) }
    override fun deleteBefore(n: Int) { ic?.deleteSurroundingText(n, 0) }
    /** one replace (composing region over the span + commit) instead of a visible delete then insert */
    override fun replaceBefore(n: Int, text: String) {
        val c = ic ?: return
        val et = c.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
        if (et != null && et.selectionStart == et.selectionEnd && et.selectionStart >= 0) {
            val end = et.startOffset + et.selectionStart
            if (end - n >= 0 && c.setComposingRegion(end - n, end)) { c.commitText(text, 1); return }
        }
        c.deleteSurroundingText(n, 0); c.commitText(text, 1)
    }
    /** a likely typo: SuggestionSpan (misspelled + easy-correct) so apps that support it underline it with a fix menu */
    override fun replaceBefore(n: Int, text: String, mark: TypoMark?) {
        if (mark == null) { replaceBefore(n, text); return }
        val ss = android.text.SpannableString(text)
        val span = android.text.style.SuggestionSpan(ctx, mark.suggestions.take(5).toTypedArray(),
            android.text.style.SuggestionSpan.FLAG_MISSPELLED or android.text.style.SuggestionSpan.FLAG_EASY_CORRECT)
        ss.setSpan(span, mark.start, mark.start + mark.len, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val c = ic ?: return
        val et = c.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
        if (et != null && et.selectionStart == et.selectionEnd && et.selectionStart >= 0) {
            val end = et.startOffset + et.selectionStart
            if (end - n >= 0 && c.setComposingRegion(end - n, end)) { c.commitText(ss, 1); return }
        }
        c.deleteSurroundingText(n, 0); c.commitText(ss, 1)
    }
    override fun beginBatch() { ic?.beginBatchEdit() }
    override fun endBatch() { ic?.endBatchEdit() }
    override fun deleteKey(): Int {
        val c = ic ?: return -1
        if (!c.getSelectedText(0).isNullOrEmpty()) { c.commitText("", 1); return -1 }
        val b = c.getTextBeforeCursor(32, 0)?.toString() ?: ""
        if (b.isEmpty()) { sendKey(KeyEvent.KEYCODE_DEL); return -1 }
        // one user-perceived character (an emoji with modifiers / ZWJ sequence counts as one)
        val it = android.icu.text.BreakIterator.getCharacterInstance()
        it.setText(b)
        val start = it.preceding(b.length).coerceAtLeast(0)
        val n = (b.length - start).coerceAtLeast(1)
        c.deleteSurroundingText(n, 0)
        return n
    }
    override fun enter() { sendChar('\n') }
    override fun capsMode(): Boolean {
        val ei = info() ?: return false
        return (ic?.getCursorCapsMode(ei.inputType) ?: 0) != 0
    }
}
