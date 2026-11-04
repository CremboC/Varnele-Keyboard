// SPDX-License-Identifier: GPL-3.0-only
package dev.imbrasas.varnele

import android.content.Context
import android.util.Log
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bridge to Varnelė, the rule-based Lithuanian checker (Rust, libvarnele_android.so,
 * built from https://github.com/CremboC/varnele). The class name must stay
 * dev.imbrasas.varnele.Native: the JNI functions are named after it.
 */
object Native {
    /** cost: Varnelė's ranking (lower is better); typoCost: edits ignoring diacritics (0 = only diacritics differ) */
    /** level: 2 applied without asking, 1 autocorrect may take it, 0 only offered (Varnelė decides) */
    data class Candidate(val word: String, val cost: Double, val typoCost: Double, val level: Int) {
        val diacriticOnly get() = typoCost == 0.0
    }

    /** status: ok | colloquial | unknown | skip */
    data class WordResult(
        val status: String,
        val suggestions: List<Candidate>,
        val contextRule: String,
        val contextFixes: List<Candidate>,
        /** the first suggestions come from the whole-word key-shift search (fofrkua -> didelis) */
        val keyShift: Boolean,
    ) {
        val isValid get() = status != "unknown"
    }

    /**
     * Formal mode (setting): spoken forms (valgyt, einam) are corrected to the standard ones and
     * never suggested, typed or swiped. Set from the settings whenever they are loaded.
     */
    @Volatile
    @JvmStatic
    var formal: Boolean = false

    /**
     * Learn from the user's writing (setting): the words and word pairs of the text as it ends up
     * are learned on the phone and mixed into predictions, the ghost and swipe ranking. Off: the
     * learned words are neither used nor added to (they stay until forgotten).
     */
    @Volatile
    private var learnSet: Boolean? = null

    @JvmStatic
    var learn: Boolean
        get() = learnSet ?: false
        set(on) {
            if (learnSet == on) return
            learnSet = on
            // not before userLoad (that would load the data for a keyboard not typing Lithuanian),
            // nor on the thread that reads the settings
            if (userLoaded) Thread { userEnabledNative(on) }.start()
        }

    @Volatile
    private var userLoaded = false

    private val libraryLoaded: Boolean = try {
        System.loadLibrary("varnele_android")
        true
    } catch (e: UnsatisfiedLinkError) {
        Log.w("Varnele", "native library not available", e)
        false
    }

    /** The data file (asset varnele.bin), memory-mapped: the library reads it in place, so it is kept. */
    @Volatile
    private var data: ByteBuffer? = null

    /**
     * False if the library is missing for this CPU type or its data could not be mapped; the
     * keyboard then works without Varnelė.
     */
    val available: Boolean get() = data != null

    @JvmStatic
    private external fun dataNative(data: ByteBuffer)

    /** Hands the library its data (App.onCreate, before anything else; later calls do nothing). */
    @Synchronized
    fun init(context: Context) {
        if (!libraryLoaded || data != null) return
        try {
            // stored uncompressed (noCompress in app/build.gradle.kts), so it can be mapped from the APK
            val buf = context.assets.openFd("varnele.bin").use { fd ->
                FileInputStream(fd.fileDescriptor).use {
                    it.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
            }
            dataNative(buf)
            data = buf
        } catch (e: java.io.IOException) {
            Log.w("Varnele", "data file not available", e)
        }
    }

    @JvmStatic
    private external fun warmUpNative()

    @JvmStatic
    private external fun word(word: String, context: String, after: String, keyShift: Boolean, formal: Boolean): String

    @JvmStatic
    private external fun check(text: String, formal: Boolean): String

    @JvmStatic
    private external fun gestureNative(
        xs: FloatArray, ys: FloatArray, keys: String, keyX: FloatArray, keyY: FloatArray, context: String, n: Int,
        formal: Boolean,
    ): String

    @JvmStatic
    private external fun predictNative(context: String, prefix: String, n: Int, formal: Boolean): String

    /**
     * The words likeliest to come next after [context] (the text before the word, up to a
     * paragraph), with their probability, likeliest first. With a [prefix] (typed with or
     * without diacritics) only the words it begins, longer than it: completions.
     */
    fun predict(context: String, prefix: String, n: Int): List<Pair<String, Double>> {
        if (!available) return emptyList()
        val a = JSONArray(predictNative(context, prefix, n, formal))
        return (0 until a.length()).map { a.getJSONObject(it).let { o -> o.getString("w") to o.getDouble("p") } }
    }

    @JvmStatic
    private external fun ghostNative(context: String, typed: String, formal: Boolean): String

    /**
     * The ghost for the word being typed after [context]: its completion (kod -> kodėl), maybe
     * with the next word; null when nothing completes it or the word as typed is likelier.
     */
    fun ghost(context: String, typed: String): String? =
        if (!available) null else ghostNative(context, typed, formal).ifEmpty { null }

    @JvmStatic
    private external fun fixRememberNative(before: String, base: Int, typed: String, committed: String)

    @JvmStatic
    private external fun fixUndoneNative()

    @JvmStatic
    private external fun fixNative(before: String, base: Int, picked: Boolean, earlier: Boolean, commas: Boolean, formal: Boolean): String

    /** Autocorrect committed [committed] for [typed]; [before] ends with it, [base] is where it starts in the field. */
    fun fixRemember(before: String, base: Int, typed: String, committed: String) {
        if (available) fixRememberNative(before, base, typed, committed)
    }

    /** The last autocorrection was undone: its word stays as typed. */
    fun fixUndone() {
        if (available) fixUndoneNative()
    }

    /**
     * The sentence fixer on the text before the cursor: how many UTF-16 units to delete before the
     * cursor and what to put there, or null if nothing changes.
     */
    fun fixSentence(before: String, base: Int, picked: Boolean, earlier: Boolean, commas: Boolean): Pair<Int, String>? {
        if (!available) return null
        val o = JSONObject(fixNative(before, base, picked, earlier, commas, formal))
        val delete = o.getInt("delete")
        val insert = o.getString("insert")
        return if (delete == 0 && insert.isEmpty()) null else delete to insert
    }

    @JvmStatic private external fun userLoadNative(path: String)
    @JvmStatic private external fun userEnabledNative(on: Boolean)
    @JvmStatic private external fun userObserveNative(text: String, cut: Boolean)
    @JvmStatic private external fun userCommitNative(gone: Boolean)
    @JvmStatic private external fun userSaveNative(): Boolean
    @JvmStatic private external fun userForgetNative()
    @JvmStatic private external fun userWordsNative(): Int

    /** Load the learned words from [path] (an app-private file) and save them there. */
    fun userLoad(path: String) {
        if (!available) return
        userLoadNative(path)
        userLoaded = true
        userEnabledNative(learn)
    }

    /**
     * The text before the cursor after a finished word, as it ends up: finished sentences are
     * learned. [cut]: the text is the end of a longer one (its first sentence may be a piece).
     */
    fun userObserve(text: String, cut: Boolean) { if (available) userObserveNative(text, cut) }

    /**
     * The message is done: its last sentence is learned too. [gone]: sent or the field cleared, so
     * the same text can't be seen again; otherwise the field was left (it may be refocused).
     */
    fun userCommit(gone: Boolean) { if (available) userCommitNative(gone) }

    /** Save the learned words if they changed; false if the file could not be written. */
    fun userSave(): Boolean = !available || userSaveNative()

    /** Forget all learned words and delete the file. */
    fun userForget() { if (available) userForgetNative() }

    /** How many words have been learned (old ones count less). */
    fun userWords(): Int = if (available) userWordsNative() else 0

    /** keys: the letters swiped (no diacritics); best: the likeliest real form of them */
    data class GestureCandidate(val keys: String, val best: String, val cost: Double)

    /**
     * The words a swipe may spell, best first. The path and the key centres (letters in [keys],
     * centres in [keyX]/[keyY]) are divided by the key width; [context] is the text before the word.
     */
    fun gesture(
        xs: FloatArray, ys: FloatArray, keys: String, keyX: FloatArray, keyY: FloatArray, context: String, n: Int,
    ): List<GestureCandidate> {
        if (!available) return emptyList()
        val a = JSONArray(gestureNative(xs, ys, keys, keyX, keyY, context, n, formal))
        return (0 until a.length()).map {
            val c = a.getJSONObject(it)
            GestureCandidate(c.getString("w"), c.getString("best"), c.getDouble("cost"))
        }
    }

    /** A problem in a text; offsets are UTF-16 indices into it. */
    data class Finding(
        val start: Int, val end: Int, val word: String, val rule: String, val suggestions: List<String>,
    )

    /** All findings in a text (spelling and grammar). */
    fun findings(text: String): List<Finding> {
        if (!available) return emptyList()
        val arr = JSONArray(check(text, false))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val s = o.getJSONArray("suggestions")
            Finding(o.getInt("start"), o.getInt("end"), o.getString("word"), o.getString("rule"),
                (0 until s.length()).map { s.getString(it) })
        }
    }

    /** Loads the data (~100 ms) so the first keystroke is fast. */
    fun warmUp() {
        if (available) warmUpNative()
    }

    /**
     * The word being typed, with the previous words of the sentence as context. Inside a sentence
     * [after] is the rest of it (and [context] the sentence before the word): the words after it decide too.
     */
    fun check(word: String, context: String = "", keyShift: Boolean = false, after: String = ""): WordResult? {
        if (!available) return null
        val o = JSONObject(word(word, context, after, keyShift, formal))
        fun list(a: JSONArray) = (0 until a.length()).map {
            val c = a.getJSONObject(it)
            Candidate(c.getString("w"), c.getDouble("cost"), c.getDouble("fcost"), c.getInt("level"))
        }
        return WordResult(
            o.getString("status"), list(o.getJSONArray("suggestions")),
            o.getString("contextRule"), list(o.getJSONArray("contextFixes")),
            o.optBoolean("keyShift", false),
        )
    }
}
