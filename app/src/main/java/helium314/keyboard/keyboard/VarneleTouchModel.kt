// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import android.content.res.Configuration
import android.util.Log
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Where this user's taps land on each letter key, learned from the words they keep (setting "adapt keys
 * to my typing", any language), and the key detection that follows from it: a key's hit box moves by the
 * mean offset of the taps meant for it, so a tap goes to the key whose moved box holds it.
 *
 * A key is a place, not a letter: its letter row counted from the bottom and its column in half keys, so
 * QWERTY, QWERTZ and a Cyrillic layout of the same shape share what was learned. Offsets are kept per
 * screen (orientation, size in dp, split, one-handed) in key pitches (the key and its gaps, as its hit
 * box) from the hit box's centre, which lies a gap's half below the key drawn: HeliBoard's hit box holds
 * the gap below the key; a key's own mean is shrunk towards its half of the row and the row's ([PRIOR] taps), the counts
 * halve every [HALVE_AT] taps so the model follows the user, and nothing moves before [MIN_TAPS].
 * Parameters from 6,613 logged English taps on the Fold7's cover screen, replayed in order: keys missed
 * 350 → 288 (112 taps now right, 50 now wrong).
 *
 * Only means are stored (`files/varnele_touch.tsv`), no text.
 */
object VarneleTouchModel {
    private const val TAG = "Varnele"
    private const val FILE = "varnele_touch.tsv"
    const val PRIOR = 30f
    const val MIN_TAPS = 100f
    const val HALVE_AT = 1000f
    const val MAX_SHIFT = 0.3f
    // taps further from their key's hit-box centre are mislabelled or slips, not aim
    const val MAX_DX = 0.9f
    const val MAX_DY = 0.7f

    class Stats(var n: Float = 0f, var sx: Float = 0f, var sy: Float = 0f)

    /** The counts, keyed `screen|t` (all keys), `screen|rROW`, `screen|hROW:SIDE`, `screen|kROW:COL`. */
    class Model {
        val stats = HashMap<String, Stats>()

        fun add(screen: String, cell: Cell, dx: Float, dy: Float): Boolean {
            if (abs(dx) > MAX_DX || abs(dy) > MAX_DY) return false
            val total = stats.getOrPut("$screen|t") { Stats() }
            for (key in listOf("$screen|t", "$screen|r${cell.row}", "$screen|h${cell.row}:${cell.side}",
                    "$screen|k${cell.row}:${cell.col}")) {
                val s = stats.getOrPut(key) { Stats() }
                s.n += 1f; s.sx += dx; s.sy += dy
            }
            if (total.n > HALVE_AT) {
                val prefix = "$screen|"
                for ((k, s) in stats) if (k.startsWith(prefix)) { s.n /= 2; s.sx /= 2; s.sy /= 2 }
            }
            return true
        }

        /** The learned offset of [cell] in hit-box units, or null before [MIN_TAPS]. */
        fun offset(screen: String, cell: Cell): FloatArray? {
            val total = stats["$screen|t"] ?: return null
            if (total.n < MIN_TAPS) return null
            var mx = total.sx / total.n
            var my = total.sy / total.n
            for (key in listOf("$screen|r${cell.row}", "$screen|h${cell.row}:${cell.side}", "$screen|k${cell.row}:${cell.col}")) {
                val s = stats[key] ?: continue
                mx = (s.sx + PRIOR * mx) / (s.n + PRIOR)
                my = (s.sy + PRIOR * my) / (s.n + PRIOR)
            }
            return floatArrayOf(mx.coerceIn(-MAX_SHIFT, MAX_SHIFT), my.coerceIn(-MAX_SHIFT, MAX_SHIFT))
        }

        fun write(): String = stats.entries.sortedBy { it.key }
            .joinToString("") { (k, s) -> "$k\t${s.n}\t${s.sx}\t${s.sy}\n" }

        fun read(text: String) {
            for (line in text.lineSequence()) {
                val f = line.split('\t')
                if (f.size != 4) continue
                val n = f[1].toFloatOrNull() ?: continue
                val sx = f[2].toFloatOrNull() ?: continue
                val sy = f[3].toFloatOrNull() ?: continue
                stats[f[0]] = Stats(n, sx, sy)
            }
        }
    }

    /** A key's place: letter row from the bottom, column in half keys, left (0) or right (1) half. */
    data class Cell(val row: Int, val col: Int, val side: Int)

    /** Each letter key's hit box moved by its learned offset, in keyboard pixels. */
    class Shifts(val keyboard: Keyboard, private val shifts: Map<Key, FloatArray>) {
        fun shift(key: Key): FloatArray? = shifts[key]

        /** The key whose moved hit box holds the tap, nearest centre first; null if none does. */
        fun detect(keys: List<Key>, x: Int, y: Int): Key? {
            var best: Key? = null
            var bestDistance = Float.MAX_VALUE
            for (key in keys) {
                val s = shifts[key]
                val px = if (s == null) x else (x - s[0]).roundToInt()
                val py = if (s == null) y else (y - s[1]).roundToInt()
                if (!key.isOnKey(px, py)) continue
                val dx = (px - centreX(key)) / pitchX(key)
                val dy = (py - centreY(key)) / pitchY(key)
                val d = dx * dx + dy * dy
                if (d < bestDistance) {
                    bestDistance = d
                    best = key
                }
            }
            return best
        }
    }

    private val model = Model()
    private var file: File? = null
    private var dirty = false
    @Volatile private var version = 0
    @Volatile private var enabled = true
    @Volatile private var screen = ""
    @Volatile private var keyboard: Keyboard? = null
    @Volatile private var cached: Shifts? = null
    @Volatile private var cachedVersion = -1
    @Volatile private var cellsOf: Pair<Keyboard, Map<Key, Cell>>? = null

    @Synchronized
    fun init(dir: File) {
        if (file != null) return
        val f = File(dir, FILE)
        file = f
        try {
            if (f.exists()) model.read(f.readText())
        } catch (e: Exception) {
            Log.w(TAG, "could not read the touch model", e)
        }
    }

    /** Which screen [kb] is on: offsets differ between orientations, screens and layouts. */
    @JvmStatic
    fun screenKey(config: Configuration, kb: Keyboard): String = buildString {
        append(if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) 'L' else 'P')
        append(config.screenWidthDp).append('x').append(config.screenHeightDp)
        if (kb.mId.isSplitLayout) append('s')
        if (kb.mId.oneHandedModeEnabled) append('o')
    }

    /** The keyboard shown and the screen it is on (MainKeyboardView.setKeyboard). */
    @JvmStatic
    @Synchronized
    fun setKeyboard(kb: Keyboard, screenKey: String, on: Boolean) {
        keyboard = kb
        screen = screenKey
        enabled = on
        cached = null
    }

    /** The shifts for [kb] if it is the keyboard shown and the model has learned enough for it. */
    @JvmStatic
    fun shiftsFor(kb: Keyboard?): Shifts? {
        if (!enabled || kb == null || kb !== keyboard) return null
        val c = cached
        if (c != null && c.keyboard === kb && cachedVersion == version) return c
        return compute(kb)
    }

    @Synchronized
    private fun compute(kb: Keyboard): Shifts? {
        val cells = cells(kb)
        val map = HashMap<Key, FloatArray>()
        for ((key, cell) in cells) {
            val o = model.offset(screen, cell) ?: continue
            map[key] = floatArrayOf(o[0] * pitchX(key), o[1] * pitchY(key))
        }
        val s = if (map.isEmpty()) null else Shifts(kb, map)
        if (s != null)
            Log.v(TAG, "touch shifts $screen: ${model.stats["$screen|t"]?.n?.roundToInt()} taps, " +
                map.entries.filter { it.key.code in "trhn".map { c -> c.code } }
                    .joinToString { (k, v) -> "${k.code.toChar()} ${v[0].roundToInt()},${v[1].roundToInt()} px" })
        cached = s
        cachedVersion = version
        return s
    }

    /** Learns that a tap at [x], [y] on [kb] was meant for [key]. */
    @Synchronized
    fun learn(kb: Keyboard, key: Key, x: Int, y: Int, screenKey: String) {
        val cell = cells(kb)[key] ?: return
        val dx = (x - centreX(key)) / pitchX(key)
        val dy = (y - centreY(key)) / pitchY(key)
        if (model.add(screenKey, cell, dx, dy)) {
            dirty = true
            version++
        }
    }

    @Synchronized
    fun save() {
        val f = file ?: return
        if (!dirty) return
        dirty = false
        try {
            f.writeText(model.write())
        } catch (e: Exception) {
            Log.w(TAG, "could not write the touch model", e)
        }
    }

    @Synchronized
    fun forget(dir: File) {
        if (file == null) init(dir)
        model.stats.clear()
        dirty = false
        version++
        cached = null
        file?.delete()
    }

    /** Letter keys' places on [kb]; empty for keyboards without letters. */
    fun cells(kb: Keyboard): Map<Key, Cell> {
        cellsOf?.let { if (it.first === kb) return it.second }
        val keys = kb.sortedKeys.filter { isLetter(it) }
        val tops = keys.map { it.y }.distinct().sortedDescending()
        val cells = keys.associateWith { key ->
            val cx = centreX(key)
            Cell(tops.indexOf(key.y), (2 * cx / pitchX(key)).roundToInt(), if (2 * cx >= kb.mOccupiedWidth) 1 else 0)
        }
        cellsOf = kb to cells
        return cells
    }

    // the hit box's centre and size without the edge keys' extension to the keyboard's edge
    private fun centreX(key: Key) = key.x + key.width / 2f
    private fun centreY(key: Key) = key.y + (key.height + key.verticalGap) / 2f
    private fun pitchX(key: Key) = (key.width + key.horizontalGap).toFloat()
    private fun pitchY(key: Key) = (key.height + key.verticalGap).toFloat()

    fun isLetter(key: Key): Boolean {
        val c = key.code
        return c > 0 && Character.isLetter(c) && !Character.isSupplementaryCodePoint(c)
    }
}
