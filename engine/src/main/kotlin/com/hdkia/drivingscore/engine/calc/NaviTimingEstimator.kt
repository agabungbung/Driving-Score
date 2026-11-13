package com.hdkia.drivingscore.engine.calc

import com.hdkia.drivingscore.engine.config.CalcConfig
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 내비 시각·위치의 불확실성. 내비가 준 값은 (받은 시각 − 지연)의 위치를 말한다고 본다.
 * [lowMs]~[highMs]는 참 지연이 들어 있다고 확신하는 범위, [estimateMs]는 가장 그럴듯한 값,
 * [marginM]은 남은 거리 자체의 오차 여유(반올림·위치 잡음). 통과 범위는 이 둘을 모두 담는다.
 */
data class LatencyWindow(val estimateMs: Long, val lowMs: Long, val highMs: Long, val marginM: Double = 1.0) {
    init {
        require(lowMs <= estimateMs && estimateMs <= highMs) { "범위 밖 추정값: $lowMs ≤ $estimateMs ≤ $highMs" }
    }

    companion object {
        /** 지연을 정확히 아는 경우 (시험·재생). */
        fun fixed(ms: Long, marginM: Double = 1.0) = LatencyWindow(ms, ms, ms, marginM)

        /** 아직 모를 때: 가정 범위 전체, 추정값 0, 거리 여유도 넓게. */
        fun unknown(config: CalcConfig) =
            LatencyWindow(0L.coerceIn(config.naviLatencyUnknownLowMs, config.naviLatencyUnknownHighMs),
                config.naviLatencyUnknownLowMs, config.naviLatencyUnknownHighMs, config.passRangeUnknownMarginM)
    }
}

/** 지금 쓸 내비 지연 범위를 주는 쪽. */
fun interface LatencySource {
    fun window(): LatencyWindow
}

/**
 * 내비 지연과 차속 배율을 **차 안에서 스스로 잰다** (2026-11-09 사용자 결정: 잴 수 있으면 잰다).
 *
 * 같은 지점(카메라·주의 지점)의 남은 거리가 연속 두 갱신 사이에 줄어든 양 Δr은 그 사이 내비 기준으로 달린 거리다.
 * 지연이 L이면 그 거리는 차속 적분으로 `D(L) = 누적거리(t2 − L) − 누적거리(t1 − L)`이고, 차속 배율이 k면 `Δr ≈ k·D(L)`.
 * 속도가 바뀌는 구간에서는 L에 따라 D(L)가 달라지므로, 격자 위 L마다 최소제곱으로 k를 맞추고 오차가 가장 작은 L을 고른다.
 *
 * - 확실성: 오차 곡선에서 99.9% 범위(카이제곱 [CalcConfig.timingChiSquare])를 구한다. 쌍이 [CalcConfig.timingMinPairs]개 넘고
 *   범위가 격자 끝에 닿지 않으며 폭이 [CalcConfig.timingCertainWidthMs] 이하이면 확실하다고 본다.
 * - 정속만 달리면 L을 가를 수 없어 범위가 넓게 남는다 → 넓은 범위를 그대로 쓴다(판정이 그만큼 너그러워진다).
 * - 위치가 튄 갱신(Δr이 적분과 [CalcConfig.timingOutlierM] 넘게 다름)은 버린다.
 * - 쓰는 법: 카메라·주의 지점 목록의 항목마다 [onRemain], 차속 샘플마다 [poll]. 거리 해상도가 거친 목록(TBT는 10m 단위)은 넣지 않는다.
 */
class NaviTimingEstimator(
    private val track: KinematicTrack,
    private val config: CalcConfig = CalcConfig(),
) : LatencySource {

    /**
     * [latency] 지연 범위(거리 여유 포함), [scale] 차속 배율 k(내비 거리 ÷ 적분 거리, 쌍이 모자라면 null),
     * [scaleError] |k−1| + 3σ, [positionNoiseM] 내비 위치 잡음 σ(연속 두 갱신 차이의 잔차 ÷ √2).
     */
    data class Estimate(
        val latency: LatencyWindow,
        val scale: Double?,
        val scaleError: Double,
        val positionNoiseM: Double?,
        val pairs: Int,
        val certain: Boolean,
    )

    private val grid: LongArray = run {
        val lo = config.naviLatencyUnknownLowMs
        val hi = config.naviLatencyUnknownHighMs
        LongArray(((hi - lo) / config.timingGridStepMs + 1).toInt()) { lo + it * config.timingGridStepMs }
    }
    private val sDD = DoubleArray(grid.size)
    private val sRD = DoubleArray(grid.size)
    private var sRR = 0.0
    private var n = 0

    private class Pair(val t1: Long, val t2: Long, val dr: Double)

    private val last = HashMap<String, kotlin.Pair<Long, Double>>()
    private val waiting = ArrayDeque<Pair>()
    private var cached: Estimate? = null

    /** 지점 [key](목록 이름 + id)의 남은 거리. */
    fun onRemain(key: String, t: Long, remainM: Double) {
        val prev = last[key]
        last[key] = t to remainM
        if (prev == null) return
        val (t1, r1) = prev
        val gap = t - t1
        if (gap < config.timingPairMinGapMs || gap > config.timingPairMaxGapMs) return
        if (r1 <= 0.0 || remainM <= 0.0 || r1 > config.timingPairMaxRemainM) return
        waiting.addLast(Pair(t1, t, r1 - remainM))
        if (last.size > 256) last.keys.retainAll(last.entries.sortedByDescending { it.value.first }.take(128).map { it.key }.toSet())
    }

    /** 차속 이력이 쌍을 덮게 되면 합에 넣는다. */
    fun poll() {
        val latest = track.latest ?: return
        val earliest = track.earliest ?: return
        while (waiting.isNotEmpty()) {
            val p = waiting.first()
            if (p.t2 - grid.first() > latest.t) return // t2 − L(최소, 음수)까지 차속이 아직 안 왔다
            waiting.removeFirst()
            if (p.t1 - grid.last() < earliest.t) continue // 너무 오래됐다
            val d = DoubleArray(grid.size)
            var ok = true
            for (i in grid.indices) {
                val a = track.odoAt(p.t1 - grid[i])
                val b = track.odoAt(p.t2 - grid[i])
                if (a == null || b == null) { ok = false; break }
                d[i] = b - a
            }
            if (!ok) continue
            val mid = d[d.size / 2]
            if (abs(p.dr - mid) > config.timingOutlierM) continue // 위치가 튄 갱신
            for (i in grid.indices) {
                sDD[i] += d[i] * d[i]
                sRD[i] += p.dr * d[i]
            }
            sRR += p.dr * p.dr
            n++
            cached = null
        }
    }

    override fun window(): LatencyWindow = estimate().latency

    fun estimate(): Estimate {
        cached?.let { return it }
        val unknown = Estimate(LatencyWindow.unknown(config), null, config.speedScaleErrorUnknown, null, n, false)
        if (n < 3) return unknown.also { cached = it }
        val sse = DoubleArray(grid.size) { i -> if (sDD[i] > 0) sRR - sRD[i] * sRD[i] / sDD[i] else Double.MAX_VALUE }
        var iMin = 0
        for (i in grid.indices) if (sse[i] < sse[iMin]) iMin = i
        val sigma2 = maxOf(sse[iMin], 0.0) / (n - 2)
        val limit = sse[iMin] + config.timingChiSquare * maxOf(sigma2, 1e-9)
        var lo = iMin
        while (lo > 0 && sse[lo - 1] <= limit) lo--
        var hi = iMin
        while (hi < grid.size - 1 && sse[hi + 1] <= limit) hi++
        val touchesEdge = lo == 0 || hi == grid.size - 1
        val enough = n >= config.timingMinPairs
        val k = sRD[iMin] / sDD[iMin]
        val seK = sqrt(sigma2 / sDD[iMin])
        val noise = sqrt(sigma2 / 2.0)
        val result = if (!enough || touchesEdge) {
            unknown.copy(
                scale = if (enough) k else null,
                scaleError = if (enough) abs(k - 1) + 3 * seK else config.speedScaleErrorUnknown,
                positionNoiseM = if (enough) noise else null,
            )
        } else {
            Estimate(
                latency = LatencyWindow(grid[iMin], grid[lo], grid[hi], config.passRangeBaseMarginM + 3 * noise),
                scale = k,
                scaleError = abs(k - 1) + 3 * seK,
                positionNoiseM = noise,
                pairs = n,
                certain = grid[hi] - grid[lo] <= config.timingCertainWidthMs,
            )
        }
        cached = result
        return result
    }
}
