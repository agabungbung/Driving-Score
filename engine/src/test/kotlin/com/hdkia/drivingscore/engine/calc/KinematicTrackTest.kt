package com.hdkia.drivingscore.engine.calc

import com.hdkia.drivingscore.engine.config.CalcConfig
import com.hdkia.drivingscore.engine.replay.SpeedProfile
import com.hdkia.drivingscore.engine.replay.sec
import com.hdkia.drivingscore.engine.replay.timeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KinematicTrackTest {

    @Test
    fun constantSpeedDistance() {
        // 36km/h = 10m/s, 10초 → 100m
        val h = CalcHarness()
        timeline { speed(SpeedProfile.seconds(0 to 36, 10 to 36)) }.replay(h)
        assertEquals(100.0, h.track.odometerM, 1e-9)
    }

    @Test
    fun rampIsIntegratedExactly() {
        // 0 → 72km/h(20m/s) 10초 → 평균 10m/s × 10초 = 100m (사다리꼴은 직선 구간에서 정확)
        val h = CalcHarness()
        timeline { speed(SpeedProfile.seconds(0 to 0, 10 to 72)) }.replay(h)
        assertEquals(100.0, h.track.odometerM, 1e-9)
    }

    @Test
    fun realisticSignalMatchesTrueDistance() {
        val profile = SpeedProfile.seconds(0 to 0, 12 to 70, 40 to 70, 46 to 20, 50 to 20, 58 to 90, 90 to 90, 100 to 0)
        val h = CalcHarness()
        timeline { speed(profile, quantizeKmh = 1.0, jitterMs = 20, seed = 7) }.replay(h)
        val truth = profile.distanceAt(profile.endT)
        val err = (h.track.odometerM - truth) / truth
        assertTrue("상대 오차 $err (참 ${truth}m, 적분 ${h.track.odometerM}m)", kotlin.math.abs(err) < 0.002)
    }

    @Test
    fun interpolatesBetweenSamples() {
        val t = KinematicTrack()
        t.add(0, 0.0)
        t.add(1000, 36.0) // 0 → 10m/s 1초: 5m
        assertEquals(5.0, t.odoAt(1000)!!, 1e-9)
        assertEquals(1.25, t.odoAt(500)!!, 1e-9) // a=10m/s², 0.5초 → 1.25m
        assertEquals(18.0, t.speedAt(500)!!, 1e-9)
        val c = t.stateAtOdo(1.25)!!
        assertEquals(500.0, c.t, 1e-6)
        assertEquals(18.0, c.kmh, 1e-6)
        assertNull(t.odoAt(1500))
        assertNull(t.stateAtOdo(5.1))
    }

    @Test
    fun stateAtOdoAndOdoAtAreInverse() {
        val profile = SpeedProfile.seconds(0 to 50, 4 to 10, 8 to 60)
        val h = CalcHarness()
        timeline { speed(profile, quantizeKmh = 1.0, jitterMs = 20) }.replay(h)
        for (odo in listOf(3.0, 20.0, 31.4, 40.0, 70.0)) {
            val c = h.track.stateAtOdo(odo)!!
            assertEquals(odo, h.track.odoAt(c.t)!!, 1e-6)
        }
    }

    @Test
    fun stopKeepsEarliestCrossing() {
        val t = KinematicTrack()
        t.add(0, 36.0)
        t.add(1000, 0.0) // 5m 가서 멈춤
        t.add(2000, 0.0)
        t.add(3000, 0.0)
        assertEquals(1000.0, t.stateAtOdo(5.0)!!.t, 1e-6)
    }

    @Test
    fun gapIsNotIntegrated() {
        val t = KinematicTrack(CalcConfig(maxSampleGapMs = 5_000))
        t.add(0, 36.0)
        t.add(1000, 36.0) // 10m
        t.add(7000, 36.0) // 6초 끊김: 더하지 않는다
        t.add(8000, 36.0) // 10m
        assertEquals(20.0, t.odometerM, 1e-9)
        assertNull("끊긴 구간의 차속은 모른다", t.speedAt(4000))
    }

    @Test
    fun minSpeedAroundPoint() {
        // 60 → 20(4초 감속) → 60: 가장 느린 지점 앞뒤 15m
        val profile = SpeedProfile.seconds(0 to 60, 4 to 20, 8 to 60)
        val h = CalcHarness()
        timeline { speed(profile) }.replay(h)
        val slowest = profile.distanceAt(sec(4))
        assertEquals(20.0, h.track.minSpeedBetweenOdo(slowest - 15, slowest + 15)!!, 1e-6)
        // 빠른 구간만 보면 최저 차속이 30을 넘는다
        val early = h.track.minSpeedBetweenOdo(0.0, 15.0)!!
        assertTrue(early > 50.0)
        assertNull("아직 안 달린 범위", h.track.minSpeedBetweenOdo(slowest, h.track.odometerM + 1))
    }

    @Test
    fun keepsOnlyRetentionWindow() {
        val h = CalcHarness(CalcConfig(trackRetentionMs = 120_000))
        timeline { speed(SpeedProfile.seconds(0 to 50, 200 to 50)) }.replay(h)
        val first = h.track.earliest!!.t
        assertTrue(first in sec(79.9)..sec(80))
        assertEquals(50.0 / 3.6 * 200, h.track.odometerM, 1e-6) // 지운 이력과 상관없이 누적 거리는 그대로
    }

    @Test
    fun continuesFromSavedOdometer() {
        // 프로세스 재시작: 저장해 둔 누적 거리에서 이어 센다
        val t = KinematicTrack(startOdometerM = 15_000.0)
        assertEquals(15_000.0, t.odometerM, 0.0)
        t.add(0, 36.0)
        t.add(1000, 36.0)
        assertEquals(15_010.0, t.odometerM, 1e-9)
        assertEquals(15_005.0, t.odoAt(500)!!, 1e-9)
    }
}
