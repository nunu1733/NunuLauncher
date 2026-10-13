package com.android.launcher3

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Contract for the Issue #563 screen-center fix: an unset (NaN) render pivot resolves to the
 * view's center instead of propagating NaN into the screen center (which Math.round collapses
 * to 0 and makes page-center consumers pick a wrong page). Explicit-pivot inputs keep the
 * scale-aware formula.
 */
class PagedViewScreenCenterTest {

    @Test
    fun `nan pivot resolves to the view center`() {
        // Stuck-entry capture (run BB4): scroll 3465, width 1080, scale 1, pivot NaN.
        assertEquals(3465 + 1080 / 2, PagedView.computeScreenCenter(3465, 1080, 1f, Float.NaN))
    }

    @Test
    fun `nan pivot with scale resolves to the view center`() {
        // Center pivot: (size/2 - size/2)/scale cancels, leaving scroll + size/2.
        assertEquals(1000 + 540, PagedView.computeScreenCenter(1000, 1080, 0.5f, Float.NaN))
    }

    @Test
    fun `center pivot keeps scroll plus half size`() {
        assertEquals(3465 + 540, PagedView.computeScreenCenter(3465, 1080, 1f, 540f))
        assertEquals(1000 + 540, PagedView.computeScreenCenter(1000, 1080, 0.5f, 540f))
    }

    @Test
    fun `off-center pivot keeps the scale aware formula`() {
        // scroll + (size/2 - pivot)/scale + pivot with pivot 0, scale 0.5, size 1080.
        assertEquals((1000 + 540f / 0.5f).toInt(), PagedView.computeScreenCenter(1000, 1080, 0.5f, 0f))
    }
}
