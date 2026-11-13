package com.hdkia.drivingscore.engine.signal

// 신호에 쓰는 값 종류.
// 내비 열거형은 **SDK 이름을 철자까지 그대로** 쓴다(SDK 오타 TURN_DEVIDE_MERGE·SIGN_MALFUNCION 포함).
// 소스 구현이 `valueOf(sdk.name)`으로 바꾸고, 모르는 값은 UNKNOWN으로 보낸다.
// 확인: navi-data 3.0.4 · vehicle-data 1.1.11 (2026-11-09, javap)

// ─────────────────────────────── 차량 ───────────────────────────────

/**
 * 기어 셀렉터 위치. SDK `VehicleGearValue`(PARK 4 · REVERSE 2 · NEUTRAL 1 · DRIVE 8).
 * 수동 단수(GEAR_1~9)는 전진이므로 [DRIVE]로 바꾼다.
 */
enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

/** 깜빡이. SDK `TurnSignalConstant`(NONE 0 · RIGHT 1 · LEFT 2). */
enum class TurnSignal { OFF, LEFT, RIGHT }

/**
 * 전조등 실제 상태. SDK `VehicleLightStateValue`(OFF 0 · ON 1 · DAYTIME_RUNNING 2).
 * 판정에서는 주간주행등만 켜진 것도 **꺼짐**으로 본다 (L9).
 */
enum class LampState {
    OFF, ON, DAYTIME_RUNNING;

    /** 하향등이 실제로 켜져 있는가 (주간주행등만이면 false). */
    val lit: Boolean get() = this == ON
}

/** 전조등 스위치 위치. SDK `VehicleLightSwitchValue`(… AUTOMATIC 256). */
enum class LampSwitch { OFF, ON, DAYTIME_RUNNING, AUTOMATIC }

/** 문·후드·트렁크. SDK `DoorConstant`(ROW_1_LEFT … HOOD · REAR). SDK의 REAR가 트렁크다. */
enum class Door {
    ROW1_LEFT, ROW1_RIGHT, ROW2_LEFT, ROW2_RIGHT, ROW3_LEFT, ROW3_RIGHT, HOOD, TRUNK;

    /** 사람이 타고 내리는 문인가 (후드·트렁크 제외). */
    val passengerDoor: Boolean get() = this != HOOD && this != TRUNK
}

/** 계기판 경고등 상태. */
enum class TelltaleState { OFF, ON, BLINK }

/** 좌우. 차선 신호와 회전 방향에 쓴다. */
enum class Side { LEFT, RIGHT }

// ─────────────────────────────── 내비 ───────────────────────────────

/** SDK `TurnType` (navi-data 3.0.4, 43종). */
enum class TurnType {
    TURN_UNKNOWN, TURN_STRAIGHT, TURN_RIGHT, TURN_LEFT, TURN_KEEP_R, TURN_KEEP_L, TURN_SHARP_R, TURN_SHARP_L,
    TURN_UTURN, TURN_ENTER_IC_R, TURN_ENTER_IC_L, TURN_ENTER_IC_C, TURN_EXIT_IC_R, TURN_EXIT_IC_L, TURN_EXIT_IC_C,
    TURN_ENTER_UNDER_R, TURN_ENTER_UNDER_L, TURN_ENTER_UNDER_C, TURN_AVOID_UNDER_R, TURN_AVOID_UNDER_L,
    TURN_AVOID_UNDER_C, TURN_ENTER_OVER_R, TURN_ENTER_OVER_L, TURN_ENTER_OVER_C, TURN_AVOID_OVER_R,
    TURN_AVOID_OVER_L, TURN_AVOID_OVER_C, TURN_ENTER_ROUNDABOUT_02, TURN_ENTER_ROUNDABOUT_03,
    TURN_ENTER_ROUNDABOUT_04, TURN_ENTER_ROUNDABOUT_06, TURN_ENTER_ROUNDABOUT_08, TURN_ENTER_ROUNDABOUT_09,
    TURN_ENTER_ROUNDABOUT_10, TURN_ENTER_ROUNDABOUT_12, TURN_EXIT_ROUNDABOUT, TURN_WAY, TURN_ARRIVE, TURN_TUNNEL,
    TURN_DEVIDE_MERGE, TURN_TOLL, TURN_ENTER_SERVICEAREA, TURN_RESTAREA,
}

/** SDK `CameraType` (10종) + 모르는 값. */
enum class CameraType {
    SPEED_CAMERA_FIX, SPEED_CAMERA_MOV, SPEED_CAMERA_SECTION_START, SECTION_SPEED_ALERT, SPEED_CAMERA_SECTION_END,
    SPEED_CAMERA_TRAFFIC_LIGHT, SPEED_CAMERA_REAR, VARIABLE_SPEED_CAMERA, SPEED_CAMERA_SCHOOL_ZONE,
    SPEED_CAMERA_SCHOOL_ZONE_TRAFFIC_LIGHT, UNKNOWN,
}

/** SDK `CautionPointType` (23종) + 모르는 값. */
enum class CautionPointType {
    SPEED_BUMP, SCHOOL_ZONE, ACCIDENT_PRONE_AREA, RAILROAD_CROSSING, FOG_AREA, FALLING_ROCK_AREA,
    DOWNHILL_CAUTION_AREA, WILDLIFE_CROSSING, NARROW_ROAD, HIGHWAY_CONSTRUCTION, TRAFFIC_INFO_COLLECTION,
    NO_PARKING_ZONE, TRAFFIC_LIGHT_VIOLATION_ENFORCEMENT, BUS_LANE_VIOLATION_ENFORCEMENT,
    CUT_IN_VIOLATION_ENFORCEMENT, BLOCK_INTERSECTION_ENFORCEMENT, LANE_CHANGE_CAMERA_ALERT, SLIPPERY_ROAD_ALERT,
    ROAD_DAMAGE_ALERT, WRONG_WAY_DRIVING_ALERT, BUS_LANE_UNAVAILABLE_NOTICE, BUS_LANE_AVAILABLE_NOTICE,
    SIGN_MALFUNCION, UNKNOWN,
}

/** SDK `TrafficCongestionType`. 목록에는 실제로 SLOW·HEAVY만 왔다. */
enum class CongestionType { UNKNOWN, FAST, MODERATE, SLOW, HEAVY }

/** SDK `TrafficEventType`. REPAIRING = 공사, EVENT = 행사. */
enum class TrafficEventType { ETC, ACCIDENT, ROAD_DAMAGE, REPAIRING, EVENT, DISASTER }

/** SDK `NaviMode`. 경로 없음 SAFE_GUIDING, 안내 중 ROUTE_GUIDING. */
enum class NaviMode { NOT_WORKING, SAFE_GUIDING, ROUTE_GUIDING, SIMULATION_GUIDING }
