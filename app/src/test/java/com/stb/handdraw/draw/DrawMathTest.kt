package com.stb.handdraw.draw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawMathTest {
    @Test
    fun letterboxCentersAFourByThreeFrame() {
        val mapping = FrameMapping(1920f, 1080f, 640f, 480f, mirror = false)
        assertEquals(2.25f, mapping.scale, 0.001f)
        assertEquals(240f, mapping.offsetX, 0.001f)
        assertEquals(0f, mapping.offsetY, 0.001f)
        val origin = mapping.toView(0f, 0f)
        assertEquals(240f, origin.x, 0.001f)
        assertEquals(0f, origin.y, 0.001f)
        val far = mapping.toView(1f, 1f)
        assertEquals(1680f, far.x, 0.001f)
        assertEquals(1080f, far.y, 0.001f)
    }

    @Test
    fun mirrorFlipsXInsideTheSameLetterbox() {
        val mapping = FrameMapping(1920f, 1080f, 640f, 480f, mirror = true)
        val origin = mapping.toView(0f, 0f)
        assertEquals(1680f, origin.x, 0.001f)
        assertEquals(0f, origin.y, 0.001f)
        val mid = mapping.toView(0.25f, 0.5f)
        val unmirrored = FrameMapping(1920f, 1080f, 640f, 480f, mirror = false).toView(0.75f, 0.5f)
        assertEquals(unmirrored.x, mid.x, 0.001f)
        assertEquals(unmirrored.y, mid.y, 0.001f)
    }

    @Test
    fun fittedSizeKeepsAspectAndDoesNotUpscale() {
        assertEquals(320 to 240, fittedSize(640, 480, 320, 240))
        assertEquals(100 to 80, fittedSize(100, 80, 640, 480))
        assertEquals(640 to 360, fittedSize(1920, 1080, 640, 480))
    }

    @Test
    fun smootherSnapsThenFollowsAndJumpsFaster() {
        val smoother = PointSmoother(alpha = 0.5f, jump = 0.2f, fast = 0.85f)
        val first = smoother.apply(0.2f, 0.3f)
        assertEquals(0.2f, first.first, 0.001f)
        val eased = smoother.apply(0.3f, 0.3f)
        assertEquals(0.25f, eased.first, 0.001f)
        val jumped = smoother.apply(1f, 0.3f)
        assertEquals(0.25f + 0.75f * 0.85f, jumped.first, 0.001f)
    }

    @Test
    fun strokeStartsAtTheCursorAndEndsOnRelease() {
        val session = DrawingSession(PointSmoother(alpha = 1f))
        session.onHand(NormPoint(0.2f, 0.2f), null)
        session.begin(color = 1, widthPx = 4f)
        session.onHand(NormPoint(0.5f, 0.5f), null)
        session.end()
        assertEquals(1, session.finished.size)
        assertTrue(session.finished[0].points.size >= 2)
        assertFalse(session.buttonHeld)
        assertNull(session.active)
    }

    @Test
    fun losingTheHandClosesTheStrokeButHoldingContinuesANewOne() {
        val session = DrawingSession(PointSmoother(alpha = 1f))
        session.begin(1, 4f)
        session.onHand(NormPoint(0.1f, 0.1f), null)
        session.onHand(NormPoint(0.4f, 0.4f), null)
        session.onHand(null, null)
        assertEquals(1, session.finished.size)
        assertTrue(session.buttonHeld)
        session.onHand(NormPoint(0.6f, 0.2f), null)
        session.onHand(NormPoint(0.9f, 0.2f), null)
        session.end()
        assertEquals(2, session.finished.size)
    }

    @Test
    fun undoDropsTheActiveStrokeThenThePreviousOne() {
        val session = DrawingSession(PointSmoother(alpha = 1f))
        session.begin(1, 4f)
        session.onHand(NormPoint(0.1f, 0.1f), null)
        session.onHand(NormPoint(0.3f, 0.3f), null)
        session.end()
        session.begin(1, 4f)
        session.onHand(NormPoint(0.6f, 0.1f), null)
        session.undo()
        assertNull(session.active)
        assertEquals(1, session.finished.size)
        session.undo()
        assertTrue(session.finished.isEmpty())
        assertFalse(session.hasInk())
    }

    @Test
    fun clearRemovesInk() {
        val session = DrawingSession(PointSmoother(alpha = 1f))
        session.begin(1, 4f)
        session.onHand(NormPoint(0.1f, 0.1f), null)
        session.onHand(NormPoint(0.4f, 0.2f), null)
        session.clear()
        assertTrue(session.finished.isEmpty())
        assertNull(session.active)
        assertFalse(session.hasInk())
    }
}
