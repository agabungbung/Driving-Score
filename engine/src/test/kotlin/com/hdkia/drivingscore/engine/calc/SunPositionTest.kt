package com.hdkia.drivingscore.engine.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 기준값은 다른 계산식(미 해군천문대 USNO 근사식)으로 따로 구했다 (2026-11-09).
 * 두 식의 차이는 0.05° 안이어야 한다.
 */
class SunPositionTest {
    private val seoul = 37.5665 to 126.9780

    private fun check(lat: Double, lon: Double, epochMs: Long, expected: Double) =
        assertEquals(expected, SunPosition.elevationDeg(lat, lon, epochMs), 0.05)

    @Test
    fun matchesIndependentReference() {
        check(seoul.first, seoul.second, 1794211200000, 3.982) // 2026-11-09 17:00 KST
        check(seoul.first, seoul.second, 1794213000000, -1.510) // 2026-11-09 17:30 KST (일몰 직후)
        check(seoul.first, seoul.second, 1782012600000, 75.845) // 2026-06-21 12:30 KST (하지 남중 부근)
        check(seoul.first, seoul.second, 1797854400000, -43.223) // 2026-12-21 21:00 KST
        check(35.1796, 129.0756, 1773999600000, -1.849) // 부산 2026-03-20 18:40 KST
        check(-33.8688, 151.2093, 1768442400000, 77.237) // 시드니 2026-01-15 (남반구)
        check(0.0, 0.0, 1774008000000, 88.135) // 적도·본초자오선 2026-03-20 정오 UTC
    }

    @Test
    fun civilDuskInSeoulIsAboutHalfHourAfterSunset() {
        // 2026-11-09 서울: 일몰(고도 약 -0.83°)은 17:26 무렵, 시민박명 끝(-6°)은 그 25~30분 뒤 (PLAN 5번 L9)
        val day = 1794211200000L - 8 * 3_600_000L // 2026-11-09 00:00 UTC
        fun firstBelow(deg: Double): Long {
            var t = day + 7 * 3_600_000L // 16:00 KST부터
            while (SunPosition.elevationDeg(seoul.first, seoul.second, t) > deg) t += 60_000
            return t
        }
        val sunset = firstBelow(-0.833)
        val dusk = firstBelow(-6.0)
        val sunsetKstMin = ((sunset - day) / 60_000 + 9 * 60) % 1440
        assertTrue("일몰 $sunsetKstMin 분", sunsetKstMin in (17 * 60 + 22)..(17 * 60 + 30))
        val gapMin = (dusk - sunset) / 60_000
        assertTrue("일몰→시민박명 끝 $gapMin 분", gapMin in 25..30)
    }
}
