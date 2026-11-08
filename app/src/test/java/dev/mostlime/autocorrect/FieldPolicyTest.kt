package dev.mostlime.autocorrect

import android.text.InputType.*
import android.view.inputmethod.EditorInfo
import dev.mostlime.autocorrect.ime.FieldPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldPolicyTest {
    private fun p(type: Int, opts: Int = 0) = FieldPolicy.of(type, opts)

    @Test fun passwordsAreSensitiveAndNeverCorrected() {
        for (t in listOf(TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_PASSWORD, TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_WEB_PASSWORD, TYPE_CLASS_NUMBER or TYPE_NUMBER_VARIATION_PASSWORD)) {
            val f = p(t)
            assertTrue(f.sensitive); assertTrue(f.password); assertFalse(f.autocorrect); assertFalse(f.suggestions)
        }
    }

    @Test fun emailUriNumberPhoneAreSensitive() {
        for (t in listOf(TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_EMAIL_ADDRESS, TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_URI, TYPE_CLASS_NUMBER, TYPE_CLASS_PHONE, TYPE_CLASS_DATETIME, TYPE_NULL)) {
            val f = p(t)
            assertTrue("$t", f.sensitive); assertFalse("$t", f.autocorrect)
        }
    }

    @Test fun incognitoIsSensitiveButStillCorrects() {
        val f = p(TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        assertTrue(f.sensitive); assertTrue(f.autocorrect)
    }

    @Test fun plainTextIsLoggable() {
        val f = p(TYPE_CLASS_TEXT or TYPE_TEXT_FLAG_CAP_SENTENCES or TYPE_TEXT_FLAG_MULTI_LINE)
        assertFalse(f.sensitive); assertTrue(f.autocorrect); assertTrue(f.capSentences); assertTrue(f.multiLine)
        assertFalse(p(TYPE_CLASS_TEXT or TYPE_TEXT_FLAG_NO_SUGGESTIONS).autocorrect)
    }
}
