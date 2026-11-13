package com.hdkia.drivingscore.engine.calc

import com.hdkia.drivingscore.engine.config.CalcConfig
import com.hdkia.drivingscore.engine.replay.CautionAt
import com.hdkia.drivingscore.engine.replay.SpeedProfile
import com.hdkia.drivingscore.engine.replay.Timeline
import com.hdkia.drivingscore.engine.replay.timeline
import com.hdkia.drivingscore.engine.signal.CautionPointType
import com.hdkia.drivingscore.engine.signal.CautionPoints
import com.hdkia.drivingscore.engine.signal.Signal
import com.hdkia.drivingscore.engine.signal.SpeedSample
import com.hdkia.drivingscore.engine.source.SignalSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class NaviTimingEstimatorTest {

    /** 시내 주행처럼 속도가 계속 바뀌는 10분. */
    private fun cityProfile(seed: Int): SpeedProfile {
        val rnd = Random(seed)
        val kf = mutableListOf(0L to 30.0)
        var t = 0.0
        var v = 30.0
        while (t < 600) {
            val target = rnd.nextDouble(10.0, 80.0)
            t += maxOf(2.0, kotlin.math.abs(target - v) / rnd.nextDouble(2.0, 8.0))
            v = target
            kf += Math.round(t * 1000) to v
        }
        return SpeedProfile(kf)
    }

    /** 내비 값을 [delayMs] 늦게, 차속은 [wheelScale]배로 넣는다. */
    private fun run(profile: SpeedProfile, delayMs: Long, wheelScale: Double): NaviTimingEstimator.Estimate {
        val points = (1..((profile.totalDistanceM - 100) / 250).toInt()).map { CautionAt("p$it", CautionPointType.SPEED_BUMP, it * 250.0) }
        val wheel = SpeedProfile((0..((profile.endT - profile.startT) / 50)).map { i ->
            val tt = profile.startT + i * 50L
            tt to profile.speedAt(tt) * wheelScale
        })
        val speeds = timeline { speed(wheel, quantizeKmh = 1.0, jitterMs = 20) }
        val navi = timeline { naviPoints(profile, points, phaseMs = 370) }.signals
            .map { (it as CautionPoints).let { s -> CautionPoints(s.t + delayMs, s.points) } }
        val track = KinematicTrack()
        val est = NaviTimingEstimator(track)
        (speeds + Timeline(navi)).replay(SignalSink<Signal> { s ->
            when (s) {
                is SpeedSample -> { track.add(s.t, s.kmh); est.poll() }
                is CautionPoints -> s.points.forEach { est.onRemain("caution:${it.id}", s.t, it.remainM.toDouble()) }
                else -> Unit
            }
        })
        return est.estimate()
    }

    @Test
    fun measuresLatencyAndScaleInCityDriving() {
        // 내비 0.3초 지연, 바퀴 차속이 2% 크게 나옴 → 지연 300ms, 배율 1/1.02 = 0.980
        val e = run(cityProfile(3), delayMs = 300, wheelScale = 1.02)
        assertTrue("쌍 ${e.pairs}", e.certain)
        assertEquals(300.0, e.latency.estimateMs.toDouble(), 60.0)
        assertTrue("${e.latency}", e.latency.lowMs <= 300 && 300 <= e.latency.highMs)
        assertEquals(1 / 1.02, e.scale!!, 0.003)
        assertTrue(e.scaleError in 0.015..0.03)
    }

    @Test
    fun zeroLatencyIsFound() {
        val e = run(cityProfile(5), delayMs = 0, wheelScale = 1.0)
        assertTrue(e.certain)
        assertTrue("${e.latency}", e.latency.lowMs <= 0 && 0 <= e.latency.highMs)
        assertEquals(1.0, e.scale!!, 0.003)
    }

    @Test
    fun constantSpeedCannotTellLatency() {
        // 정속이면 지연에 따라 달린 거리가 같아 가를 수 없다 → 확실하지 않음, 넓은 가정 범위를 그대로 쓴다
        val e = run(SpeedProfile.seconds(0 to 50, 600 to 50), delayMs = 300, wheelScale = 1.0)
        assertFalse(e.certain)
        assertEquals(LatencyWindow.unknown(CalcConfig()), e.latency)
        assertEquals(1.0, e.scale!!, 0.003) // 배율은 정속에서도 잴 수 있다
    }

    @Test
    fun beforeEnoughDataEverythingIsUnknown() {
        val e = NaviTimingEstimator(KinematicTrack()).estimate()
        assertFalse(e.certain)
        assertEquals(LatencyWindow.unknown(CalcConfig()), e.latency)
        assertEquals(0.03, e.scaleError, 0.0)
    }
}
