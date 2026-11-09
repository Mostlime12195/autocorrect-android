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
    private val timer = object : Timer {
        override fun post(delayMs: Long, r: () -> Unit): Any { if (delayMs < 100) r(); return Any() }
        override fun cancel(token: Any) {}
    }

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
            assertEquals(KeyboardController.StripMode.RECENT, ctl.stripMode)
            assertEquals("teh → the", ctl.shown[0])
            ctl.pickSuggestion(0)
            assertEquals("so the quick brown fox ", et.text.toString())
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
