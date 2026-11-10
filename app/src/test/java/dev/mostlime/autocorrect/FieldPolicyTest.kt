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

    @Test fun sensitiveFieldsAndExcludedAppsNeverUpload() {
        for (t in listOf(TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_PASSWORD, TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_WEB_PASSWORD, TYPE_CLASS_NUMBER or TYPE_NUMBER_VARIATION_PASSWORD,
            TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_EMAIL_ADDRESS, TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_URI, TYPE_CLASS_NUMBER, TYPE_CLASS_PHONE, TYPE_CLASS_DATETIME,
            TYPE_CLASS_DATETIME or TYPE_DATETIME_VARIATION_DATE, TYPE_NULL)) {
            assertTrue("$t", p(t).noUpload); assertTrue("$t", p(t).noContext)
        }
        val incognito = p(TYPE_CLASS_TEXT or TYPE_TEXT_FLAG_MULTI_LINE, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        assertTrue(incognito.noUpload); assertTrue(incognito.noContext)
        assertTrue(FieldPolicy.NONE.noUpload); assertTrue(FieldPolicy.NONE.noContext)
        // password managers, authenticators and terminals: a plain text field there still uploads nothing
        for (pkg in listOf("com.x8bit.bitwarden", "com.bitwarden.authenticator", "com.agilebits.onepassword", "com.onepassword.android",
            "keepass2android.keepass2android", "com.kunzisoft.keepass.free", "com.termux", "com.termux.api", "org.connectbot",
            "com.google.android.apps.authenticator2", "COM.Termux")) {
            val f = FieldPolicy.of(TYPE_CLASS_TEXT, 0, pkg)
            assertTrue(pkg, f.noUpload); assertTrue(pkg, f.noContext)
            assertFalse(pkg, f.sensitive); assertTrue(pkg, f.autocorrect)   // the keyboard itself works as usual there
        }
        for (pkg in listOf("com.whatsapp", "com.termuxx", "org.connectbots", "com.dashlanes", null)) {
            val f = FieldPolicy.of(TYPE_CLASS_TEXT, 0, pkg)
            assertFalse("$pkg", f.noUpload); assertFalse("$pkg", f.noContext)
        }
    }

    @Test fun nameAddressFilterAndNoSuggestFieldsGiveNoSentenceContext() {
        for (t in listOf(TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_PERSON_NAME, TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_POSTAL_ADDRESS,
            TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_FILTER, TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_PHONETIC,
            TYPE_CLASS_TEXT or TYPE_TEXT_FLAG_NO_SUGGESTIONS)) {
            val f = p(t)
            assertTrue("$t", f.noContext); assertFalse("$t", f.noUpload); assertFalse("$t", f.sensitive)
        }
        for (t in listOf(TYPE_CLASS_TEXT, TYPE_CLASS_TEXT or TYPE_TEXT_FLAG_CAP_SENTENCES or TYPE_TEXT_FLAG_MULTI_LINE,
            TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_SHORT_MESSAGE, TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_LONG_MESSAGE,
            TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_EMAIL_SUBJECT, TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_WEB_EDIT_TEXT)) {
            assertFalse("$t", p(t).noContext); assertFalse("$t", p(t).noUpload)
        }
    }

    @Test fun plainTextIsLoggable() {
        val f = p(TYPE_CLASS_TEXT or TYPE_TEXT_FLAG_CAP_SENTENCES or TYPE_TEXT_FLAG_MULTI_LINE)
        assertFalse(f.sensitive); assertTrue(f.autocorrect); assertTrue(f.capSentences); assertTrue(f.multiLine)
        assertFalse(p(TYPE_CLASS_TEXT or TYPE_TEXT_FLAG_NO_SUGGESTIONS).autocorrect)
    }
}
