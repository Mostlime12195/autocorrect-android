package dev.mostlime.autocorrect

import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Keylime selected as the system spell checker answers a sentence request like the framework sends it. */
@RunWith(AndroidJUnit4::class)
class SpellCheckerServiceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext

    private fun shell(cmd: String) = inst.uiAutomation.executeShellCommand(cmd).close()

    @Test fun flagsAMisspellingWithOurFixes() {
        shell("settings put secure spell_checker_enabled 1")
        shell("settings put secure selected_spell_checker ${ctx.packageName}/.spell.KeylimeSpellChecker")
        shell("settings put secure selected_spell_checker_subtype 0")
        Thread.sleep(1500)
        val tsm = ctx.getSystemService(TextServicesManager::class.java)
        val done = CountDownLatch(1)
        var got: Array<SentenceSuggestionsInfo>? = null
        val listener = object : SpellCheckerSession.SpellCheckerSessionListener {
            override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) {}
            override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) { got = results?.map { it }?.toTypedArray(); done.countDown() }
        }
        lateinit var session: SpellCheckerSession
        inst.runOnMainSync { session = tsm.newSpellCheckerSession(null, Locale.US, listener, true)!! }
        val text = "i went to teh store"
        session.getSentenceSuggestions(arrayOf(TextInfo(text)), 5)
        assertTrue("no answer from the spell checker", done.await(30, TimeUnit.SECONDS))
        val s = got!!.first()
        val hits = (0 until s.suggestionsCount).map { j ->
            val info = s.getSuggestionsInfoAt(j)
            Triple(text.substring(s.getOffsetAt(j), s.getOffsetAt(j) + s.getLengthAt(j)), info.suggestionsAttributes,
                (0 until info.suggestionsCount).map { info.getSuggestionAt(it) })
        }
        val teh = hits.firstOrNull { it.first == "teh" }
        assertTrue("teh not flagged: $hits", teh != null && (teh.second and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO) != 0)
        assertTrue("no 'the' in ${teh!!.third}", "the" in teh.third)
        assertTrue("store flagged: $hits", hits.none { it.first == "store" })
        session.close()
    }
}
