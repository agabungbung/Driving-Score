package com.hdkia.drivingscore.engine.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepeatCountersTest {

    @Test
    fun distanceRepeatFirstThenEveryInterval() {
        // L7 운전자 벨트: 처음 + 10km마다
        val c = DistanceRepeatCounter(10_000.0)
        assertEquals(0, c.update(false, 0.0))
        assertEquals(1, c.update(true, 500.0))
        assertEquals(0, c.update(true, 10_499.0))
        assertEquals(1, c.update(true, 10_500.0)) // 10km를 달린 순간
        assertEquals(0, c.update(true, 20_000.0))
        assertEquals(1, c.update(true, 20_500.0))
    }

    @Test
    fun distanceRepeatRestartsAfterRelease() {
        val c = DistanceRepeatCounter(1_000.0)
        assertEquals(1, c.update(true, 0.0))
        assertEquals(0, c.update(true, 900.0))
        assertEquals(0, c.update(false, 950.0)) // 풀리면 끝
        assertEquals(1, c.update(true, 960.0)) // 다시 시작되면 새로 센다
        assertEquals(0, c.update(true, 1_900.0))
        assertEquals(1, c.update(true, 1_960.0))
    }

    @Test
    fun distanceRepeatStateRoundTrips() {
        val a = DistanceRepeatCounter(5_000.0)
        a.update(true, 100.0)
        val b = DistanceRepeatCounter(5_000.0, a.state) // 프로세스 재시작 뒤 이어 가기
        assertEquals(0, b.update(true, 5_000.0))
        assertEquals(1, b.update(true, 5_100.0))
    }

    @Test
    fun timeRepeatMultiplierBoundariesAreStrict() {
        // L1: 3분 '넘게' 이어지면 2배, 6분 넘게 3배, 최대 3배
        val c = TimeRepeatCounter(180_000, 3)
        assertEquals(1, c.multiplierFor(0))
        assertEquals(1, c.multiplierFor(180_000))
        assertEquals(2, c.multiplierFor(180_001))
        assertEquals(2, c.multiplierFor(360_000))
        assertEquals(3, c.multiplierFor(360_001))
        assertEquals(3, c.multiplierFor(3_600_000))
    }

    @Test
    fun timeRepeatOnline() {
        val c = TimeRepeatCounter(180_000, 3)
        assertEquals(1, c.update(true, 10_000))
        assertEquals(0, c.update(true, 190_000))
        assertEquals(1, c.update(true, 190_001))
        assertEquals(1, c.update(true, 400_000))
        assertEquals(0, c.update(true, 900_000))
        assertEquals(3, c.state.multiplier)
        assertEquals(0, c.update(false, 900_100))
        assertEquals(1, c.update(true, 900_200))
    }

    @Test
    fun episodeLatchOncePerEpisode() {
        // S12: N이 이어지는 동안 조건이 두 번 걸려도 1건
        val l = EpisodeLatch()
        assertFalse(l.update(inEpisode = true, conditionMet = false))
        assertTrue(l.update(inEpisode = true, conditionMet = true))
        assertFalse(l.update(inEpisode = true, conditionMet = false))
        assertFalse(l.update(inEpisode = true, conditionMet = true))
        assertFalse(l.update(inEpisode = false, conditionMet = false))
        assertTrue(l.update(inEpisode = true, conditionMet = true))
    }

    @Test
    fun cooldownAllowsOnePerKilometre() {
        val c = DistanceCooldown(1_000.0)
        assertTrue(c.tryCount(100.0))
        assertFalse(c.tryCount(1_050.0))
        assertTrue(c.tryCount(1_100.0))
        assertFalse(c.tryCount(1_200.0))
    }

    @Test
    fun repeatSurvivesRestartWithSavedOdometer() {
        // 벨트 미착용 10km 반복 중 15km 지점에서 재시작: 다음 1건은 5km 뒤(20km)여야 한다
        val before = DistanceRepeatCounter(10_000.0)
        before.update(true, 0.0)
        assertEquals(1, before.update(true, 15_000.0))
        val savedOdo = 15_000.0
        val track = KinematicTrack(startOdometerM = savedOdo)
        val after = DistanceRepeatCounter(10_000.0, before.state)
        for (s in 0..499) track.add(s * 1_000L, 36.0) // 10m/s × 499초 = 4,990m
        assertEquals(0, after.update(true, track.odometerM))
        track.add(500_000, 36.0) // 5,000m
        assertEquals(1, after.update(true, track.odometerM))
    }
}
