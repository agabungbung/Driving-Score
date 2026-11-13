package com.hdkia.drivingscore.engine.config

import com.hdkia.drivingscore.engine.signal.CautionPointType
import com.hdkia.drivingscore.engine.signal.TrafficEventType
import com.hdkia.drivingscore.engine.signal.TurnType

/**
 * 판정 엔진의 모든 상수 (PLAN 5번 "임계값은 상수로 분리해 실주행 데이터로 다시 맞출 수 있게 한다").
 *
 * - 값은 기획서 v0.3(2026-11-05 4차 확정)·확정 결정 목록을 그대로 옮겼다. 출처는 각 항목 주석.
 * - 기본값은 [EngineConfig.DEFAULT]. 테스트·debug 보정은 `copy()`로 바꾼다.
 * - 단위: 속도 km/h, 가감속 km/h/s, 거리 m, 시간 ms, 각도 °, 횡가속 g.
 * - 경계: 과속 초과폭은 모두 **'초과'**(결정 53). 그 밖의 "이상/이하/초과"는 각 주석이 PLAN 문구를 따른다.
 */
data class EngineConfig(
    val calc: CalcConfig = CalcConfig(),
    val score: ScoreConfig = ScoreConfig(),
    val context: ContextConfig = ContextConfig(),
    val night: NightConfig = NightConfig(),
    val briefing: BriefingConfig = BriefingConfig(),
    val law: LawConfig = LawConfig(),
    val safety: SafetyConfig = SafetyConfig(),
    val manner: MannerConfig = MannerConfig(),
    val tech: TechConfig = TechConfig(),
) {
    companion object {
        val DEFAULT = EngineConfig()
    }
}

/** 초과폭 구간별 값. "초과폭 > [overKmh]"인 구간 중 가장 높은 것의 [deduction]을 쓴다. */
data class OverBand(val overKmh: Double, val deduction: Double)

/** 초과폭 [excessKmh](= 차속 − 제한)에 맞는 하락값. 어느 구간도 넘지 않으면 null. 경계는 '초과'. */
fun List<OverBand>.deductionFor(excessKmh: Double): Double? =
    sortedBy { it.overKmh }.lastOrNull { excessKmh > it.overKmh }?.deduction

// ─────────────────────────────── 공통 계산 ───────────────────────────────

/** 공통 계산 (가감속·적분·보간). 실측 신호 품질은 ITEM_CHECK 2번. */
data class CalcConfig(
    /** 가감속 선형회귀 창. 10Hz 차속의 1초 (ITEM_CHECK 2·3번). */
    val accelWindowMs: Long = 1_000,
    /** 회귀에 필요한 최소 샘플 수. 실측 약 7~10Hz라 1초에 7개 이상 들어온다. 구현 값. */
    val accelMinSamples: Int = 5,
    /** 회귀 창이 최소 이만큼은 덮어야 한다. 구현 값. */
    val accelMinSpanMs: Long = 800,
    /** 1초 차분: 창 시작 시각을 둘러싼 두 샘플 간격이 이보다 길면 보간하지 않는다. 구현 값. */
    val accelMaxEdgeGapMs: Long = 500,
    /** 차속 샘플 간격이 이보다 길면 끊긴 것으로 보고 그 사이 거리를 더하지 않는다. 구현 값. */
    val maxSampleGapMs: Long = 5_000,
    /** 보간용 차속 이력 보관 시간. M1(통과 후 15초)·L13(60초)보다 길게. 구현 값. */
    val trackRetentionMs: Long = 120_000,
    /** 통과 보간: 마지막 남은 거리 갱신 뒤 이 시간 안에 닿지 않으면 버린다. 내비 1초 주기 × 2. 구현 값. */
    val passMaxExtrapolationMs: Long = 2_000,
    /**
     * 통과 보간: 지점이 빠진 새 목록이 왔을 때, 그 순간 계산상 아직 이만큼보다 더 남아 있었으면 지나지 않은 것으로 보고 버린다
     * (경로 이탈 등으로 빠진 지점을 통과로 만들지 않는다). 내비 남은 거리 반올림·위치 오차 여유. 구현 값, 실차 로그로 다시 맞춘다.
     */
    val passVanishToleranceM: Double = 10.0,
    /** 통과 보간: 적분으로 구한 통과 후보를 이 시간 안에 온 내비 목록으로 확인하지 못하면 버린다. 내비 1초 주기 × 2. 구현 값. */
    val passConfirmWaitMs: Long = 2_000,
    /**
     * 내비 지연(값이 실제 위치보다 늦게 오는 시간)을 아직 모를 때 가정하는 범위. 통과 시각을 이 범위로 내서,
     * 판정기는 범위 전체에서 위반일 때만 감점한다 (애매하면 감점이 덜 되는 쪽). 음수 = 내비가 앞질러 예측해서 줌. 구현 값.
     */
    val naviLatencyUnknownLowMs: Long = -500,
    val naviLatencyUnknownHighMs: Long = 1_500,
    /**
     * 통과 범위에 더하는 거리 여유(m): 남은 거리 반올림(±0.5m)·적분 오차 몫 [passRangeBaseMarginM] + 내비 위치 잡음 σ의 3배.
     * 잡음을 아직 모르면 [passRangeUnknownMarginM] (GPS 3m × 3 + 1). 구현 값.
     */
    val passRangeBaseMarginM: Double = 1.0,
    val passRangeUnknownMarginM: Double = 10.0,
    /** 내비 지연·차속 배율 자동 측정: 격자 간격, 같은 지점의 연속 두 갱신 간격 범위, 쓸 남은 거리 상한, 튄 값 기준. 구현 값. */
    val timingGridStepMs: Long = 20,
    val timingPairMinGapMs: Long = 500,
    val timingPairMaxGapMs: Long = 1_500,
    val timingPairMaxRemainM: Double = 1_000.0,
    val timingOutlierM: Double = 20.0,
    /** 측정이 확실하다고 보는 조건: 쌍 개수 하한, 지연 범위 폭 상한. 범위는 99.9% (카이제곱 10.83). 구현 값. */
    val timingMinPairs: Int = 100,
    val timingCertainWidthMs: Long = 200,
    val timingChiSquare: Double = 10.83,
    /** 차속 배율 오차를 아직 모를 때 가정하는 값 (차속 센서 오차 2~3%, PLAN L3 근거). */
    val speedScaleErrorUnknown: Double = 0.03,
    /** 차속 해상도를 아직 모를 때 가정하는 값 (에뮬레이터 실측 1km/h, 거친 쪽). */
    val speedResolutionUnknownKmh: Double = 1.0,
    /** 차속 해상도·잡음 측정: 모을 변화 개수 하한·보관 개수. 구현 값. */
    val speedQualityMinChanges: Int = 100,
    val speedQualityKeep: Int = 500,
    /** 통과한 지점 id를 기억하는 시간 (같은 지점 두 번 통과 방지). 구현 값. */
    val passDoneRetentionMs: Long = 600_000,
)

// ─────────────────────────────── 점수 ───────────────────────────────

/** 운전 점수 (PLAN 6번, 결정 40 — 현재 값으로 확정). */
data class ScoreConfig(
    val start: Double = 80.0,
    val min: Double = 0.0,
    val max: Double = 100.0,
    /** 1km마다 `(100 − 점수)² ÷ 이 값`만큼 회복. */
    val recoveryDivisor: Double = 20_000.0,
    /** 배치 구간: 첫 1,000km는 하락·회복 모두 2배. */
    val placementKm: Double = 1_000.0,
    val placementFactor: Double = 2.0,
)

// ─────────────────────────────── 상황 라벨·유예 ───────────────────────────────

/** 상황 라벨과 판정 유예 (PLAN 5번 "상황 라벨", "판정 유예·제외"). */
data class ContextConfig(
    /** 급감속 "카메라" 라벨: 카메라 300m 안. 감점은 그대로. */
    val cameraLabelRangeM: Double = 300.0,
    /** 급감속 "방지턱" 라벨: 방지턱 100m 안. */
    val bumpLabelRangeM: Double = 100.0,
    /** 방지턱·정체·돌발 급감속(S3·S4), 합류 급가속(S1)은 절반. */
    val softenedFactor: Double = 0.5,
    /** "돌발" 라벨 주의 지점 (내비 2): 도로 파손·낙석·야생동물. "근처" 거리는 판정기 단계에서 정한다. */
    val suddenCautionTypes: Set<CautionPointType> = setOf(
        CautionPointType.ROAD_DAMAGE_ALERT,
        CautionPointType.FALLING_ROCK_AREA,
        CautionPointType.WILDLIFE_CROSSING,
    ),
    /** "돌발" 라벨 교통정보 (내비 3, `trafficEventList`): 사고·공사·행사·재난. */
    val suddenTrafficEventTypes: Set<TrafficEventType> = setOf(
        TrafficEventType.ACCIDENT,
        TrafficEventType.REPAIRING,
        TrafficEventType.EVENT,
        TrafficEventType.DISASTER,
    ),
    /** "합류" 라벨: 제한속도가 이만큼 이상 오른 뒤 [mergeWindowMs] 동안 (또는 TBT IC 진입·분기합류 직후). */
    val mergeLimitRiseKmh: Int = 30,
    val mergeWindowMs: Long = 30_000,
    /** "합류" 라벨 TBT. */
    val mergeTurnTypes: Set<TurnType> = setOf(
        TurnType.TURN_ENTER_IC_L, TurnType.TURN_ENTER_IC_R, TurnType.TURN_ENTER_IC_C, TurnType.TURN_DEVIDE_MERGE,
    ),
    /** 제한속도가 내려간 지점부터 이 시간은 L1 판정을 미룬다. */
    val limitDropGraceMs: Long = 10_000,
    /** 제한속도가 이 시간 안에 튀었다 돌아오면 무시한다 (지도 매칭 오류). "5초 이내". */
    val limitSpikeIgnoreMs: Long = 5_000,
    /** 회전교차로 TBT: L5 깜빡이·T1 회전 거침을 판정하지 않는다. */
    val roundaboutTurnTypes: Set<TurnType> = setOf(
        TurnType.TURN_ENTER_ROUNDABOUT_02, TurnType.TURN_ENTER_ROUNDABOUT_03, TurnType.TURN_ENTER_ROUNDABOUT_04,
        TurnType.TURN_ENTER_ROUNDABOUT_06, TurnType.TURN_ENTER_ROUNDABOUT_08, TurnType.TURN_ENTER_ROUNDABOUT_09,
        TurnType.TURN_ENTER_ROUNDABOUT_10, TurnType.TURN_ENTER_ROUNDABOUT_12, TurnType.TURN_EXIT_ROUNDABOUT,
    ),
)

/** "확정적인 밤" (결정 45). L9 야간과 안전 축 1.5배가 같은 정의를 쓴다. */
data class NightConfig(
    /** 해 고도가 이 값 **이하** (시민박명 끝). */
    val sunAltitudeMaxDeg: Double = -6.0,
    /** `NIGHT_MODE`가 이 시간 이상 켜져 있음. */
    val nightModeMinMs: Long = 300_000,
    /** 야간 안전 축(S1~S12)만 곱한다. L1 과속의 안전 몫 30%에는 곱하지 않는다 (결정 53). */
    val safetyMultiplier: Double = 1.5,
)

/** 브리핑 시점 (결정 50). */
data class BriefingConfig(
    /** 목적지 도착 뒤 이 시간 안에 P가 되면 알린다. */
    val arrivalToParkMaxMs: Long = 600_000,
)

// ─────────────────────────────── 법규 ───────────────────────────────

data class LawConfig(
    val l1: L1Speeding = L1Speeding(),
    val l2: L2SchoolZone = L2SchoolZone(),
    val l3: L3SpotCamera = L3SpotCamera(),
    val l4: L4SectionCamera = L4SectionCamera(),
    val l5: L5TurnSignal = L5TurnSignal(),
    val l6: L6SignalTiming = L6SignalTiming(),
    val l7: L7DriverBelt = L7DriverBelt(),
    val l9: L9Headlamp = L9Headlamp(),
    val l13: L13HighwayReverse = L13HighwayReverse(),
)

/** L1 과속 (PLAN 5·6번, 결정 9·10·39·53). */
data class L1Speeding(
    /** 초과폭 구간별 하락: 20 초과 1.0 · 40 초과 2.0 · 60 초과 5.0 · 80 초과 8.0. */
    val bands: List<OverBand> = listOf(
        OverBand(20.0, 1.0), OverBand(40.0, 2.0), OverBand(60.0, 5.0), OverBand(80.0, 8.0),
    ),
    /** 3초 이상 이어져야 1건. 구간 값은 3초 이상 유지한 최고 초과폭. */
    val holdMs: Long = 3_000,
    /**
     * 연속 1건으로 묶기 (2026-11-09 사용자 결정, 간격 값은 위임 판단): 과속 구간이 시작된 뒤 +20 이하로 내려간 시간이
     * 이 값 **미만**이면 같은 과속으로 이어 본다(앞차 때문에 잠깐 늦춘 것). 이 값 이상 내려가 있으면 그 과속은 끝난다.
     * 3분 반복의 "이어진 시간"은 **실제로 +20을 넘은 시간만** 더한다(잠깐 내려간 시간은 빼서, 공단 장기과속
     * "+20 초과로 3분 이상"과 같게. 애매하면 감점이 덜 되는 쪽).
     */
    val mergeGapMs: Long = 30_000,
    /** 3분 넘게 이어지면 3분마다 같은 값, 최대 3배. */
    val repeatIntervalMs: Long = 180_000,
    val maxMultiplier: Int = 3,
    /** 법규 70% · 안전 30% (L1 일반 과속에만). */
    val lawShare: Double = 0.7,
    /** 흐름 과속 예외: 정체·서행 정보가 있고 차속이 구간 평균 이하일 때, 초과폭 20 초과 ~ 40 이하는 감점 0(브리핑 기록). */
    val flowExceptionOverKmh: Double = 20.0,
    val flowExceptionMaxKmh: Double = 40.0,
)

/** L2 스쿨존 과속 (결정 10·45). */
data class L2SchoolZone(
    /** 5 초과 3.0 · 20 초과 8.0. */
    val bands: List<OverBand> = listOf(OverBand(5.0, 3.0), OverBand(20.0, 8.0)),
    val holdMs: Long = 3_000,
    /** 구간 = 스쿨존 지점 통과 후 제한속도가 이 값 이하로 유지되는 동안, 단 지점 통과 후 [zoneMaxLengthM]까지. */
    val zoneMaxLimitKmh: Int = 30,
    val zoneMaxLengthM: Double = 500.0,
)

/** L3 단속 지점 초과: 통과 순간(보간) 차속이 카메라 제한 +15 초과 (결정 52). */
data class L3SpotCamera(val overKmh: Double = 15.0, val deduction: Double = 1.0)

/** L4 구간단속 초과: 구간 안 차속 평균이 구간 제한 +15 초과 (결정 52). */
data class L4SectionCamera(val overKmh: Double = 15.0, val deduction: Double = 1.0)

/** L5 회전 깜빡이 미점등·방향 불일치 (결정 37·44·53). */
data class L5TurnSignal(
    /** 회전 확인: TBT 지점 앞뒤 이 거리 안에서 진행 방향이 [turnMinHeadingChangeDeg] 이상 바뀜. */
    val turnWindowM: Double = 50.0,
    val turnMinHeadingChangeDeg: Double = 60.0,
    val deduction: Double = 0.5,
    /** 감점 대상 TBT (TURN_UTURN은 왼쪽). */
    val penalizedTurnTypes: Set<TurnType> = setOf(
        TurnType.TURN_LEFT, TurnType.TURN_RIGHT, TurnType.TURN_SHARP_L, TurnType.TURN_SHARP_R,
        TurnType.TURN_UTURN, TurnType.TURN_ENTER_IC_L, TurnType.TURN_ENTER_IC_R,
    ),
    /** 기록만 (debug 전용): 출구·분기·차로 선택. */
    val recordOnlyTurnTypes: Set<TurnType> = setOf(
        TurnType.TURN_KEEP_R, TurnType.TURN_KEEP_L,
        TurnType.TURN_EXIT_IC_R, TurnType.TURN_EXIT_IC_L, TurnType.TURN_EXIT_IC_C,
        TurnType.TURN_ENTER_IC_C, TurnType.TURN_DEVIDE_MERGE,
        TurnType.TURN_ENTER_UNDER_R, TurnType.TURN_ENTER_UNDER_L, TurnType.TURN_ENTER_UNDER_C,
        TurnType.TURN_AVOID_UNDER_R, TurnType.TURN_AVOID_UNDER_L, TurnType.TURN_AVOID_UNDER_C,
        TurnType.TURN_ENTER_OVER_R, TurnType.TURN_ENTER_OVER_L, TurnType.TURN_ENTER_OVER_C,
        TurnType.TURN_AVOID_OVER_R, TurnType.TURN_AVOID_OVER_L, TurnType.TURN_AVOID_OVER_C,
    ),
)

/** L6 깜빡이 시점: 켠 뒤 회전 시작까지 달린 거리가 30m(고속도로 100m) 미만. IMU 없으면 제외. 고속도로 구분은 판정기 단계에서 정한다. */
data class L6SignalTiming(
    val minDistanceM: Double = 30.0,
    val highwayMinDistanceM: Double = 100.0,
    val deduction: Double = 0.1,
)

/** L7 운전자 안전벨트: 미착용 + 20km/h 이상 30초 (결정 20·52). 처음 + 10km마다. */
data class L7DriverBelt(
    val minSpeedKmh: Double = 20.0,
    val holdMs: Long = 30_000,
    val repeatM: Double = 10_000.0,
    val deduction: Double = 2.0,
)

/** L9 야간·터널 전조등 (결정 27·45·53). 하향등 꺼짐 또는 주간주행등만 + 차속 10 초과. 처음 + 10km마다. */
data class L9Headlamp(
    val minSpeedKmh: Double = 10.0,
    /** 야간: 확정적인 밤에서 미점등이 이 시간 이어지면 1건. */
    val nightUnlitHoldMs: Long = 60_000,
    /** 터널: `TURN_TUNNEL` 지점 통과 후 이 거리 안에 켜지지 않음. 스위치 AUTO면 판정 안 함. */
    val tunnelWindowM: Double = 300.0,
    val repeatM: Double = 10_000.0,
    val deduction: Double = 2.0,
)

/** L13 고속도로·자동차전용도로 후진 (결정 47). */
data class L13HighwayReverse(
    /** 제한속도 90 이상 도로. 정지부터 후진까지 유지. */
    val minLimitKmh: Int = 90,
    /** 60km/h 이상으로 달리다 60초 안에 정지. */
    val approachMinSpeedKmh: Double = 60.0,
    val stopWithinMs: Long = 60_000,
    /** 그 사이 30km/h 이하로 달린 거리 100m 이하. */
    val slowMaxSpeedKmh: Double = 30.0,
    val slowMaxDistanceM: Double = 100.0,
    /** 후진으로 5m 이상 이동. */
    val reverseMinDistanceM: Double = 5.0,
    /** 휴게소 진입 TBT 뒤 2분 제외. */
    val serviceAreaTurnTypes: Set<TurnType> = setOf(TurnType.TURN_ENTER_SERVICEAREA, TurnType.TURN_RESTAREA),
    val serviceAreaExclusionMs: Long = 120_000,
    val deduction: Double = 3.0,
)

// ─────────────────────────────── 안전 ───────────────────────────────

data class SafetyConfig(
    val s1: S1HardAccel = S1HardAccel(),
    val s2: S2HardStart = S2HardStart(),
    val s3: S3HardDecel = S3HardDecel(),
    val s4: S4HardStop = S4HardStop(),
    val s6: S6SpeedBump = S6SpeedBump(),
    val s7: S7CautionZone = S7CautionZone(),
    val s9: S9Esc = S9Esc(),
    val s10: S10DoorOpen = S10DoorOpen(),
    val s11: S11ReverseSpeed = S11ReverseSpeed(),
    val s12: S12NeutralCoast = S12NeutralCoast(),
)

/**
 * 공단 표기 그대로의 속도 구간: [fromKmh] 이상(또는 [fromInclusive]가 false면 초과) ~ [toKmh] 이하(null = 위로 끝없음).
 * 판정값([com.hdkia.drivingscore.engine.calc.Accel.rateKmhPerS])의 크기가 [thresholdKmhPerS] 이상이면 해당.
 */
data class SpeedBandThreshold(
    val fromKmh: Double,
    val toKmh: Double?,
    val thresholdKmhPerS: Double,
    val fromInclusive: Boolean = true,
) {
    fun contains(v: Double): Boolean =
        (if (fromInclusive) v >= fromKmh else v > fromKmh) && (toKmh == null || v <= toKmh)
}

/**
 * 창 시작 속도 [startKmh]에 맞는 기준. 공단 표기는 정수 km/h라 구간 사이에 정하지 않은 소수 차속이 있다
 * (10~11, 20~21 사이, 그리고 "11~20"과 "21 초과" 사이의 21). 그 사이는 **양쪽 기준 중 높은 값**(감점이 덜 되는 쪽)을 쓴다.
 * 첫 구간 아래는 null(해당 없음).
 */
fun List<SpeedBandThreshold>.thresholdFor(startKmh: Double): Double? {
    firstOrNull { it.contains(startKmh) }?.let { return it.thresholdKmhPerS }
    val below = filter { it.toKmh != null && it.toKmh < startKmh }.maxByOrNull { it.toKmh!! } ?: return null
    val above = filter { it.fromKmh >= startKmh }.minByOrNull { it.fromKmh } ?: return null
    return maxOf(below.thresholdKmhPerS, above.thresholdKmhPerS)
}

/*
 * S1~S4 공통 (2026-11-09)
 * - 판정값은 1초 차분과 1초 회귀가 **둘 다** 넘을 때의 값이다 ([com.hdkia.drivingscore.engine.calc.Accel]).
 * - 속도 구간은 **창 시작(1초 전) 속도**로 고른다. 가속이면 낮은 속도 = 높은 기준, 감속이면 높은 속도 = 높은 기준이라
 *   어느 쪽이든 감점이 덜 되는 쪽이다.
 * - 공단 표기 사이의 소수 차속은 양쪽 중 높은 기준 ([thresholdFor]). 5 초과 6 미만은 S1·S2 모두 해당 없음.
 * - 창 시작 속도 5 이하에서의 가속은 S2(급출발)로 보고 S1로 세지 않는다 (한 번의 출발은 하나만, PLAN 6번 겹침 규칙).
 */

/** S1 급가속 (eTAS 택시 기준, ITEM_CHECK 3번): 6~10km/h 12, 11~20km/h 10, 21km/h 초과 8km/h/s 이상 (공단 표기 그대로). */
data class S1HardAccel(
    val bands: List<SpeedBandThreshold> = listOf(
        SpeedBandThreshold(6.0, 10.0, 12.0),
        SpeedBandThreshold(11.0, 20.0, 10.0),
        SpeedBandThreshold(21.0, null, 8.0, fromInclusive = false),
    ),
    val deduction: Double = 0.2,
)

/** S2 급출발: 창 시작 속도 5km/h 이하에서 10km/h/s 이상 가속. */
data class S2HardStart(
    val startMaxKmh: Double = 5.0,
    val thresholdKmhPerS: Double = 10.0,
    val deduction: Double = 0.2,
)

/**
 * S3 급감속: 창 안 최저 속도가 6km/h 이상(유지), 창 시작 속도 30 이하 14 · 30 초과 15km/h/s 이상.
 * 감속이 5km/h 이하로 끝나면 S3가 아니라 S4다 (한 번의 감속은 하나만).
 */
data class S3HardDecel(
    val minSpeedKmh: Double = 6.0,
    val lowSpeedMaxKmh: Double = 30.0,
    val lowSpeedThresholdKmhPerS: Double = 14.0,
    val highSpeedThresholdKmhPerS: Double = 15.0,
    val deduction: Double = 0.3,
) {
    /** 창 시작 속도 [startKmh]에서의 기준. */
    fun thresholdFor(startKmh: Double): Double =
        if (startKmh <= lowSpeedMaxKmh) lowSpeedThresholdKmhPerS else highSpeedThresholdKmhPerS
}

/** S4 급정거: 14km/h/s 이상 감속해 5km/h 이하. */
data class S4HardStop(
    val thresholdKmhPerS: Double = 14.0,
    val endMaxKmh: Double = 5.0,
    val deduction: Double = 1.0,
)

/** S6 과속방지턱 고속 통과: 방지턱 앞뒤 15m 안의 최저 차속이 30 초과 (결정 52). */
data class S6SpeedBump(
    val windowM: Double = 15.0,
    val maxMinSpeedKmh: Double = 30.0,
    val deduction: Double = 0.5,
)

/** S7 위험구간 과속: 지점 200m 전 ~ 통과 후 200m에서 제한 +10 초과 3초 이상. 구간 1곳에 1건. */
data class S7CautionZone(
    val beforeM: Double = 200.0,
    val afterM: Double = 200.0,
    val overKmh: Double = 10.0,
    val holdMs: Long = 3_000,
    val deduction: Double = 1.0,
    /** 사고다발·내리막·낙석·안개·좁은 길·공사·미끄럼·도로 파손. */
    val cautionTypes: Set<CautionPointType> = setOf(
        CautionPointType.ACCIDENT_PRONE_AREA, CautionPointType.DOWNHILL_CAUTION_AREA,
        CautionPointType.FALLING_ROCK_AREA, CautionPointType.FOG_AREA, CautionPointType.NARROW_ROAD,
        CautionPointType.HIGHWAY_CONSTRUCTION, CautionPointType.SLIPPERY_ROAD_ALERT, CautionPointType.ROAD_DAMAGE_ALERT,
    ),
)

/** S9 ESC 개입 (선택, 권한 없으면 제외). */
data class S9Esc(val deduction: Double = 2.0)

/** S10 문·트렁크 열린 채 주행: 10km/h 초과. 처음 + 1km마다. */
data class S10DoorOpen(
    val minSpeedKmh: Double = 10.0,
    val repeatM: Double = 1_000.0,
    val deduction: Double = 1.0,
)

/** S11 후진 과속: 후진 중 10km/h 초과 3초 (절댓값). */
data class S11ReverseSpeed(
    val overKmh: Double = 10.0,
    val holdMs: Long = 3_000,
    val deduction: Double = 0.2,
)

/** S12 중립 타력 주행: N으로 30km/h 이상 10초 이상. N 1번에 1건. */
data class S12NeutralCoast(
    val minSpeedKmh: Double = 30.0,
    val holdMs: Long = 10_000,
    val deduction: Double = 0.3,
)

// ─────────────────────────────── 매너 ───────────────────────────────

data class MannerConfig(
    val m1: M1CameraReaccel = M1CameraReaccel(),
    val m3: M3SignalLeftOn = M3SignalLeftOn(),
    val m5: M5LaneMoves = M5LaneMoves(),
)

/** M1 카메라 앞 감속 후 재가속 (결정 24·53). */
data class M1CameraReaccel(
    /** 지점 단속 카메라 300m 전부터 본다. */
    val approachM: Double = 300.0,
    /** 진입·재가속 = 카메라 제한 +15 초과 (L3와 같다). */
    val overKmh: Double = 15.0,
    /** 통과 시(보간) 제한 이하로 15km/h 이상 감속. */
    val minDropKmh: Double = 15.0,
    /** 통과 후 15초 안에 다시 +15 초과. */
    val reaccelWithinMs: Long = 15_000,
    /** 그 사이 10km/h 이하로 섰으면(신호 대기) 제외. */
    val stopMaxKmh: Double = 10.0,
    val deduction: Double = 0.5,
)

/** M3 깜빡이 켠 채 주행 (결정 49·53): 30km/h 이상에서 같은 방향 30초, 그 사이 회전(30° 이상) 없음. 처음 + 5km마다. */
data class M3SignalLeftOn(
    val minSpeedKmh: Double = 30.0,
    val holdMs: Long = 30_000,
    val turnHeadingChangeDeg: Double = 30.0,
    val repeatM: Double = 5_000.0,
    val deduction: Double = 0.1,
)

/** M5 차선 계열: 기록만(debug 전용), 감점 0. 공단 급진로변경 정의. */
data class M5LaneMoves(
    val minSpeedKmh: Double = 30.0,
    val headingRateDegPerS: Double = 10.0,
    val returnWithinMs: Long = 5_000,
    val deduction: Double = 0.0,
)

// ─────────────────────────────── 기술 ───────────────────────────────

data class TechConfig(
    /** 구간 단위 항목은 1km에 최대 1건. */
    val perItemMinGapM: Double = 1_000.0,
    val t1: T1TurnHarsh = T1TurnHarsh(),
    val t2: T2UturnHarsh = T2UturnHarsh(),
    val t5: T5SpeedSurge = T5SpeedSurge(),
    val t7: T7CornerLateral = T7CornerLateral(),
    val t10: T10ParkingShuffle = T10ParkingShuffle(),
    val t11: T11Economy = T11Economy(),
    val lane: LaneExtensionConfig = LaneExtensionConfig(),
)

/** T1 좌·우회전 거침 (공단 급좌우회전): 30km/h 이상, 2초 안에 누적 회전각 60~160°. IMU 자이로. */
data class T1TurnHarsh(
    val minSpeedKmh: Double = 30.0,
    val windowMs: Long = 2_000,
    val minAngleDeg: Double = 60.0,
    val maxAngleDeg: Double = 160.0,
    val deduction: Double = 0.2,
)

/** T2 유턴 거침 (공단 급U턴): 25km/h 이상, 4초 안에 160~180°. */
data class T2UturnHarsh(
    val minSpeedKmh: Double = 25.0,
    val windowMs: Long = 4_000,
    val minAngleDeg: Double = 160.0,
    val maxAngleDeg: Double = 180.0,
    val deduction: Double = 0.3,
)

/** T5 속도 출렁임 (결정 28·32): 1초에 3km/h 이상 올렸다 내리기를 1km 안에서 3왕복. */
data class T5SpeedSurge(
    val rateKmhPerS: Double = 3.0,
    val windowM: Double = 1_000.0,
    val minRoundTrips: Int = 3,
    /** 가속이 끝나고 이 시간 안에 감속이 시작돼야 1왕복. */
    val accelToDecelMaxMs: Long = 4_000,
    /** 1km 평균 25km/h 미만(정체)은 제외. */
    val minAvgSpeedKmh: Double = 25.0,
    /** 10km/h 이하로 떨어졌다 출발한 가감속(신호 대기)은 제외. */
    val stopMaxKmh: Double = 10.0,
    val deduction: Double = 0.2,
)

/** T7 코너 쏠림 (결정 36): IMU 횡가속이 기준을 1초 이상 넘음. 40km/h 이하 0.40g → 90km/h 이상 0.30g, 사이는 직선. */
data class T7CornerLateral(
    val lowSpeedKmh: Double = 40.0,
    val lowSpeedLimitG: Double = 0.40,
    val highSpeedKmh: Double = 90.0,
    val highSpeedLimitG: Double = 0.30,
    val holdMs: Long = 1_000,
    val deduction: Double = 0.3,
) {
    /** 차속 [kmh]에서의 횡가속 기준(g). */
    fun limitG(kmh: Double): Double = when {
        kmh <= lowSpeedKmh -> lowSpeedLimitG
        kmh >= highSpeedKmh -> highSpeedLimitG
        else -> lowSpeedLimitG + (highSpeedLimitG - lowSpeedLimitG) * (kmh - lowSpeedKmh) / (highSpeedKmh - lowSpeedKmh)
    }
}

/** T10 주차 되돌림 (결정 32): 전진·후진 이동이 6번(앞뒤×3)을 넘으면. 주차 1번에 1건. */
data class T10ParkingShuffle(val maxMoves: Int = 6, val deduction: Double = 0.2)

/** T11 경제 운전: 기록만. */
data class T11Economy(val deduction: Double = 0.0)

/** 차선 확장 판정기 (결정 35·41). 공급원이 생기면 켜진다. */
data class LaneExtensionConfig(
    /** 차로 변경 깜빡이 미점등 (법규). */
    val laneChangeSignalDeduction: Double = 0.5,
    /** 차로 유지 불량 (기술, 1km당 1건). */
    val laneKeepingDeduction: Double = 0.2,
    val laneKeepingMinGapM: Double = 1_000.0,
)
