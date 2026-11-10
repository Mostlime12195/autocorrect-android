package dev.mostlime.autocorrect.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * What the keyboard may do in a field.
 *
 * sensitive (never logged, never learned from, never uploaded): password fields (text password / visible password /
 * web password / number password), incognito fields (IME_FLAG_NO_PERSONALIZED_LEARNING), email, URI, number, phone
 * and date/time fields, and raw TYPE_NULL fields.
 * autocorrect: off in all of the above except incognito, and when the field asks for no suggestions.
 * noUpload / noContext: what the opt-in data sharing may take from the field (see the fields below).
 */
data class FieldPolicy(
    val sensitive: Boolean,
    val autocorrect: Boolean,
    val suggestions: Boolean,
    val password: Boolean,
    val numeric: Boolean,
    val multiLine: Boolean,
    val capSentences: Boolean,
    /** TYPE_TEXT_FLAG_CAP_WORDS / CAP_CHARACTERS */
    val capWords: Boolean = false,
    val capChars: Boolean = false,
    /** no upload events at all from this field: it is sensitive, or it belongs to an excluded app ([EXCLUDED_APPS]) */
    val noUpload: Boolean = sensitive,
    /**
     * no sentence context from this field even with that opt-in on (events stay word-level): everything in [noUpload],
     * plus name, postal-address, filter / search-as-you-type and phonetic fields, and fields that ask for no suggestions
     * (usernames, codes).
     */
    val noContext: Boolean = noUpload,
) {
    /** the field asks for some automatic capitals */
    val anyCaps get() = capSentences || capWords || capChars

    companion object {
        val NONE = FieldPolicy(sensitive = true, autocorrect = false, suggestions = false, password = false, numeric = false, multiLine = false, capSentences = false)

        fun of(info: EditorInfo?): FieldPolicy = if (info == null) NONE else of(info.inputType, info.imeOptions, info.packageName)

        /**
         * Apps whose fields never produce upload events, whatever the field type says: password managers,
         * authenticators, terminals / SSH clients. Matched on the package name or a prefix of it ending in a dot.
         */
        val EXCLUDED_APPS = listOf(
            "com.x8bit.bitwarden", "com.bitwarden.", "com.agilebits.onepassword", "com.onepassword.", "com.lastpass.lpandroid",
            "com.dashlane", "keepass2android.", "com.kunzisoft.keepass.", "org.keepassdroid", "com.keepassdroid", "proton.android.pass",
            "com.nordpass.android.app.password.manager", "com.callpod.android_apps.keeper", "com.enpass.", "io.enpass.",
            "com.authy.authy", "com.google.android.apps.authenticator2", "com.azure.authenticator", "org.shadowice.flocke.andotp",
            "com.beemdevelopment.aegis", "com.termux", "org.connectbot", "com.sonelli.juicessh", "com.server.auditor.ssh.client",
        )

        fun excludedApp(pkg: String?): Boolean {
            val p = pkg?.lowercase() ?: return false
            return EXCLUDED_APPS.any { if (it.endsWith('.')) p.startsWith(it) else p == it || p.startsWith("$it.") }
        }

        fun of(inputType: Int, imeOptions: Int, pkg: String? = null): FieldPolicy {
            val cls = inputType and InputType.TYPE_MASK_CLASS
            val v = inputType and InputType.TYPE_MASK_VARIATION
            val flags = inputType and InputType.TYPE_MASK_FLAGS
            val text = cls == InputType.TYPE_CLASS_TEXT
            val password = (text && (v == InputType.TYPE_TEXT_VARIATION_PASSWORD || v == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
                (cls == InputType.TYPE_CLASS_NUMBER && v == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
            val incognito = (imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
            val emailUri = text && (v == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || v == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
                v == InputType.TYPE_TEXT_VARIATION_URI)
            val numeric = cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE || cls == InputType.TYPE_CLASS_DATETIME
            val rawNull = cls == 0
            val noSuggest = (flags and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0
            val sensitive = password || incognito || emailUri || numeric || rawNull
            val autocorrect = text && !password && !emailUri && !noSuggest
            val noUpload = sensitive || excludedApp(pkg)
            val personal = text && (v == InputType.TYPE_TEXT_VARIATION_PERSON_NAME || v == InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS ||
                v == InputType.TYPE_TEXT_VARIATION_FILTER || v == InputType.TYPE_TEXT_VARIATION_PHONETIC)
            return FieldPolicy(
                noUpload = noUpload,
                noContext = noUpload || personal || noSuggest,
                sensitive = sensitive,
                autocorrect = autocorrect,
                suggestions = autocorrect,
                password = password,
                numeric = numeric,
                multiLine = (flags and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0,
                capSentences = text && !password && !emailUri && (flags and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) != 0,
                capWords = text && !password && !emailUri && (flags and InputType.TYPE_TEXT_FLAG_CAP_WORDS) != 0,
                capChars = text && !password && !emailUri && (flags and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS) != 0,
            )
        }
    }
}
