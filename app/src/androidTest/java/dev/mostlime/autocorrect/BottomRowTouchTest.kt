package dev.mostlime.autocorrect

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mostlime.autocorrect.engine.Touch
import dev.mostlime.autocorrect.ime.KeyDef
import dev.mostlime.autocorrect.ime.KeyType
import dev.mostlime.autocorrect.ime.KeyboardView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The bottom row must work whatever insets the phone reports (Galaxy A12: 3-button nav, IME window not edge-to-edge,
 * ~48 dp "system gesture" inset but no bottom padding of ours) -- and a swipe up from the gesture handle never types.
 */
@RunWith(AndroidJUnit4::class)
class BottomRowTouchTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val dp = ctx.resources.displayMetrics.density

    private class Rec : KeyboardView.Listener {
        val keys = ArrayList<KeyType>()
        override fun onKey(key: KeyDef, touch: Touch) { keys.add(key.type) }
        override fun onAlternate(key: KeyDef, text: String, touch: Touch) {}
        override fun onSuggestion(index: Int) {}
        override fun onSwitchIme() {}
        override fun onSettings() {}
    }

    private fun view(gesture: Int, bottom: Int): Pair<KeyboardView, Rec> {
        val r = Rec()
        val v = KeyboardView(ctx, r)
        v.setInsetsForTest(gesture, bottom)
        v.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        return v to r
    }

    private fun ev(v: View, action: Int, x: Float, y: Float, down: Long) =
        MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).also { v.dispatchTouchEvent(it); it.recycle() }

    private fun tap(v: View, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        ev(v, MotionEvent.ACTION_DOWN, x, y, t); ev(v, MotionEvent.ACTION_UP, x, y, t)
    }

    /** centre of the bottom key row (letters layer: 4 rows under the strip, 8 dp margin, then our bottom padding) */
    private fun bottomRowY(v: KeyboardView): Float {
        val keysBottom = v.height - 8 * dp - v.bottomInsetPx
        val rowH = (keysBottom - v.stripHeight) / 4f
        return keysBottom - rowH / 2
    }

    @Test fun bottomRowWorksWithAGestureInsetAndNoPadding() {
        inst.runOnMainSync {
            val (v, r) = view((48 * dp).toInt(), 0)
            val y = bottomRowY(v)
            tap(v, v.width * 0.5f, y)      // space
            tap(v, v.width * 0.80f, y)     // .
            tap(v, v.width * 0.925f, y)    // enter
            tap(v, v.width * 0.075f, y)    // ?123
            assertEquals(listOf(KeyType.SPACE, KeyType.CHAR, KeyType.ENTER, KeyType.TO_SYMBOLS), r.keys)
            assertEquals(0, KeyboardView.deadBand((48 * dp).toInt(), 0))
        }
    }

    @Test fun swipeUpFromTheGestureHandleNeverTypes() {
        inst.runOnMainSync {
            // gesture navigation, edge-to-edge: our padding under the keys overlaps the system's gesture band
            val (v, r) = view((32 * dp).toInt(), (24 * dp).toInt())
            val t = SystemClock.uptimeMillis()
            val x = v.width * 0.5f
            ev(v, MotionEvent.ACTION_DOWN, x, v.height - 6 * dp, t)
            ev(v, MotionEvent.ACTION_MOVE, x, v.height / 2f, t)
            ev(v, MotionEvent.ACTION_MOVE, x, -20f, t)
            ev(v, MotionEvent.ACTION_UP, x, -20f, t)
            assertTrue("typed ${r.keys}", r.keys.isEmpty())
            // the bottom row itself still types
            tap(v, x, bottomRowY(v))
            assertEquals(listOf(KeyType.SPACE), r.keys)
            // a space press that turns into a swipe up off the keyboard is cancelled
            val t2 = SystemClock.uptimeMillis()
            ev(v, MotionEvent.ACTION_DOWN, x, bottomRowY(v), t2)
            ev(v, MotionEvent.ACTION_MOVE, x, -40f, t2)
            ev(v, MotionEvent.ACTION_UP, x, -40f, t2)
            assertEquals(listOf(KeyType.SPACE), r.keys)
        }
    }
}
