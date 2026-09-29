#!/usr/bin/env python3
"""로또 6/45 '확률·신뢰도 개선' 연구 스크립트.

두 갈래를 검증한다.
A. 당첨 확률 자체를 올릴 수 있는가 → 추첨이 무작위가 아닌 흔적(편향)이 있는지 검정.
   A1 번호 빈도 균일성(카이제곱) — 전체 / 추첨기(호기)별
   A2 워크포워드 예측력 — '핫/콜드/호기별 핫/직전 회차' 상위 K개가 실제로 더 맞는지
   A3 직전 회차 번호 반복 수, 연속번호 비율 등 구조 검정
B. 당첨 시 받을 기대 금액을 올릴 수 있는가 → 다른 사람이 덜 고르는 조합 찾기.
   회차별 판매량 대비 3·4·5등 당첨자 수로 '번호/패턴 인기도'를 역산해 모델을 만들고,
   표본 외 회차에서 1·2등 당첨자 수까지 예측되는지 검증한다.

필요 패키지: numpy, scipy (연구용. 주간 예측 스크립트는 표준 라이브러리만 사용)
"""
import json
import math
import sys
import urllib.request
from collections import Counter
from pathlib import Path

import numpy as np
from scipy import stats as st

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

ROOT = Path(__file__).resolve().parent.parent
N_COMB = math.comb(45, 6)
P3 = 6 * 38 / N_COMB                     # 5개 일치(보너스 제외)
P4 = math.comb(6, 4) * math.comb(39, 2) / N_COMB
P5 = math.comb(6, 3) * math.comb(39, 3) / N_COMB
P2 = 6 / N_COMB
P1 = 1 / N_COMB
out = []


def say(s=""):
    print(s)
    out.append(s)


def load():
    req = urllib.request.Request("https://smok95.github.io/lotto/results/all.json",
                                 headers={"User-Agent": "Mozilla/5.0"})
    raw = json.load(urllib.request.urlopen(req, timeout=60))
    raw.sort(key=lambda d: d["draw_no"])
    mach = {int(k): v for k, v in json.load(open(ROOT / "data" / "machines.json")).items()}
    return raw, mach


# ------------------------------------------------------------------ A. 편향 검정
def chi_uniform(draws):
    c = Counter(n for d in draws for n in d["numbers"])
    obs = np.array([c[n] for n in range(1, 46)])
    return st.chisquare(obs)


def hypergeom_z(hits, k, trials):
    """상위 k개 번호 중 당첨번호 개수 합의 z (초기하 기대값 대비)."""
    mean = 6 * k / 45
    var = 6 * (k / 45) * (39 / 45) * ((45 - k) / 44)
    return (hits / trials - mean) / math.sqrt(var / trials), hits / trials, mean


def walk_forward(raw, mach, start=300):
    """각 회차 t에 대해 t 이전 정보만으로 번호 순위를 매기고 상위 K 적중을 센다."""
    strategies = {
        "전체 핫": lambda h, t: Counter(n for d in h for n in d["numbers"]),
        "전체 콜드": lambda h, t: Counter({n: -c for n, c in
                                       Counter(n for d in h for n in d["numbers"]).items()}),
        "최근 30회 핫": lambda h, t: Counter(n for d in h[-30:] for n in d["numbers"]),
        "최근 30회 콜드": lambda h, t: Counter({n: -Counter(x for d in h[-30:] for x in d["numbers"])[n]
                                          for n in range(1, 46)}),
        "직전 회차 번호": lambda h, t: Counter(h[-1]["numbers"]),
        "같은 호기 핫": lambda h, t: Counter(n for d in h if mach.get(d["draw_no"]) == mach.get(t)
                                         for n in d["numbers"]),
        "같은 호기 콜드": lambda h, t: Counter({n: -Counter(x for d in h if mach.get(d["draw_no"]) == mach.get(t)
                                                        for x in d["numbers"])[n] for n in range(1, 46)}),
    }
    res = {}
    for name, fn in strategies.items():
        for k in (6, 10, 15):
            if name == "직전 회차 번호" and k != 6:
                continue
            hits = trials = 0
            for i in range(start, len(raw)):
                t = raw[i]["draw_no"]
                if "호기" in name and t not in mach:
                    continue
                score = fn(raw[:i], t)
                # 동점은 번호로 고정 → 재현 가능
                top = sorted(range(1, 46), key=lambda n: (-score.get(n, 0), n))[:k]
                hits += len(set(top) & set(raw[i]["numbers"]))
                trials += 1
            res[(name, k)] = hypergeom_z(hits, k, trials) + (trials,)
    return res


def part_a(raw, mach):
    say("## A. 당첨 확률을 올릴 수 있는가 — 추첨 편향 검정")
    say()
    chi = chi_uniform(raw)
    say(f"### A1. 번호 빈도 균일성 (카이제곱, df=44)")
    say()
    say("| 대상 | 회차 수 | χ² | p-value |")
    say("|---|---|---|---|")
    say(f"| 전체 1~{raw[-1]['draw_no']}회 | {len(raw)} | {chi.statistic:.1f} | {chi.pvalue:.3f} |")
    for m in (1, 2, 3):
        sub = [d for d in raw if mach.get(d["draw_no"]) == m]
        c = chi_uniform(sub)
        say(f"| {m}호기 | {len(sub)} | {c.statistic:.1f} | {c.pvalue:.3f} |")
    # 호기 간 분포 차이 (분할표 독립성)
    table = np.array([[Counter(n for d in raw if mach.get(d["draw_no"]) == m for n in d["numbers"])[n]
                       for n in range(1, 46)] for m in (1, 2, 3)])
    ch2, p, dof, _ = st.chi2_contingency(table)
    say(f"| 호기 간 번호분포 차이(독립성) | {sum(1 for d in raw if d['draw_no'] in mach)} | {ch2:.1f} (df={dof}) | {p:.3f} |")
    say()
    say("p-value가 0.05보다 크면 '무작위 추첨'과 구분되지 않는다는 뜻이다.")
    say()

    say("### A2. 워크포워드 예측력 (301회차부터, 매 회차 이전 데이터만 사용)")
    say()
    say("상위 K개 번호를 골랐을 때 실제 당첨번호가 몇 개 들어있었는지. 무작위 기대값 = 6K/45.")
    say()
    say("| 전략 | K | 회차 | 평균 적중 | 무작위 기대 | z |")
    say("|---|---|---|---|---|---|")
    wf = walk_forward(raw, mach)
    for (name, k), (z, mean, exp, n) in wf.items():
        say(f"| {name} | {k} | {n} | {mean:.3f} | {exp:.3f} | {z:+.2f} |")
    n_tests = len(wf)
    say()
    say(f"{n_tests}개 검정을 동시에 했으므로 본페로니 보정 기준 |z| ≥ {st.norm.ppf(1 - 0.025 / n_tests):.2f} 이어야 유의하다.")
    sig = [(k, v) for k, v in wf.items() if abs(v[0]) >= st.norm.ppf(1 - 0.025 / n_tests)]
    say(f"→ 유의한 전략: {', '.join(f'{k[0]}(K={k[1]})' for k, _ in sig) if sig else '없음'}")
    say()

    say("### A3. 구조 검정")
    say()
    rep = [len(set(a["numbers"]) & set(b["numbers"])) for a, b in zip(raw, raw[1:])]
    say(f"- 직전 회차와 겹치는 번호 수: 평균 {np.mean(rep):.3f} (무작위 기대 0.800)")
    cons = np.mean([any(b - a == 1 for a, b in zip(sorted(d['numbers']), sorted(d['numbers'])[1:])) for d in raw])
    exp_cons = 1 - math.comb(40, 6) / N_COMB
    say(f"- 연속번호 포함 회차 비율: {cons:.1%} (무작위 기대 {exp_cons:.1%})")
    odd = Counter(sum(n % 2 for n in d["numbers"]) for d in raw)
    exp_odd = {k: math.comb(23, k) * math.comb(22, 6 - k) / N_COMB for k in range(7)}
    ch = st.chisquare([odd[k] for k in range(7)], [exp_odd[k] * len(raw) for k in range(7)])
    say(f"- 홀수 개수 분포 적합도: p={ch.pvalue:.3f}")
    sums = [sum(d["numbers"]) for d in raw]
    say(f"- 번호 합계 평균 {np.mean(sums):.1f} (무작위 기대 138.0), 표준편차 {np.std(sums):.1f} (기대 {math.sqrt(6*(45**2-1)/12*39/44):.1f})")
    ks = sorted(mach)
    same = np.mean([mach[a] == mach[b] for a, b in zip(ks, ks[1:]) if b == a + 1])
    say(f"- 다음 주 호기가 이번 주와 같을 확률: {same:.0%} (호기 자체는 예측 가능하나 A1에서 호기별 편향이 없으므로 활용 가치 없음)")
    say()


# ------------------------------------------------------------------ B. 인기도 모델
from lotto_features import FEATURE_NAMES, ROWS, features  # noqa: E402


def ridge(X, y, lam):
    Xm, ym = X.mean(0), y.mean()
    Xc = X - Xm
    beta = np.linalg.solve(Xc.T @ Xc + lam * np.eye(X.shape[1]), Xc.T @ (y - ym))
    return beta, ym - Xm @ beta


def r2(y, p):
    return 1 - ((y - p) ** 2).sum() / ((y - y.mean()) ** 2).sum()


def poisson_ll(k, lam):
    return st.poisson.logpmf(k, lam).sum()


def fit_gamma(k, base, z):
    """λ = base·exp(γ·z) 에서 포아송 최대우도 γ (1차원 격자)."""
    grid = np.linspace(0, 3, 301)
    lls = [poisson_ll(k, base * np.exp(g * z) / np.mean(np.exp(g * z))) for g in grid]
    return grid[int(np.argmax(lls))]


def part_b(raw):
    say("## B. 당첨 시 기대 금액을 올릴 수 있는가 — 조합 인기도 모델")
    say()
    say("1~3등은 당첨금을 당첨자끼리 나눈다(패리뮤추얼). 남들이 많이 고르는 조합이 나오면 1인당 당첨금이 줄어든다.")
    say("회차별 (당첨자 수 ÷ 무작위 기대 당첨자 수)의 로그를 '인기도'로 정의하고, 번호·패턴으로 설명되는지 본다.")
    say()
    data = [d for d in raw if d["draw_no"] >= 100 and d.get("total_sales_amount")]
    tickets = np.array([d["total_sales_amount"] / 1000 for d in data])
    X = np.array([features(d["numbers"]) for d in data])
    w = {k: np.array([d["divisions"][i].get("winners", 0) for d in data], float) for k, i in
         (("1", 0), ("2", 1), ("3", 2), ("4", 3), ("5", 4))}
    y = {
        "5등": np.log(w["5"] / (tickets * P5)),
        "4등": np.log(w["4"] / (tickets * P4)),
        "3등": np.log((w["3"] + 0.5) / (tickets * P3)),
    }
    n = len(data)
    tr, va = int(n * 0.6), int(n * 0.8)
    rng_s = lambda a, b: f"{data[a]['draw_no']}~{data[b-1]['draw_no']}회"
    say(f"3분할: 학습 {rng_s(0, tr)} / 모델 선택 {rng_s(tr, va)} / 최종 검증 {rng_s(va, n)} (시간순, 미래 정보 누수 없음)")
    say()
    say("### B1. 하위 등수 당첨자 수로 인기도 학습")
    say()
    say("| 인기도 목표 | λ(릿지) | 학습 R² | 선택구간 R² | 최종검증 R² |")
    say("|---|---|---|---|---|")
    models = {}
    for name, yy in y.items():
        best = None
        for lam in (0.1, 1, 10, 100):
            b, a = ridge(X[:tr], yy[:tr], lam)
            sc = r2(yy[tr:va], X[tr:va] @ b + a)
            if best is None or sc > best[0]:
                best = (sc, lam, b, a)
        sc, lam, b, a = best
        say(f"| {name} | {lam} | {r2(yy[:tr], X[:tr] @ b + a):.2f} | {sc:.2f} | {r2(yy[va:], X[va:] @ b + a):.2f} |")
        models[name] = (b, a, lam)
    say()

    say("### B2. 인기도가 1·2등 당첨자 수(= 당첨금 분할)를 예측하는가")
    say()
    say("각 모델 점수 z를 선택구간에서 λ=판매량×확률×exp(γz)로 보정(γ 최대우도)한 뒤, 최종검증 구간에서 판매량만 쓴 기준 모델과 포아송 로그우도를 비교.")
    say()
    say("| 인기도 모델 | γ(2등 보정) | 선택구간 ΔLL(1등+2등) | 최종검증 ΔLL 1등 | 최종검증 ΔLL 2등 |")
    say("|---|---|---|---|---|")
    V, T = slice(tr, va), slice(va, n)
    base1 = tickets * P1 * (w["1"][:tr].sum() / (tickets[:tr] * P1).sum())
    base2 = tickets * P2 * (w["2"][:tr].sum() / (tickets[:tr] * P2).sum())
    chosen = None
    for name, (b, a, lam) in models.items():
        z = X @ b
        z = (z - z[:tr].mean()) / z[:tr].std()
        g = fit_gamma(w["2"][V], base2[V], z[V])
        adj = lambda base, sl: base[sl] * np.exp(g * z[sl]) / np.mean(np.exp(g * z[:tr]))
        dv = (poisson_ll(w["1"][V], adj(base1, V)) - poisson_ll(w["1"][V], base1[V])
              + poisson_ll(w["2"][V], adj(base2, V)) - poisson_ll(w["2"][V], base2[V]))
        d1 = poisson_ll(w["1"][T], adj(base1, T)) - poisson_ll(w["1"][T], base1[T])
        d2 = poisson_ll(w["2"][T], adj(base2, T)) - poisson_ll(w["2"][T], base2[T])
        say(f"| {name} | {g:.2f} | {dv:+.1f} | {d1:+.1f} | {d2:+.1f} |")
        if chosen is None or dv > chosen[0]:
            chosen = (dv, name, g, b, z)
    _, cname, g, b, z = chosen
    # 우도비 검정(최종검증, 1등+2등, 보정 파라미터는 선택구간에서 고정 → 자유도 0이므로 부트스트랩 대신 순열검정)
    adjT1 = base1[T] * np.exp(g * z[T]) / np.mean(np.exp(g * z[:tr]))
    adjT2 = base2[T] * np.exp(g * z[T]) / np.mean(np.exp(g * z[:tr]))
    real = (poisson_ll(w["1"][T], adjT1) + poisson_ll(w["2"][T], adjT2))
    rng = np.random.default_rng(1)
    perm = []
    for _ in range(2000):
        pz = rng.permutation(z[T])
        f = np.exp(g * pz) / np.mean(np.exp(g * z[:tr]))
        perm.append(poisson_ll(w["1"][T], base1[T] * f) + poisson_ll(w["2"][T], base2[T] * f))
    pval = (np.sum(np.array(perm) >= real) + 1) / (len(perm) + 1)
    rho = st.spearmanr(z[T], w["2"][T] / tickets[T])
    say()
    say(f"→ 선택: **{cname} 인기도 모델** (γ={g:.2f}). 최종검증 순열검정 p={pval:.4f}, "
        f"2등 당첨자/판매량과 순위상관 ρ={rho.correlation:.2f}")
    say()

    say("### B3. 무엇이 인기 있는가 (선택 모델 계수 × γ, +면 남들이 많이 고름)")
    say()
    bstd = b / (X[:tr] @ b).std() * g
    say("| 패턴 | 1단위당 1·2등 당첨자 배율 |")
    say("|---|---|")
    for nm, c in zip(FEATURE_NAMES, bstd[:len(FEATURE_NAMES)]):
        say(f"| {nm} | ×{math.exp(c):.2f} |")
    num = bstd[len(FEATURE_NAMES):]
    order = np.argsort(num)
    say()
    say("- 가장 인기 없는 번호: " + ", ".join(f"{i+1}(×{math.exp(num[i]):.2f})" for i in order[:10]))
    say("- 가장 인기 있는 번호: " + ", ".join(f"{i+1}(×{math.exp(num[i]):.2f})" for i in order[::-1][:10]))
    say()

    rng = np.random.default_rng(0)
    cand = np.array([np.sort(rng.choice(np.arange(1, 46), 6, replace=False)) for _ in range(200000)])
    Xc = np.array([features(c) for c in cand])
    zc = (Xc @ b - (X[:tr] @ b).mean()) / (X[:tr] @ b).std()
    popc = np.exp(g * zc) / np.mean(np.exp(g * zc))
    avg_tickets = tickets[-52:].mean()
    lam_c = avg_tickets * P1 * popc
    share = (1 - np.exp(-lam_c)) / lam_c     # E[1/(1+K)], K~Poisson(λ): 1등 당첨금 중 내 몫
    q = np.quantile(popc, [0.01, 0.5, 0.99])
    say("### B4. 1등 당첨 시 기대 몫 (최근 52주 평균 판매량, 무작위 20만 조합 시뮬레이션)")
    say()
    say(f"- 평균 판매량 {avg_tickets/1e6:.0f}백만 장 → 평균 조합 하나를 같이 산 다른 사람 기대 수 λ≈{avg_tickets*P1:.1f}명")
    say(f"- 인기도 하위 1%: 배율 ×{q[0]:.2f}, 기대 몫 {np.mean(share[popc<=q[0]]):.1%}")
    say(f"- 중앙값: 배율 ×{q[1]:.2f}, 기대 몫 {np.median(share):.1%}")
    say(f"- 인기도 상위 1%: 배율 ×{q[2]:.2f}, 기대 몫 {np.mean(share[popc>=q[2]]):.1%}")
    gain = np.mean(share[popc <= q[0]]) / np.median(share)
    say(f"→ 같은 1등이라도 저인기(하위 1%) 조합의 기대 수령액은 중앙값 조합의 약 **{gain:.2f}배**")
    say()
    say("※ 이 모델은 '당첨자들이 고른 조합'의 부분집합(3~5개 번호) 인기도로 학습했다. 1-2-3-4-5-6 같은")
    say("  극단적 패턴의 인기는 과소평가될 수 있으므로 실제 추천에는 패턴 필터를 함께 쓴다.")
    say()
    json.dump({"model": cname, "gamma": float(g), "coef": b.tolist(),
               "z_mean": float((X[:tr] @ b).mean()), "z_std": float((X[:tr] @ b).std()),
               "feature_names": FEATURE_NAMES, "rows": ROWS,
               "validated_p": float(pval), "trained_through": data[tr - 1]["draw_no"]},
              open(ROOT / "data" / "popularity_model.json", "w"), ensure_ascii=False, indent=1)
    return gain


def main():
    raw, mach = load()
    say(f"# 로또 6/45 확률·신뢰도 개선 연구 (제1~{raw[-1]['draw_no']}회)")
    say()
    part_a(raw, mach)
    part_b(raw)
    (ROOT / "research" / "REPORT.md").write_text("\n".join(out) + "\n")


if __name__ == "__main__":
    main()
