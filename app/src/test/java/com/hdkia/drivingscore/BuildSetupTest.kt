package com.hdkia.drivingscore

import org.junit.Assert.assertEquals
import org.junit.Test

/** 단위 테스트 실행 환경 확인용. 판정 엔진 테스트는 개발 단계에서 추가한다. */
class BuildSetupTest {
    @Test
    fun applicationIdIsFixed() {
        assertEquals("com.hdkia.drivingscore", BuildConfig.APPLICATION_ID)
    }
}
