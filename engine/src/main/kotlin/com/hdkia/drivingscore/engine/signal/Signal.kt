package com.hdkia.drivingscore.engine.signal

/**
 * 판정 엔진이 받는 신호 (PLAN 8번 "이벤트 정규화"의 결과 형태).
 *
 * 모든 소스(Real·Scenario)는 SDK 값을 이 형태로 바꿔서 보낸다. 엔진은 SDK 타입을 모른다.
 *
 * - 시각 [t]는 **단조 증가 ms**다. 실제 주행은 `SystemClock.elapsedRealtime()` 기준, 재생은 가상 시각이다.
 *   벽시계 시각이 필요한 계산(해 고도)은 [com.hdkia.drivingscore.engine.source.EpochClock]으로 바꾼다.
 * - 단위: 속도 km/h, 거리 m, 각도 °(도), IMU는 안드로이드 원단위(m/s², rad/s).
 * - 신호는 공급원별로 나뉜다: [VehicleSignal] · [ImuSignal] · [NaviSignal] · [LaneSignal].
 * - `…Changed` 신호는 상태 신호다. 공급원이 시작 직후 현재값을 한 번 보내고, 그 전에는 엔진이 "모름"으로 본다
 *   ([com.hdkia.drivingscore.engine.source.SignalSource] 약속).
 */
sealed interface Signal {
    val t: Long
}

// ─────────────────────────────── 차량 ───────────────────────────────

/** 차량 신호. AAOS `CarPropertyManager`(차속·기어·야간)와 Pleos Vehicle SDK에서 온다. */
sealed interface VehicleSignal : Signal

/**
 * 차속 (AAOS `PERF_VEHICLE_SPEED`, m/s → km/h). 계기판 표시 속도는 쓰지 않는다.
 * 후진에서 음수로 올 수 있다. 판정·적분은 절댓값으로 한다 (S11).
 */
data class SpeedSample(override val t: Long, val kmh: Double) : VehicleSignal

/** 기어 셀렉터 위치 (`GEAR_SELECTION`). 실제 변속단(`CURRENT_GEAR`)은 쓰지 않는다 (PLAN 8번, S12). */
data class GearChanged(override val t: Long, val gear: Gear) : VehicleSignal

/** 깜빡이 (`TurnSignal`). 비상등은 SDK에서 `OFF`로 온다. */
data class TurnSignalChanged(override val t: Long, val state: TurnSignal) : VehicleSignal

/** 브레이크 페달 밟음/뗌. 세기는 오지 않는다. */
data class BrakePedalChanged(override val t: Long, val pressed: Boolean) : VehicleSignal

/**
 * 조향각 SDK 원값(°, 범위 약 -470~500). 부호는 콘솔과 반대로 온다.
 * 진행 방향(IMU·heading)으로 부호를 맞추기 **전** 값이다 (PLAN 5번 "조향 부호 보정").
 */
data class SteeringAngle(override val t: Long, val deg: Double) : VehicleSignal

/** 하향등 **실제 점등 상태**. 스위치가 AUTO여도 실제 상태가 온다. */
data class LowBeamChanged(override val t: Long, val state: LampState) : VehicleSignal

/** 하향등 스위치 위치. 변경 알림이 없어 소스가 주기적으로 조회해 보낸다 (L9 터널: AUTO면 판정 안 함). */
data class LowBeamSwitchPolled(override val t: Long, val position: LampSwitch) : VehicleSignal

/** AAOS `NIGHT_MODE`. */
data class NightModeChanged(override val t: Long, val night: Boolean) : VehicleSignal

/** 운전석 안전벨트 (L7). SDK 벨트 값은 반전되지 않는다. 동승자 벨트·착석값은 쓰지 않는다 (결정 52). */
data class DriverSeatBeltChanged(override val t: Long, val buckled: Boolean) : VehicleSignal

/** 문·트렁크·후드 열림. 트렁크·후드의 0~100 값은 0보다 크면 열림으로 바꿔서 보낸다. */
data class DoorChanged(override val t: Long, val door: Door, val open: Boolean) : VehicleSignal

/**
 * ESC 경고등 (`Safety.registerTelltales`, S9). 권한(`PRIVATE_CAR_SAFETY`)이 없으면 오지 않는다.
 * 오지 않으면 S9는 조용히 제외된다.
 */
data class EscTelltaleChanged(override val t: Long, val state: TelltaleState) : VehicleSignal

// ─────────────────────────────── IMU ───────────────────────────────

/**
 * 안드로이드 IMU (`*_LIMITED_AXES_UNCALIBRATED`, 센서 번호 40·41). 권한 없이 읽힌다.
 *
 * 축: AOSP 규약("Automotive axes")상 자동차는 **차체 기준**(X 오른쪽 · Y 앞 · Z 위, 오른손 좌표계)이어야 한다.
 * 그러나 에뮬레이터는 휴대폰 축(정지 시 중력이 y축)이라 규약을 어긴다. 엔진은 규약을 믿지 않고
 * 정차 중 중력 방향과 직진 가감속으로 축을 확인·보정한 뒤에만 IMU 판정을 한다.
 */
sealed interface ImuSignal : Signal

/**
 * 가속도(m/s²) = SDK 원값 `values[0..2]` − SDK bias 추정 `values[3..5]`. 공급원이 빼서 보낸다.
 * 지원하지 않는 축은 0이다([ImuStatus.accelAxes]). 축 보정 전 값이다.
 */
data class AccelSample(override val t: Long, val x: Double, val y: Double, val z: Double) : ImuSignal

/** 각속도(rad/s) = 원값 − SDK drift 추정. 축·지원 여부는 [AccelSample]과 같다([ImuStatus.gyroAxes]). */
data class GyroSample(override val t: Long, val x: Double, val y: Double, val z: Double) : ImuSignal

/** 센서가 값을 주는 축. LIMITED_AXES 센서의 `values[6..8]` 플래그이고 빌드 때 정해져 바뀌지 않는다. */
data class Axes(val x: Boolean, val y: Boolean, val z: Boolean) {
    val any: Boolean get() = x || y || z
    val all: Boolean get() = x && y && z

    companion object {
        val NONE = Axes(false, false, false)
        val ALL = Axes(true, true, true)
    }
}

/**
 * IMU 유무와 지원 축. 이 신호를 받기 전에는 **없음**으로 본다. 센서가 없으면 [Axes.NONE].
 * 필요한 축이 없거나 축 확인이 끝나지 않으면 T1·T2·T7·L6은 조용히 제외한다 (결정 36·46, PLAN 5번 L6).
 */
data class ImuStatus(override val t: Long, val accelAxes: Axes, val gyroAxes: Axes) : ImuSignal {
    val hasAccel: Boolean get() = accelAxes.any
    val hasGyro: Boolean get() = gyroAxes.any
}

// ─────────────────────────────── 내비 ───────────────────────────────

/** Pleos NaviHelper 신호. 위치 기반 값은 실차에서 동작한다고 가정한다 (결정 5). */
sealed interface NaviSignal : Signal

/** 현재 도로 제한속도 (`onCurrentRoadSpeedLimit`, 매초). SDK의 0(정보 없음)은 null로 바꾼다. */
data class RoadSpeedLimit(override val t: Long, val kmh: Int?) : NaviSignal

/** 현재 위치 (`onCurrentLocationInfo`, 약 1초마다). heading은 진북 기준 °. */
data class NaviPosition(
    override val t: Long,
    val latitude: Double,
    val longitude: Double,
    val headingDeg: Double,
    val routeId: String?,
) : NaviSignal

/** 단속 카메라 목록 (`onCameraAlert`). 매 갱신마다 전체 목록이 온다. */
data class CameraAlerts(override val t: Long, val alerts: List<CameraAlert>) : NaviSignal

data class CameraAlert(
    val id: String,
    val type: CameraType,
    /** SDK `status` 원값. 의미는 SDK 주석에 없다. */
    val status: Boolean,
    val speedLimitKmh: Int,
    val remainM: Int,
    /** 구간단속 구간의 평균속도 (SDK 값). */
    val sectionAvgKmh: Int,
    /** 내비 자체 과속 판단. 기준이 공개되지 않아 판정에 쓰지 않고 debug에만 남긴다 (결정 26). */
    val naviOverSpeed: Boolean,
    val naviOverSectionAvg: Boolean,
)

/** 주의 지점 목록 (`onCautionPointInfo`). 매 갱신마다 전체 목록이 온다. */
data class CautionPoints(override val t: Long, val points: List<CautionPoint>) : NaviSignal

data class CautionPoint(val id: String, val type: CautionPointType, val remainM: Int)

/**
 * 회전 안내 전체 목록 (`onTBTInfo`, 안내 중 약 1초마다).
 * 첫 항목이 다음 회전이고 그 distance는 다음 회전까지 거리다. 나머지는 직전 지점부터의 구간 거리로 보인다.
 */
data class TurnByTurn(override val t: Long, val items: List<TbtItem>) : NaviSignal

data class TbtItem(val type: TurnType, val distanceM: Double, val description: String)

/**
 * 경로 상태 (`onRouteStateInfo`). 교통정보는 경로 안내 중에만, **정체·서행 구간만** 온다.
 * 원활 구간은 목록에 없다 (흐름 정보 없음 = 일반 판정).
 */
data class RouteState(
    override val t: Long,
    val mode: NaviMode,
    val routing: Boolean,
    val congestions: List<Congestion>,
    val trafficEvents: List<TrafficEvent>,
) : NaviSignal

data class Congestion(
    /** 현재 위치에서 정체 시작점까지 거리 (SDK 주석). */
    val offsetM: Double,
    val lengthM: Double,
    val type: CongestionType,
    /** 구간 평균속도 = 흐름 속도. */
    val avgSpeedKmh: Double,
    val roadName: String,
)

data class TrafficEvent(
    val latitude: Double,
    val longitude: Double,
    val type: TrafficEventType,
    val description: String,
)

/** 경로 안내 시작 (`onRouteStarted`). 브리핑 범위의 시작이다. */
data class RouteStarted(override val t: Long, val distanceM: Int?, val destinationName: String?) : NaviSignal

/** 경로 안내 취소. 브리핑하지 않는다. */
data class RouteCancelled(override val t: Long) : NaviSignal

/** 목적지 도착 (`onDestinationArrived`). 브리핑은 이것으로만 연다 (결정 21). */
data class DestinationArrived(override val t: Long) : NaviSignal

/** 경유지 도착. 브리핑하지 않는다. */
data class WaypointArrived(override val t: Long) : NaviSignal

// ─────────────────────────────── 차선 (확장) ───────────────────────────────

/**
 * 차선 정보 (카메라·ADAS 확장, 결정 35). 지금은 공급원이 없다.
 * 차량 OS ADAS API(`Lane`·`LaneLine`·`LaneDepartureWarning`·`LaneChangeStatus`)는 이름만 확인했고 형식은 비공개다.
 * 공급원이 열리면 그 형식을 아래 신호로 바꿔 넣는다.
 */
sealed interface LaneSignal : Signal

/** 차선 공급원 유무. 받기 전에는 **없음**으로 보고 차선 판정을 끈다. */
data class LaneStatus(override val t: Long, val available: Boolean) : LaneSignal

/** 차로 안 좌우 위치: 차 가장자리에서 양쪽 차선까지 거리(m). 모르면 null. */
data class LanePosition(override val t: Long, val leftLineM: Double?, val rightLineM: Double?) : LaneSignal

/** 차선을 밟음 (바퀴가 차선 위에 올라감). */
data class LaneLineTouched(override val t: Long, val side: Side) : LaneSignal

/** 차로 이탈 경고. */
data class LaneDeparted(override val t: Long, val side: Side) : LaneSignal

/** 차로 변경 시작 (옆 차로로 넘어가기 시작). */
data class LaneChangeStarted(override val t: Long, val side: Side) : LaneSignal

/** 차로 변경 완료 (옆 차로로 완전히 옮김). */
data class LaneChangeCompleted(override val t: Long, val side: Side) : LaneSignal
