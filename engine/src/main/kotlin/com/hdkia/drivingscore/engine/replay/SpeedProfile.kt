package com.hdkia.drivingscore.engine.replay

import com.hdkia.drivingscore.engine.calc.solveTravelTime

/**
 * 시간에 따른 **참 차속** 곡선 (구간별 직선). 재생용 차속 샘플과 내비 남은 거리를 이 곡선에서 만든다.
 * 거리는 정확한 적분값이라, 판정 계산(샘플 적분·보간)이 얼마나 맞는지 비교하는 기준이 된다.
 *
 * - 시각은 ms, 속도는 km/h(0 이상). 첫 키프레임 전은 첫 값, 마지막 뒤는 마지막 값을 유지한다.
 * - 거리는 첫 키프레임 시각부터 잰다(m).
 */
class SpeedProfile(keyframes: List<Pair<Long, Double>>) {
    private val ts: LongArray
    private val vs: DoubleArray
    /** 각 키프레임 시각까지의 누적 거리(m). */
    private val ds: DoubleArray

    init {
        require(keyframes.isNotEmpty()) { "키프레임이 없다" }
        ts = LongArray(keyframes.size) { keyframes[it].first }
        vs = DoubleArray(keyframes.size) { keyframes[it].second }
        for (i in 1 until ts.size) require(ts[i] > ts[i - 1]) { "키프레임 시각은 늘어나야 한다: ${ts[i - 1]} → ${ts[i]}" }
        require(vs.all { it >= 0.0 }) { "참 차속은 0 이상이다 (후진 방향은 기어로 표현)" }
        ds = DoubleArray(ts.size)
        for (i in 1 until ts.size) ds[i] = ds[i - 1] + (vs[i - 1] + vs[i]) / 2.0 / 3.6 * (ts[i] - ts[i - 1]) / 1000.0
    }

    val startT: Long get() = ts.first()
    val endT: Long get() = ts.last()
    val totalDistanceM: Double get() = ds.last()

    fun speedAt(t: Long): Double = speedAt(t.toDouble())

    fun speedAt(t: Double): Double {
        if (t <= ts.first()) return vs.first()
        if (t >= ts.last()) return vs.last()
        val i = segmentIndex(t)
        val f = (t - ts[i]) / (ts[i + 1] - ts[i])
        return vs[i] + (vs[i + 1] - vs[i]) * f
    }

    fun distanceAt(t: Long): Double = distanceAt(t.toDouble())

    fun distanceAt(t: Double): Double {
        if (t <= ts.first()) return 0.0
        if (t >= ts.last()) return ds.last() + vs.last() / 3.6 * (t - ts.last()) / 1000.0
        val i = segmentIndex(t)
        return ds[i] + (vs[i] + speedAt(t)) / 2.0 / 3.6 * (t - ts[i]) / 1000.0
    }

    /** 누적 거리가 [distanceM]이 되는 처음 시각(ms, 소수). 닿지 않으면 null. */
    fun timeAtDistance(distanceM: Double): Double? {
        if (distanceM <= 0.0) return ts.first().toDouble()
        for (i in 0 until ts.size - 1) {
            if (ds[i + 1] >= distanceM) {
                val v1 = vs[i] / 3.6
                val a = (vs[i + 1] - vs[i]) / 3.6 / ((ts[i + 1] - ts[i]) / 1000.0)
                return ts[i] + 1000.0 * solveTravelTime(v1, a, distanceM - ds[i])
            }
        }
        val vEnd = vs.last() / 3.6
        if (vEnd <= 0.0) return null
        return ts.last() + 1000.0 * (distanceM - ds.last()) / vEnd
    }

    private fun segmentIndex(t: Double): Int {
        var lo = 0
        var hi = ts.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (ts[mid] <= t) lo = mid else hi = mid
        }
        return lo
    }

    companion object {
        /** 초·km/h로 쓴다: `SpeedProfile.seconds(0 to 0, 10 to 60, 15 to 60, 17 to 0)`. */
        fun seconds(vararg keyframes: Pair<Number, Number>) =
            SpeedProfile(keyframes.map { (s, v) -> Math.round(s.toDouble() * 1000.0) to v.toDouble() })
    }
}
