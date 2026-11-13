import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 판정 엔진: 안드로이드·Pleos SDK에 의존하지 않는 순수 Kotlin 모듈 (PLAN 8번).
// SDK 값은 app 모듈의 소스 구현이 이 모듈의 신호 모델로 바꿔서 넣는다. 단위 테스트는 JVM에서 바로 돈다.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit)
}
