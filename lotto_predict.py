#!/usr/bin/env python3
"""한국 로또 6/45 주간 분석 및 1등 번호 예측 (백테스트 기반 전략 선택).

1. 역대 1등 당첨번호 전체를 받는다.
2. 여러 가중치 전략과 무작위 기준선을 과거 회차에 대해 워크포워드 백테스트한다.
   (각 회차는 그 이전 데이터만으로 예측 → 실제 결과와 비교, 미래 정보 누수 없음)
   - 선택 구간: 전략 중 최고 성적을 고른다.
   - 검증 구간: 고른 전략을 한 번도 보지 않은 최근 회차로 다시 평가한다.
3. 검증 구간에서도 무작위 기준선보다 나은 전략만 채택하고, 아니면 균등 무작위+필터를 쓴다.
4. 채택한 전략으로 이번 주 추천 번호 5세트를 만들고 지난주 예측을 채점한다.

※ 로또는 매 회차 독립 시행이다. 백테스트는 과적합 여부를 드러낼 뿐 당첨 확률을 높이지 않는다.

사용법: python3 lotto_predict.py
외부 패키지 없이 표준 라이브러리만 사용한다.
"""
import json
import math
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
SELECT_DRAWS = 300    # 전략 선택 구간 회차 수
VALIDATE_DRAWS = 200  # 검증(표본 외) 구간 회차 수
TICKET = 1000

# 이름: (전체 빈도, 최근 빈도, 미출현 기간, 콜드) 가중치
STRATEGIES = {
    "혼합(빈도40·최근40·미출현20)": (0.4, 0.4, 0.2, 0),
    "전체 빈도(핫)": (1, 0, 0, 0),
    "최근 50회 빈도": (0, 1, 0, 0),
    "미출현 기간": (0, 0, 1, 0),
    "최근 콜드(적게 나온 번호)": (0, 0, 0, 1),
    "균등 무작위+필터": (0, 0, 0, 0),
}
BASELINE = "균등 무작위+필터"
PURE_RANDOM = "완전 무작위(필터 없음)"

# 한 세트(6개)가 당첨번호와 k개 일치할 초기하분포 확률
P_MATCH = {k: math.comb(6, k) * math.comb(39, 6 - k) / math.comb(45, 6) for k in range(7)}
EXP_MATCH = sum(k * p for k, p in P_MATCH.items())                       # 0.8
VAR_MATCH = sum((k - EXP_MATCH) ** 2 * p for k, p in P_MATCH.items())


def fetch_draws():
    req = urllib.request.Request(DATA_URL, headers={"User-Agent": "Mozilla/5.0"})
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            raw = json.load(resp)
        draws = [
            {"draw": d["draw_no"], "date": d["date"][:10],
             "numbers": sorted(d["numbers"]), "bonus": d["bonus_no"],
             "prizes": [div.get("prize", 0) for div in d.get("divisions", [])][:5]}
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
        "n": len(draws), "total": total, "recent": recent, "gap": gap,
        "sum_lo": sums[int(len(sums) * 0.15)], "sum_hi": sums[int(len(sums) * 0.85)],
        "odd_dist": odd_dist, "consec_ratio": consec / len(draws),
    }


def weights(stats, mix):
    a, b, c, d = mix
    if not any(mix):
        return {n: 1.0 for n in NUMBERS}
    exp_total = stats["n"] * 6 / 45
    exp_recent = min(stats["n"], RECENT_WINDOW) * 6 / 45
    w = {}
    for n in NUMBERS:
        f_total = stats["total"][n] / exp_total
        f_recent = stats["recent"][n] / exp_recent
        f_gap = min(stats["gap"][n] / 7.5, 3.0)  # 기대 출현 간격 ≈ 7.5회
        f_cold = max(0.1, 2 - f_recent)
        w[n] = max(0.01, a * f_total + b * f_recent + c * f_gap + d * f_cold)
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


def generate(stats, w, draws, seed, use_filter=True):
    rng = random.Random(seed)
    last_numbers = draws[-1]["numbers"]
    past = {tuple(d["numbers"]) for d in draws} if use_filter else set()
    pool, wts = list(w), list(w.values())
    sets = []
    for _ in range(100000):
        combo = set()
        while len(combo) < 6:
            combo.add(rng.choices(pool, wts)[0])
        combo = tuple(sorted(combo))
        if combo in sets:
            continue
        if use_filter:
            if combo in past or not valid(combo, stats, last_numbers):
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
        return 1
    if hit == 5 and bonus:
        return 2
    return {5: 3, 4: 4, 3: 5}.get(hit, 0)


def prize(rank, draw):
    if rank == 0:
        return 0
    prizes = draw.get("prizes") or []
    if len(prizes) >= rank and prizes[rank - 1]:
        return prizes[rank - 1]
    return {5: 5000, 4: 50000}.get(rank, 0)


RANK_NAME = {0: "낙첨", 1: "1등", 2: "2등", 3: "3등", 4: "4등", 5: "5등"}


# ---------------------------------------------------------------- 백테스트
def new_score():
    return {"sets": 0, "match": 0, "ranks": Counter(), "won": 0}


def add_score(sc, sets, draw):
    for s in sets:
        r = grade(s, draw)
        sc["sets"] += 1
        sc["match"] += len(set(s) & set(draw["numbers"]))
        sc["ranks"][r] += 1
        sc["won"] += prize(r, draw)


def summary(sc):
    n = sc["sets"]
    mean = sc["match"] / n
    z = (mean - EXP_MATCH) / math.sqrt(VAR_MATCH / n)
    wins = sum(v for k, v in sc["ranks"].items() if k)
    return {"mean": mean, "z": z, "win_rate": wins / n, "roi": sc["won"] / (n * TICKET),
            "ranks": sc["ranks"], "sets": n}


def backtest(draws):
    """워크포워드: 회차 t는 draws[:t]만으로 예측한다."""
    start = len(draws) - SELECT_DRAWS - VALIDATE_DRAWS
    split = len(draws) - VALIDATE_DRAWS
    names = list(STRATEGIES) + [PURE_RANDOM]
    scores = {"select": {k: new_score() for k in names},
              "validate": {k: new_score() for k in names}}
    for t in range(start, len(draws)):
        hist, actual = draws[:t], draws[t]
        stats = analyze(hist)
        phase = "select" if t < split else "validate"
        for i, name in enumerate(names):
            mix = STRATEGIES.get(name, (0, 0, 0, 0))
            sets = generate(stats, weights(stats, mix), hist, seed=actual["draw"] * 101 + i,
                            use_filter=name != PURE_RANDOM)
            add_score(scores[phase][name], sets, actual)
    res = {ph: {k: summary(v) for k, v in d.items()} for ph, d in scores.items()}
    res["range"] = {
        "select": (draws[start]["draw"], draws[split - 1]["draw"]),
        "validate": (draws[split]["draw"], draws[-1]["draw"]),
    }
    best = max(STRATEGIES, key=lambda k: res["select"][k]["mean"])
    v_best, v_base = res["validate"][best], res["validate"][BASELINE]
    # 채택 조건: 검증 구간에서 기준선보다 평균 일치 수가 높고, 이론 기대값 대비 유의(z≥1.96)
    adopted = best if (best != BASELINE and v_best["mean"] > v_base["mean"]
                       and v_best["z"] >= 1.96) else BASELINE
    res["best"], res["adopted"] = best, adopted
    return res


def fmt(nums):
    return " ".join(f"{n:02d}" for n in nums)


def review_previous(draws):
    """직전 회차 예측 파일이 있으면 실제 결과와 비교."""
    latest = draws[-1]
    prev_file = PRED_DIR / f"{latest['draw']}.json"
    if not prev_file.exists():
        return None
    prev = json.loads(prev_file.read_text())
    lines = []
    for i, s in enumerate(prev["sets"]):
        hit = sorted(set(s) & set(latest["numbers"]))
        lines.append(f"| {chr(65 + i)} | {fmt(s)} | {len(hit)}개 {fmt(hit)} | {RANK_NAME[grade(s, latest)]} |")
    return lines


def backtest_table(res, phase):
    rows = ["| 전략 | 평균 일치 | z | 5등 이상 비율 | 환수율 | 당첨 내역 |", "|---|---|---|---|---|---|"]
    for name, s in sorted(res[phase].items(), key=lambda kv: -kv[1]["mean"]):
        ranks = ", ".join(f"{RANK_NAME[r]} {s['ranks'][r]}" for r in (1, 2, 3, 4, 5) if s["ranks"][r]) or "-"
        mark = " ⭐" if name == res["adopted"] else ""
        rows.append(f"| {name}{mark} | {s['mean']:.3f} | {s['z']:+.2f} | {s['win_rate']:.2%} | "
                    f"{s['roi']:.0%} | {ranks} |")
    return rows


def main():
    draws = fetch_draws()
    latest = draws[-1]
    target = latest["draw"] + 1
    target_date = date.fromisoformat(latest["date"]) + timedelta(days=7)

    bt = backtest(draws)
    adopted = bt["adopted"]
    stats = analyze(draws)
    w = weights(stats, STRATEGIES[adopted])
    sets = generate(stats, w, draws, seed=target)

    PRED_DIR.mkdir(exist_ok=True)
    (PRED_DIR / f"{target}.json").write_text(json.dumps(
        {"draw": target, "date": target_date.isoformat(),
         "generated_at": datetime.now().isoformat(timespec="seconds"),
         "based_on": latest["draw"], "strategy": adopted,
         "backtest": {ph: {k: {m: v[m] for m in ("mean", "z", "win_rate", "roi")}
                           for k, v in bt[ph].items()} for ph in ("select", "validate")},
         "sets": [list(s) for s in sets]},
        ensure_ascii=False, indent=2))

    hot = [n for n, _ in stats["total"].most_common(10)]
    cold = [n for n, _ in sorted(stats["total"].items(), key=lambda x: x[1])[:10]]
    recent_hot = [n for n, _ in stats["recent"].most_common(10)]
    overdue = sorted(NUMBERS, key=lambda n: -stats["gap"][n])[:10]
    odd_common = stats["odd_dist"].most_common(3)
    sel, val = bt["range"]["select"], bt["range"]["validate"]
    vb = bt["validate"][bt["best"]]
    verdict = (
        f"선택 구간 1위 **{bt['best']}** 전략이 검증 구간에서도 기준선을 넘고 유의(z={vb['z']:+.2f})해 채택."
        if adopted != BASELINE else
        f"선택 구간 1위는 **{bt['best']}** 전략이었지만 검증 구간(z={vb['z']:+.2f})에서 무작위 대비 "
        f"유의한 우위가 재현되지 않아 **{BASELINE}** 전략을 채택."
    )

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
        f"## 🎯 이번 주 추천 번호 (전략: {adopted})",
        "",
        "| 세트 | 번호 | 합계 | 홀:짝 |",
        "|---|---|---|---|",
        *[f"| {chr(65 + i)} | **{fmt(s)}** | {sum(s)} | {sum(n % 2 for n in s)}:{6 - sum(n % 2 for n in s)} |"
          for i, s in enumerate(sets)],
        "",
        "## 백테스트 (워크포워드, 회차마다 이전 데이터만 사용)",
        "",
        f"무작위 기대값: 세트당 평균 일치 {EXP_MATCH:.3f}개, 5등 이상 {sum(P_MATCH[k] for k in (3, 4, 5, 6)):.2%}. "
        "z는 이 기대값 대비 표준점수(|z|<1.96이면 우연 범위).",
        "",
        f"### 선택 구간: 제{sel[0]}~{sel[1]}회 ({SELECT_DRAWS}회 × {SETS}세트)",
        *backtest_table(bt, "select"),
        "",
        f"### 검증 구간(표본 외): 제{val[0]}~{val[1]}회 ({VALIDATE_DRAWS}회 × {SETS}세트)",
        *backtest_table(bt, "validate"),
        "",
        f"**판정:** {verdict}",
        "",
        "## 통계 요약",
        f"- 역대 최다 출현: {fmt(hot)}",
        f"- 역대 최소 출현: {fmt(cold)}",
        f"- 최근 {RECENT_WINDOW}회 최다 출현: {fmt(recent_hot)}",
        f"- 장기 미출현: " + ", ".join(f"{n:02d}({stats['gap'][n]}회)" for n in overdue),
        f"- 당첨번호 합계 70% 구간: {stats['sum_lo']} ~ {stats['sum_hi']}",
        f"- 홀수 개수 분포 상위: " + ", ".join(f"{k}개 {v / len(draws):.0%}" for k, v in odd_common),
        f"- 연속번호 포함 비율: {stats['consec_ratio']:.0%}",
        "",
        "## 방법",
        "후보 전략(전체 빈도·최근 빈도·미출현·콜드·혼합·균등)별 번호 가중치로 가중 무작위 추출 후,",
        "합계 70% 구간, 홀짝 2~4개, 저(1-22)/고 2~4개, 같은 번호대 최대 3개, 직전 회차와 최대 2개 중복,",
        "역대 1등 조합 제외, 세트 간 중복 최대 2개 필터 적용. 선택 구간 1위 전략이 검증 구간에서도",
        "기준선을 넘고 z≥1.96일 때만 채택. 시드=회차 번호(재현 가능).",
        "",
        "> ⚠️ 로또는 매 회차 독립 시행이며 모든 조합의 1등 확률은 1/8,145,060으로 같습니다. 재미로만 참고하세요.",
    ]
    out = PRED_DIR / f"{target}.md"
    out.write_text("\n".join(md) + "\n")
    (ROOT / "LATEST.md").write_text("\n".join(md) + "\n")
    print("\n".join(md))


if __name__ == "__main__":
    main()
