// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.inputlogic

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import helium314.keyboard.keyboard.Key
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.keyboard.VarneleTouchModel
import helium314.keyboard.latin.WordComposer
import helium314.keyboard.latin.settings.SettingsValues
import helium314.keyboard.latin.utils.FoldableUtils
import helium314.keyboard.latin.utils.InputTypeUtils
import java.io.File
import java.io.OutputStream
import java.text.Normalizer
import java.util.Locale

/**
 * Records where each tap lands on the key it was meant for (setting "record typing accuracy", off by
 * default), to measure whether misses are systematic, and teaches [VarneleTouchModel] the same taps
 * (setting "adapt keys to my typing", on by default).
 *
 * A word's taps are labelled with the word as it was kept: committed (typed, autocorrected or picked)
 * and not reverted with backspace before the next word. Only words whose letters match the taps one
 * to one (diacritics ignored: `zodziu` kept as `žodžių`) are used, and only taps on the main keyboard's
 * letter keys. A tap whose letter is the word's, with or without its diacritics, was meant for the key
 * hit (`ä` on a German `ä` key, `z` for `ž`); a miss was meant for the word's letter without diacritics. Each tap becomes a row: the letter meant, the key hit, the letter before it, the offset
 * from the meant key's centre in key widths and heights, and the conditions. Rows are shuffled in
 * batches of [BATCH] before they are written, so the file can't be read back as text. Not recorded in
 * incognito mode, fields that ask for no learning, passwords, URLs and e-mail addresses.
 */
object VarneleTapLog {
    private const val TAG = "Varnele"
    private const val FILE = "varnele_taps.tsv"
    private const val BATCH = 200
    private const val MAX_BYTES = 16L shl 20
    private const val HEADER = "meant\thit\tprev\tpos\tdx\tdy\tx\ty\tkey_w\tkey_h\trow\tkb_w\tkb_h\t" +
        "lang\tcorrected\tlandscape\tfolded\tsplit\tone_handed\tscreen_dp\n"

    private var file: File? = null
    private var appContext: Context? = null
    private var keyboard: Keyboard? = null
    private var letterKeys: Map<Char, Key> = emptyMap()
    private var rowTops: List<Int> = emptyList()
    private var pending: Pending? = null

    /** A word's taps: the meant key and where it was hit; [rows] for the log when it is on. */
    private class Pending(val keyboard: Keyboard, val screen: String, val taps: List<Triple<Key, Int, Int>>,
        val rows: List<String>?)
    private val buffer = ArrayList<String>()

    fun init(context: Context) {
        appContext = context.applicationContext
        file = File(context.filesDir, FILE)
        VarneleTouchModel.init(context.filesDir)
    }

    /** The keyboard shown (MainKeyboardView.setKeyboard); taps are in its coordinates. */
    @JvmStatic
    fun setKeyboard(kb: Keyboard) {
        val keys = HashMap<Char, Key>()
        for (key in kb.sortedKeys) {
            val c = key.code
            if (c <= 0 || !Character.isLetter(c) || Character.isSupplementaryCodePoint(c)) continue
            keys.putIfAbsent(Character.toLowerCase(c).toChar(), key)
        }
        if (keys.isEmpty()) return  // symbols: a word's separator may be typed there, keep the letters
        keyboard = kb
        letterKeys = keys
        rowTops = keys.values.map { it.y }.distinct().sorted()
    }

    fun mayRecord(settings: SettingsValues): Boolean =
        (settings.mVarneleTapLog || settings.mVarneleTouchModel)
            && !settings.mIncognitoModeEnabled
            && !settings.mInputAttributes.mIsPasswordField
            && !InputTypeUtils.isUriOrEmailType(settings.mInputAttributes.mInputType)

    /** Before [composer]'s word is committed as [committed]: the previous word was kept. */
    @Synchronized
    fun onCommit(composer: WordComposer, committed: String, settings: SettingsValues, locale: Locale) {
        accept()
        if (!mayRecord(settings) || composer.isBatchMode || composer.isResumed) return
        pending = rows(composer, committed, settings, locale)
    }

    /** The last word was reverted with backspace: its taps meant what was typed, but may be retyped. */
    @Synchronized
    fun onRevert() {
        pending = null
    }

    /** The field was left: the last word was kept. */
    @Synchronized
    fun onFinishInput() {
        accept()
        VarneleTouchModel.save()
    }

    private fun accept() {
        val p = pending ?: return
        pending = null
        for ((key, x, y) in p.taps) VarneleTouchModel.learn(p.keyboard, key, x, y, p.screen)
        if (p.rows == null) return
        buffer.addAll(p.rows)
        if (buffer.size >= BATCH) write()
    }

    private fun rows(composer: WordComposer, committed: String, settings: SettingsValues, locale: Locale): Pending? {
        val kb = keyboard ?: return null
        val typed = composer.typedWord.lowercase(locale)
        val word = Normalizer.normalize(committed.lowercase(locale), Normalizer.Form.NFC)
        val folded = fold(word)
        val n = composer.size()
        if (n < 1 || typed.length != n || folded.length != n) return null
        val meant = CharArray(n) { i ->
            if (folded[i] == typed[i] || (word.length == n && word[i] == typed[i])) typed[i] else folded[i]
        }
        val pointers = composer.inputPointers
        if (pointers.pointerSize < n) return null
        val xs = pointers.xCoordinates
        val ys = pointers.yCoordinates
        val config = appContext?.resources?.configuration
        val conditions = listOf(
            locale.language,
            if (typed == fold(committed.lowercase(locale))) 0 else 1,
            if (config?.orientation == Configuration.ORIENTATION_LANDSCAPE) 1 else 0,
            if (FoldableUtils.isFolded) 1 else 0,
            if (kb.mId.isSplitLayout) 1 else 0,
            if (kb.mId.oneHandedModeEnabled) 1 else 0,
            "${config?.screenWidthDp ?: 0}x${config?.screenHeightDp ?: 0}",
        ).joinToString("\t")
        val out = ArrayList<String>(n)
        val taps = ArrayList<Triple<Key, Int, Int>>(n)
        for (i in 0 until n) {
            val meantKey = letterKeys[meant[i]] ?: return null
            val hitKey = letterKeys[typed[i]] ?: return null  // not a popup key: its tap is elsewhere
            val x = xs[i]
            val y = ys[i]
            if (x < 0 || y < 0) return null
            if (settings.mVarneleTouchModel) taps.add(Triple(meantKey, x, y))
            if (!settings.mVarneleTapLog) continue
            val dx = (x - (meantKey.x + meantKey.width / 2f)) / meantKey.width
            val dy = (y - (meantKey.y + meantKey.height / 2f)) / meantKey.height
            out.add(listOf(
                meant[i], hitKey.code.toChar().lowercaseChar(), if (i == 0) "_" else meant[i - 1].toString(), i,
                "%.3f".format(Locale.US, dx), "%.3f".format(Locale.US, dy),
                "%.4f".format(Locale.US, x.toFloat() / kb.mOccupiedWidth),
                "%.4f".format(Locale.US, y.toFloat() / kb.mOccupiedHeight),
                meantKey.width, meantKey.height, rowTops.indexOf(meantKey.y),
                kb.mOccupiedWidth, kb.mOccupiedHeight, conditions,
            ).joinToString("\t"))
        }
        val screen = if (config == null) "" else VarneleTouchModel.screenKey(config, kb)
        return Pending(kb, screen, taps, if (settings.mVarneleTapLog) out else null)
    }

    private fun fold(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(MARKS, "")
    private val MARKS = Regex("\\p{Mn}+")

    @Synchronized
    private fun write() {
        val f = file ?: return
        if (buffer.isEmpty()) return
        val rows = buffer.shuffled()
        buffer.clear()
        try {
            if (f.length() > MAX_BYTES) return
            val header = !f.exists() || f.length() == 0L
            f.appendText((if (header) HEADER else "") + rows.joinToString("\n", postfix = "\n"))
        } catch (e: Exception) {
            Log.w(TAG, "could not write the tap log", e)
        }
    }

    /** Writes what is buffered (shuffled) and copies the log to [out]; false if there is none. */
    @Synchronized
    fun export(context: Context, out: OutputStream): Boolean {
        if (file == null) init(context)
        accept()
        write()
        val f = file ?: return false
        if (!f.exists()) return false
        f.inputStream().use { it.copyTo(out) }
        return true
    }

    /** Taps recorded so far, written or not. */
    @Synchronized
    fun count(context: Context): Int {
        if (file == null) init(context)
        val f = file
        val written = if (f != null && f.exists()) f.useLines { lines -> lines.count() - 1 }.coerceAtLeast(0) else 0
        return written + buffer.size + (pending?.rows?.size ?: 0)
    }

    @Synchronized
    fun delete(context: Context) {
        if (file == null) init(context)
        pending = null
        buffer.clear()
        file?.delete()
    }
}
