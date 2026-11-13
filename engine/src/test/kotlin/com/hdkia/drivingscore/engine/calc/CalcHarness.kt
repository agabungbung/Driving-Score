package com.hdkia.drivingscore.engine.calc

import com.hdkia.drivingscore.engine.config.CalcConfig
import com.hdkia.drivingscore.engine.signal.CameraAlerts
import com.hdkia.drivingscore.engine.signal.CautionPoints
import com.hdkia.drivingscore.engine.signal.Signal
import com.hdkia.drivingscore.engine.signal.SpeedSample
import com.hdkia.drivingscore.engine.source.SignalSink

/** 시간축 재생을 공통 계산에 잇는 시험용 sink. 엔진 파이프라인이 할 연결을 흉내 낸다. */
/** [latency] 기본은 지연 0·거리 여유 0으로 정확히 안다고 본다 (시험이 정확한 시각을 확인하도록). 모르는 경우는 따로 시험한다. */
class CalcHarness(
    config: CalcConfig = CalcConfig(),
    startOdometerM: Double = 0.0,
    latency: LatencySource = LatencySource { LatencyWindow.fixed(0, marginM = 0.0) },
) : SignalSink<Signal> {
    val track = KinematicTrack(config, startOdometerM)
    val accel = AccelEstimator(config)

    /** 목록마다 하나씩 (카메라 목록에 없다고 주의 지점이 빠진 것은 아니다). */
    val cameraPassing = PassingInterpolator(track, config, latency)
    val cautionPassing = PassingInterpolator(track, config, latency)

    val accels = mutableListOf<Accel>()
    val passes = mutableListOf<PassingInterpolator.Passing>()

    override fun accept(signal: Signal) {
        when (signal) {
            is SpeedSample -> {
                track.add(signal.t, signal.kmh)
                accel.add(signal.t, signal.kmh)?.let { accels += it }
                passes += cameraPassing.poll()
                passes += cautionPassing.poll()
            }
            is CameraAlerts -> {
                cameraPassing.onList(signal.t, signal.alerts.associate { it.id to it.remainM.toDouble() })
                passes += cameraPassing.poll()
            }
            is CautionPoints -> {
                cautionPassing.onList(signal.t, signal.points.associate { it.id to it.remainM.toDouble() })
                passes += cautionPassing.poll()
            }
            else -> Unit
        }
    }

    fun accelAt(t: Long): Accel? = accels.lastOrNull { it.t <= t }
}
