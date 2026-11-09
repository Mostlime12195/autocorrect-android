package dev.mostlime.autocorrect.spell

import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.UserDictionary
import android.service.textservice.SpellCheckerService
import android.util.Log
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import dev.mostlime.autocorrect.App
import dev.mostlime.autocorrect.engine.SEG
import dev.mostlime.autocorrect.engine.SentenceCheck
import dev.mostlime.autocorrect.engine.SentenceFix
import dev.mostlime.autocorrect.engine.SentenceReq
import dev.mostlime.autocorrect.ime.AutocorrectIME
import dev.mostlime.autocorrect.ime.EngineHolder
import dev.mostlime.autocorrect.ime.KeyboardController
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keylime as the system spell checker (Settings -> System -> Languages -> Spell checker): apps' text fields get the
 * platform's red underlines and fix menu, with our candidates. Runs in the keyboard's process (":ime") so it shares the
 * loaded model with the keyboard. Each word is its own job on the engine thread, so the keyboard's own corrections
 * slot in between words and typing never waits for a spell check.
 * "Add to dictionary" in the system menu goes to Android's personal dictionary; those words are imported into ours.
 */
class KeylimeSpellChecker : SpellCheckerService() {
    override fun createSession(): Session = KeylimeSession()

    private var dictObserver: ContentObserver? = null

    override fun onDestroy() {
        dictObserver?.let { runCatching { contentResolver.unregisterContentObserver(it) } }
        super.onDestroy()
    }

    /** Android's personal dictionary (the system menu's "Add to dictionary") -> explicit adds in ours */
    private fun importSystemDictionary() {
        runCatching {
            contentResolver.query(UserDictionary.Words.CONTENT_URI, arrayOf(UserDictionary.Words.WORD), null, null, null)?.use { c ->
                val words = ArrayList<String>()
                while (c.moveToNext()) c.getString(0)?.let { words.add(it) }
                if (words.isNotEmpty()) (application as App).user.addAll(words)
            }
        }.onFailure { Log.i(TAG, "personal dictionary not readable: $it") }
    }

    inner class KeylimeSession : Session() {
        override fun onCreate() {
            val app = application as App
            EngineHolder.preload(app)
            importSystemDictionary()
            if (dictObserver == null) {
                dictObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) = importSystemDictionary()
                }.also { runCatching { contentResolver.registerContentObserver(UserDictionary.Words.CONTENT_URI, true, it) } }
            }
        }

        override fun onGetSuggestions(textInfo: TextInfo, suggestionsLimit: Int): SuggestionsInfo {
            val text = textInfo.text.orEmpty()
            val r = sentence(text, suggestionsLimit).firstOrNull { it.first.start == 0 && it.first.from.length == text.trim().length }
            return r?.let { info(it.first, it.second, suggestionsLimit, textInfo) }
                ?: SuggestionsInfo(SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY, emptyArray(), textInfo.cookie, textInfo.sequence)
        }

        override fun onGetSentenceSuggestionsMultiple(textInfos: Array<TextInfo>, suggestionsLimit: Int): Array<SentenceSuggestionsInfo> =
            textInfos.map { ti ->
                val found = sentence(ti.text.orEmpty(), suggestionsLimit)
                SentenceSuggestionsInfo(found.map { info(it.first, it.second, suggestionsLimit, ti) }.toTypedArray(),
                    found.map { it.first.start }.toIntArray(), found.map { it.first.from.length }.toIntArray())
            }.toTypedArray()
    }

    private fun info(f: SentenceFix, grammar: Boolean, limit: Int, ti: TextInfo): SuggestionsInfo {
        var attrs = SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS
        attrs = attrs or if (grammar && Build.VERSION.SDK_INT >= 31) ATTR_GRAMMAR
            else SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO
        return SuggestionsInfo(attrs, f.fixes.take(maxOf(1, limit)).toTypedArray(), ti.cookie, ti.sequence)
    }

    /**
     * Flagged words of a sentence with whether each is a grammar flag (a real word that should be another). With the
     * model loaded: the same sentence check as suggest-only mode; without it: words not in the lexicon, cheap fixes.
     */
    private fun sentence(text: String, limit: Int): List<Pair<SentenceFix, Boolean>> {
        val app = application as App
        app.user.reloadIfChanged()
        // the first request after the process starts waits for the lexicon (a few seconds on slow phones)
        val corr = EngineHolder.corrector ?: runCatching {
            runBlocking { withTimeoutOrNull(LEXICON_TIMEOUT_MS) { EngineHolder.scope.async { EngineHolder.ensureLexicon(app) }.await() } }
        }.getOrNull() ?: return emptyList()
        val words = SEG.findAll(text).take(SentenceCheck.MAX_WORDS).toList()
        val out = ArrayList<Pair<SentenceFix, Boolean>>()
        val req = SentenceReq("", text)
        for (i in words.indices) {
            val w = words[i]
            if (app.user.has(w.value)) continue
            val inLex = corr.lex.index.containsKey(w.value.lowercase())
            val sc = EngineHolder.scorer?.takeIf { !it.step.closed }
            val fix: SentenceFix? = if (sc != null) {
                runCatching {
                    runBlocking {
                        withTimeoutOrNull(WORD_TIMEOUT_MS) {
                            EngineHolder.scope.async {
                                SentenceCheck.checkWord(corr, req, words, i, sc, EngineHolder.params ?: AutocorrectIME.DEFAULT_PARAMS, KeyboardController.FLAG_MARGIN)
                            }.await()
                        }
                    }
                }.getOrNull()
            } else null
            when {
                fix != null -> out.add(fix to inLex)
                sc == null && !inLex -> {
                    // no model loaded: unknown words, cheap suggestions (spatial + word frequency)
                    val s = corr.suggest(w.value, null, maxOf(3, limit)).filter { it.lowercase() != w.value.lowercase() }
                    if (s.isNotEmpty()) out.add(SentenceFix(w.range.first, w.value, s[0], 0.0, s) to false)
                }
            }
        }
        return out
    }

    companion object {
        const val TAG = "AcSpell"
        /** SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_GRAMMATICAL_ERROR (API 31), by value */
        const val ATTR_GRAMMAR = 0x0008
        /** one word's model check; a slower one falls back to nothing for that word */
        const val WORD_TIMEOUT_MS = 1500L
        const val LEXICON_TIMEOUT_MS = 20_000L
    }
}
