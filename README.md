# Driving Score

> 주행이 끝나면, 내비가 아는 **상황**과 차가 아는 **조작**을 합쳐 오늘 운전을 **법규·안전·매너·기술**로 판정해 브리핑한다.

Pleos Connect(AAOS) 차량용 앱 · 현대자동차그룹 차량용 앱 개발 공모전 출품작

> **상태: 개발 중.** 판정 엔진 기반(신호 모델·공급원 인터페이스·시간축 재생·공통 계산·상수, 단위 테스트)까지 있다. 판정 항목·화면은 아직 없다.

## APK 위치

- 1차 접수는 기획서(접수양식)로 했다. release APK는 아직 없다.
- 판정 항목·화면·체험 주행을 갖춘 release APK는 `apk/` 폴더에 넣는다.

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

# 판정 엔진 단위 테스트 (JVM, 에뮬레이터 불필요)
./gradlew :engine:test

# 에뮬레이터 운전자 프로필(user 10)에 설치·실행
adb install --user 10 -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start --user 10 -n com.hdkia.drivingscore/.MainActivity
```

- 첫 실행 권한 허용, 체험 주행 방법은 기능 개발과 함께 채운다

## 구성

| 모듈 | 역할 |
|---|---|
| `app` | 화면, 기록 서비스, SDK에서 신호를 받는 공급원 구현 |
| `engine` | 판정 엔진. 안드로이드·SDK에 의존하지 않는 순수 Kotlin |

`engine` 패키지
- `signal` — 엔진이 받는 신호 (차량·IMU·내비·차선). SDK 값을 이 형태로 바꿔 넣는다
- `source` — 신호 공급원 인터페이스 `VehicleSource`·`ImuSource`·`NaviSource`·`LaneSource` (실제 / 시나리오 교체)
- `replay` — 시간축 재생: 참 차속 곡선에서 실제와 같은 성질(10Hz, 1km/h 단위, 시각 흔들림, 1초 내비 갱신)의 신호를 만들어 넣는다
- `calc` — 공통 계산: 가감속(1초 차분과 1초 회귀가 둘 다 넘을 때), 차속 적분 거리, 지점 통과 범위 보간(적분 + 내비 확인), 내비 지연·차속 배율·차속 해상도 자동 측정, 반복 감점 카운터, 해 고도
- `config` — 판정 임계값·하락값 상수 (`EngineConfig`)

debug 빌드 전용 도구 (`app/src/debug`, release에는 들어가지 않는다)
- `NaviRawProbeActivity` — 내비 서비스에 콜백을 직접 걸어 SDK가 버리기 전의 원본 JSON을 `files/navi-raw-*.jsonl`로 남긴다. 실행 방법은 파일 주석

- Kotlin · Jetpack Compose · Room
- 차속·기어: AAOS `CarPropertyManager` / 차량 신호: Pleos Vehicle SDK / 내비: Pleos NaviHelper
