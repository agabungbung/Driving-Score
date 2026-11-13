package com.hdkia.drivingscore.engine.calc

import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * 해의 고도 (L9 "확정적인 밤", 야간 1.5배 — 결정 45).
 *
 * NOAA 태양 위치 계산식(Meeus 기반). 위치와 시각만으로 계산하므로 외부 데이터가 아니다.
 * 결과는 **기하학적 고도**(대기 굴절 보정 없음)다. 시민박명 경계 -6°가 해 중심의 기하학적 고도로 정의되기 때문이다.
 * 정확도는 수 세기 범위에서 약 0.01° — 판정 경계(-6°)를 가르기에 충분하다.
 */
object SunPosition {

    /** 위도·경도(°, 동경 +)와 벽시계 시각(epoch ms, UTC)에서 해의 고도(°). */
    fun elevationDeg(latitudeDeg: Double, longitudeDeg: Double, epochMs: Long): Double {
        val jd = epochMs / 86_400_000.0 + 2_440_587.5
        val c = (jd - 2_451_545.0) / 36_525.0 // 율리우스 세기

        val l0 = norm360(280.46646 + c * (36000.76983 + c * 0.0003032)) // 평균 황경
        val m = 357.52911 + c * (35999.05029 - 0.0001537 * c) // 평균 근점 이각
        val e = 0.016708634 - c * (0.000042037 + 0.0000001267 * c) // 지구 궤도 이심률
        val mRad = rad(m)
        val center = sin(mRad) * (1.914602 - c * (0.004817 + 0.000014 * c)) +
            sin(2 * mRad) * (0.019993 - 0.000101 * c) +
            sin(3 * mRad) * 0.000289
        val trueLong = l0 + center
        val omega = 125.04 - 1934.136 * c
        val apparentLong = trueLong - 0.00569 - 0.00478 * sin(rad(omega))
        val meanObliq = 23.0 + (26.0 + (21.448 - c * (46.815 + c * (0.00059 - c * 0.001813))) / 60.0) / 60.0
        val obliq = meanObliq + 0.00256 * cos(rad(omega))
        val decl = asin(sin(rad(obliq)) * sin(rad(apparentLong)))

        // 균시차(분)
        val y = tan(rad(obliq / 2)).let { it * it }
        val l0Rad = rad(l0)
        val eqTimeMin = 4.0 * deg(
            y * sin(2 * l0Rad) - 2 * e * sin(mRad) + 4 * e * y * sin(mRad) * cos(2 * l0Rad) -
                0.5 * y * y * sin(4 * l0Rad) - 1.25 * e * e * sin(2 * mRad),
        )

        // 진태양시(분) → 시간각(°). cos만 쓰므로 360° 범위로 접지 않아도 된다
        val utcMinutes = Math.floorMod(epochMs, 86_400_000L) / 60_000.0
        val trueSolarMin = utcMinutes + eqTimeMin + 4.0 * longitudeDeg
        val hourAngle = trueSolarMin / 4.0 - 180.0

        val lat = rad(latitudeDeg)
        val cosZenith = (sin(lat) * sin(decl) + cos(lat) * cos(decl) * cos(rad(hourAngle))).coerceIn(-1.0, 1.0)
        return 90.0 - deg(acos(cosZenith))
    }

    private fun rad(d: Double) = Math.toRadians(d)
    private fun deg(r: Double) = Math.toDegrees(r)
    private fun norm360(d: Double) = ((d % 360.0) + 360.0) % 360.0
}
