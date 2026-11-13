package com.hdkia.drivingscore.engine.calc

import com.hdkia.drivingscore.engine.replay.SpeedProfile
import com.hdkia.drivingscore.engine.replay.sec
import com.hdkia.drivingscore.engine.replay.timeline
import com.hdkia.drivingscore.engine.signal.SpeedSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AccelEstimatorTest {

    @Test
    fun steadyRampGivesExactSlopeAndDelta() {
        // 0 → 60km/h 10초 = 6km/h/s
        val h = CalcHarness()
        timeline { speed(SpeedProfile.seconds(0 to 0, 10 to 60)) }.replay(h)
        val a = h.accelAt(sec(5))!!
        assertEquals(6.0, a.slopeKmhPerS, 1e-9)
        assertEquals(6.0, a.deltaKmhPerS, 1e-9)
        assertEquals(6.0, a.rateKmhPerS, 1e-9)
        assertEquals(24.0, a.startKmh, 1e-9) // 1초 전 속도
        assertEquals(30.0, a.endKmh, 1e-9)
        assertEquals(6.0, h.accelAt(sec(10))!!.rateKmhPerS, 1e-9)
    }

    @Test
    fun realisticSignalStaysNearTrueSlope() {
        // 실측 성질: 1km/h 계단 + 시각 흔들림 ±20ms
        val h = CalcHarness()
        timeline { speed(SpeedProfile.seconds(0 to 0, 10 to 60), quantizeKmh = 1.0, jitterMs = 20) }.replay(h)
        val inRamp = h.accels.filter { it.t in sec(1.5)..sec(9.5) }.map { it.rateKmhPerS }
        assertTrue(inRamp.size > 70)
        val worst = inRamp.maxOf { abs(it - 6.0) }
        assertTrue("최대 오차 $worst", worst < 1.0)
        assertEquals(6.0, inRamp.average(), 0.2)
    }

    @Test
    fun hardStopIsMeasured() {
        // 60 → 0km/h 2초 = -30km/h/s (VERIFICATION 1번 급정거 시나리오)
        val h = CalcHarness()
        timeline { speed(SpeedProfile.seconds(0 to 60, 5 to 60, 7 to 0, 9 to 0), quantizeKmh = 1.0) }.replay(h)
        assertEquals(-30.0, h.accelAt(sec(6))!!.rateKmhPerS, 1.0)
        assertEquals(0.0, h.accelAt(sec(9))!!.rateKmhPerS, 1e-9)
        assertEquals(0.0, h.accelAt(sec(8))!!.minKmh, 1e-9)
    }

    @Test
    fun shortBrakeIsReadAsOneSecondChange() {
        // 0.4초 동안 30km/h/s로 12km/h 감소. 공단 기준("1초 동안 속도 변화")으로는 12다.
        // 회귀만 쓰면 16.8로 읽혀 급감속(14·15)에 걸린다 → 판정값은 1초 차분 쪽(작은 값)을 쓴다
        val h = CalcHarness()
        timeline { speed(SpeedProfile.seconds(0 to 60, 5 to 60, 5.4 to 48, 10 to 48), quantizeKmh = 1.0, jitterMs = 20) }.replay(h)
        val worstSlope = h.accels.minOf { it.slopeKmhPerS }
        val worstRate = h.accels.minOf { it.rateKmhPerS }
        assertTrue("회귀 $worstSlope", worstSlope < -15.0)
        assertEquals(-12.0, worstRate, 1.0)
        assertTrue(worstRate > -14.0)
    }

    @Test
    fun sustainedJustBelowThresholdIsNotFlagged() {
        // 참 13km/h/s를 2초 유지 (기준 14 바로 아래): 계단 잡음이 있어도 14에 닿지 않는다
        for (seed in 1..100) {
            val h = CalcHarness()
            val p = SpeedProfile.seconds(0 to 60, 3 + seed * 0.007 to 60, 5 + seed * 0.007 to 34, 9 to 34)
            timeline { speed(p, quantizeKmh = 1.0, jitterMs = 20, seed = seed) }.replay(h)
            val worst = h.accels.minOf { it.rateKmhPerS }
            assertTrue("seed $seed: $worst", worst > -14.0)
        }
    }

    @Test
    fun opposingSignsGiveZero() {
        // 끝점은 같고(차분 0) 중간에 솟은 모양: 방향이 엇갈리면 판정값 0
        val a = Accel(0, slopeKmhPerS = 3.0, deltaKmhPerS = -1.0, startKmh = 50.0, endKmh = 49.0, minKmh = 49.0, maxKmh = 53.0)
        assertEquals(0.0, a.rateKmhPerS, 0.0)
    }

    @Test
    fun needsOneSecondOfHistoryAndEnoughSamples() {
        val a = AccelEstimator()
        for (t in 0L..900L step 100) assertNull("1초 이력 전 ($t)", a.add(t, t / 100.0))
        assertEquals(10.0, a.add(1000, 10.0)!!.rateKmhPerS, 1e-9)
        // 샘플이 성기면(창 안 4개) null
        val b = AccelEstimator()
        for (t in listOf(0L, 300L, 600L, 900L, 1200L)) b.add(t, 0.0)
        assertNull(b.current)
    }

    @Test
    fun gapResetsWindow() {
        val a = AccelEstimator()
        for (t in 0L..1000L step 100) a.add(t, 50.0)
        assertNotNull(a.current)
        // 6초 끊긴 뒤 0km/h: 끊김을 가로지르는 기울기(-50/6)를 만들지 않는다
        assertNull(a.add(7000, 0.0))
    }

    @Test
    fun wideGapAtWindowStartIsNotInterpolated() {
        // 창 시작(1초 전)을 둘러싼 샘플 간격 0.8초 > 0.5초: 1초 전 속도를 모른다
        val a = AccelEstimator()
        for (t in 0L..1000L step 100) a.add(t, 50.0)
        a.add(1800, 50.0)
        for (t in 1900L..2700L step 100) a.add(t, 50.0)
        assertNull(a.add(2750, 50.0)) // 1750 = 1000~1800 사이
    }

    @Test
    fun ignoresOutOfOrderAndUsesMagnitude() {
        val a = AccelEstimator()
        for (t in 0L..1000L step 100) a.add(t, -(t / 100.0)) // 후진 차속 음수: 절댓값이 1초에 10씩 커짐
        assertEquals(10.0, a.current!!.rateKmhPerS, 1e-9)
        assertNull(a.add(900, 0.0))
        assertEquals(10.0, a.current!!.rateKmhPerS, 1e-9)
    }

    @Test
    fun samplesFromTimelineAreAccepted() {
        val samples = timeline { speed(SpeedProfile.seconds(0 to 30, 3 to 30)) }.only<SpeedSample>()
        val a = AccelEstimator()
        samples.forEach { a.add(it.t, it.kmh) }
        assertEquals(0.0, a.current!!.rateKmhPerS, 1e-9)
    }
}
