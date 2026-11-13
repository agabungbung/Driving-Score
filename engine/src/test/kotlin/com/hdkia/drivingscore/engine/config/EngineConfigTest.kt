package com.hdkia.drivingscore.engine.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineConfigTest {
    private val c = EngineConfig.DEFAULT

    @Test
    fun speedingBandsAreStrictlyOver() {
        // 결정 53: 경계는 모두 '초과', +20까지 인정
        val l1 = c.law.l1.bands
        assertNull(l1.deductionFor(20.0))
        assertEquals(1.0, l1.deductionFor(20.1)!!, 0.0)
        assertEquals(1.0, l1.deductionFor(40.0)!!, 0.0)
        assertEquals(2.0, l1.deductionFor(40.1)!!, 0.0)
        assertEquals(2.0, l1.deductionFor(60.0)!!, 0.0)
        assertEquals(5.0, l1.deductionFor(60.1)!!, 0.0)
        assertEquals(5.0, l1.deductionFor(80.0)!!, 0.0)
        assertEquals(8.0, l1.deductionFor(80.1)!!, 0.0)
    }

    @Test
    fun schoolZoneBands() {
        val l2 = c.law.l2.bands
        assertNull(l2.deductionFor(5.0))
        assertEquals(3.0, l2.deductionFor(5.1)!!, 0.0)
        assertEquals(3.0, l2.deductionFor(20.0)!!, 0.0)
        assertEquals(8.0, l2.deductionFor(20.1)!!, 0.0)
    }

    @Test
    fun cornerLimitFallsLinearlyWithSpeed() {
        val t7 = c.tech.t7
        assertEquals(0.40, t7.limitG(30.0), 1e-12)
        assertEquals(0.40, t7.limitG(40.0), 1e-12)
        assertEquals(0.35, t7.limitG(65.0), 1e-12)
        assertEquals(0.30, t7.limitG(90.0), 1e-12)
        assertEquals(0.30, t7.limitG(130.0), 1e-12)
    }

    @Test
    fun turnTypeSetsDoNotOverlap() {
        val l5 = c.law.l5
        val roundabout = c.context.roundaboutTurnTypes
        assertTrue(l5.penalizedTurnTypes.intersect(l5.recordOnlyTurnTypes).isEmpty())
        assertTrue(l5.penalizedTurnTypes.intersect(roundabout).isEmpty())
        assertTrue(l5.recordOnlyTurnTypes.intersect(roundabout).isEmpty())
        assertEquals(7, l5.penalizedTurnTypes.size)
    }

    @Test
    fun deductionTableMatchesPlan() {
        // PLAN 6번 하락표 중 큰 값과 기록만(0) 항목
        assertEquals(3.0, c.law.l13.deduction, 0.0)
        assertEquals(2.0, c.law.l7.deduction, 0.0)
        assertEquals(2.0, c.law.l9.deduction, 0.0)
        assertEquals(1.0, c.safety.s4.deduction, 0.0)
        assertEquals(0.0, c.manner.m5.deduction, 0.0)
        assertEquals(0.0, c.tech.t11.deduction, 0.0)
        assertEquals(80.0, c.score.start, 0.0)
        assertEquals(20_000.0, c.score.recoveryDivisor, 0.0)
    }

    @Test
    fun hardAccelBandsFollowEtasAndGapsAreLenient() {
        // 공단 표기 6~10 → 12 · 11~20 → 10 · 21 초과 → 8. 표기가 정하지 않은 사이는 높은 기준(감점이 덜 되는 쪽)
        val b = c.safety.s1.bands
        assertNull("5 이하는 S2", b.thresholdFor(5.0))
        assertNull("5~6 사이는 해당 없음", b.thresholdFor(5.5))
        assertEquals(12.0, b.thresholdFor(6.0)!!, 0.0)
        assertEquals(12.0, b.thresholdFor(10.0)!!, 0.0)
        assertEquals("10~11 사이 → 12", 12.0, b.thresholdFor(10.5)!!, 0.0)
        assertEquals(10.0, b.thresholdFor(11.0)!!, 0.0)
        assertEquals(10.0, b.thresholdFor(20.0)!!, 0.0)
        assertEquals("20~21 사이 → 10", 10.0, b.thresholdFor(20.5)!!, 0.0)
        assertEquals("21은 표기에 없다 → 10", 10.0, b.thresholdFor(21.0)!!, 0.0)
        assertEquals(8.0, b.thresholdFor(21.01)!!, 0.0)
        assertEquals(8.0, b.thresholdFor(130.0)!!, 0.0)
        var v = 6.0
        while (v < 200) { assertTrue("$v", b.thresholdFor(v) != null); v += 0.01 }
    }

    @Test
    fun hardDecelThresholdBySpeed() {
        val s3 = c.safety.s3
        assertEquals(14.0, s3.thresholdFor(30.0), 0.0)
        assertEquals(15.0, s3.thresholdFor(30.1), 0.0)
    }

    @Test
    fun speedingMergeGap() {
        assertEquals(30_000L, c.law.l1.mergeGapMs)
    }
}
