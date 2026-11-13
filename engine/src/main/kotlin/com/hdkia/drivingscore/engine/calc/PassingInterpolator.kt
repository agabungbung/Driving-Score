package com.hdkia.drivingscore.engine.calc

import com.hdkia.drivingscore.engine.config.CalcConfig
import kotlin.math.max
import kotlin.math.min

/**
 * 지점 통과 순간 보간 (PLAN 5번 "통과 순간 차속", 결정 49 — L2 지점·L3·M1·S6).
 *
 * 내비는 "지점까지 남은 거리"를 1초에 한 번 줄 뿐, **통과했다는 신호는 따로 없다**(NaviHelper 이벤트 26종, 원본 JSON도 같음, 2026-11-09).
 * 남은 거리만 쓰면 최대 1초 앞(30km/h 8m, 100km/h 28m)의 속도로 판정하게 된다. 그래서
 *
 * 1. **시각**: 마지막 남은 거리에서 그 뒤 달린 거리(차속 적분)를 빼 0이 되는 순간을 찾는다. 내비 값은 (받은 시각 − 지연)의
 *    위치를 말하므로 지연 범위([LatencySource]) 양 끝으로 각각 계산해 **통과 시각을 범위로** 낸다.
 *    지연을 아직 모르면 넓은 가정 범위(−0.5~1.5초), [NaviTimingEstimator]가 재면 좁아진다.
 * 2. **확인**: 내비 목록이 통과를 인정해야 확정한다 — 남은 거리 0 이하를 줬거나, 지점이 빠진 목록이 왔고 그 순간 계산상 남은
 *    거리가 [CalcConfig.passVanishToleranceM] 이하일 때. 계산상 그보다 더 남았는데 빠졌으면 지나기 전에 빠진 것(경로 이탈 등)이라 버린다.
 * 3. 내비가 다시 "아직 남았다"고 하면 내비 값으로 다시 맞추되, 앞서 계산한 통과 시각도 범위에 남긴다(어느 쪽인지 모르므로 넓힌다).
 *
 * 버리는 경우 (확실하지 않은 통과는 만들지 않는다): 지나기 전에 빠짐, 마지막 갱신 뒤 [CalcConfig.passMaxExtrapolationMs] 안에
 * 닿지 않음, 닿은 뒤 [CalcConfig.passConfirmWaitMs](+ 지연 상한) 안에 내비로 확인하지 못함, 이력이 지워진 먼 과거.
 *
 * 판정기는 범위 전체에서 위반일 때만 감점한다 (예: L3은 [Passing.kmhMin]이 기준을 넘을 때만).
 * 쓰는 법: **목록 하나(카메라·주의 지점 등)에 하나씩** 만든다. 목록이 올 때마다 [onList], 차속 샘플마다 [poll].
 * 통과한 지점은 [CalcConfig.passDoneRetentionMs] 동안 같은 id를 무시한다 (한 지점은 한 번만 통과).
 */
class PassingInterpolator(
    private val track: KinematicTrack,
    private val config: CalcConfig = CalcConfig(),
    private val latency: LatencySource = LatencySource { LatencyWindow.unknown(config) },
) {
    /**
     * 통과. [t]·[kmh]·[odoM]은 가장 그럴듯한 값(지연 추정값 기준), 나머지는 범위.
     * [tMin]~[tMax] 사이 어딘가에서 지났고, 그동안 차속은 [kmhMin]~[kmhMax], 누적 거리는 [odoMin]~[odoMax]였다.
     */
    data class Passing(
        val id: String,
        val t: Double,
        val kmh: Double,
        val odoM: Double,
        val tMin: Double,
        val tMax: Double,
        val kmhMin: Double,
        val kmhMax: Double,
        val odoMin: Double,
        val odoMax: Double,
    ) {
        val tMs: Long get() = Math.round(t)
    }

    private class Pending(var anchorT: Long, var anchorR: Double) {
        var vanishedAt: Long? = null
        /** 내비가 통과를 인정했다 (0 이하, 또는 여유 안에서 빠짐). */
        var confirmed = false
        /** 지금까지 계산한 통과 시각 범위 (다시 맞춰도 넓히기만 한다). */
        var tMin: Double? = null
        var tMax: Double? = null
    }

    private val pending = LinkedHashMap<String, Pending>()
    private val done = LinkedHashMap<String, Long>()
    private val ready = mutableListOf<Passing>()
    private var lastListT = Long.MIN_VALUE

    /** 지금 지켜보는 지점 id. */
    val watching: Set<String> get() = pending.keys

    /** 내비 목록 전체 (id → 남은 거리 m). 늦게 온 옛 목록([t]가 마지막 목록보다 앞)은 버린다. */
    fun onList(t: Long, remains: Map<String, Double>) {
        if (t < lastListT) return
        lastListT = t
        for ((id, r) in remains) {
            if (id in done) continue
            val p = pending.getOrPut(id) { Pending(t, r) }
            if (t < p.anchorT) continue
            p.anchorT = t
            p.anchorR = r
            p.vanishedAt = null
            p.confirmed = r <= 0.0 // 0 이하 = 내비가 이미 지났다고 한다. 다시 남았다고 하면 확인을 거둔다
        }
        for ((id, p) in pending) if (id !in remains && p.vanishedAt == null && t > p.anchorT) p.vanishedAt = t
    }

    /** 지점을 더 보지 않는다 (예: 경로 취소). */
    fun forget(id: String) {
        pending.remove(id)
    }

    /** 확정된 통과를 돌려준다 (한 번 돌려준 것은 다시 주지 않는다). */
    fun poll(): List<Passing> {
        val latest = track.latest
        val first = track.earliest
        if (latest != null && first != null) {
            val w = latency.window()
            val it = pending.entries.iterator()
            while (it.hasNext()) {
                val (id, p) = it.next()
                when (step(id, p, w, latest, first)) {
                    Step.WAIT -> Unit
                    Step.DROP -> it.remove()
                    Step.DONE -> {
                        it.remove()
                        done[id] = latest.t
                    }
                }
            }
            val expireBefore = latest.t - config.passDoneRetentionMs
            done.entries.removeIf { it.value < expireBefore }
        }
        if (ready.isEmpty()) return emptyList()
        return ready.toList().also { ready.clear() }
    }

    private enum class Step { WAIT, DROP, DONE }

    private fun step(id: String, p: Pending, w: LatencyWindow, latest: KinematicTrack.Point, first: KinematicTrack.Point): Step {
        // 지연 L의 목표 누적 거리 = 누적거리(받은 시각 − L) + 남은 거리. L이 작을수록 목표가 멀다
        if (p.anchorT - w.lowMs > latest.t) return Step.WAIT // 그 시각의 차속이 아직 안 왔다
        // 거리 여유: 남은 거리 반올림·위치 잡음만큼 앞뒤로 넓힌다
        val far = (target(p, w.lowMs) ?: return Step.WAIT) + w.marginM
        val near = (target(p, w.highMs) ?: return Step.DROP) - w.marginM
        val best = target(p, w.estimateMs) ?: return Step.WAIT
        if (near < first.odoM) return Step.DROP // 이력이 지워진 먼 과거

        val vanished = p.vanishedAt
        if (vanished != null && !p.confirmed) {
            // 이미 닿았으면 그 뒤 어느 순간이든 계산상 남은 거리는 0 이하다 → 바로 확인
            if (latest.odoM >= best && vanished - w.estimateMs >= latest.t) p.confirmed = true
            else if (vanished - w.estimateMs > latest.t) return Step.WAIT
        }
        if (vanished != null && !p.confirmed) {
            val odoThen = odoAt(vanished - w.estimateMs) ?: return Step.WAIT
            if (best - odoThen > config.passVanishToleranceM) return Step.DROP // 지나기 전에 빠졌다
            p.confirmed = true
        }

        // 외삽 한도는 가장 그럴듯한 값 기준: 내비 값(그 시각 기준)에서 2초 넘게 센 통과는 만들지 않는다
        val bestFrom = p.anchorT - w.estimateMs
        if (latest.odoM < best) {
            return if (latest.t > bestFrom + config.passMaxExtrapolationMs) Step.DROP else Step.WAIT
        }
        val cBest = track.stateAtOdo(best) ?: return Step.DROP
        if (cBest.t - bestFrom > config.passMaxExtrapolationMs) return Step.DROP
        val cNear = track.stateAtOdo(near) ?: return Step.DROP
        val waitUntil = cBest.t + max(w.highMs, 0) + config.passConfirmWaitMs
        // 범위 끝: 먼 쪽 목표에 닿은 순간. 그 전에 차가 서서 닿지 않으면 기다린 끝 시각까지로 넓힌다(그동안 어딘가에서 지났다)
        val tFar = if (latest.odoM >= far) {
            (track.stateAtOdo(far) ?: return Step.DROP).t
        } else {
            if (latest.t <= waitUntil) return Step.WAIT
            latest.t.toDouble()
        }
        p.tMin = min(p.tMin ?: cNear.t, cNear.t)
        p.tMax = max(p.tMax ?: tFar, tFar)

        if (!p.confirmed) {
            return if (latest.t > waitUntil) Step.DROP else Step.WAIT // 내비로 확인하지 못했다
        }
        val tMin = p.tMin!!
        val tMax = p.tMax!!
        val range = track.speedRange(tMin, tMax) ?: return Step.DROP
        val oMin = track.odoAt(tMin) ?: return Step.DROP
        val oMax = track.odoAt(tMax) ?: return Step.DROP
        ready += Passing(id, cBest.t, cBest.kmh, cBest.odoM, tMin, tMax, range.first, range.second, oMin, oMax)
        return Step.DONE
    }

    private fun target(p: Pending, latencyMs: Long): Double? = odoAt(p.anchorT - latencyMs)?.let { it + p.anchorR }

    private fun odoAt(t: Long): Double? {
        val first = track.earliest ?: return null
        return if (t < first.t) first.odoM else track.odoAt(t)
    }
}
