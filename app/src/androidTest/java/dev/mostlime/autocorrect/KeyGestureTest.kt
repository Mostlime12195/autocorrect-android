package dev.mostlime.autocorrect

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mostlime.autocorrect.engine.Touch
import dev.mostlime.autocorrect.ime.KeyDef
import dev.mostlime.autocorrect.ime.KeyboardView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A character key types only once its gesture is a tap (release, or the next key down): a press that becomes a swipe
 * or a long-press popup never types its letter, and overlapping presses keep their order.
 */
@RunWith(AndroidJUnit4::class)
class KeyGestureTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext

    private class Rec : KeyboardView.Listener {
        val out = ArrayList<String>()
        override fun onKey(key: KeyDef, touch: Touch) { out.add(key.text) }
        override fun onAlternate(key: KeyDef, text: String, touch: Touch) { out.add("alt:${key.text}>$text") }
        override fun onSuggestion(index: Int) {}
        override fun onSwitchIme() {}
        override fun onSettings() {}
        override fun onSwipeStart() { out.add("swipe-start") }
        override fun onSwipe(path: List<Touch>) { out.add("swipe") }
    }

    private lateinit var v: KeyboardView
    private val r = Rec()
    private val t0 = SystemClock.uptimeMillis()

    private fun setUp() = inst.runOnMainSync {
        v = KeyboardView(ctx, r)
        v.setInsetsForTest(0, 0)
        v.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
    }

    /** centre of letter row `row` (0 = q row), key `col` (0..9) */
    private fun at(row: Int, col: Float): Pair<Float, Float> {
        val dp = ctx.resources.displayMetrics.density
        val rowH = (v.height - 8 * dp - v.bottomInsetPx - v.stripHeight) / 4f
        return v.width * (col + 0.5f) / 10f to v.stripHeight + rowH * (row + 0.5f)
    }

    private fun ev(action: Int, xs: List<Pair<Float, Float>>, ids: List<Int> = xs.indices.toList()) = inst.runOnMainSync {
        val props = Array(xs.size) { i -> MotionEvent.PointerProperties().apply { id = ids[i]; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(xs.size) { i -> MotionEvent.PointerCoords().apply { x = xs[i].first; y = xs[i].second; pressure = 1f; size = 1f } }
        val e = MotionEvent.obtain(t0, SystemClock.uptimeMillis(), action, xs.size, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        v.dispatchTouchEvent(e); e.recycle()
    }
    private fun idx(action: Int, i: Int) = action or (i shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    @Test fun tapTypesOnReleaseOnly() {
        setUp()
        val e = at(0, 2f)
        ev(MotionEvent.ACTION_DOWN, listOf(e))
        assertTrue("typed on press: ${r.out}", r.out.isEmpty())
        ev(MotionEvent.ACTION_UP, listOf(e))
        assertEquals(listOf("e"), r.out)
    }

    @Test fun overlappingPressesKeepTheirOrder() {
        setUp()
        val t = at(0, 4f); val h = at(1, 5.5f)
        // t down, h down, h up, t up (the second finger lifts first)
        ev(MotionEvent.ACTION_DOWN, listOf(t))
        ev(idx(MotionEvent.ACTION_POINTER_DOWN, 1), listOf(t, h))
        ev(idx(MotionEvent.ACTION_POINTER_UP, 1), listOf(t, h))
        ev(MotionEvent.ACTION_UP, listOf(t))
        assertEquals(listOf("t", "h"), r.out)
    }

    @Test fun swipeNeverTypesItsFirstLetter() {
        setUp()
        ev(MotionEvent.ACTION_DOWN, listOf(at(0, 0f)))
        for (c in 1..9) ev(MotionEvent.ACTION_MOVE, listOf(at(0, c.toFloat())))
        ev(MotionEvent.ACTION_UP, listOf(at(0, 9f)))
        assertEquals(listOf("swipe-start", "swipe"), r.out)
    }

    @Test fun longPressPopupNeverTypesTheBaseLetter() {
        setUp()
        inst.runOnMainSync { v.longPressMs = 100 }
        val e = at(0, 2f)
        ev(MotionEvent.ACTION_DOWN, listOf(e))
        Thread.sleep(400)
        inst.waitForIdleSync()
        assertTrue("typed during long press: ${r.out}", r.out.isEmpty())
        ev(MotionEvent.ACTION_UP, listOf(e))
        assertEquals(1, r.out.size)
        assertTrue(r.out.toString(), r.out[0].startsWith("alt:e>"))
    }
}
