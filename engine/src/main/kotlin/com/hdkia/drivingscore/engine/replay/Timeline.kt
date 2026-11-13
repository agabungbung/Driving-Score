package com.hdkia.drivingscore.engine.replay

import com.hdkia.drivingscore.engine.signal.CameraAlert
import com.hdkia.drivingscore.engine.signal.CameraAlerts
import com.hdkia.drivingscore.engine.signal.CameraType
import com.hdkia.drivingscore.engine.signal.CautionPoint
import com.hdkia.drivingscore.engine.signal.CautionPointType
import com.hdkia.drivingscore.engine.signal.CautionPoints
import com.hdkia.drivingscore.engine.signal.RoadSpeedLimit
import com.hdkia.drivingscore.engine.signal.Signal
import com.hdkia.drivingscore.engine.signal.SpeedSample
import com.hdkia.drivingscore.engine.source.SignalSink
import kotlin.math.roundToInt
import kotlin.random.Random

/** 초 → ms. */
fun sec(seconds: Number): Long = Math.round(seconds.toDouble() * 1000.0)

/**
 * 시간축: 신호를 시각순으로 담고 그대로 재생한다 (단위 테스트·시나리오 공용).
 * 같은 시각의 신호는 넣은 순서를 지킨다. 재생은 기다리지 않고 바로 넣는다(가상 시각).
 */
class Timeline(signals: Iterable<Signal>) {
    /** 시각순 (안정 정렬). */
    val signals: List<Signal> = signals.sortedBy { it.t }

    val startT: Long? get() = signals.firstOrNull()?.t
    val endT: Long? get() = signals.lastOrNull()?.t

    /** [fromT] 이상 [untilT] 이하의 신호를 시각순으로 [sink]에 넣는다. 넣은 개수를 돌려준다. */
    fun replay(sink: SignalSink<Signal>, fromT: Long = Long.MIN_VALUE, untilT: Long = Long.MAX_VALUE): Int {
        var n = 0
        for (s in signals) {
            if (s.t < fromT) continue
            if (s.t > untilT) break
            sink.accept(s)
            n++
        }
        return n
    }

    /** 두 시간축을 합친다. 같은 시각이면 이쪽이 먼저다. */
    operator fun plus(other: Timeline) = Timeline(signals + other.signals)

    inline fun <reified S : Signal> only(): List<S> = signals.filterIsInstance<S>()
}

fun timeline(block: TimelineBuilder.() -> Unit): Timeline = TimelineBuilder().apply(block).build()

/** 재생 결과를 모으는 sink. */
class SignalRecorder : SignalSink<Signal> {
    val received = mutableListOf<Signal>()
    override fun accept(signal: Signal) {
        received += signal
    }
}

/** 경로 위 지점. [atM]은 [SpeedProfile] 거리 기준(출발점부터 m). */
sealed interface RoutePoint {
    val id: String
    val atM: Double
}

data class CameraAt(
    override val id: String,
    val type: CameraType,
    override val atM: Double,
    val limitKmh: Int,
    val sectionAvgKmh: Int = 0,
) : RoutePoint

data class CautionAt(override val id: String, val type: CautionPointType, override val atM: Double) : RoutePoint

/** [fromM]부터 다음 구간 전까지의 제한속도. null = 정보 없음. */
data class LimitZone(val fromM: Double, val kmh: Int?)

/**
 * 시간축 만들기. 실제 신호의 성질을 흉내 낸다.
 * - 차속: 약 10Hz, 1km/h 단위 계단([speed]의 quantizeKmh), 도착 시각 흔들림(jitterMs)
 * - 내비: 1초마다 전체 목록, 남은 거리는 정수 m, 차속 샘플과 시각이 어긋난다(phaseMs)
 */
class TimelineBuilder {
    private val out = mutableListOf<Signal>()

    fun add(vararg signals: Signal) {
        out += signals
    }

    fun addAll(signals: Iterable<Signal>) {
        out += signals
    }

    /** [fromT]+[phaseMs]부터 [untilT]까지 [periodMs]마다 [make]가 준 신호를 넣는다 (null이면 건너뜀). */
    fun every(periodMs: Long, fromT: Long, untilT: Long, phaseMs: Long = 0, make: (t: Long) -> Signal?) {
        require(periodMs > 0)
        var t = fromT + phaseMs
        while (t <= untilT) {
            make(t)?.let { out += it }
            t += periodMs
        }
    }

    /**
     * [profile]의 참 차속을 샘플로 넣는다.
     * @param quantizeKmh 0보다 크면 그 단위로 반올림 (실측: 1km/h 단위)
     * @param jitterMs 샘플 시각을 ±jitterMs 안에서 흔든다 (실측: 95%가 20ms 이내). 시각 순서는 지킨다
     */
    fun speed(
        profile: SpeedProfile,
        periodMs: Long = 100,
        quantizeKmh: Double = 0.0,
        jitterMs: Long = 0,
        seed: Int = 1,
        fromT: Long = profile.startT,
        untilT: Long = profile.endT,
    ) {
        require(periodMs > 0)
        val rnd = Random(seed)
        var nominal = fromT
        var prev = Long.MIN_VALUE
        while (nominal <= untilT) {
            var t = if (jitterMs > 0) nominal + rnd.nextLong(-jitterMs, jitterMs + 1) else nominal
            if (t <= prev) t = prev + 1
            val v = profile.speedAt(t)
            val kmh = if (quantizeKmh > 0.0) Math.round(v / quantizeKmh) * quantizeKmh else v
            out += SpeedSample(t, kmh)
            prev = t
            nominal += periodMs
        }
    }

    /**
     * 경로 지점의 내비 목록(카메라·주의 지점)을 [periodMs]마다 넣는다.
     * 남은 거리 = 지점 위치 − [profile]의 참 거리(정수 m로 반올림). [lookaheadM] 안에 들어오면 목록에 나타나고,
     * 지나면(남은 거리 < 0) 빠진다. 통과 순간 판정의 정답은 `profile.timeAtDistance(atM)`이다.
     */
    fun naviPoints(
        profile: SpeedProfile,
        points: List<RoutePoint>,
        periodMs: Long = 1000,
        phaseMs: Long = 0,
        lookaheadM: Double = 1000.0,
        fromT: Long = profile.startT,
        untilT: Long = profile.endT,
    ) {
        val cameras = points.filterIsInstance<CameraAt>()
        val cautions = points.filterIsInstance<CautionAt>()
        var t = fromT + phaseMs
        while (t <= untilT) {
            val d = profile.distanceAt(t)
            fun remainOf(p: RoutePoint): Double? = (p.atM - d).takeIf { it >= 0.0 && it <= lookaheadM }
            if (cameras.isNotEmpty()) {
                val list = cameras.mapNotNull { c ->
                    remainOf(c)?.let { r ->
                        CameraAlert(
                            id = c.id, type = c.type, status = true, speedLimitKmh = c.limitKmh,
                            remainM = r.roundToInt(), sectionAvgKmh = c.sectionAvgKmh,
                            naviOverSpeed = false, naviOverSectionAvg = false,
                        )
                    }
                }
                out += CameraAlerts(t, list)
            }
            if (cautions.isNotEmpty()) {
                val list = cautions.mapNotNull { c -> remainOf(c)?.let { r -> CautionPoint(c.id, c.type, r.roundToInt()) } }
                out += CautionPoints(t, list)
            }
            t += periodMs
        }
    }

    /** 경로 거리 구간별 제한속도를 [periodMs]마다 넣는다 (`onCurrentRoadSpeedLimit`처럼 매초). */
    fun speedLimits(
        profile: SpeedProfile,
        zones: List<LimitZone>,
        periodMs: Long = 1000,
        phaseMs: Long = 0,
        fromT: Long = profile.startT,
        untilT: Long = profile.endT,
    ) {
        val sorted = zones.sortedBy { it.fromM }
        every(periodMs, fromT, untilT, phaseMs) { t ->
            val d = profile.distanceAt(t)
            RoadSpeedLimit(t, sorted.lastOrNull { it.fromM <= d }?.kmh)
        }
    }

    fun build() = Timeline(out)
}
