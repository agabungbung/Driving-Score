package com.hdkia.drivingscore.engine.calc

import com.hdkia.drivingscore.engine.config.CalcConfig
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 한 시각의 가감속 (S1~S4 판정 재료). 창은 지금부터 1초 전까지다.
 *
 * 판정에는 [rateKmhPerS]를 쓴다 (2026-11-09):
 * - 공단 기준은 "초당 X km/h" = **1초 동안의 속도 변화**다. 그대로 재는 값이 [deltaKmhPerS](1초 차분)다.
 * - 1초 차분은 끝점 두 개만 써서 1km/h 계단 잡음에 흔들린다. 그래서 1초 선형회귀 기울기 [slopeKmhPerS]도 구해
 *   **둘 다 기준을 넘을 때만** 넘은 것으로 본다(둘 중 크기가 작은 값).
 * - 회귀만 쓰면 0.4~0.8초짜리 짧은 제동을 최대 1.4배 크게 읽는다(0.4초에 12km/h 감소 → 16.8).
 *   실제 제동 모양 3,000회 시뮬레이션에서 기준 14 근처 오감점: 회귀만 7.2%, 차분만 1.0%, 둘 다 0.2% (놓침 0%).
 */
data class Accel(
    val t: Long,
    /** 1초 선형회귀 기울기 (km/h/s). */
    val slopeKmhPerS: Double,
    /** 1초 동안 속도 변화 ÷ 창 길이 (km/h/s). 창이 1초면 곧 "1초 동안 몇 km/h 바뀌었나". */
    val deltaKmhPerS: Double,
    /** 창 시작(1초 전) 속도, 보간 (km/h). 급가속·급감속 속도 구간은 이 값으로 고른다. */
    val startKmh: Double,
    /** 지금 속도 (km/h). */
    val endKmh: Double,
    /** 창 안 최저·최고 속도 (창 시작 보간값 포함). S3 "6km/h 이상 유지"에 쓴다. */
    val minKmh: Double,
    val maxKmh: Double,
) {
    /** 판정값: 두 계산이 같은 방향이면 크기가 작은 쪽, 방향이 다르면 0. 가속 +, 감속 −. */
    val rateKmhPerS: Double
        get() = when {
            slopeKmhPerS > 0.0 && deltaKmhPerS > 0.0 -> min(slopeKmhPerS, deltaKmhPerS)
            slopeKmhPerS < 0.0 && deltaKmhPerS < 0.0 -> max(slopeKmhPerS, deltaKmhPerS)
            else -> 0.0
        }
}

/**
 * 가감속 계산기 (PLAN 5번 S1~S4, ITEM_CHECK 3번). 차속 샘플을 넣을 때마다 그 시각의 [Accel]을 준다.
 *
 * - 창은 지금 샘플 기준 뒤로 [CalcConfig.accelWindowMs]. 회귀는 창 안 샘플(경계 포함, 10Hz면 11개)로,
 *   1초 차분은 창 시작 시각의 속도를 앞뒤 샘플로 보간해서 구한다.
 * - 이런 때는 null: 창 안 샘플이 [CalcConfig.accelMinSamples]개 미만, 창이 [CalcConfig.accelMinSpanMs]보다 짧음,
 *   창 시작을 둘러싼 두 샘플 간격이 [CalcConfig.accelMaxEdgeGapMs]보다 김 (시작 직후·끊김 직후의 두세 점으로 큰 값이 나오지 않게).
 * - 속도는 절댓값으로 넣는다 (후진 음수 차속).
 */
class AccelEstimator(private val config: CalcConfig = CalcConfig()) {
    private val ts = ArrayDeque<Long>()
    private val vs = ArrayDeque<Double>()

    /** 마지막으로 계산한 값. */
    var current: Accel? = null
        private set

    /** 샘플을 넣고, 이 샘플 시각의 가감속을 돌려준다. 시각이 같거나 거꾸로 오면 버리고 null(이때 [current]는 그대로). */
    fun add(t: Long, kmh: Double): Accel? {
        val last = ts.lastOrNull()
        if (last != null && t <= last) return null
        // 끊긴 뒤의 첫 샘플이면 이전 창을 버린다 (끊김을 가로지르는 기울기는 만들지 않는다)
        if (last != null && t - last > config.maxSampleGapMs) clear()
        ts.addLast(t)
        vs.addLast(abs(kmh))
        val from = t - config.accelWindowMs
        // 창 시작 보간을 위해 창 밖 샘플 하나(from 이하 중 마지막)는 남긴다
        while (ts.size >= 2 && ts[1] <= from) {
            ts.removeFirst()
            vs.removeFirst()
        }
        current = compute(t, from)
        return current
    }

    fun clear() {
        ts.clear()
        vs.clear()
        current = null
    }

    private fun compute(t: Long, from: Long): Accel? {
        if (ts.first() > from) return null // 아직 1초 이력이 없다
        val first = if (ts.first() < from) 1 else 0 // 회귀는 창 안 샘플만
        val n = ts.size - first
        if (n < config.accelMinSamples) return null
        if (ts.last() - ts[first] < config.accelMinSpanMs) return null

        // 창 시작 속도 보간
        val startKmh = if (ts[0] == from) vs[0] else {
            val gap = ts[1] - ts[0]
            if (gap > config.accelMaxEdgeGapMs) return null
            vs[0] + (vs[1] - vs[0]) * (from - ts[0]).toDouble() / gap
        }

        // 선형회귀 (정밀도를 위해 창 시작 기준 초 단위, 평균을 빼고 계산)
        var meanT = 0.0
        var meanV = 0.0
        var minV = startKmh
        var maxV = startKmh
        for (i in first until ts.size) {
            meanT += (ts[i] - from) / 1000.0
            meanV += vs[i]
            minV = min(minV, vs[i])
            maxV = max(maxV, vs[i])
        }
        meanT /= n
        meanV /= n
        var sxy = 0.0
        var sxx = 0.0
        for (i in first until ts.size) {
            val dx = (ts[i] - from) / 1000.0 - meanT
            sxy += dx * (vs[i] - meanV)
            sxx += dx * dx
        }
        if (sxx <= 0.0) return null
        val endKmh = vs.last()
        return Accel(
            t = t,
            slopeKmhPerS = sxy / sxx,
            deltaKmhPerS = (endKmh - startKmh) / (config.accelWindowMs / 1000.0),
            startKmh = startKmh,
            endKmh = endKmh,
            minKmh = minV,
            maxKmh = maxV,
        )
    }
}
