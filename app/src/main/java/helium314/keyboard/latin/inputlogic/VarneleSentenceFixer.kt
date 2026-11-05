// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.inputlogic

import android.util.Log
import dev.imbrasas.varnele.Native
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.RichInputConnection
import helium314.keyboard.latin.settings.Settings
import java.util.Locale

/**
 * After each finished word, Varnelė looks at the sentence before the cursor and says how to change
 * it (Rust: SentenceFixer): the word just finished, earlier words by the words after them, commas,
 * and the diacritics the keyboard gave a word itself. This hands it the text and applies the edit.
 */
object VarneleSentenceFixer {
    private const val TAG = "Varnele"
    private const val MAX_CONTEXT = 400

    fun isActive(locale: Locale) = locale.language == "lt" && Native.available

    /** Autocorrect committed [committed] for [typed]: Varnelė may change its diacritics later. */
    fun rememberAutocorrection(connection: RichInputConnection, typed: String, committed: String) {
        val before = connection.getTextBeforeCursor(MAX_CONTEXT, 0)?.toString() ?: return
        Native.fixRemember(before, connection.expectedSelectionStart - before.length, typed, committed)
    }

    /** The last autocorrection was undone (backspace): its word stays as typed. */
    fun forgetLastAutocorrection() = Native.fixUndone()

    /**
     * @param picked the last word was picked from the suggestion strip: the user chose it,
     *   so it is left alone (only earlier words and commas are fixed)
     * @return true if the text was changed
     */
    @JvmOverloads
    fun apply(connection: RichInputConnection, picked: Boolean = false): Boolean {
        val before = connection.getTextBeforeCursor(MAX_CONTEXT, 0)?.toString() ?: return false
        if (connection.expectedSelectionStart != connection.expectedSelectionEnd) return false
        // the rest of the sentence, read now (a cached copy may be from before a picked suggestion or a
        // cursor swipe): it is checked and fixed too, a word typed into the middle of a sentence decided by
        // the words after it. A piece cut at the limit loses its last, maybe partial, word (kavinė|je).
        var after = connection.getTextAfterCursor(MAX_CONTEXT, 0)?.toString() ?: ""
        if (after.length >= MAX_CONTEXT) after = after.substring(0, maxOf(0, after.lastIndexOf(' ')))
        val settings = Settings.getValues()
        val e = Native.fixSentence(before, after, connection.expectedSelectionStart - before.length, picked,
            settings.mVarneleFixEarlier, settings.mVarneleCommas) ?: return false
        if (BuildConfig.DEBUG) Log.d(TAG, "sentence fix: '${before.takeLast(e.delete)}' -> '${e.insert}'" +
            if (e.deleteAfter > 0 || e.insertAfter.isNotEmpty())
                ", after: '${after.substring(e.skip, e.skip + e.deleteAfter)}' -> '${e.insertAfter}'" else "")
        connection.beginBatchEdit()
        if (e.delete > 0 || e.insert.isNotEmpty()) {
            connection.deleteTextBeforeCursor(e.delete)
            connection.commitText(e.insert, 1)
        }
        if (e.deleteAfter > 0 || e.insertAfter.isNotEmpty()) {
            // select the words to change after the cursor, replace them, put the cursor back; if the app
            // refuses the selection, leave them (committing would insert at the cursor instead)
            val cursor = connection.expectedSelectionStart
            if (connection.setSelection(cursor + e.skip, cursor + e.skip + e.deleteAfter))
                connection.commitText(e.insertAfter, 1)
            connection.setSelection(cursor, cursor)
        }
        connection.endBatchEdit()
        return true
    }
}
