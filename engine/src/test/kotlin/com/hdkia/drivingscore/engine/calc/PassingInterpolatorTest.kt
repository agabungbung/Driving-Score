package com.hdkia.drivingscore.engine.calc

import com.hdkia.drivingscore.engine.config.CalcConfig
import com.hdkia.drivingscore.engine.replay.CameraAt
import com.hdkia.drivingscore.engine.replay.CautionAt
import com.hdkia.drivingscore.engine.replay.SpeedProfile
import com.hdkia.drivingscore.engine.replay.Timeline
import com.hdkia.drivingscore.engine.replay.sec
import com.hdkia.drivingscore.engine.replay.timeline
import com.hdkia.drivingscore.engine.signal.CameraAlerts
import com.hdkia.drivingscore.engine.signal.CameraType
import com.hdkia.drivingscore.engine.signal.CautionPoint
import com.hdkia.drivingscore.engine.signal.CautionPointType
import com.hdkia.drivingscore.engine.signal.CautionPoints
import com.hdkia.drivingscore.engine.signal.SpeedSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PassingInterpolatorTest {

    @Test
    fun cameraAt100kmhIsTimedWithinMeters() {
        // 100km/h 정속, 카메라 1,000m. 내비는 1초마다(차속 샘플과 370ms 어긋남), 정수 m. 차속은 1km/h 계단·흔들림
        val profile = SpeedProfile.seconds(0 to 100, 60 to 100)
        val h = CalcHarness()
        timeline {
            speed(profile, quantizeKmh = 1.0, jitterMs = 20)
            naviPoints(profile, listOf(CameraAt("cam", CameraType.SPEED_CAMERA_FIX, 1000.0, 80)), phaseMs = 370)
        }.replay(h)

        val pass = h.passes.single()
        val truth = profile.timeAtDistance(1000.0)!!
        // 40ms = 100km/h에서 1.1m
        assertTrue("오차 ${pass.t - truth}ms", abs(pass.t - truth) < 40.0)
        assertEquals(100.0, pass.kmh, 1.0)
    }

    @Test
    fun decelerationThroughBumpBeatsLastNaviValue() {
        // 60 → 20km/h(5km/h/s) 감속 중 방지턱 통과. 참값: t=5s, 35km/h
        val profile = SpeedProfile.seconds(0 to 60, 8 to 20, 12 to 20)
        val bumpAt = profile.distanceAt(sec(5))
        val h = CalcHarness()
        timeline {
            speed(profile, quantizeKmh = 1.0, jitterMs = 20)
            // 내비 갱신 4.1s(남은 약 9m) 다음은 5.1s(이미 지나 목록에서 빠짐)
            naviPoints(profile, listOf(CautionAt("bump", CautionPointType.SPEED_BUMP, bumpAt)), phaseMs = 100)
        }.replay(h)

        val pass = h.passes.single()
        // 내비 남은 거리가 정수 m라 ±0.5m(35km/h에서 ±51ms)는 원래 있는 오차다
        assertEquals(5000.0, pass.t, 60.0)
        assertEquals(35.0, pass.kmh, 1.0)
        // 마지막 내비 값만 쓰면 4.1s의 39.5km/h로 판정한다 (4km/h 넘게 틀림)
        val naive = profile.speedAt(sec(4.1))
        assertTrue(abs(naive - 35.0) > 4.0)
    }

    @Test
    fun vanishedPointFarAwayIsNotPassed() {
        // 남은 40m에서 목록에서 빠짐(경로 이탈 등). 10km/h로는 2초에 5.6m뿐 → 통과로 만들지 않는다
        val profile = SpeedProfile.seconds(0 to 10, 30 to 10)
        val h = CalcHarness()
        timeline {
            speed(profile)
            add(CautionPoints(500, listOf(CautionPoint("p", CautionPointType.SPEED_BUMP, 40))))
            add(CautionPoints(1500, emptyList()))
        }.replay(h)
        assertTrue(h.passes.isEmpty())
        assertTrue(h.cautionPassing.watching.isEmpty())
        assertTrue("그 뒤 40m 넘게 달렸다", h.track.odometerM > 60.0)
    }

    @Test
    fun pointDroppedRightAfterLastUpdateStillPasses() {
        // 남은 3m(30km/h = 8.33m/s → 0.36초 뒤 통과) 직후 목록에서 빠져도 보간으로 통과를 잡는다
        val profile = SpeedProfile.seconds(0 to 30, 10 to 30)
        val h = CalcHarness()
        timeline {
            speed(profile)
            add(CautionPoints(2000, listOf(CautionPoint("p", CautionPointType.SPEED_BUMP, 3))))
            add(CautionPoints(3000, emptyList()))
        }.replay(h)
        val pass = h.passes.single()
        assertEquals(2000.0 + 3.0 / (30 / 3.6) * 1000, pass.t, 1.0)
    }

    @Test
    fun negativeRemainMeansAlreadyPassed() {
        val profile = SpeedProfile.seconds(0 to 36, 10 to 36) // 10m/s
        val h = CalcHarness()
        timeline {
            speed(profile)
            add(CautionPoints(5000, listOf(CautionPoint("p", CautionPointType.SPEED_BUMP, -5))))
        }.replay(h)
        assertEquals(4500.0, h.passes.single().t, 1.0)
    }

    @Test
    fun latestAnchorWinsAndRangeKeepsEarlier() {
        val profile = SpeedProfile.seconds(0 to 36, 20 to 36) // 10m/s
        val h = CalcHarness()
        timeline {
            speed(profile)
            add(CautionPoints(1000, listOf(CautionPoint("p", CautionPointType.SPEED_BUMP, 30))))
            add(CautionPoints(2000, listOf(CautionPoint("p", CautionPointType.SPEED_BUMP, 5)))) // → 2.5초에 닿음
            // 적분으로는 이미 지났는데 내비는 "아직 2m": 내비 값으로 다시 맞춘다(3.2초). 어느 쪽인지 모르므로 범위는 2.5~3.2초
            add(CautionPoints(3000, listOf(CautionPoint("p", CautionPointType.SPEED_BUMP, 2))))
            add(CautionPoints(4000, emptyList())) // 빠짐 = 확인
            add(CautionPoints(5000, listOf(CautionPoint("p", CautionPointType.SPEED_BUMP, -10)))) // 통과 뒤 늦게 온 값은 무시
        }.replay(h)
        val pass = h.passes.single()
        assertEquals(3_200.0, pass.t, 1.0)
        assertEquals(2_500.0, pass.tMin, 1.0)
        assertEquals(3_200.0, pass.tMax, 1.0)
    }

    @Test
    fun naviUpdateBeforeAnySpeedSample() {
        val h = CalcHarness()
        timeline {
            add(CautionPoints(0, listOf(CautionPoint("p", CautionPointType.SPEED_BUMP, 10))))
            add(SpeedSample(500, 0.0))
            add(SpeedSample(1000, 72.0)) // 0.5→1.0s: 0 → 20m/s, 5m
            add(SpeedSample(1500, 72.0)) // 1.0→1.5s: 10m
            add(CautionPoints(2000, emptyList())) // 통과 뒤 목록에서 빠짐 = 확인
        }.replay(h)
        // 첫 차속 전에는 서 있었다고 본다: 10m 지점 = 1.0s(5m) 뒤 5m ÷ 20m/s → 1250ms
        assertEquals(1250.0, h.passes.single().t, 1.0)
    }

    @Test
    fun crossingBeyondExtrapolationLimitIsDropped() {
        // 남은 24m, 10m/s: 2.4초 뒤에 닿는다. 마지막 갱신 뒤 2초를 넘겨 센 통과는 만들지 않는다
        val profile = SpeedProfile.seconds(0 to 36, 10 to 36)
        val h = CalcHarness()
        timeline {
            speed(profile, periodMs = 500)
            add(CautionPoints(1000, listOf(CautionPoint("p", CautionPointType.SPEED_BUMP, 24))))
        }.replay(h)
        assertTrue(h.passes.isEmpty())
        assertTrue(h.cautionPassing.watching.isEmpty())
    }

    @Test
    fun vanishedBeforeReachingAtHighSpeedIsNotPassed() {
        // 100km/h(27.8m/s), 남은 50m에서 목록에서 빠짐(고속도로 출구로 나감 등). 1초 뒤 목록이 왔을 때 계산상 아직 22m 남음
        // → 지나기 전에 빠진 것이다. 2초 외삽(55m)으로 통과를 만들면 안 된다
        val profile = SpeedProfile.seconds(0 to 100, 30 to 100)
        val h = CalcHarness()
        timeline {
            speed(profile)
            add(CautionPoints(10_000, listOf(CautionPoint("p", CautionPointType.SPEED_BUMP, 50))))
            add(CautionPoints(11_000, emptyList()))
        }.replay(h)
        assertTrue(h.passes.isEmpty())
        assertTrue(h.cautionPassing.watching.isEmpty())
    }

    @Test
    fun vanishedWithinToleranceStillPasses() {
        // 100km/h, 남은 30m에서 마지막 갱신, 다음 목록(1초 뒤)에 없음: 계산상 2.2m 남음(여유 10m 안) → 1.08초 뒤 통과
        val profile = SpeedProfile.seconds(0 to 100, 30 to 100)
        val h = CalcHarness()
        timeline {
            speed(profile)
            add(CautionPoints(10_000, listOf(CautionPoint("p", CautionPointType.SPEED_BUMP, 30))))
            add(CautionPoints(11_000, emptyList()))
        }.replay(h)
        assertEquals(10_000 + 30 / (100 / 3.6) * 1000, h.passes.single().t, 1.0)
    }

    @Test
    fun briefDropoutThenReappearPassesOnce() {
        // 목록이 한 번 비었다가(내비 갱신 누락) 다시 나타나면 새 기준으로 이어서 본다
        val profile = SpeedProfile.seconds(0 to 36, 30 to 36) // 10m/s
        val h = CalcHarness()
        timeline {
            speed(profile)
            add(CautionPoints(1_000, listOf(CautionPoint("p", CautionPointType.SPEED_BUMP, 80))))
            add(CautionPoints(2_000, emptyList())) // 계산상 70m 남음 → 버림
            for (s in 3..8) add(CautionPoints(s * 1_000L, listOf(CautionPoint("p", CautionPointType.SPEED_BUMP, 90 - 10 * s))))
            add(CautionPoints(10_000, emptyList()))
        }.replay(h)
        assertEquals(9_000.0, h.passes.single().t, 1.0)
    }

    @Test
    fun listsAreIndependent() {
        // 카메라 목록이 비어 있어도 주의 지점은 그대로 본다
        val profile = SpeedProfile.seconds(0 to 36, 10 to 36)
        val h = CalcHarness()
        timeline {
            speed(profile)
            add(CautionPoints(1_000, listOf(CautionPoint("p", CautionPointType.SPEED_BUMP, 15))))
            add(CameraAlerts(2_000, emptyList()))
            add(CautionPoints(3_000, emptyList()))
        }.replay(h)
        assertEquals(2_500.0, h.passes.single().t, 1.0)
    }

    @Test
    fun candidateWithoutNaviConfirmationIsDropped() {
        // 적분으로는 2.5초에 닿지만, 그 뒤 내비 목록이 오지 않아 확인할 수 없다 → 통과로 만들지 않는다
        val profile = SpeedProfile.seconds(0 to 36, 10 to 36) // 10m/s
        val h = CalcHarness()
        timeline {
            speed(profile)
            add(CautionPoints(1_000, listOf(CautionPoint("p", CautionPointType.SPEED_BUMP, 15))))
        }.replay(h)
        assertTrue(h.passes.isEmpty())
        assertTrue(h.cautionPassing.watching.isEmpty())
    }

    @Test
    fun naviSayingStillAheadReanchors() {
        // 적분으로는 3.0초 통과인데 3.2초 목록이 "아직 15m" → 내비 값으로 다시 맞춰 4.7초 통과, 5초 목록에서 빠져 확정
        val profile = SpeedProfile.seconds(0 to 36, 10 to 36) // 10m/s
        val h = CalcHarness()
        timeline {
            speed(profile)
            add(CautionPoints(1_000, listOf(CautionPoint("p", CautionPointType.SPEED_BUMP, 20))))
            add(CautionPoints(3_200, listOf(CautionPoint("p", CautionPointType.SPEED_BUMP, 15))))
            add(CautionPoints(5_000, emptyList()))
        }.replay(h)
        assertEquals(4_700.0, h.passes.single().t, 1.0)
    }

    @Test
    fun naviLatencyShiftsTheAnchor() {
        // 내비 값이 0.5초 늦게 온다: 1.0초에 받은 "10m"는 0.5초의 위치(5m) 기준 → 15m 지점 = 1.5초 통과
        val profile = SpeedProfile.seconds(0 to 36, 10 to 36)
        val h = CalcHarness(latency = LatencySource { LatencyWindow.fixed(500, marginM = 0.0) })
        timeline {
            speed(profile)
            add(CautionPoints(1_000, listOf(CautionPoint("p", CautionPointType.SPEED_BUMP, 10))))
            add(CautionPoints(2_000, emptyList()))
        }.replay(h)
        assertEquals(1_500.0, h.passes.single().t, 1.0)
    }

    @Test
    fun unknownLatencyGivesRangeContainingTruth() {
        // 지연을 모를 때(−0.5~1.5초 가정): 실제 지연 0.3초인 내비 값에서도 통과 시각 범위가 참값을 품고, 범위 안 최저 차속은 참값 이하
        val profile = SpeedProfile.seconds(0 to 60, 8 to 20, 12 to 20) // 감속 중 방지턱
        val bumpAt = profile.distanceAt(sec(5))
        val h = CalcHarness(latency = LatencySource { LatencyWindow.unknown(CalcConfig()) })
        val delayed = timeline {
            naviPoints(profile, listOf(CautionAt("bump", CautionPointType.SPEED_BUMP, bumpAt)), phaseMs = 100)
        }.signals.map { s -> (s as CautionPoints).let { CautionPoints(it.t + 300, it.points) } }
        (timeline { speed(profile, quantizeKmh = 1.0, jitterMs = 20) } + Timeline(delayed)).replay(h)
        val pass = h.passes.single()
        assertTrue("${pass.tMin} ~ ${pass.tMax}", pass.tMin <= 5_000.0 && 5_000.0 <= pass.tMax)
        assertTrue(pass.kmhMin <= 35.0 + 1.0 && pass.kmhMax >= 35.0 - 1.0)
    }
}
