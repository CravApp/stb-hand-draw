package com.stb.handdraw.hand

import com.stb.handdraw.draw.NormPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandMathTest {
    @Test
    fun unmirroredFrameFlipsMediaPipeHandedness() {
        assertEquals(
            UserHand.LEFT,
            userHandFromMediaPipe("Right", 0, analysisFrameIsMirrored = false),
        )
        assertEquals(
            UserHand.RIGHT,
            userHandFromMediaPipe("Left", 1, analysisFrameIsMirrored = false),
        )
        assertEquals(
            UserHand.RIGHT,
            userHandFromMediaPipe("Right", 0, analysisFrameIsMirrored = true),
        )
    }

    @Test
    fun indexFallbackMatchesTheBinaryLabelMap() {
        assertEquals(UserHand.LEFT, userHandFromMediaPipe("", 0, analysisFrameIsMirrored = false))
        assertEquals(UserHand.RIGHT, userHandFromMediaPipe("", 1, analysisFrameIsMirrored = false))
        assertNull(userHandFromMediaPipe("", 3, analysisFrameIsMirrored = false))
    }

    @Test
    fun onlyThePreferredHandIsSelected() {
        val right = hand(UserHand.RIGHT, 0.4f)
        val left = hand(UserHand.LEFT, 0.9f)
        assertEquals(right, selectHand(listOf(left, right), UserHand.RIGHT))
        assertEquals(left, selectHand(listOf(left, right), UserHand.LEFT))
        assertNull(selectHand(listOf(left), UserHand.RIGHT))
    }

    @Test
    fun higherScoreWinsWhenTheSameHandAppearsTwice() {
        val weak = hand(UserHand.RIGHT, 0.5f)
        val strong = hand(UserHand.RIGHT, 0.8f)
        assertEquals(strong, selectHand(listOf(weak, strong), UserHand.RIGHT))
    }

    @Test
    fun creationFallsBackFromGpuAndLite() {
        val attempts = creationAttempts(
            HandConfig(HandModel.LITE, DelegateKind.GPU, numHands = 1, AnalysisSize.R640),
        )
        assertEquals(
            listOf(
                HandConfig(HandModel.LITE, DelegateKind.GPU, 1, AnalysisSize.R640),
                HandConfig(HandModel.LITE, DelegateKind.CPU, 1, AnalysisSize.R640),
                HandConfig(HandModel.FULL, DelegateKind.CPU, 1, AnalysisSize.R640),
            ),
            attempts,
        )
        assertEquals(
            1,
            creationAttempts(
                HandConfig(HandModel.FULL, DelegateKind.CPU, 2, AnalysisSize.R320),
            ).size,
        )
    }

    @Test
    fun perfLineIsStableForAdb() {
        val line = handDrawPerfLine(
            fps = 7,
            inferMs = 140,
            frames = 7,
            model = "lite",
            delegate = "CPU",
            targetW = 640,
            targetH = 480,
            frameW = 320,
            frameH = 240,
            hands = 1,
            tracked = 1,
        )
        assertEquals(
            "fps=7 inferMs=140 frames=7 model=lite delegate=CPU target=640x480 frame=320x240 hands=1 tracked=1",
            line,
        )
        assertTrue(HAND_DRAW_PERF_TAG == "HandDrawPerf")
    }

    private fun hand(user: UserHand, score: Float) = DetectedHand(
        userHand = user,
        score = score,
        landmarks = List(HAND_LANDMARK_COUNT) { NormPoint(it / 21f, 0.2f) },
    )
}
