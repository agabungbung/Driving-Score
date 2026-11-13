package com.hdkia.drivingscore.engine.calc

/*
 * 반복 감점 카운터 (PLAN 6번 "반복 감점 기준" 표, 결정 42).
 * 위반이 풀리면 끝나고, 다시 시작되면 새로 센다. 주행·여정 구분은 쓰지 않는다.
 * 상태는 data class로 꺼내고 되돌릴 수 있다 (프로세스 재시작·프로필 전환 뒤 이어 가기, PLAN 8번).
 *
 * | 항목 | 카운터 |
 * |---|---|
 * | L7 벨트·L9 전조등 (10km), S10 문 (1km), M3 깜빡이 (5km) | [DistanceRepeatCounter] |
 * | L1 과속 (3분마다 같은 값, 최대 3배) | [TimeRepeatCounter] |
 * | L2 스쿨존·S7 위험구간 (구간 1곳에 1건), S12 중립 (N 1번에 1건) | [EpisodeLatch] |
 * | T5·T7 등 기술 (1km에 최대 1건) | [DistanceCooldown] |
 */

/**
 * 처음 1건 + 위반이 이어지는 동안 [intervalM]마다 1건.
 * 거리 경계는 "닿으면" 센다 (10km를 달린 순간 2건째).
 */
class DistanceRepeatCounter(val intervalM: Double, state: State = State()) {
    init {
        require(intervalM > 0.0)
    }

    /** [nextAtOdoM]: 다음 1건이 생기는 누적 거리 (위반 중일 때만 의미가 있다). */
    data class State(val active: Boolean = false, val nextAtOdoM: Double = 0.0)

    var state: State = state
        private set

    /** 위반 여부와 지금 누적 거리를 넣으면 이번에 새로 생긴 건수를 돌려준다. */
    fun update(violating: Boolean, odoM: Double): Int {
        if (!violating) {
            state = State()
            return 0
        }
        var count = 0
        var next = state.nextAtOdoM
        if (!state.active) {
            count = 1
            next = odoM + intervalM
        }
        while (odoM >= next) {
            count++
            next += intervalM
        }
        state = State(active = true, nextAtOdoM = next)
        return count
    }
}

/**
 * 시간으로 반복: 위반이 이어진 시간이 [intervalMs]를 **넘을** 때마다 1배씩 더한다. 최대 [maxMultiplier]배.
 * L1: 3분 넘게 이어지면 2배, 6분 넘게 3배 (공단 장기과속 기준 3분).
 * 위반 시작 시각은 판정기가 정한다 (예: L1은 초과가 시작된 순간).
 */
class TimeRepeatCounter(val intervalMs: Long, val maxMultiplier: Int, state: State = State()) {
    init {
        require(intervalMs > 0 && maxMultiplier >= 1)
    }

    data class State(val active: Boolean = false, val startT: Long = 0, val multiplier: Int = 0)

    var state: State = state
        private set

    /** 위반 여부와 지금 시각을 넣으면 이번에 새로 늘어난 배수를 돌려준다 (시작 순간 1). */
    fun update(violating: Boolean, t: Long): Int {
        if (!violating) {
            state = State()
            return 0
        }
        val start = if (state.active) state.startT else t
        val m = multiplierFor(t - start)
        val added = m - state.multiplier
        state = State(active = true, startT = start, multiplier = m)
        return added
    }

    /** 이어진 시간 [durationMs]에 대한 배수 (1 ~ [maxMultiplier]). */
    fun multiplierFor(durationMs: Long): Int {
        if (durationMs <= intervalMs) return 1
        val extra = (durationMs - 1) / intervalMs // 경계는 '넘게'
        return minOf(1 + extra, maxMultiplier.toLong()).toInt()
    }
}

/**
 * 구간·에피소드 1번에 1건. 에피소드(스쿨존 구간, N 기어 유지)가 이어지는 동안 조건이 몇 번 걸려도 1건이고,
 * 에피소드가 끝나면 다시 센다.
 */
class EpisodeLatch(state: State = State()) {
    data class State(val inEpisode: Boolean = false, val counted: Boolean = false)

    var state: State = state
        private set

    /** 이번에 1건이 생기면 true. */
    fun update(inEpisode: Boolean, conditionMet: Boolean): Boolean {
        if (!inEpisode) {
            state = State()
            return false
        }
        val fire = conditionMet && !state.counted
        state = State(inEpisode = true, counted = state.counted || conditionMet)
        return fire
    }
}

/**
 * 기술 항목 "1km에 최대 1건": 마지막 1건을 센 거리에서 [minGapM]을 더 달려야 다음 1건을 받는다.
 * 고정 1km 칸이 아니라 마지막 1건 기준이라, 어느 1km 안에도 2건이 생기지 않는다.
 */
class DistanceCooldown(val minGapM: Double, state: State = State()) {
    init {
        require(minGapM > 0.0)
    }

    data class State(val lastCountOdoM: Double? = null)

    var state: State = state
        private set

    /** 지금 1건을 받을 수 있으면 세고 true. */
    fun tryCount(odoM: Double): Boolean {
        val last = state.lastCountOdoM
        if (last != null && odoM - last < minGapM) return false
        state = State(odoM)
        return true
    }
}
