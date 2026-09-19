package com.example.trajectoryoverlay

import android.graphics.PointF
import android.graphics.RectF
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TrajectoryEngineTest {

    @Test
    fun selfTestPasses() {
        assertNull(TrajectoryEngine.selfTest())
    }

    @Test
    fun straightDirectShotExists() {
        val table = RectF(50f, 50f, 1050f, 550f)
        val cue = Ball(PointF(250f, 300f), 18f, cue = true)
        val target = Ball(PointF(650f, 300f), 18f)
        val pocket = PointF(1000f, 300f)

        val result = TrajectoryEngine.calculate(
            table,
            listOf(cue, target),
            listOf(pocket),
            direct = true,
            banks = false,
            secondary = true
        )

        assertTrue(result.isNotEmpty())
        assertEquals(PathKind.DIRECT, result.first().kind)
        assertTrue(result.first().cutAngleDeg < 1f)
    }

    @Test
    fun blockerRejectsStraightShot() {
        val table = RectF(50f, 50f, 1050f, 550f)
        val cue = Ball(PointF(250f, 300f), 18f, cue = true)
        val blocker = Ball(PointF(450f, 300f), 18f)
        val target = Ball(PointF(650f, 300f), 18f)
        val pocket = PointF(1000f, 300f)

        val result = TrajectoryEngine.calculate(
            table,
            listOf(cue, blocker, target),
            listOf(pocket),
            direct = true,
            banks = false,
            secondary = false
        )

        val targetGhostX = 650f - 36f
        assertTrue(result.none { kotlin.math.abs(it.ghost.x - targetGhostX) < 3f })
    }

    @Test
    fun aimDirectionProducesCurrentShot() {
        val table = RectF(50f, 50f, 1050f, 550f)
        val cue = Ball(PointF(250f, 300f), 18f, cue = true)
        val target = Ball(PointF(650f, 300f), 18f)

        val result = TrajectoryEngine.calculateFromAim(
            table,
            listOf(cue, target),
            PointF(1f, 0f),
            banks = true,
            secondary = true
        )

        assertEquals(1, result.size)
        assertTrue(result.first().cuePath.size >= 2)
        assertTrue(result.first().objectPath.size >= 2)
    }

    @Test
    fun aimWithoutBallProducesKickPath() {
        val table = RectF(50f, 50f, 1050f, 550f)
        val cue = Ball(PointF(250f, 300f), 18f, cue = true)

        val result = TrajectoryEngine.calculateFromAim(
            table,
            listOf(cue),
            PointF(1f, 0.2f),
            banks = true,
            secondary = false
        )

        assertEquals(1, result.size)
        assertTrue(result.first().cuePath.size >= 3)
        assertTrue(result.first().objectPath.isEmpty())
    }

    @Test
    fun lineToRectEdgeFindsForwardBoundary() {
        val rect = RectF(0f, 0f, 100f, 50f)
        val hit = Geometry.lineToRectEdge(PointF(50f, 25f), PointF(1f, 0f), rect)
        assertNotNull(hit)
        assertEquals(100f, hit!!.x, 0.01f)
        assertEquals(25f, hit.y, 0.01f)
    }
}
