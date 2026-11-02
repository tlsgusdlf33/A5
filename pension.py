#!/usr/bin/env python3
"""연금복권720+ 분석·추천 엔진 (로또와 같은 원칙: 편향 검정 → 백테스트 게이트 → 확률 계산).

게임: 조(1~5) + 6자리 번호(000000~999999). 매주 목요일 추첨. 번호마다 표가 한 장씩만 팔린다.
등수: 1등 조+6자리, 2등 6자리(조 무관), 3~7등 뒤 5·4·3·2·1자리, 보너스 6자리(조 무관).
당첨금이 고정이라(나눠 갖지 않음) 로또의 '인기도' 전략은 해당 없다.
쓸 수 있는 개선은 '여러 장 중 하나라도 당첨' 확률(커버리지)뿐: 끝자리가 서로 다르면 5장 기준 41% → 50%.

python3 pension.py  → predictions/pension/<회차>.json, data/pension720.json, PENSION_LATEST.md
"""
import json
import math
import random
import sys
import urllib.request
from collections import Counter
from datetime import date, datetime, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parent
DATA_FILE = ROOT / "data" / "pension720.json"
PRED_DIR = ROOT / "predictions" / "pension"
SOURCES = [  # 서로 독립적으로 수집한 두 미러 (339회까지 전부 일치 확인)
    "https://raw.githubusercontent.com/Dalbonz/lotto-recommender/main/data/pension720.json",
    "https://raw.githubusercontent.com/yinnet2804/potential-spork-pansionlot/main/data/pension720.json",
]
TICKETS = 5
TICKET = 1000
PRIZES = {1: 7_000_000 * 240, 2: 1_000_000 * 120, "B": 1_000_000 * 120,
          3: 1_000_000, 4: 100_000, 5: 50_000, 6: 5_000, 7: 1_000}
RANK_NAME = {0: "낙첨", 1: "1등", 2: "2등", "B": "보너스", 3: "3등", 4: "4등", 5: "5등", 6: "6등", 7: "7등"}
SELECT, VALIDATE = 120, 100  # 백테스트 선택·검증 구간 회차 수


# ------------------------------------------------------------------ 데이터
def _norm(x):
    return {"round": int(x["round"]), "date": x["date"][:10], "group": int(x["group"]),
            "number": str(x["number"]).zfill(6), "bonus": str(x.get("bonusNumber") or x.get("bonus")).zfill(6)}


def fetch():
    for url in SOURCES:
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
            raw = json.load(urllib.request.urlopen(req, timeout=30))
            rows = raw["draws"] if isinstance(raw, dict) else raw
            draws = sorted((_norm(x) for x in rows), key=lambda d: d["round"])
            if len(draws) > 300:
                DATA_FILE.parent.mkdir(exist_ok=True)
                DATA_FILE.write_text(json.dumps(draws, ensure_ascii=False, separators=(",", ":")))
                return draws
        except Exception as e:  # 다음 소스로
            print(f"[경고] {url}: {e}", file=sys.stderr)
    return json.loads(DATA_FILE.read_text())


# ------------------------------------------------------------------ 규칙
def is_reserved(number):
    """개인 전용 영역(번호의 약 1/7). 개인 추천은 안에서만, 공개·앱 추천은 밖에서만 고른다.
    앱의 PensionEngine.isReserved 와 같은 식."""
    h = 0
    for ch in number:
        h = (h * 131 + int(ch)) % 1_000_003
    return h % 7 == 0


def tail_match(a, b):
    k = 0
    while k < 6 and a[5 - k] == b[5 - k]:
        k += 1
    return k


def grade(ticket, d):
    """ticket = (조, '6자리'). 본 등수와 보너스를 함께 돌려준다 (보너스는 별도 지급)."""
    g, n = ticket
    if n == d["number"]:
        main = 1 if g == d["group"] else 2
    else:
        main = {5: 3, 4: 4, 3: 5, 2: 6, 1: 7}.get(tail_match(n, d["number"]), 0)
    return main, n == d["bonus"]


def prize(ticket, d):
    main, bonus = grade(ticket, d)
    return (PRIZES[main] if main else 0) + (PRIZES["B"] if bonus else 0)


def coverage(tickets):
    """'하나라도 당첨' 확률 (정확): 끝 k자리가 서로 다른 개수 / 10^k. 보너스(1/10^6)는 무시할 만큼 작다."""
    def p(k):
        return len({n[-k:] for _, n in tickets}) / 10 ** k
    n = len(tickets)
    return {"p_any": p(1), "p_5th_or_better": p(3), "p_4th_or_better": p(4),
            "random_p_any": 1 - 0.9 ** n, "random_p_5th_or_better": 1 - 0.999 ** n}


# ------------------------------------------------------------------ 검정·백테스트
def chi_p(obs, exp):
    """카이제곱 생존함수 (scipy 없이: 정규화 불완전 감마)."""
    x = sum((o - e) ** 2 / e for o, e in zip(obs, exp))
    k = len(obs) - 1
    return x, _gammaq(k / 2, x / 2)


def _gammaq(a, x):
    if x <= 0:
        return 1.0
    if x < a + 1:
        s = term = 1 / a
        n = a
        for _ in range(500):
            n += 1
            term *= x / n
            s += term
            if abs(term) < abs(s) * 1e-14:
                break
        return 1 - s * math.exp(-x + a * math.log(x) - math.lgamma(a))
    b, c, d = x + 1 - a, 1e300, 1 / (x + 1 - a)
    h = d
    for i in range(1, 500):
        an = -i * (i - a)
        b += 2
        d = an * d + b
        d = 1e-300 if abs(d) < 1e-300 else d
        c = b + an / c
        c = 1e-300 if abs(c) < 1e-300 else c
        d = 1 / d
        h *= d * c
        if abs(d * c - 1) < 1e-14:
            break
    return math.exp(-x + a * math.log(x) - math.lgamma(a)) * h


def bias_tests(draws):
    n = len(draws)
    out = {"rounds": n}
    g = Counter(d["group"] for d in draws)
    out["group"] = chi_p([g[i] for i in range(1, 6)], [n / 5] * 5)
    for key in ("number", "bonus"):
        for pos in range(6):
            c = Counter(d[key][pos] for d in draws)
            out[f"{key}_pos{pos + 1}"] = chi_p([c[str(i)] for i in range(10)], [n / 10] * 10)
    tests = {k: v for k, v in out.items() if k != "rounds"}
    out["min_p"] = min(v[1] for v in tests.values())
    out["bonferroni"] = 0.05 / len(tests)
    out["significant"] = [k for k, v in tests.items() if v[1] < out["bonferroni"]]
    return out


def _digit_rank(hist, pos, mode):
    c = Counter(d["number"][pos] for d in hist[-30:] if mode.startswith("recent")) if mode.startswith("recent") \
        else Counter(d["number"][pos] for d in hist)
    order = sorted("0123456789", key=lambda ch: (-c[ch], ch))
    return order if mode.endswith("hot") else order[::-1]


STRATEGIES = {  # 끝자리 5개를 고르는 방법
    "전체 핫(끝자리 빈도 상위)": lambda h: _digit_rank(h, 5, "all-hot")[:TICKETS],
    "전체 콜드": lambda h: _digit_rank(h, 5, "all-cold")[:TICKETS],
    "최근 30회 핫": lambda h: _digit_rank(h, 5, "recent-hot")[:TICKETS],
    "최근 30회 콜드": lambda h: _digit_rank(h, 5, "recent-cold")[:TICKETS],
    "직전 회차 끝자리 포함": lambda h: [h[-1]["number"][5]] + [c for c in "0123456789" if c != h[-1]["number"][5]][:TICKETS - 1],
}


def backtest(draws):
    """워크포워드: 회차 t 의 끝자리 5개를 t 이전 데이터만으로 고르고 실제 끝자리 적중을 센다.
    무작위로 서로 다른 5개를 고르면 적중 확률 0.5 (이항 기준)."""
    start = len(draws) - SELECT - VALIDATE
    split = len(draws) - VALIDATE
    res = {}
    for name, fn in STRATEGIES.items():
        hits = {"select": [], "validate": []}
        for t in range(start, len(draws)):
            pick = fn(draws[:t])
            hits["select" if t < split else "validate"].append(draws[t]["number"][5] in pick)
        res[name] = {ph: {"rate": sum(v) / len(v), "z": (sum(v) / len(v) - 0.5) / math.sqrt(0.25 / len(v)), "n": len(v)}
                     for ph, v in hits.items()}
    best = max(res, key=lambda k: res[k]["select"]["rate"])
    v = res[best]["validate"]
    adopted = best if v["z"] >= 1.96 else None  # 검증 구간에서도 유의해야 채택
    return {"results": res, "best": best, "adopted": adopted,
            "range": {"select": (draws[start]["round"], draws[split - 1]["round"]),
                      "validate": (draws[split]["round"], draws[-1]["round"])}}


# ------------------------------------------------------------------ 추천
def pick(draws, rng, reserved_only=False, tail_digits=None, avoid=()):
    """조 1~5 를 한 장씩, 끝자리는 서로 다르게(커버리지 최대). 나머지 자리는 균등 무작위.
    reserved_only=False(공개·앱)는 개인 영역 번호를 쓰지 않고, True(개인)는 그 안에서만 고른다."""
    tails = list(tail_digits) if tail_digits else rng.sample("0123456789", TICKETS)
    groups = rng.sample(range(1, 6), TICKETS)
    avoid = set(avoid)
    out = []
    for g, last in zip(groups, tails):
        while True:
            n = "".join(rng.choice("0123456789") for _ in range(5)) + last
            if is_reserved(n) == reserved_only and n not in avoid and all(n != m for _, m in out):
                out.append((g, n))
                break
    return sorted(out, key=lambda t: t[1][-1])


def review(draws, file):
    """지난 회차 추천 채점."""
    last = draws[-1]
    f = PRED_DIR / f"{last['round']}.json" if file is None else file
    if not f.exists():
        return None
    prev = json.loads(f.read_text())
    rows = []
    for g, n in prev["tickets"]:
        main, bonus = grade((g, n), last)
        rows.append({"ticket": [g, n], "tail": tail_match(n, last["number"]), "rank": main, "bonus": bonus,
                     "prize": prize((g, n), last)})
    return {"round": last["round"], "date": last["date"], "group": last["group"], "number": last["number"],
            "bonus": last["bonus"], "rows": rows, "won": sum(r["prize"] for r in rows), "spent": len(rows) * TICKET}


def main():
    draws = fetch()
    last = draws[-1]
    target = last["round"] + 1
    tdate = date.fromisoformat(last["date"]) + timedelta(days=7)
    bias = bias_tests(draws)
    bt = backtest(draws)
    tails = STRATEGIES[bt["adopted"]](draws) if bt["adopted"] else None
    rng = random.Random(target * 7919)  # 공개 예시는 재현 가능 (앱은 기기별 시드)
    tickets = pick(draws, rng, reserved_only=False, tail_digits=tails)
    cov = coverage(tickets)
    rev = review(draws, None)
    PRED_DIR.mkdir(parents=True, exist_ok=True)
    rec = {"round": target, "date": tdate.isoformat(), "generated_at": datetime.now().isoformat(timespec="seconds"),
           "based_on": last["round"], "tickets": [[g, n] for g, n in tickets], "coverage": cov,
           "verification": {"rounds": bias["rounds"], "group_p": bias["group"][1], "min_digit_p": bias["min_p"],
                            "tests": len([k for k in bias if "_pos" in k or k == "group"]),
                            "significant": bias["significant"], "backtest_best": bt["best"],
                            "backtest_best_validate_z": bt["results"][bt["best"]]["validate"]["z"],
                            "adopted": bt["adopted"] or "균등 무작위 + 끝자리 분산"},
           "review": rev}
    (PRED_DIR / f"{target}.json").write_text(json.dumps(rec, ensure_ascii=False, indent=2))

    sel, val = bt["range"]["select"], bt["range"]["validate"]
    md = [f"# 연금복권720+ 제{target}회 추천 ({tdate} 추첨)", "",
          f"분석 기준: 제1~{last['round']}회 ({len(draws)}회차)", "",
          f"## 제{last['round']}회 당첨번호: {last['group']}조 {last['number']} · 보너스 {last['bonus']}", ""]
    if rev:
        md += ["| 표 | 끝자리 일치 | 결과 |", "|---|---|---|"]
        md += [f"| {r['ticket'][0]}조 {r['ticket'][1]} | {r['tail']}자리 | {RANK_NAME[r['rank']]}"
               f"{' + 보너스' if r['bonus'] else ''} |" for r in rev["rows"]]
        md += ["", f"당첨금 {rev['won']:,}원 / 구매 {rev['spent']:,}원", ""]
    md += ["## 이번 주 추천 5장", "", "| 조 | 번호 |", "|---|---|",
           *[f"| {g}조 | **{n}** |" for g, n in tickets], "",
           f"- 5장 중 하나라도 당첨(7등 이상): **{cov['p_any']:.0%}** (아무렇게나 고른 5장 {cov['random_p_any']:.1%})",
           "- 끝자리를 서로 다르게 골라 같은 금액으로 '하나라도 당첨' 확률을 최대로 만든다. 표 한 장의 확률은 그대로.",
           "", "## 검증", "",
           f"- 편향 검정: 조 분포 p={bias['group'][1]:.2f}, 자리별 숫자 {len(bias) - 5}개 검정 중 최소 p={bias['min_p']:.3f} "
           f"(본페로니 기준 {bias['bonferroni']:.4f}) → {'유의한 편향 없음' if not bias['significant'] else '유의: ' + ', '.join(bias['significant'])}",
           f"- 끝자리 예측 전략 백테스트 (선택 제{sel[0]}~{sel[1]}회 / 검증 제{val[0]}~{val[1]}회, 무작위 적중률 50%):",
           *[f"  - {k}: 선택 {v['select']['rate']:.0%} (z={v['select']['z']:+.2f}) · 검증 {v['validate']['rate']:.0%} "
             f"(z={v['validate']['z']:+.2f})" for k, v in bt["results"].items()],
           f"- 판정: {'채택 ' + bt['adopted'] if bt['adopted'] else '검증 구간에서 유의한 전략 없음 → 균등 무작위 + 끝자리 분산'}",
           "", "> 연금복권은 당첨금이 고정이고 번호마다 한 장씩만 팔려 나눠 갖지 않으므로, 로또의 인기도 전략은 적용하지 않습니다. "
           "표 한 장의 1등 확률은 1/5,000,000으로 모든 번호가 같습니다."]
    (ROOT / "PENSION_LATEST.md").write_text("\n".join(md) + "\n")
    print("\n".join(md))


if __name__ == "__main__":
    main()
