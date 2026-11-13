package com.hdkia.drivingscore.engine.calc

import com.hdkia.drivingscore.engine.config.CalcConfig
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sqrt

/**
 * 차속 신호의 해상도와 잡음을 **스스로 잰다** (2026-11-09 사용자 결정: 측정 오차 안의 경계값은 감점하지 않는다, 잴 수 있으면 잰다).
 *
 * - 해상도 q: 연속 샘플 사이 0이 아닌 변화량의 하위 5% (1km/h 계단이면 1, 0.1 단위면 0.1).
 * - 계단 신호인가: 변화량의 90% 이상이 q의 정수배(1% 안)면 계단 신호다. 계단 신호의 끝점 오차는 ±q/2를 넘지 않아
 *   1초 차분 오차가 q를 넘지 않는다 (시뮬레이션 최대 +0.97, q=1).
 * - 계단이 아니면(매끄러운 신호 + 잡음): 정속 구간 2차 차분의 제곱평균으로 잡음 σ를 구하고 여유를 5.7σ(= 4·√2·σ)로 잡는다.
 * - 변화가 [CalcConfig.speedQualityMinChanges]개 모이기 전에는 거친 쪽([CalcConfig.speedResolutionUnknownKmh])을 가정한다.
 */
class SpeedQualityEstimator(private val config: CalcConfig = CalcConfig()) {
    private val diffs = ArrayDeque<Double>()
    private val d2 = ArrayDeque<Double>()
    private var v1: Double? = null
    private var v2: Double? = null

    /** 차속 샘플(절댓값)과 그 순간 판정값(정속 여부 판단, 없으면 null). */
    fun add(kmh: Double, rateKmhPerS: Double?) {
        val v = abs(kmh)
        val a = v1
        if (a != null) {
            val d = abs(v - a)
            if (d >= 0.001) push(diffs, d) // float 변환 잡음(1e-6 수준)은 무시
            val b = v2
            if (b != null && rateKmhPerS != null && abs(rateKmhPerS) < 1.0) push(d2, v - 2 * a + b)
        }
        v2 = a
        v1 = v
    }

    private fun push(q: ArrayDeque<Double>, x: Double) {
        q.addLast(x)
        while (q.size > config.speedQualityKeep) q.removeFirst()
    }

    val measured: Boolean get() = diffs.size >= config.speedQualityMinChanges

    /** 해상도 q (km/h). */
    val resolutionKmh: Double
        get() = if (!measured) config.speedResolutionUnknownKmh else diffs.sorted()[(diffs.size * 0.05).toInt()]

    /** 계단 신호인가. */
    val quantized: Boolean
        get() {
            if (!measured) return true
            val q = resolutionKmh
            if (q < 0.01) return false
            val onGrid = diffs.count { val m = it / q; abs(m - round(m)) <= 0.01 * max(1.0, round(m)) }
            return onGrid >= diffs.size * 0.9
        }

    /** 정속 구간 잡음 σ (km/h). */
    val noiseKmh: Double
        get() = if (d2.size < config.speedQualityMinChanges) 0.0 else sqrt(d2.sumOf { it * it } / d2.size / 6.0)

    /** 1초 차분·회귀 판정값에 둘 측정 여유 (km/h/s, 창 1초 기준). */
    fun accelMarginKmhPerS(windowS: Double = 1.0): Double {
        val q = resolutionKmh
        val m = if (quantized) q else max(q, 5.7 * noiseKmh)
        return m / windowS
    }
}

/**
 * 가감속이 **확실히** 기준 이상인가 (S1~S4, 2026-11-09). 판정값 크기에서 해상도·잡음 여유와 차속 배율 오차를 빼고도
 * 기준 이상일 때만 true. 배율 오차는 [NaviTimingEstimator]가 잰 값, 모르면 3%.
 */
fun certainlyAtLeast(rateAbsKmhPerS: Double, thresholdKmhPerS: Double, marginKmhPerS: Double, scaleError: Double): Boolean =
    rateAbsKmhPerS - marginKmhPerS - rateAbsKmhPerS * scaleError >= thresholdKmhPerS
