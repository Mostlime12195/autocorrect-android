package dev.mostlime.autocorrect

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.Spanned
import android.text.style.SuggestionSpan
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mostlime.autocorrect.engine.Slot
import dev.mostlime.autocorrect.engine.Touch
import dev.mostlime.autocorrect.engine.WordResult
import dev.mostlime.autocorrect.ime.ControllerConfig
import dev.mostlime.autocorrect.ime.ControllerUi
import dev.mostlime.autocorrect.ime.CorrectionEngine
import dev.mostlime.autocorrect.ime.CorrectionRequest
import dev.mostlime.autocorrect.ime.FieldPolicy
import dev.mostlime.autocorrect.ime.IcEditor
import dev.mostlime.autocorrect.ime.KeyType
import dev.mostlime.autocorrect.ime.KeyboardController
import dev.mostlime.autocorrect.ime.KeyboardLayout
import dev.mostlime.autocorrect.ime.Shift
import dev.mostlime.autocorrect.ime.Timer
import dev.mostlime.autocorrect.ui.BackgroundImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On a real device / emulator: the keyboard's InputConnection editor against a real EditText.
 * - suggest-only marks a misspelling with a SuggestionSpan(FLAG_MISSPELLED) and never changes the text
 * - the strip's "teh → the" chip fixes the word in place several words later
 * - the background image is decoded and saved (big JPEG)
 */
@RunWith(AndroidJUnit4::class)
class SuggestOnlyEditTextTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext

    private fun result(typed: String, cands: List<String>, scores: DoubleArray): WordResult {
        var k = 0
        for (i in scores.indices) if (scores[i] > scores[k]) k = i
        return WordResult(Slot(typed, cands, DoubleArray(cands.size), cands.indexOf(typed)), scores, scores, k, cands[k],
            skipped = false, cheap = false, nScored = cands.size, nCands = cands.size, lmMs = 1.0, recheck = null)
    }

    private val engine = object : CorrectionEngine {
        override fun correct(req: CorrectionRequest, done: (WordResult?) -> Unit) = done(
            if (req.input.typed == "teh") result("teh", listOf("teh", "the", "ten"), doubleArrayOf(0.0, 6.0, 1.0))
            else result(req.input.typed, listOf(req.input.typed), doubleArrayOf(0.0)))
        override fun suggest(typed: String, touches: List<Touch>, done: (List<String>) -> Unit) {}
        override fun cancelQueued() {}
    }
    /** short delays run at once; the idle timer waits for fire() */
    private val idle = LinkedHashMap<Any, () -> Unit>()
    private val timer = object : Timer {
        override fun post(delayMs: Long, r: () -> Unit): Any { val t = Any(); if (delayMs < 100) r() else idle[t] = r; return t }
        override fun cancel(token: Any) { idle.remove(token) }
    }
    private fun fire() { val t = idle.values.toList(); idle.clear(); t.forEach { it() } }

    @Test fun suggestOnlyMarksAMisspellingWithASuggestionSpan() {
        lateinit var et: EditText
        lateinit var ctl: KeyboardController
        inst.runOnMainSync {
            et = EditText(ctx)
            val info = EditorInfo().apply { inputType = android.text.InputType.TYPE_CLASS_TEXT }
            val ic = et.onCreateInputConnection(info)
            val ed = IcEditor(ctx, { ic }, { info }, {}, {})
            ctl = KeyboardController({ ed }, engine, object : ControllerUi {
                override fun showStrip(words: List<String>, bold: Int, undo: Int) {}
                override fun setShift(shift: Shift) {}
                override fun onResult(r: WordResult?) {}
            }, timer) {}
            ctl.startInput(ControllerConfig(FieldPolicy.of(android.text.InputType.TYPE_CLASS_TEXT, 0), true, "standard", { "t" }, "t", suggestOnly = true))
            for (c in "so teh ") {
                val k = if (c == ' ') KeyboardLayout.LETTERS.keys.first { it.type == KeyType.SPACE } else KeyboardLayout.LETTERS.keys.first { it.text == c.toString() }
                ctl.key(k, Touch.APOS)
            }
        }
        inst.runOnMainSync {
            // nothing is written while typing; the underline comes after a pause
            assertTrue(et.text.getSpans(0, et.text.length, SuggestionSpan::class.java).none { (it.flags and SuggestionSpan.FLAG_MISSPELLED) != 0 })
            fire()
            val text = et.text
            assertEquals("so teh ", text.toString())
            val spans = text.getSpans(0, text.length, SuggestionSpan::class.java)
            val s = spans.firstOrNull { (it.flags and SuggestionSpan.FLAG_MISSPELLED) != 0 }
            assertTrue("no misspelled SuggestionSpan in '${text}' (spans: ${spans.size})", s != null)
            assertEquals(3, text.getSpanStart(s)); assertEquals(6, text.getSpanEnd(s))
            assertEquals("the", s!!.suggestions[0])
            // keep typing: the mark stays, the chip fixes it in place
            for (c in "quick brown fox ") {
                val k = if (c == ' ') KeyboardLayout.LETTERS.keys.first { it.type == KeyType.SPACE } else KeyboardLayout.LETTERS.keys.first { it.text == c.toString() }
                ctl.key(k, Touch.APOS)
            }
            assertTrue(et.text.getSpans(0, et.text.length, SuggestionSpan::class.java).any { (it.flags and SuggestionSpan.FLAG_MISSPELLED) != 0 })
            assertEquals(KeyboardController.StripMode.FLAG, ctl.stripMode)
            assertEquals("teh → the", ctl.shownAction)
            ctl.stripAction()
            assertEquals("so the quick brown fox ", et.text.toString())
            assertEquals(et.text.length, et.selectionStart)
        }
    }

    /**
     * A field that drops spans (like React Native / Discord: every edit re-sets plain text), real async timing: corrections
     * arrive 80 ms late, keys every 40 ms, idle passes on a real 1.5 s timer. The text must end exactly as typed.
     */
    @Test fun fastTypingInASpanDroppingFieldEndsExactlyAsTyped() {
        val typed = "so teh quick brown fox jumps over teh lazy dog and then fized it "
        lateinit var et: EditText
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        val realTimer = object : Timer {
            override fun post(delayMs: Long, r: () -> Unit): Any { val t = Runnable { r() }; h.postDelayed(t, delayMs); return t }
            override fun cancel(token: Any) { h.removeCallbacks(token as Runnable) }
        }
        val slow = object : CorrectionEngine {
            override fun correct(req: CorrectionRequest, done: (WordResult?) -> Unit) {
                val r = engine.let { e -> var out: WordResult? = null; e.correct(req) { out = it }; out }
                h.postDelayed({ done(r) }, 80)
            }
            override fun suggest(typed: String, touches: List<Touch>, done: (List<String>) -> Unit) {}
            override fun cancelQueued() {}
        }
        inst.runOnMainSync {
            et = EditText(ctx)
            et.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: android.text.Editable) {
                    for (sp in e.getSpans(0, e.length, SuggestionSpan::class.java)) e.removeSpan(sp)
                }
            })
            val info = EditorInfo().apply { inputType = android.text.InputType.TYPE_CLASS_TEXT }
            val ic = et.onCreateInputConnection(info)
            val ed = IcEditor(ctx, { ic }, { info }, {}, {})
            val ctl = KeyboardController({ ed }, slow, object : ControllerUi {
                override fun showStrip(words: List<String>, bold: Int, undo: Int) {}
                override fun setShift(shift: Shift) {}
                override fun onResult(r: WordResult?) {}
            }, realTimer) {}
            ctl.startInput(ControllerConfig(FieldPolicy.of(android.text.InputType.TYPE_CLASS_TEXT, 0), true, "standard", { "t" }, "t", suggestOnly = true))
            typed.forEachIndexed { i, c ->
                // a 2 s pause in the middle lets the idle pass try its marks
                val at = i * 40L + if (i > 34) 2000L else 0L
                h.postDelayed({
                    val k = if (c == ' ') KeyboardLayout.LETTERS.keys.first { it.type == KeyType.SPACE } else KeyboardLayout.LETTERS.keys.first { it.text == c.toString() }
                    ctl.key(k, Touch.APOS)
                }, at)
            }
        }
        Thread.sleep(typed.length * 40L + 2000L + 2500L)
        inst.runOnMainSync { assertEquals(typed, et.text.toString()) }
    }

    /** a slow phone: every correction takes 500 ms; "teh quick " typed fast ends as "the quick " with the cursor at the end */
    @Test fun lateFixNeverBreaksTheWordBeingTyped() {
        lateinit var et: EditText
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        val realTimer = object : Timer {
            override fun post(delayMs: Long, r: () -> Unit): Any { val t = Runnable { r() }; h.postDelayed(t, delayMs); return t }
            override fun cancel(token: Any) { h.removeCallbacks(token as Runnable) }
        }
        val slow = object : CorrectionEngine {
            override fun correct(req: CorrectionRequest, done: (WordResult?) -> Unit) {
                var out: WordResult? = null; engine.correct(req) { out = it }
                h.postDelayed({ done(out) }, 500)
            }
            override fun suggest(typed: String, touches: List<Touch>, done: (List<String>) -> Unit) {}
            override fun cancelQueued() {}
        }
        inst.runOnMainSync {
            et = EditText(ctx)
            val info = EditorInfo().apply { inputType = android.text.InputType.TYPE_CLASS_TEXT }
            val ic = et.onCreateInputConnection(info)
            val ed = IcEditor(ctx, { ic }, { info }, {}, {})
            val ctl = KeyboardController({ ed }, slow, object : ControllerUi {
                override fun showStrip(words: List<String>, bold: Int, undo: Int) {}
                override fun setShift(shift: Shift) {}
                override fun onResult(r: WordResult?) {}
            }, realTimer) {}
            ctl.startInput(ControllerConfig(FieldPolicy.of(android.text.InputType.TYPE_CLASS_TEXT, 0), true, "standard", { "t" }, "t"))
            "teh quick ".forEachIndexed { i, c ->
                h.postDelayed({
                    val k = if (c == ' ') KeyboardLayout.LETTERS.keys.first { it.type == KeyType.SPACE } else KeyboardLayout.LETTERS.keys.first { it.text == c.toString() }
                    ctl.key(k, Touch.APOS)
                }, i * 60L)
            }
        }
        Thread.sleep(10 * 60L + 2500L)
        inst.runOnMainSync {
            assertEquals("the quick ", et.text.toString())
            assertEquals(et.text.length, et.selectionStart)
        }
    }

    @Test fun bigJpegBackgroundIsSavedAtScreenWidth() {
        val dir = File(ctx.cacheDir, "bgtest").apply { mkdirs() }
        val src = File(dir, "big.jpg")
        val bmp = Bitmap.createBitmap(6000, 4000, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3366AA.toInt()) }
        src.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bmp.recycle()
        val out = File(dir, "out.jpg")
        assertNull(BackgroundImage.save(ctx, Uri.fromFile(src), out, 1080))
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(out.path, o)
        assertEquals(1080, o.outWidth); assertEquals(720, o.outHeight)
        // garbage gives a reason, not a crash
        val bad = File(dir, "bad.jpg").apply { writeText("not an image") }
        assertTrue(BackgroundImage.save(ctx, Uri.fromFile(bad), File(dir, "o2.jpg"), 1080) != null)
        dir.deleteRecursively()
    }
}
