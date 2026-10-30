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
        val settings = Settings.getValues()
        val (delete, insert) = Native.fixSentence(before, connection.expectedSelectionStart - before.length, picked,
            settings.mVarneleFixEarlier, settings.mVarneleCommas) ?: return false
        if (BuildConfig.DEBUG) Log.d(TAG, "sentence fix: '${before.takeLast(delete)}' -> '$insert'")
        connection.beginBatchEdit()
        connection.deleteTextBeforeCursor(delete)
        connection.commitText(insert, 1)
        connection.endBatchEdit()
        return true
    }
}
