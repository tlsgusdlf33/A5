#!/usr/bin/env bash
# 세 가지 결과물을 빌드해 비공개 저장소(tlsgusdlf33/lotto-private) Releases 에 올리고, 예전 릴리스는 모두 지운다.
#   - LottoLab-<버전>.apk          일반 사용자용 앱 (직접 설치·테스트용, 앱 안 업데이트 포함)
#   - LottoLab-<버전>-play.aab     Google Play 업로드용 (자체 업데이트·설치 권한 없음)
#   (개인 전용 앱은 중단: 개인 번호는 비공개 저장소 Actions + Claude 주간 루틴이 알림)
# 일반 앱의 앱 안 업데이트 파일(android/releases/)도 최신본만 남기고 갱신한다.
# 광고: 기본은 테스트 광고. 정식 출시용은  USE_REAL_ADS=1 ./release_all.sh "..."
# 사용법: app/build.gradle.kts 의 versionCode·versionName 을 올린 뒤
#        ./release_all.sh "변경 내용"   → 이후 공개 저장소 변경분 커밋·푸시
set -euo pipefail
cd "$(dirname "$0")"
NOTES="${1:-업데이트}"
REPO=tlsgusdlf33/lotto-private
[ -f keystore.properties ] || { echo "keystore.properties 없음 (같은 서명 키 필요)" >&2; exit 1; }

./publish_update.sh "$NOTES"                      # 일반 앱 앱 안 업데이트용 (android/releases/, 최신 1개)
./gradlew bundlePlayRelease --no-daemon -q

NAME=$(python3 -c "import json;print(json.load(open('releases/latest.json'))['versionName'])")
ADS=$([ "${USE_REAL_ADS:-0}" = "1" ] && echo "실제 광고" || echo "테스트 광고")
NOTES="$NOTES ($ADS)"
TAG="v$NAME"
OUT=$(mktemp -d)
cp "releases/LottoLab-$NAME.apk"                                  "$OUT/LottoLab-$NAME.apk"
cp app/build/outputs/bundle/playRelease/app-play-release.aab      "$OUT/LottoLab-$NAME-play.aab"
(cd "$OUT" && sha256sum *.apk *.aab > SHA256SUMS.txt)

# 비공개 저장소 dist/ 에 넣고 푸시 → 그 저장소의 publish-release.yml 이 Release 생성 + 예전 릴리스 삭제
PRIVATE_DIR="${PRIVATE_DIR:-$(cd .. && cd .. && pwd)/lotto-private}"
[ -d "$PRIVATE_DIR/.git" ] || git clone -q "https://github.com/$REPO" "$PRIVATE_DIR"
git -C "$PRIVATE_DIR" pull -q origin main
mkdir -p "$PRIVATE_DIR/dist"
cp "$OUT"/* "$PRIVATE_DIR/dist/"
echo "$NAME" > "$PRIVATE_DIR/dist/version.txt"
printf '%s\n\n- LottoLab-%s.apk : 일반 사용자용 앱 (폰에 직접 설치·테스트)\n- LottoLab-%s-play.aab : Google Play Console 업로드용\n- SHA256SUMS.txt : 파일 무결성 확인값\n' \
  "$NOTES" "$NAME" "$NAME" > "$PRIVATE_DIR/dist/notes.md"
git -C "$PRIVATE_DIR" add dist
git -C "$PRIVATE_DIR" commit -qm "Release $TAG"
git -C "$PRIVATE_DIR" push -q origin main
rm -rf "$OUT"
echo "비공개 저장소에 $TAG 업로드 완료 → GitHub Actions 가 Release 를 만들고 예전 릴리스를 지웁니다."
