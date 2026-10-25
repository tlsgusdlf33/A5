#!/usr/bin/env bash
# 직접 설치(sideload) 빌드를 만들어 android/releases/ 에 올릴 준비를 한다.
# 폰의 앱은 releases/latest.json 의 versionCode 가 더 높으면 업데이트를 안내한다.
# 사용법: 1) app/build.gradle.kts 의 versionCode/versionName 올리기
#        2) ./publish_update.sh "업데이트 내용"   3) git commit && git push
set -euo pipefail
cd "$(dirname "$0")"
NOTES="${1:-버그 수정 및 개선}"
[ -f keystore.properties ] || { echo "keystore.properties 없음: 기존 앱과 같은 키로 서명해야 업데이트됩니다." >&2; exit 1; }
./gradlew assembleSideloadRelease --no-daemon -q
APK=app/build/outputs/apk/sideload/release/app-sideload-release.apk
read -r CODE NAME < <(python3 - "$APK" <<'PY'
import re, subprocess, sys, glob
aapt = sorted(glob.glob("/opt/android-sdk/build-tools/*/aapt2") or glob.glob(__import__("os").environ.get("ANDROID_HOME", "") + "/build-tools/*/aapt2"))[-1]
out = subprocess.run([aapt, "dump", "badging", sys.argv[1]], capture_output=True, text=True).stdout
m = re.search(r"versionCode='(\d+)' versionName='([^']+)'", out)
print(m.group(1), m.group(2))
PY
)
mkdir -p releases
rm -f releases/LottoLab-*.apk          # 최신 1개만 유지 (예전 버전은 git 기록에 남음)
cp "$APK" "releases/LottoLab-$NAME.apk"
SHA=$(sha256sum "releases/LottoLab-$NAME.apk" | cut -d' ' -f1)
python3 - "$CODE" "$NAME" "$SHA" "$NOTES" <<'PY'
import json, sys
code, name, sha, notes = sys.argv[1:]
json.dump({"versionCode": int(code), "versionName": name,
           "apkUrl": f"android/releases/LottoLab-{name}.apk", "sha256": sha, "notes": notes},
          open("releases/latest.json", "w"), ensure_ascii=False, indent=2)
PY
cat releases/latest.json
