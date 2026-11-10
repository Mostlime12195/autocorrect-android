package dev.mostlime.autocorrect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CorrectionModeTest {
    @Test fun suggestOnlyIsTheDefault() {
        assertEquals("suggest", Prefs.DEFAULT_CORRECTION_MODE)
        assertEquals("suggest", Prefs.correctionModeOf(null))
        assertEquals("suggest", Prefs.correctionModeOf("bogus"))
    }

    @Test fun anExplicitChoiceIsKept() {
        assertEquals("auto", Prefs.correctionModeOf("auto"))
        assertEquals("suggest", Prefs.correctionModeOf("suggest"))
    }

    @Test fun flagTyposFollowsTheModeUntilSet() {
        // default user: suggest-only, so typos are flagged (the only feedback that mode gives)
        assertTrue(Prefs.flagTyposOf(null, Prefs.correctionModeOf(null) == "suggest"))
        assertFalse(Prefs.flagTyposOf(null, suggestOnly = false))
        assertFalse(Prefs.flagTyposOf(false, suggestOnly = true))
        assertTrue(Prefs.flagTyposOf(true, suggestOnly = false))
    }

    @Test fun settingsScreenDefaultMatches() {
        val f = listOf("src/main/res/xml/prefs_correction.xml", "app/src/main/res/xml/prefs_correction.xml").map(::File).first { it.exists() }
        val pref = Regex("""<ListPreference[^>]*app:key="correction_mode"[^>]*>""").find(f.readText())!!.value
        assertEquals(Prefs.DEFAULT_CORRECTION_MODE, Regex("""app:defaultValue="([^"]*)"""").find(pref)!!.groupValues[1])
        val arrays = listOf("src/main/res/values/arrays.xml", "app/src/main/res/values/arrays.xml").map(::File).first { it.exists() }.readText()
        val values = Regex("""<string-array name="correction_mode_values">(.*?)</string-array>""", RegexOption.DOT_MATCHES_ALL).find(arrays)!!
            .groupValues[1].let { Regex("<item>([^<]*)</item>").findAll(it).map { m -> m.groupValues[1] }.toList() }
        assertEquals(Prefs.CORRECTION_MODES.toSet(), values.toSet())
    }
}
