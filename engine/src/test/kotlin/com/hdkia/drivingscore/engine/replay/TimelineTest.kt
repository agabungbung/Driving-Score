package com.hdkia.drivingscore.engine.replay

import com.hdkia.drivingscore.engine.signal.CautionPointType
import com.hdkia.drivingscore.engine.signal.CautionPoints
import com.hdkia.drivingscore.engine.signal.Gear
import com.hdkia.drivingscore.engine.signal.GearChanged
import com.hdkia.drivingscore.engine.signal.RoadSpeedLimit
import com.hdkia.drivingscore.engine.signal.SpeedSample
import com.hdkia.drivingscore.engine.signal.TurnSignal
import com.hdkia.drivingscore.engine.signal.TurnSignalChanged
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineTest {

    @Test
    fun replaysInTimeOrderKeepingInsertionOrderForTies() {
        val tl = timeline {
            add(TurnSignalChanged(2000, TurnSignal.LEFT))
            add(GearChanged(0, Gear.DRIVE))
            add(GearChanged(2000, Gear.PARK))
            add(SpeedSample(1000, 10.0))
        }
        val rec = SignalRecorder()
        assertEquals(4, tl.replay(rec))
        assertEquals(listOf(0L, 1000L, 2000L, 2000L), rec.received.map { it.t })
        assertTrue(rec.received[2] is TurnSignalChanged) // 같은 시각이면 넣은 순서
    }

    @Test
    fun replayWindow() {
        val tl = timeline { every(1000, 0, 10_000) { t -> SpeedSample(t, 0.0) } }
        val rec = SignalRecorder()
        assertEquals(3, tl.replay(rec, fromT = 2000, untilT = 4000))
        assertEquals(listOf(2000L, 3000L, 4000L), rec.received.map { it.t })
    }

    @Test
    fun profileDistanceAndInverse() {
        val p = SpeedProfile.seconds(0 to 0, 10 to 72, 20 to 72, 25 to 0)
        assertEquals(100.0, p.distanceAt(sec(10)), 1e-9)
        assertEquals(300.0, p.distanceAt(sec(20)), 1e-9)
        assertEquals(350.0, p.totalDistanceM, 1e-9)
        assertEquals(sec(10).toDouble(), p.timeAtDistance(100.0)!!, 1e-6)
        assertEquals(sec(15).toDouble(), p.timeAtDistance(200.0)!!, 1e-6)
        assertNull("멈춘 뒤로는 닿지 않는다", p.timeAtDistance(351.0))
    }

    @Test
    fun speedSamplesMimicRealSignal() {
        val p = SpeedProfile.seconds(0 to 0, 10 to 60)
        val samples = timeline { speed(p, quantizeKmh = 1.0, jitterMs = 20) }.only<SpeedSample>()
        assertEquals(101, samples.size) // 10Hz, 10초
        assertTrue(samples.zipWithNext().all { (a, b) -> b.t > a.t })
        assertTrue(samples.all { it.kmh == Math.rint(it.kmh) }) // 1km/h 단위
        assertTrue(samples.all { kotlin.math.abs(it.kmh - p.speedAt(it.t)) <= 0.5 })
    }

    @Test
    fun naviPointsCountDownAndDisappear() {
        val p = SpeedProfile.seconds(0 to 36, 30 to 36) // 10m/s
        val lists = timeline {
            naviPoints(p, listOf(CautionAt("bump", CautionPointType.SPEED_BUMP, 150.0)), lookaheadM = 100.0)
        }.only<CautionPoints>()
        val remains = lists.map { it.t to it.points.singleOrNull()?.remainM }
        assertEquals(null, remains.first { it.first == sec(4) }.second) // 남은 110m: 아직 안 보임
        assertEquals(100, remains.first { it.first == sec(5) }.second)
        assertEquals(0, remains.first { it.first == sec(15) }.second)
        assertEquals(null, remains.first { it.first == sec(16) }.second) // 지나면 빠짐
    }

    @Test
    fun speedLimitsFollowRouteDistance() {
        val p = SpeedProfile.seconds(0 to 36, 30 to 36) // 10m/s
        val limits = timeline {
            speedLimits(p, listOf(LimitZone(0.0, 50), LimitZone(100.0, 30), LimitZone(200.0, null)))
        }.only<RoadSpeedLimit>()
        assertEquals(50, limits.first { it.t == sec(9) }.kmh)
        assertEquals(30, limits.first { it.t == sec(10) }.kmh)
        assertNull(limits.first { it.t == sec(25) }.kmh)
    }
}
