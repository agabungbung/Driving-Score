package com.hdkia.drivingscore.engine.source

import com.hdkia.drivingscore.engine.signal.ImuSignal
import com.hdkia.drivingscore.engine.signal.LaneSignal
import com.hdkia.drivingscore.engine.signal.NaviSignal
import com.hdkia.drivingscore.engine.signal.Signal
import com.hdkia.drivingscore.engine.signal.VehicleSignal

/** 신호를 받는 쪽. 엔진·파이프라인·기록기가 구현한다. */
fun interface SignalSink<in S : Signal> {
    fun accept(signal: S)
}

/**
 * 신호 공급원 (PLAN 8번 구조). 구현을 바꿔 끼운다.
 *
 * | 상황 | Vehicle | Imu | Navi | Lane |
 * |---|---|---|---|---|
 * | 실제 주행 | Real | Real | Real | None |
 * | 2차 PT·개발 | Real(콘솔) + Scenario(벨트·야간) | Real(`adb emu sensor`) | Scenario | None |
 * | release 체험 주행 | Scenario | Scenario | Scenario | None |
 *
 * 약속
 * - [start] 뒤에만 보내고, [stop] 뒤에는 보내지 않는다.
 * - 한 공급원 안에서는 시각 순서대로 보낸다. 여러 공급원을 합쳐 시간순으로 엔진에 넣는 일은 파이프라인이 한다.
 * - 데이터가 없거나 권한이 없으면 **조용히 보내지 않는다** (설계 원칙 4 "조용한 대체"). 예외를 던지지 않는다.
 * - **상태 신호는 [start] 직후 현재값을 한 번 보낸다**: 기어·깜빡이·브레이크·하향등 상태·하향등 스위치·야간·
 *   운전석 벨트·문(문마다)·ESC 경고등·[com.hdkia.drivingscore.engine.signal.ImuStatus]·
 *   [com.hdkia.drivingscore.engine.signal.LaneStatus]. Pleos Vehicle SDK는 `register*` 직후 현재값으로 콜백을
 *   한 번 부른다(2026-11-03 로그: 벨트·문·하향등·기어·깜빡이·브레이크 모두 10ms 안). AAOS 속성도 등록 때 현재값이 온다.
 *   스위치처럼 알림이 없는 값은 시작할 때 조회해 보낸다.
 * - 엔진은 상태 신호의 첫 값을 받기 전에는 그 상태를 **모름**으로 보고, 그 상태가 필요한 판정을 하지 않는다
 *   (예: 벨트 첫 값 전에는 L7을 보지 않는다. 미착용으로 가정하지 않는다).
 * - 신호 시각 `t`: 값에 시각이 붙어 오면 그 시각(`CarPropertyValue.timestamp`·`SensorEvent.timestamp`,
 *   모두 `elapsedRealtimeNanos` 기준 → ms), 없으면(Pleos SDK·NaviHelper 콜백) 받은 순간의 `SystemClock.elapsedRealtime()`.
 */
interface SignalSource<out S : Signal> {
    fun start(sink: SignalSink<S>)
    fun stop()
}

/** 차량: 차속·기어(AAOS), 깜빡이·브레이크·조향·전조등·벨트·문·경고등(Pleos Vehicle SDK). */
interface VehicleSource : SignalSource<VehicleSignal>

/** IMU: 안드로이드 `SensorManager`. 시작할 때 [com.hdkia.drivingscore.engine.signal.ImuStatus]로 유무를 알린다. */
interface ImuSource : SignalSource<ImuSignal>

/** 내비: Pleos NaviHelper (순정 내비, 결정 34). */
interface NaviSource : SignalSource<NaviSignal>

/**
 * 차선: 카메라·ADAS 확장 (결정 35). 공급원이 생기면 이 인터페이스로 연결만 하면 차선 판정이 켜진다.
 * 시작할 때 [com.hdkia.drivingscore.engine.signal.LaneStatus]로 유무를 알린다.
 */
interface LaneSource : SignalSource<LaneSignal>

/** 차선 공급원 없음 (지금). 아무것도 보내지 않으므로 차선 판정은 꺼진 채로 있다. */
object NoLaneSource : LaneSource {
    override fun start(sink: SignalSink<LaneSignal>) = Unit
    override fun stop() = Unit
}

/**
 * 신호 시각(단조 ms) → 벽시계 시각(epoch ms). 해 고도처럼 실제 시각이 필요한 계산에만 쓴다.
 * 실제 주행: `System.currentTimeMillis() - SystemClock.elapsedRealtime() + t`. 재생: 시나리오 시작 시각 + t.
 */
fun interface EpochClock {
    fun epochMs(t: Long): Long

    companion object {
        /** 재생용: 신호 시각 0이 [epochAtZero]인 시계. */
        fun fixed(epochAtZero: Long) = EpochClock { t -> epochAtZero + t }
    }
}
