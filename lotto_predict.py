#!/usr/bin/env python3
"""한국 로또 6/45 주간 분석 및 1등 번호 예측.

역대 1등 당첨번호 전체를 받아 통계를 내고, 가중 무작위 추출 + 패턴 필터로
이번 주 추천 번호 5세트를 만든다. 지난주 예측이 있으면 실제 결과와 비교한다.

※ 로또는 매 회차 독립 시행이다. 이 분석은 재미용이며 당첨 확률을 높이지 않는다.

사용법: python3 lotto_predict.py
외부 패키지 없이 표준 라이브러리만 사용한다.
"""
import json
import random
import sys
import urllib.request
from collections import Counter
from datetime import date, datetime, timedelta
from pathlib import Path

DATA_URL = "https://smok95.github.io/lotto/results/all.json"
ROOT = Path(__file__).resolve().parent
DATA_FILE = ROOT / "data" / "draws.json"
PRED_DIR = ROOT / "predictions"
NUMBERS = range(1, 46)
RECENT_WINDOW = 50
SETS = 5


def fetch_draws():
    req = urllib.request.Request(DATA_URL, headers={"User-Agent": "Mozilla/5.0"})
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            raw = json.load(resp)
        draws = [
            {"draw": d["draw_no"], "date": d["date"][:10],
             "numbers": sorted(d["numbers"]), "bonus": d["bonus_no"]}
            for d in raw
        ]
        draws.sort(key=lambda d: d["draw"])
        DATA_FILE.parent.mkdir(exist_ok=True)
        DATA_FILE.write_text(json.dumps(draws, ensure_ascii=False, indent=0))
        return draws
    except Exception as e:  # 네트워크 실패 시 저장된 데이터로 진행
        if DATA_FILE.exists():
            print(f"[경고] 데이터 다운로드 실패({e}), 저장된 데이터 사용", file=sys.stderr)
            return json.loads(DATA_FILE.read_text())
        raise


def analyze(draws):
    total = Counter(n for d in draws for n in d["numbers"])
    recent = Counter(n for d in draws[-RECENT_WINDOW:] for n in d["numbers"])
    last_seen = {}
    for d in draws:
        for n in d["numbers"]:
            last_seen[n] = d["draw"]
    latest = draws[-1]["draw"]
    gap = {n: latest - last_seen.get(n, 0) for n in NUMBERS}
    sums = sorted(sum(d["numbers"]) for d in draws)
    odd_dist = Counter(sum(n % 2 for n in d["numbers"]) for d in draws)
    consec = sum(
        any(b - a == 1 for a, b in zip(d["numbers"], d["numbers"][1:]))
        for d in draws
    )
    return {
        "total": total, "recent": recent, "gap": gap,
        "sum_lo": sums[int(len(sums) * 0.15)], "sum_hi": sums[int(len(sums) * 0.85)],
        "odd_dist": odd_dist, "consec_ratio": consec / len(draws),
    }


def weights(stats, n_draws):
    """전체 빈도 40% + 최근 빈도 40% + 미출현 기간 20%를 섞은 가중치."""
    exp_total = n_draws * 6 / 45
    exp_recent = RECENT_WINDOW * 6 / 45
    w = {}
    for n in NUMBERS:
        f_total = stats["total"][n] / exp_total
        f_recent = stats["recent"][n] / exp_recent
        f_gap = min(stats["gap"][n] / 7.5, 3.0)  # 기대 출현 간격 ≈ 7.5회
        w[n] = 0.4 * f_total + 0.4 * f_recent + 0.2 * f_gap
    return w


def valid(combo, stats, last_numbers):
    odd = sum(n % 2 for n in combo)
    low = sum(n <= 22 for n in combo)
    decades = Counter((n - 1) // 10 for n in combo)
    return (
        stats["sum_lo"] <= sum(combo) <= stats["sum_hi"]
        and 2 <= odd <= 4
        and 2 <= low <= 4
        and max(decades.values()) <= 3
        and len(set(combo) & set(last_numbers)) <= 2
    )


def generate(stats, w, draws, seed):
    rng = random.Random(seed)
    last_numbers = draws[-1]["numbers"]
    past = {tuple(d["numbers"]) for d in draws}
    pool, wts = list(w), list(w.values())
    sets = []
    for _ in range(100000):
        combo = set()
        while len(combo) < 6:
            combo.add(rng.choices(pool, wts)[0])
        combo = tuple(sorted(combo))
        if combo in past or combo in sets or not valid(combo, stats, last_numbers):
            continue
        # 세트 간 겹치는 번호는 최대 2개로 제한해 다양성 확보
        if any(len(set(combo) & set(s)) > 2 for s in sets):
            continue
        sets.append(combo)
        if len(sets) == SETS:
            break
    return sets


def grade(pick, draw):
    hit = len(set(pick) & set(draw["numbers"]))
    bonus = draw["bonus"] in pick
    if hit == 6:
        return "1등"
    if hit == 5 and bonus:
        return "2등"
    return {5: "3등", 4: "4등", 3: "5등"}.get(hit, "낙첨")


def review_previous(draws):
    """직전 회차 예측 파일이 있으면 실제 결과와 비교."""
    latest = draws[-1]
    prev_file = PRED_DIR / f"{latest['draw']}.json"
    if not prev_file.exists():
        return None
    prev = json.loads(prev_file.read_text())
    lines = []
    for i, s in enumerate(prev["sets"], 1):
        hit = sorted(set(s) & set(latest["numbers"]))
        lines.append(f"| {chr(64 + i)} | {fmt(s)} | {len(hit)}개 {fmt(hit) if hit else ''} | {grade(s, latest)} |")
    return lines


def fmt(nums):
    return " ".join(f"{n:02d}" for n in nums)


def main():
    draws = fetch_draws()
    latest = draws[-1]
    target = latest["draw"] + 1
    target_date = date.fromisoformat(latest["date"]) + timedelta(days=7)
    stats = analyze(draws)
    w = weights(stats, len(draws))
    sets = generate(stats, w, draws, seed=target)

    PRED_DIR.mkdir(exist_ok=True)
    (PRED_DIR / f"{target}.json").write_text(json.dumps(
        {"draw": target, "date": target_date.isoformat(),
         "generated_at": datetime.now().isoformat(timespec="seconds"),
         "based_on": latest["draw"], "sets": [list(s) for s in sets]},
        ensure_ascii=False, indent=2))

    hot = [n for n, _ in stats["total"].most_common(10)]
    cold = [n for n, _ in sorted(stats["total"].items(), key=lambda x: x[1])[:10]]
    recent_hot = [n for n, _ in stats["recent"].most_common(10)]
    overdue = sorted(NUMBERS, key=lambda n: -stats["gap"][n])[:10]
    top_w = sorted(NUMBERS, key=lambda n: -w[n])[:12]
    odd_common = stats["odd_dist"].most_common(3)

    md = [
        f"# 제{target}회 로또 예측 ({target_date} 추첨)",
        "",
        f"분석 기준: 제1회 ~ 제{latest['draw']}회 ({len(draws)}회차) · 생성 {datetime.now():%Y-%m-%d %H:%M}",
        "",
        f"## 제{latest['draw']}회 당첨번호 ({latest['date']})",
        f"**{fmt(latest['numbers'])}** + 보너스 {latest['bonus']:02d}",
        "",
    ]
    review = review_previous(draws)
    if review:
        md += [f"### 지난주 예측 결과 (제{latest['draw']}회)", "",
               "| 세트 | 번호 | 일치 | 결과 |", "|---|---|---|---|", *review, ""]
    md += [
        "## 🎯 이번 주 추천 번호",
        "",
        "| 세트 | 번호 | 합계 | 홀:짝 |",
        "|---|---|---|---|",
        *[f"| {chr(65 + i)} | **{fmt(s)}** | {sum(s)} | {sum(n % 2 for n in s)}:{6 - sum(n % 2 for n in s)} |"
          for i, s in enumerate(sets)],
        "",
        "## 통계 요약",
        f"- 역대 최다 출현: {fmt(hot)}",
        f"- 역대 최소 출현: {fmt(cold)}",
        f"- 최근 {RECENT_WINDOW}회 최다 출현: {fmt(recent_hot)}",
        f"- 장기 미출현: " + ", ".join(f"{n:02d}({stats['gap'][n]}회)" for n in overdue),
        f"- 종합 가중치 상위: {fmt(top_w)}",
        f"- 당첨번호 합계 70% 구간: {stats['sum_lo']} ~ {stats['sum_hi']}",
        f"- 홀수 개수 분포 상위: " + ", ".join(f"{k}개 {v / len(draws):.0%}" for k, v in odd_common),
        f"- 연속번호 포함 비율: {stats['consec_ratio']:.0%}",
        "",
        "## 방법",
        "전체 빈도(40%)·최근 50회 빈도(40%)·미출현 기간(20%)으로 번호별 가중치를 만들고 가중 무작위 추출.",
        "합계 70% 구간, 홀짝 2~4개, 저(1-22)/고 2~4개, 같은 번호대 최대 3개, 직전 회차와 최대 2개 중복,",
        "역대 1등 조합 제외, 세트 간 중복 최대 2개 필터 적용. 시드=회차 번호(재현 가능).",
        "",
        "> ⚠️ 로또는 매 회차 독립 시행이며 모든 조합의 1등 확률은 1/8,145,060으로 같습니다. 재미로만 참고하세요.",
    ]
    out = PRED_DIR / f"{target}.md"
    out.write_text("\n".join(md) + "\n")
    (ROOT / "LATEST.md").write_text("\n".join(md) + "\n")
    print("\n".join(md))


if __name__ == "__main__":
    main()
