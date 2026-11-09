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

    private fun view(gesture: Int, bottom: Int, gestureNav: Boolean = true, clipBy: Int = 0): Pair<KeyboardView, Rec> {
        val r = Rec()
        val v = KeyboardView(ctx, r)
        v.setInsetsForTest(gesture, bottom, gestureNav)
        v.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        v.layout(0, 0, v.measuredWidth, v.measuredHeight - clipBy)   // clipBy: the window didn't give us the height we asked for
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

    /** Galaxy A12 (3-button nav): 48 dp gesture inset AND 48 dp bottom inset reported, window 48 dp shorter than asked */
    @Test fun threeButtonNavNeverDeadensTheBottomRow() {
        for (gestureNav in listOf(false, true)) inst.runOnMainSync {
            val (v, r) = view((48 * dp).toInt(), (48 * dp).toInt(), gestureNav, clipBy = (48 * dp).toInt())
            val keysBottom = v.height - 8 * dp            // the padding we asked for didn't fit: keys end 8 dp above the bottom
            val y = keysBottom - 3 * dp                  // the lowest part of the bottom row
            tap(v, v.width * 0.5f, y)                    // space
            tap(v, v.width * 0.80f, y)                   // .
            assertEquals("gestureNav=$gestureNav", listOf(KeyType.SPACE, KeyType.CHAR), r.keys)
        }
    }

    @Test fun longPressSpaceOpensThePickerAndDragCancelsIt() {
        lateinit var v: KeyboardView
        val switched = java.util.concurrent.atomic.AtomicInteger()
        val keys = ArrayList<KeyType>()
        inst.runOnMainSync {
            v = KeyboardView(ctx, object : KeyboardView.Listener {
                override fun onKey(key: KeyDef, touch: Touch) { keys.add(key.type) }
                override fun onAlternate(key: KeyDef, text: String, touch: Touch) {}
                override fun onSuggestion(index: Int) {}
                override fun onSwitchIme() { switched.incrementAndGet() }
                override fun onSettings() {}
            })
            v.setInsetsForTest(0, 0, false)
            v.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        }
        val t = SystemClock.uptimeMillis()
        var y = 0f
        inst.runOnMainSync { y = bottomRowY(v); ev(v, MotionEvent.ACTION_DOWN, v.width * 0.5f, y, t) }
        Thread.sleep(800)
        inst.runOnMainSync { ev(v, MotionEvent.ACTION_UP, v.width * 0.5f, y, t) }
        assertEquals(1, switched.get())
        assertTrue("typed $keys", keys.isEmpty())
        // a drag along the space bar is not a long press
        val t2 = SystemClock.uptimeMillis()
        inst.runOnMainSync { ev(v, MotionEvent.ACTION_DOWN, v.width * 0.4f, y, t2); ev(v, MotionEvent.ACTION_MOVE, v.width * 0.6f, y, t2) }
        Thread.sleep(800)
        inst.runOnMainSync { ev(v, MotionEvent.ACTION_UP, v.width * 0.6f, y, t2) }
        assertEquals(1, switched.get())
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
