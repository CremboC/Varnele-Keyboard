// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import helium314.keyboard.keyboard.VarneleTouchModel
import helium314.keyboard.keyboard.VarneleTouchModel.Cell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VarneleTouchModelTest {
    private val t = Cell(2, 9, 0)      // `t` on QWERTY: top letter row, left half
    private val r = Cell(2, 7, 0)
    private val h = Cell(1, 12, 1)     // `h`: middle row, right half

    @Test fun nothingMovesBeforeEnoughTaps() {
        val m = VarneleTouchModel.Model()
        repeat(VarneleTouchModel.MIN_TAPS.toInt() - 1) { m.add("P", t, -0.1f, 0.1f) }
        assertNull(m.offset("P", t))
        m.add("P", t, -0.1f, 0.1f)
        val o = m.offset("P", t)!!
        assertEquals(-0.1f, o[0], 1e-4f)
        assertEquals(0.1f, o[1], 1e-4f)
    }

    @Test fun aKeyWithFewTapsLeansOnItsRow() {
        val m = VarneleTouchModel.Model()
        repeat(200) { m.add("P", r, 0f, 0.1f) }
        repeat(10) { m.add("P", t, -0.2f, 0.1f) }
        val o = m.offset("P", t)!!
        // its own -0.2 against its row and half (both ~-0.01): 10 taps against PRIOR 30
        assert(o[0] < -0.03f && o[0] > -0.1f) { o[0] }
        assertEquals(0.1f, o[1], 1e-4f)
        // an unseen key gets its row's offset, another row only the screen's
        assertEquals(m.offset("P", Cell(2, 11, 1))!![1], 0.1f, 1e-4f)
        assertEquals(0.1f, m.offset("P", h)!![1], 1e-4f)
    }

    @Test fun screensAreSeparate() {
        val m = VarneleTouchModel.Model()
        repeat(200) { m.add("P411x960", t, 0.2f, 0f) }
        assertNull(m.offset("P750x832s", t))
    }

    @Test fun slipsAreNotAimAndShiftsAreCapped() {
        val m = VarneleTouchModel.Model()
        repeat(200) { m.add("P", t, 0f, 0.5f) }
        repeat(50) { m.add("P", t, 0f, 0.9f) }  // more than MAX_DY away: ignored
        assertEquals(VarneleTouchModel.MAX_SHIFT, m.offset("P", t)!![1], 1e-4f)
    }

    @Test fun oldTapsFade() {
        val m = VarneleTouchModel.Model()
        repeat(1000) { m.add("P", h, 0.2f, 0f) }
        repeat(3000) { m.add("P", h, -0.1f, 0f) }
        assertEquals(-0.1f, m.offset("P", h)!![0], 0.02f)
        assert(m.stats["P|t"]!!.n <= VarneleTouchModel.HALVE_AT)
    }

    @Test fun savedAndRead() {
        val m = VarneleTouchModel.Model()
        repeat(150) { m.add("P", h, 0.1f, 0.05f) }
        val m2 = VarneleTouchModel.Model()
        m2.read(m.write())
        assertEquals(m.offset("P", h)!![0], m2.offset("P", h)!![0], 1e-5f)
        assertEquals(m.offset("P", h)!![1], m2.offset("P", h)!![1], 1e-5f)
    }
}
