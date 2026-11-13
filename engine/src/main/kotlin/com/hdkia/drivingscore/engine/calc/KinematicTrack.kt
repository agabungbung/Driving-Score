package com.hdkia.drivingscore.engine.calc

import com.hdkia.drivingscore.engine.config.CalcConfig
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 차속 적분 거리와 최근 이력.
 *
 * - 누적 거리 = 차속 샘플 사이를 **사다리꼴**로 적분 (샘플 사이 차속은 직선으로 변한다고 본다). 후진도 절댓값으로 더한다.
 * - 샘플 간격이 [CalcConfig.maxSampleGapMs]보다 길면 그 사이는 모르는 구간으로 보고 거리를 더하지 않는다.
 * - 최근 [CalcConfig.trackRetentionMs] 동안의 샘플을 남겨 "그 시각의 거리·차속", "그 거리의 시각"을 보간으로 답한다.
 *   통과 순간(L2·L3·M1·S6), 회전 시작 뒤 거리(L6), 방지턱 앞뒤 최저 차속(S6)이 이것을 쓴다.
 * - 반복 감점(10km마다 등)과 점수 회복(1km마다)은 [odometerM]을 쓴다.
 * - 프로세스 재시작·프로필 전환 뒤에는 저장해 둔 누적 거리를 [startOdometerM]으로 넘겨 이어 센다
 *   (반복 감점 카운터의 상태가 이 누적 거리를 기준으로 저장되기 때문이다, PLAN 8번).
 */
class KinematicTrack(
    private val config: CalcConfig = CalcConfig(),
    startOdometerM: Double = 0.0,
) {
    init {
        require(startOdometerM >= 0.0) { "누적 거리는 0 이상이다: $startOdometerM" }
    }

    /** 샘플 시각의 차속(절댓값 km/h)과 그 시각까지의 누적 거리(m). */
    data class Point(val t: Long, val kmh: Double, val odoM: Double)

    private val points = ArrayDeque<Point>()

    /** 누적 거리(m). 저장해 두었다가 다음 시작 때 [startOdometerM]으로 넘긴다. */
    var odometerM: Double = startOdometerM
        private set

    val latest: Point? get() = points.lastOrNull()
    val earliest: Point? get() = points.firstOrNull()

    /** 남아 있는 이력 (오래된 것부터). */
    fun history(): List<Point> = points.toList()

    /** 차속 샘플을 넣는다. 시각이 같거나 거꾸로 오면 버리고 null. */
    fun add(t: Long, kmh: Double): Point? {
        val v = abs(kmh)
        val last = points.lastOrNull()
        if (last != null) {
            if (t <= last.t) return null
            if (!broken(last.t, t)) odometerM += segmentDistanceM(last.kmh, v, t - last.t)
        }
        val p = Point(t, v, odometerM)
        points.addLast(p)
        // 보간 경계를 위해 창 밖이라도 바로 앞 샘플 하나는 남긴다
        val keepFrom = t - config.trackRetentionMs
        while (points.size > 2 && points[1].t <= keepFrom) points.removeFirst()
        return p
    }

    /** 시각 [t]의 누적 거리. 이력 범위 밖이면 null. */
    fun odoAt(t: Long): Double? = odoAt(t.toDouble())

    fun odoAt(t: Double): Double? {
        val i = segmentAtTime(t) ?: return null
        val a = points[i]
        if (t <= a.t || i == points.size - 1) return a.odoM
        val b = points[i + 1]
        if (broken(a.t, b.t)) return a.odoM
        val s = (t - a.t) / 1000.0
        val v1 = a.kmh / 3.6
        val acc = (b.kmh - a.kmh) / 3.6 / ((b.t - a.t) / 1000.0)
        return a.odoM + v1 * s + 0.5 * acc * s * s
    }

    /** 시각 [t]의 차속(km/h). 이력 범위 밖이거나 끊긴 구간이면 null. */
    fun speedAt(t: Long): Double? = speedAt(t.toDouble())

    fun speedAt(t: Double): Double? {
        val i = segmentAtTime(t) ?: return null
        val a = points[i]
        if (t <= a.t || i == points.size - 1) return a.kmh
        val b = points[i + 1]
        if (broken(a.t, b.t)) return null
        return a.kmh + (b.kmh - a.kmh) * (t - a.t) / (b.t - a.t)
    }

    /**
     * 누적 거리가 처음으로 [odoM]에 닿은 순간 (시각 ms·차속·거리). 이력 범위 밖이면 null.
     * 멈춰 있던 동안 거리가 그대로면 처음 닿은 시각을 준다.
     */
    fun stateAtOdo(odoM: Double): Crossing? {
        if (points.isEmpty()) return null
        val first = points.first()
        val last = points.last()
        if (odoM < first.odoM || odoM > last.odoM) return null
        if (odoM == first.odoM) return Crossing(first.t.toDouble(), first.kmh, first.odoM)
        // odo는 늘기만 하므로 b.odo >= odoM인 첫 구간을 찾는다
        var lo = 0
        var hi = points.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (points[mid].odoM >= odoM) hi = mid else lo = mid
        }
        val a = points[lo]
        val b = points[hi]
        if (broken(a.t, b.t) || b.odoM == a.odoM) return Crossing(b.t.toDouble(), b.kmh, b.odoM)
        val v1 = a.kmh / 3.6
        val dtS = (b.t - a.t) / 1000.0
        val acc = (b.kmh - a.kmh) / 3.6 / dtS
        val s = min(solveTravelTime(v1, acc, odoM - a.odoM), dtS)
        val t = a.t + s * 1000.0
        return Crossing(t, a.kmh + (b.kmh - a.kmh) * (s / dtS), odoM)
    }

    /**
     * 누적 거리 [fromM]~[toM] 사이의 최저 차속 (경계는 보간). S6 방지턱 앞뒤 15m에 쓴다.
     * 범위가 이력에 다 들어 있지 않으면(아직 덜 달렸거나 오래돼 지워짐) null.
     */
    fun minSpeedBetweenOdo(fromM: Double, toM: Double): Double? {
        if (fromM > toM) return null
        val start = stateAtOdo(fromM) ?: return null
        val end = stateAtOdo(toM) ?: return null
        var best = min(start.kmh, end.kmh)
        // 범위 안 샘플 (멈춘 채 같은 거리에 머문 샘플 포함)
        for (p in points) if (p.odoM in fromM..toM) best = min(best, p.kmh)
        return best
    }

    /**
     * 시각 [fromT]~[toT] 사이 최저·최고 차속 (경계는 보간). 통과 시각이 범위로 주어질 때 쓴다.
     * 범위가 이력 밖이거나 끊긴 구간을 지나면 null.
     */
    fun speedRange(fromT: Double, toT: Double): kotlin.Pair<Double, Double>? {
        if (fromT > toT) return null
        val a = speedAt(fromT) ?: return null
        val b = speedAt(toT) ?: return null
        var lo = minOf(a, b)
        var hi = maxOf(a, b)
        for (i in points.indices) {
            val p = points[i]
            if (p.t <= fromT || p.t >= toT) continue
            if (i > 0 && broken(points[i - 1].t, p.t)) return null
            lo = min(lo, p.kmh)
            hi = max(hi, p.kmh)
        }
        return lo to hi
    }

    /** 이력 시각 범위 안이면 [t]가 속한 구간의 시작 인덱스. */
    private fun segmentAtTime(t: Double): Int? {
        if (points.isEmpty()) return null
        if (t < points.first().t || t > points.last().t) return null
        var lo = 0
        var hi = points.size - 1
        if (t >= points[hi].t) return hi
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (points[mid].t <= t) lo = mid else hi = mid
        }
        return lo
    }

    private fun broken(t1: Long, t2: Long) = t2 - t1 > config.maxSampleGapMs

    /** 거리가 정해진 순간. [t]는 ms(소수). */
    data class Crossing(val t: Double, val kmh: Double, val odoM: Double) {
        val tMs: Long get() = Math.round(t)
    }

    companion object {
        /** 두 샘플 사이 사다리꼴 거리(m). */
        fun segmentDistanceM(kmh1: Double, kmh2: Double, dtMs: Long): Double =
            (max(kmh1, 0.0) + max(kmh2, 0.0)) / 2.0 / 3.6 * dtMs / 1000.0
    }
}
