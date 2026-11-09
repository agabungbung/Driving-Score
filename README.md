# Driving Score

> 주행이 끝나면, 내비가 아는 **상황**과 차가 아는 **조작**을 합쳐 오늘 운전을 **법규·안전·매너·기술**로 판정해 브리핑한다.

Pleos Connect(AAOS) 차량용 앱 · 현대자동차그룹 차량용 앱 개발 공모전 출품작

> **상태: 개발 전 세팅.** 빌드·실행 환경만 갖춰져 있고 기능은 아직 없다.

## APK 위치

- `apk/` 폴더 (제출 전에 release APK를 넣는다)

## 테스트 환경

| 항목 | 값 |
|---|---|
| 에뮬레이터 | Pleos Connect Emulator 2.1.0 (API 34) |
| 실행 프로필 | 운전자 프로필 (user 10) |
| SDK | Pleos Playground `Vehicle` 2.3.2, `NaviHelper` 3.0.5 |
| 빌드 | Gradle 8.7 · AGP 8.5.2 · Kotlin 2.0.21 · compileSdk/targetSdk 34 · minSdk 28 · JDK 17~21 |

## 실행 방법

```bash
# 빌드 (JDK 17 또는 21)
./gradlew assembleDebug

# 에뮬레이터 운전자 프로필(user 10)에 설치·실행
adb install --user 10 -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start --user 10 -n com.hdkia.drivingscore/.MainActivity
```

- 첫 실행 권한 허용, 체험 주행 방법은 기능 개발과 함께 채운다

## 구성

- Kotlin · Jetpack Compose · Room
- 차속·기어: AAOS `CarPropertyManager` / 차량 신호: Pleos Vehicle SDK / 내비: Pleos NaviHelper
