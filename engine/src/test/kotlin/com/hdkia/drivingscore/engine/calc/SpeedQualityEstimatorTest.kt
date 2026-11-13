package com.hdkia.drivingscore.engine.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin
import kotlin.random.Random

class SpeedQualityEstimatorTest {

    private fun feed(q: Double, noise: Double = 0.0, n: Int = 3000): SpeedQualityEstimator {
        val e = SpeedQualityEstimator()
        val rnd = Random(1)
        for (i in 0 until n) {
            val tS = i / 10.0
            val v = 50 + 20 * sin(tS / 15) + (if (noise > 0) rnd.nextDouble(-noise, noise) * 1.732 else 0.0) // 균등 잡음, 표준편차 = noise
            val out = if (q > 0) Math.round(v / q) * q else v
            e.add(out, 20.0 / 15 * kotlin.math.cos(tS / 15) * 3.6 / 3.6) // 판정값(정속 여부)만 쓴다
        }
        return e
    }

    @Test
    fun unknownBeforeEnoughChangesAssumesCoarse() {
        val e = SpeedQualityEstimator()
        e.add(50.0, 0.0)
        assertFalse(e.measured)
        assertEquals(1.0, e.resolutionKmh, 0.0)
        assertEquals(1.0, e.accelMarginKmhPerS(), 0.0)
    }

    @Test
    fun oneKmhStepsGiveMarginOne() {
        val e = feed(1.0)
        assertTrue(e.measured)
        assertEquals(1.0, e.resolutionKmh, 1e-9)
        assertTrue(e.quantized)
        assertEquals(1.0, e.accelMarginKmhPerS(), 1e-9)
    }

    @Test
    fun finerStepsGiveSmallerMargin() {
        val e = feed(0.1)
        assertEquals(0.1, e.resolutionKmh, 1e-9)
        assertTrue(e.quantized)
        assertEquals(0.1, e.accelMarginKmhPerS(), 1e-9)
    }

    @Test
    fun noisySmoothSignalUsesNoise() {
        // 매끄러운 값에 표준편차 0.2km/h 잡음: 해상도는 작지만 여유는 잡음으로 잡는다 (5.7σ ≈ 1.1)
        val e = feed(0.0, noise = 0.2)
        assertFalse(e.quantized)
        assertEquals(1.14, e.accelMarginKmhPerS(), 0.25)
    }

    @Test
    fun certaintyRule() {
        // 판정값 15.5, 기준 14: 여유 1 + 배율 3%(0.47) 뺀 14.03 → 확실. 15.4면 13.94 → 아님
        assertTrue(certainlyAtLeast(15.5, 14.0, 1.0, 0.03))
        assertFalse(certainlyAtLeast(15.4, 14.0, 1.0, 0.03))
    }
}
