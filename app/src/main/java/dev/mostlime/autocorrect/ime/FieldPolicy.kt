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
 */
data class FieldPolicy(
    val sensitive: Boolean,
    val autocorrect: Boolean,
    val suggestions: Boolean,
    val password: Boolean,
    val numeric: Boolean,
    val multiLine: Boolean,
    val capSentences: Boolean,
) {
    companion object {
        val NONE = FieldPolicy(sensitive = true, autocorrect = false, suggestions = false, password = false, numeric = false, multiLine = false, capSentences = false)

        fun of(info: EditorInfo?): FieldPolicy = if (info == null) NONE else of(info.inputType, info.imeOptions)

        fun of(inputType: Int, imeOptions: Int): FieldPolicy {
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
            return FieldPolicy(
                sensitive = sensitive,
                autocorrect = autocorrect,
                suggestions = autocorrect,
                password = password,
                numeric = numeric,
                multiLine = (flags and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0,
                capSentences = text && !password && !emailUri && (flags and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) != 0,
            )
        }
    }
}
