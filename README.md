# 로또 6/45 주간 예측

매주 월요일 08:00(KST)에 Claude Code 루틴이 `lotto_predict.py`를 실행해
역대 1등 당첨번호 전체를 분석하고 그 주 토요일 추첨 회차의 추천 번호 5세트를 만든다.

예측 전에 워크포워드 백테스트를 돌린다. 후보 전략(전체 빈도, 최근 빈도, 미출현 기간,
콜드, 혼합, 균등 무작위)을 과거 300회(선택 구간)로 평가해 1위를 고르고, 한 번도 보지 않은
최근 200회(검증 구간)에서도 무작위 기준선을 넘고 통계적으로 유의(z≥1.96)할 때만 채택한다.
그렇지 않으면 균등 무작위+패턴 필터를 쓴다. 결과표는 `LATEST.md`에 함께 기록된다.

- `LATEST.md` — 가장 최근 예측
- `predictions/<회차>.md|json` — 회차별 예측 기록 (다음 주 실행 때 실제 결과와 비교)
- `data/draws.json` — 역대 당첨번호 캐시 (출처: https://smok95.github.io/lotto/ , 동행복권 데이터 미러)

## 연구 결과 요약 (`research/REPORT.md`, `python3 research/research.py`, numpy·scipy 필요)

**A. 당첨 확률을 올릴 수 있는가? — 아니오.**
- 1,243회 번호 빈도 카이제곱 p=0.96, 추첨기(1·2·3호기)별 p=0.33~0.99, 호기 간 분포 차이 p=0.74.
- 핫/콜드/최근/직전회차/같은 호기 등 19개 번호 선택 전략을 943회 워크포워드 검증: 모두 |z|<1.1.
- 결론: 추첨은 통계적으로 무작위와 구분되지 않으며, 과거 번호로 다음 번호를 맞출 수 없다.

**B. 당첨됐을 때 받는 돈(기대값)을 올릴 수 있는가? — 예, 검증됨.**
- 1~3등은 당첨금을 나눠 갖는다. 회차별 판매량 대비 3·4·5등 당첨자 수로 번호·패턴별 인기도를 학습.
- 시간순 3분할(학습/모델 선택/최종 검증)에서 인기도 모델이 1·2등 당첨자 수를 판매량보다 잘 예측
  (최종 검증 순열검정 p≈0.01).
- 인기도 최하위 조합은 1등 당첨 시 기대 수령액이 중앙값 조합보다 약 15% 많다.

`lotto_predict.py`는 이 결과대로 번호 예측 전략은 백테스트를 통과할 때만 쓰고(현재 균등 무작위),
그 후보 중 인기도가 가장 낮은 조합 5개를 고른다.

```
python3 lotto_predict.py
```

> 로또는 매 회차 독립 시행이다. 이 분석은 재미용이며 당첨 확률을 높이지 않는다.

## Android 앱 (`android/`)

"로또 번호 연구소" — 위 연구 결과를 폰에서 바로 쓰는 앱 (Kotlin + Jetpack Compose).

- **이번 주 추천**: 최신 당첨번호를 받아 기기에서 인기도 최하위 조합 5세트 생성(회차 번호 시드라 같은 주엔 같은 결과),
  '새로 뽑기'로 다른 조합, 주간 루틴이 올린 `predictions/<회차>.json` 이 있으면 함께 표시.
- **당첨 결과**: 최근 30회. **분석**: 카이제곱 무작위성 검정, 번호별 출현 횟수, 참고 통계.
- **내 번호**: 6개를 골라 역대 등수 이력, 인기도, 1등 시 기대 수령액 배율 확인.
- 데이터: smok95 미러(동행복권 데이터) → 앱 캐시 → APK 내장 데이터 순. 인기도 모델은 이 저장소에서 매주 갱신본을 받음.
- 광고: AdMob 하단 배너 + '새로 뽑기'/'확인하기'를 누를 때마다 전면 광고, UMP 동의. **현재 Google 테스트 광고 ID** —
  출시 전 `android/app/build.gradle.kts` 의 `admobAppId`, `BANNER_AD_UNIT`, `INTERSTITIAL_AD_UNIT` 을 본인 ID 로 교체.

빌드 (Android SDK 필요, `android/local.properties` 에 `sdk.dir`):

```
cd android
./gradlew testDebugUnitTest   # 엔진이 Python 과 같은 값을 내는지 검증
./gradlew assembleRelease     # keystore.properties 가 있으면 그 키로, 없으면 debug 키로 서명
```

빌드 종류(flavor):
- `sideload` — 폰에 직접 설치하는 테스트용. 실행 시 `android/releases/latest.json` 을 확인해 새 버전이 있으면
  APK 를 받아 SHA-256 검증 후 기존 앱 위에 업데이트(재설치 불필요). 최초 1회 '이 출처 허용'만 켜면 된다.
- `play` — Google Play 출시용. Play 정책상 자체 업데이트 기능과 `REQUEST_INSTALL_PACKAGES` 권한이 빠져 있다.

새 버전 배포: `app/build.gradle.kts` 의 versionCode·versionName 을 올리고 `./publish_update.sh "변경 내용"` 후 커밋·푸시.

`keystore.properties`(storeFile, storePassword, keyAlias, keyPassword)와 `.jks` 는 저장소에 올리지 않는다.
