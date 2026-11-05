// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.dictionary

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.SuggestionSpan
import android.util.Log
import android.util.LruCache
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.latin.BuildConfig
import dev.imbrasas.varnele.Native
import helium314.keyboard.latin.NgramContext
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.inputlogic.VarneleUserLm
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsValuesForSuggestion
import helium314.keyboard.latin.utils.JniUtils
import java.util.Locale

/**
 * Lithuanian main dictionary backed by Varnelė, a rule-based morphological checker.
 *
 * A word list cannot hold Lithuanian: every noun, adjective and verb has dozens to
 * hundreds of forms, plus prefixes and the -si- infix. Varnelė generates them from
 * grammar rules, so valid forms are never "corrected", and typing without
 * diacritics ("maciau", "pries pora") is fixed using the grammar of the sentence.
 *
 * Scores follow HeliBoard's scale: a suggestion autocorrects when
 * score / 1,000,000 * (1 - editDistance / length) passes the user's threshold.
 * Fixes that only add diacritics and are clearly the best guess are marked
 * whitelisted, which makes them autocorrect regardless of the threshold.
 */
class VarneleDictionary(
    locale: Locale,
    /** setting: try neighbouring keys for every letter of a completely unrecognised word */
    private val keyShiftEnabled: () -> Boolean = { false },
) : Dictionary(TYPE_MAIN, locale) {

    private val validCache = LruCache<String, Boolean>(2000)

    init {
        Log.i(TAG, "created for $locale")
        Thread {
            val t = System.nanoTime()
            Native.warmUp()
            Log.i(TAG, "data loaded in ${(System.nanoTime() - t) / 1_000_000} ms")
            VarneleUserLm.loadNow()
        }.start()
    }

    override fun getSuggestions(
        composedData: ComposedData,
        ngramContext: NgramContext,
        proximityInfoHandle: Long,
        settingsValuesForSuggestion: SettingsValuesForSuggestion,
        sessionId: Int,
        weightForLocale: Float,
        inOutWeightOfLangModelVsSpatialModel: FloatArray,
    ): ArrayList<SuggestedWordInfo> {
        val out = ArrayList<SuggestedWordInfo>()
        val typed = composedData.mTypedWord
        if (composedData.mIsBatchMode) return gestureSuggestions(composedData, ngramContext)
        if (typed.isEmpty()) return predictions(ngramContext)
        // a word inside a sentence (typed or touched with the cursor back in the text): the words
        // after it decide too ("kad žiema žymiai rimtesne| negu" -> rimtesnė)
        val sentence = sentenceAround()
        val context = sentence?.first ?: previousWords(ngramContext).ifEmpty { sentenceSoFar() }
        val after = sentence?.second ?: ""
        val t = System.nanoTime()
        val r = Native.check(typed, context, keyShiftEnabled(), after) ?: return out
        validCache.put(typed, r.isValid)
        if (BuildConfig.DEBUG) Log.d(TAG, "'${context.takeLast(60)}' + '$typed' + '$after': ${r.status} " +
            "fix=${r.contextFixes.map { it.word }} sugg=${r.suggestions.take(3).map { it.word }} " +
            "${(System.nanoTime() - t) / 1000} µs")

        if (r.isValid) {
            out.add(info(typed, VALID_SCORE, SuggestedWordInfo.KIND_CORRECTION))
            // grammar says this valid word is wrong here: "prieš pora" -> "porą"
            r.contextFixes.forEachIndexed { i, c ->
                out.add(
                    if (c.level == 2) info(c.word, CONTEXT_FIX_SCORE - i, AUTOCORRECT_KIND)
                    else info(c.word, CONTEXT_HINT_SCORE - i, SuggestedWordInfo.KIND_CORRECTION)
                )
            }
            addResumed(typed, out)
            return out
        }

        val s = r.suggestions
        var previous = Int.MAX_VALUE
        s.forEachIndexed { i, c ->
            // whole-word key slip: the user enabled it and nothing else matched, so the most
            // likely word is applied; the other guesses stay in the strip
            val keySlipGuess = r.keyShift && i == 0
            val kind = when (c.level) {
                2 -> AUTOCORRECT_KIND
                // missing diacritics are free: zodzou -> žodžių is one typo, not four edits
                1 -> SuggestedWordInfo.KIND_CORRECTION or SuggestedWordInfo.KIND_FLAG_APPROPRIATE_FOR_AUTO_CORRECTION
                else -> SuggestedWordInfo.KIND_CORRECTION
            }
            // keep the strip in Varnelė's order: each score strictly below the previous one
            val sc = minOf(if (keySlipGuess) KEY_SLIP_SCORE else score(typed, c), previous - 1).coerceAtLeast(1)
            previous = sc
            out.add(info(c.word, sc, kind))
        }
        addResumed(typed, out)
        return out
    }

    /**
     * The sentence around the word being typed when it goes on after the word: the sentence before
     * it and the rest after it (up to its full stop). Null at the end of a sentence.
     */
    private fun sentenceAround(): Pair<String, String>? {
        val after = textAfter
        var end = 0
        while (end < after.length && after[end] !in SENTENCE_ENDS) end++
        if (end < after.length) end++   // the full stop too: "Mama didelė." ends the clause
        val rest = after.substring(0, end)
        if (rest.none { it.isLetter() }) return null
        val before = textBefore
        var start = before.length
        while (start > 0 && before[start - 1] !in SENTENCE_ENDS) start--
        return before.substring(start) to rest
    }

    /** A word touched by the cursor: what its spans offered (autocorrect's typed word) after Varnelė's own. */
    private fun addResumed(typed: String, out: ArrayList<SuggestedWordInfo>) {
        val (word, words) = resumed ?: return
        if (word != typed) {
            resumed = null   // the word was edited
            return
        }
        var sc = (out.minOfOrNull { it.mScore } ?: VALID_SCORE) - 1
        for (w in words) {
            if (out.none { it.mWord == w }) out.add(info(w, sc.coerceAtLeast(1), SuggestedWordInfo.KIND_CORRECTION))
            sc--
        }
    }

    /**
     * Next-word predictions, before a letter is typed: Varnelė's language model on the text before
     * the cursor (the previous word and the paragraph's topic), in its order.
     */
    private fun predictions(ngramContext: NgramContext): ArrayList<SuggestedWordInfo> {
        val out = ArrayList<SuggestedWordInfo>()
        val context = textBefore.ifEmpty { previousWords(ngramContext) }
        val t = System.nanoTime()
        val words = Native.predict(context, "", PREDICTIONS)
        if (BuildConfig.DEBUG) Log.d(TAG, "predict '${context.takeLast(40)}': ${words.map { it.first }} " +
            "${(System.nanoTime() - t) / 1000} µs")
        words.forEachIndexed { i, (w, _) -> out.add(info(w, PREDICTION_SCORE - i * 1000, SuggestedWordInfo.KIND_PREDICTION)) }
        return out
    }

    /**
     * A swipe: Varnelė's gesture search finds the keys swiped (no diacritics), then each is
     * checked like a word typed without diacritics, with the words before it, to choose its
     * diacritics (pries -> prieš, dienu -> dienų). The first one is committed.
     */
    private fun gestureSuggestions(composedData: ComposedData, ngramContext: NgramContext): ArrayList<SuggestedWordInfo> {
        val out = ArrayList<SuggestedWordInfo>()
        val geometry = gestureGeometry ?: return out
        val pointers = composedData.mInputPointers
        val n = pointers.pointerSize
        if (n == 0) return out
        val px = pointers.xCoordinates
        val py = pointers.yCoordinates
        val xs = FloatArray(n) { (px[it] + geometry.offsetX) / geometry.unit }
        val ys = FloatArray(n) { (py[it] + geometry.offsetY) / geometry.unit }
        val context = previousWords(ngramContext)
        val t = System.nanoTime()
        val candidates = Native.gesture(xs, ys, geometry.keys, geometry.keyX, geometry.keyY, context, GESTURE_CANDIDATES)
        val words = LinkedHashSet<String>()
        for (c in candidates) words.add(withDiacritics(c, context))
        if (BuildConfig.DEBUG) Log.d(TAG, "gesture '$context' + $n points: ${candidates.take(3).map { it.keys }} -> " +
            "${words.take(3)} ${(System.nanoTime() - t) / 1000} µs")
        words.forEachIndexed { i, w -> out.add(info(w, GESTURE_SCORE - i * 1000, SuggestedWordInfo.KIND_CORRECTION)) }
        return out
    }

    /** As typing the keys without diacritics would: a diacritic-only context fix of a valid word
     *  (metu -> metų, rare twins), else the word itself; for an unknown word its best diacritic-only
     *  suggestion, else the gesture search's own best form. */
    private fun withDiacritics(c: Native.GestureCandidate, context: String): String {
        val r = Native.check(c.keys, context) ?: return c.best
        if (r.isValid) return r.contextFixes.firstOrNull { it.diacriticOnly }?.word ?: c.keys
        return r.suggestions.firstOrNull { it.diacriticOnly }?.word ?: c.best
    }

    override fun isInDictionary(word: String): Boolean {
        validCache.get(word)?.let { return it }
        val valid = Native.check(word)?.isValid ?: false
        validCache.put(word, valid)
        return valid
    }

    override fun getFrequency(word: String): Int = if (isInDictionary(word)) 150 else NOT_A_PROBABILITY

    override fun getMaxFrequencyOfExactMatches(word: String): Int = getFrequency(word)

    /** Up to three previous words of the current sentence, oldest first. */
    /**
     * The sentence before the word, its last 80 characters, when HeliBoard's n-gram context is empty although
     * the sentence has words: after a comma, colon, quote or bracket (Labas, Boruta: Boruta is a name inside
     * a sentence, not a sentence start).
     */
    private fun sentenceSoFar(): String {
        val before = textBefore
        var start = before.length
        while (start > 0 && before[start - 1] !in SENTENCE_ENDS) start--
        val s = before.substring(maxOf(start, before.length - 80)).trim()
        return if (s.any { it.isLetter() }) s else ""
    }

    private fun previousWords(ngramContext: NgramContext): String {
        val words = ArrayList<String>()
        for (n in 1..3) {
            val w = ngramContext.getNthPrevWord(n)?.toString()
            if (w.isNullOrBlank()) break   // start of the text: no stray leading space
            words.add(w)
            if (ngramContext.isNthPrevWordBeginningOfSentence(n)) break
        }
        return words.asReversed().joinToString(" ")
    }

    private fun info(word: String, score: Int, kind: Int) = SuggestedWordInfo(
        word, "", score, kind, this, SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE,
    )

    /**
     * HeliBoard autocorrects when score / 1,000,000 * (1 - editDistance / length) passes the
     * user's threshold, counting every changed letter, diacritics included: zodzou -> žodžių
     * is 4 edits in 6 letters and would never pass. So aim for a normalized value based on
     * Varnelė's diacritic-free typo cost and divide by HeliBoard's own weight; the user's
     * autocorrect strength setting still decides.
     */
    private fun score(typed: String, c: Native.Candidate): Int {
        val target = when {
            c.diacriticOnly -> 1.0
            c.level >= 1 -> 0.9 - 0.4 * c.typoCost                           // one typo: ~0.5
            else -> 0.05                                                     // shown, not applied
        }
        val weight = 1.0 - editDistance(typed.lowercase(), c.word.lowercase()).toDouble() / c.word.length
        if (weight <= 0.0) return if (c.diacriticOnly) WHITELIST_FLOOR else 1
        return (target * 1_000_000 / weight).coerceIn(1.0, 1_900_000_000.0).toInt()
            .let { if (c.diacriticOnly) maxOf(it, WHITELIST_FLOOR) else it }
    }

    /** Plain Levenshtein distance, as HeliBoard's native calcNormalizedScore uses. */
    private fun editDistance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }

    /** The letter keys of the keyboard on screen, for gestures, in key widths. */
    private class GestureGeometry(
        val keys: String, val keyX: FloatArray, val keyY: FloatArray, val unit: Float, val offsetX: Float, val offsetY: Float,
    )

    companion object {
        @Volatile
        private var gestureGeometry: GestureGeometry? = null

        /**
         * The text before the word being typed (InputLogic sets it before asking for suggestions):
         * predictions use the whole paragraph, the NgramContext has only three words.
         */
        @Volatile
        @JvmStatic
        var textBefore: String = ""

        /** The text after the word being typed (set with [textBefore]): a word typed or touched inside a sentence. */
        @Volatile
        @JvmStatic
        var textAfter: String = ""

        /** A word touched by the cursor and the words its SuggestionSpans held (InputLogic.restartSuggestions). */
        @Volatile
        private var resumed: Pair<String, List<String>>? = null

        @JvmStatic
        fun setResumed(word: String, spanWords: List<String>) {
            resumed = word to spanWords
        }

        private const val SENTENCE_ENDS = ".!?…\n"

        /** Marks the ghost in the strip (SuggestionStripLayoutHelper greys its completed part). */
        @JvmField
        val GHOST: Dictionary = Dictionary.PhonyDictionary("varnele_ghost")

        /**
         * The ghost: the word being typed completed (kod -> kodėl), and the next word too when the
         * language model is nearly sure of it (Rust `keyboard_ghost`). Shown in the strip, committed
         * only when tapped. Null if nothing completes [typed] or [typed] itself is likelier.
         * The next word only if its setting is on.
         */
        @JvmStatic
        fun ghost(typed: String): String? {
            if (typed.isEmpty() || !typed.all { it.isLetter() }) return null
            val t = System.nanoTime()
            var ghost = Native.ghost(textBefore, typed)
            if (!Settings.getValues().mVarneleGhostNext) ghost = ghost?.substringBefore(' ')
            if (BuildConfig.DEBUG) Log.d(TAG, "ghost '$typed': $ghost ${(System.nanoTime() - t) / 1000} µs")
            return ghost
        }

        @JvmStatic
        fun isGhost(info: SuggestedWordInfo) = info.mSourceDict === GHOST

        /**
         * The keyboard shown (MainKeyboardView.setKeyboard): gesture points are in view coordinates,
         * keys in keyboard coordinates, [offsetX]/[offsetY] (as given to the key detector) turn one into the other.
         */
        @JvmStatic
        fun setGestureKeyboard(keyboard: Keyboard, offsetX: Float, offsetY: Float) {
            val unit = keyboard.mMostCommonKeyWidth.toFloat()
            if (unit <= 0f) return
            val keys = StringBuilder()
            val kx = ArrayList<Float>()
            val ky = ArrayList<Float>()
            for (key in keyboard.sortedKeys) {
                val c = key.code
                if (c !in 'A'.code..'Z'.code && c !in 'a'.code..'z'.code) continue
                val letter = c.toChar().lowercaseChar()
                if (keys.indexOf(letter) >= 0) continue
                keys.append(letter)
                kx.add((key.x + key.width / 2f) / unit)
                ky.add((key.y + key.height / 2f) / unit)
            }
            gestureGeometry = GestureGeometry(keys.toString(), kx.toFloatArray(), ky.toFloatArray(), unit, offsetX, offsetY)
            // view coordinates of the key centres, for replaying swipes with adb shell input motionevent
            if (BuildConfig.DEBUG) Log.d(TAG, "gesture keys (view px): " + keys.indices.joinToString(" ") {
                "${keys[it]}=${(kx[it] * unit - offsetX).toInt()},${(ky[it] * unit - offsetY).toInt()}"
            })
        }

        /** Gesture typing works with Google's library (any language) or Varnelė (Lithuanian). */
        @JvmStatic
        fun gesturesFor(locale: Locale) = JniUtils.sHaveGestureLib || (Native.available && locale.language == "lt")

        /** For the settings screens: gesture typing can work for some language. */
        @JvmStatic
        val gesturesAvailable get() = JniUtils.sHaveGestureLib || Native.available


        private const val TAG = "Varnele"
        private const val VALID_SCORE = 1_200_000
        private const val CONTEXT_FIX_SCORE = 1_300_000   // above the typed word: autocorrects
        private const val CONTEXT_HINT_SCORE = 1_100_000  // below it: shown, not applied
        private const val GESTURE_CANDIDATES = 5
        private const val PREDICTIONS = 5
        private const val PREDICTION_SCORE = 1_000_000  // predictions keep Varnelė's order
        private const val GESTURE_SCORE = 1_000_000  // gesture suggestions keep Varnelė's order
        private const val WHITELIST_FLOOR = 1_300_000
        // every letter differs, so HeliBoard's distance weight is tiny: needs a large raw score
        private const val KEY_SLIP_SCORE = 1_900_000_000
        private const val AUTOCORRECT_KIND =
            SuggestedWordInfo.KIND_WHITELIST or SuggestedWordInfo.KIND_FLAG_APPROPRIATE_FOR_AUTO_CORRECTION

        fun isAvailable(locale: Locale) = locale.language == "lt" && Native.available

        /**
         * A committed word Varnelė does not know gets a misspelled SuggestionSpan, so the text
         * field draws the red underline and offers the corrections on tap, as Gboard does,
         * whatever the system spell checker is. Words with digits or punctuation are left alone.
         */
        @JvmStatic
        fun markMisspelled(context: Context, text: CharSequence, locale: Locale): CharSequence {
            val word = text.toString()
            if (word.length < 2 || !word.all { it.isLetter() || it == '-' }) return text
            // the words before it in the sentence: with none, a capital may be the keyboard's own
            // (Ziwma -> Žiema), but a capitalised foreign word inside a sentence is a name (Wiley)
            val before = textBefore.substring(textBefore.indexOfLast { it in SENTENCE_ENDS } + 1)
            val previous = before.split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }.takeLast(3).joinToString(" ")
            val r = Native.check(word, previous) ?: return text
            if (r.isValid) return text
            val suggestions = r.suggestions.take(SuggestionSpan.SUGGESTIONS_MAX_SIZE).map { it.word }.toTypedArray()
            // FLAG_MISSPELLED alone: text fields drop a span that also has FLAG_EASY_CORRECT
            val span = SuggestionSpan(context, locale, suggestions, SuggestionSpan.FLAG_MISSPELLED, null)
            val out = SpannableString(text)
            for (old in out.getSpans(0, out.length, SuggestionSpan::class.java)) out.removeSpan(old)
            out.setSpan(span, 0, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return out
        }
    }
}
