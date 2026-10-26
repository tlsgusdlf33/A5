package com.tlsgusdlf33.lottolab

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random

/** 한 회차 당첨 결과. prizes = 1~5등 1인당 당첨금, sales = 총 판매액(원). */
data class Draw(
    val no: Int,
    val date: String,
    val numbers: List<Int>,
    val bonus: Int,
    val prizes: List<Long> = emptyList(),
    val sales: Long = 0,
)

/** research/research.py 가 학습·검증한 조합 인기도 모델 (data/popularity_model.json). */
data class PopularityModel(
    val coef: DoubleArray,
    val zMean: Double,
    val zStd: Double,
    val gamma: Double,
    val validatedP: Double,
) {
    val usable get() = validatedP < 0.05
}

data class Pick(val numbers: List<Int>, val popularity: Double, val shareVsMedian: Double)

data class NumberStats(
    val total: IntArray,
    val recent: IntArray,
    val gap: IntArray,
    val sumLo: Int,
    val sumHi: Int,
    val chiSquare: Double,
    val chiP: Double,
)

data class CheckResult(val ranks: IntArray, val bestRank: Int, val bestDraw: Int?)

/** lotto_predict.py 의 조합 생성·필터·인기도 계산을 그대로 옮긴 엔진. */
object LottoEngine {
    const val TOTAL_COMBOS = 8_145_060.0
    private const val RECENT_WINDOW = 50
    private const val SETS = 5
    private const val CANDIDATES = 5000

    fun stats(draws: List<Draw>): NumberStats {
        val total = IntArray(46)
        val recent = IntArray(46)
        val lastSeen = IntArray(46)
        draws.forEachIndexed { i, d ->
            for (n in d.numbers) {
                total[n]++
                if (i >= draws.size - RECENT_WINDOW) recent[n]++
                lastSeen[n] = d.no
            }
        }
        val latest = draws.last().no
        val gap = IntArray(46) { if (it == 0) 0 else latest - lastSeen[it] }
        val sums = draws.map { it.numbers.sum() }.sorted()
        val expected = draws.size * 6 / 45.0
        val chi = (1..45).sumOf { (total[it] - expected).let { d -> d * d / expected } }
        return NumberStats(
            total, recent, gap,
            sumLo = sums[(sums.size * 0.15).toInt()],
            sumHi = sums[(sums.size * 0.85).toInt()],
            chiSquare = chi,
            chiP = chiSquareSurvival(chi, 44),
        )
    }

    /** lotto_features.py 와 동일한 특성 벡터. */
    fun features(combo: List<Int>): DoubleArray {
        val nums = combo.sorted()
        val rows = nums.groupingBy { (it - 1) / 7 }.eachCount()
        val cols = nums.groupingBy { (it - 1) % 7 }.eachCount()
        val diffs = nums.zipWithNext { a, b -> b - a }
        val lastDigit = nums.groupingBy { it % 10 }.eachCount()
        val f = doubleArrayOf(
            nums.count { it <= 12 }.toDouble(),
            nums.count { it <= 31 }.toDouble(),
            diffs.count { it == 1 }.toDouble(),
            rows.values.max().toDouble(),
            cols.values.max().toDouble(),
            if (diffs.toSet().size == 1) 1.0 else 0.0,
            nums.count { it % 7 == 0 }.toDouble(),
            nums.count { it % 5 == 0 }.toDouble(),
            lastDigit.values.max().toDouble(),
            nums.count { it >= 40 }.toDouble(),
            nums.sum() / 100.0,
        )
        return f + DoubleArray(45) { if ((it + 1) in nums) 1.0 else 0.0 }
    }

    /** 1·2등 당첨자 수 배율 추정치 (1.0 = 평균 조합). */
    fun popularity(combo: List<Int>, m: PopularityModel): Double {
        val x = features(combo)
        var dot = 0.0
        for (i in x.indices) dot += m.coef[i] * x[i]
        return exp(m.gamma * (dot - m.zMean) / m.zStd)
    }

    /** 1등 당첨 시 총 당첨금 중 내 몫의 기대값 E[1/(1+K)], K~Poisson(다른 구매자 수). */
    fun jackpotShare(mult: Double, tickets: Double): Double {
        val lam = tickets / TOTAL_COMBOS * mult
        return (1 - exp(-lam)) / lam
    }

    fun avgTickets(draws: List<Draw>): Double {
        val recent = draws.takeLast(52).filter { it.sales > 0 }
        return if (recent.isEmpty()) 1.2e8 else recent.sumOf { it.sales / 1000.0 } / recent.size
    }

    private fun valid(c: List<Int>, s: NumberStats, last: List<Int>): Boolean {
        val odd = c.count { it % 2 == 1 }
        val low = c.count { it <= 22 }
        val decades = c.groupingBy { (it - 1) / 10 }.eachCount().values.max()
        return c.sum() in s.sumLo..s.sumHi && odd in 2..4 && low in 2..4 &&
            decades <= 3 && c.count { it in last } <= 2
    }

    /** 3연속, 등차수열, 용지 한 줄 4개 이상 — 모델이 과소평가할 수 있는 대량 중복 구매 패턴. */
    fun obviousPattern(c: List<Int>): Boolean {
        val diffs = c.zipWithNext { a, b -> b - a }
        var run = 1
        var best = 1
        for (d in diffs) {
            run = if (d == 1) run + 1 else 1
            if (run > best) best = run
        }
        val rows = c.groupingBy { (it - 1) / 7 }.eachCount().values.max()
        val cols = c.groupingBy { (it - 1) % 7 }.eachCount().values.max()
        return best >= 3 || diffs.toSet().size == 1 || rows >= 4 || cols >= 4
    }

    private const val POP_CAP = 0.92
    private const val SEARCH_STARTS = 60

    /**
     * 균등 무작위 후보를 만들고(백테스트에서 번호 선택 전략의 우위가 없었으므로), 인기도 ×0.92 이하 후보 안에서
     * 세트끼리 겹치는 번호 ≤1(또는 0)인 5세트 조합을 여러 개 만들어 '하나라도 5등 이상' 확률이 가장 높은 것을 고른다.
     * lotto_predict.py 의 pick_unpopular 와 같은 방법.
     */
    fun pickUnpopular(draws: List<Draw>, model: PopularityModel, seed: Long): List<Pick> {
        val s = stats(draws)
        val rng = Random(seed)
        val last = draws.last().numbers
        val past = draws.map { it.numbers.sorted() }.toHashSet()
        val cands = HashSet<List<Int>>()
        var guard = 0
        while (cands.size < CANDIDATES && guard++ < 500_000) {
            val c = (1..45).shuffled(rng).take(6).sorted()
            if (c !in past && valid(c, s, last) && !obviousPattern(c)) cands.add(c)
        }
        val pop = cands.associateWith { popularity(it, model) }
        val scored = cands.sortedWith(compareBy<List<Int>> { pop.getValue(it) }.thenBy { it.joinToString() })
        val median = pop.getValue(scored[scored.size / 2])
        var pool = scored.filter { pop.getValue(it) <= POP_CAP }
        if (pool.size < 50) pool = scored.take(200)

        fun greedy(order: List<List<Int>>, maxOverlap: Int): List<List<Int>>? {
            val out = mutableListOf<List<Int>>()
            for (c in order) {
                if (out.all { o -> o.count { it in c } <= maxOverlap }) {
                    out += c
                    if (out.size == SETS) return out
                }
            }
            return null
        }

        var best: List<List<Int>>? = null
        var bestP = -1.0
        var bestPop = Double.MAX_VALUE
        for (start in pool.take(SEARCH_STARTS)) {
            for (ov in intArrayOf(1, 0)) {
                val g = greedy(listOf(start) + pool, ov) ?: continue
                val p = pAnyMatch(g, 3)
                val avg = g.sumOf { pop.getValue(it) } / SETS
                if (p > bestP + 1e-12 || (abs(p - bestP) <= 1e-12 && avg < bestPop)) {
                    best = g; bestP = p; bestPop = avg
                }
            }
        }
        val chosen = best ?: greedy(scored, 1) ?: greedy(scored, 2) ?: scored.take(SETS)
        val tickets = avgTickets(draws)
        val base = jackpotShare(median, tickets)
        return chosen.sortedBy { pop.getValue(it) }.map {
            val p = pop.getValue(it)
            Pick(it, p, jackpotShare(p, tickets) / base)
        }
    }

    /** 세트 1개가 5등 이상일 확률 (3개 이상 일치). */
    val P_SINGLE_5TH = (3..6).sumOf { comb(6, it) * comb(39, 6 - it) } / TOTAL_COMBOS

    /** 아무렇게나 고른 n세트 중 하나라도 5등 이상일 확률의 평균 (= 독립 근사, 정확). */
    fun randomAny5th(n: Int) = 1 - Math.pow(1 - P_SINGLE_5TH, n.toDouble())

    /**
     * P(세트 중 하나라도 need개 이상 일치) — 번호를 '어느 세트들에 속하는가'로 묶어 조합 수로 정확히 계산.
     */
    fun pAnyMatch(sets: List<List<Int>>, need: Int): Double {
        val cells = HashMap<List<Int>, Int>()
        for (n in 1..45) {
            val sig = sets.indices.filter { n in sets[it] }
            cells[sig] = (cells[sig] ?: 0) + 1
        }
        val keys = cells.entries.map { it.key to it.value }
        var hit = 0.0
        fun rec(i: Int, left: Int, matches: IntArray, ways: Double) {
            if (i == keys.size) {
                if (left == 0 && matches.max() >= need) hit += ways
                return
            }
            val (sig, size) = keys[i]
            for (k in 0..minOf(size, left)) {
                val m = matches.copyOf()
                for (j in sig) m[j] += k
                rec(i + 1, left - k, m, ways * comb(size, k))
            }
        }
        rec(0, 6, IntArray(sets.size), 1.0)
        return hit / TOTAL_COMBOS
    }

    private fun comb(n: Int, k: Int): Double {
        if (k < 0 || k > n) return 0.0
        var r = 1.0
        for (i in 0 until k) r = r * (n - i) / (i + 1)
        return r
    }

    /** 무작위 조합 인기도의 중앙값 (기대 수령액 배율의 기준). */
    fun medianPopularity(m: PopularityModel): Double {
        val rng = Random(7)
        val pops = List(4000) { popularity((1..45).shuffled(rng).take(6).sorted(), m) }.sorted()
        return pops[pops.size / 2]
    }

    fun medianShare(draws: List<Draw>, model: PopularityModel, combo: List<Int>): Double {
        val tickets = avgTickets(draws)
        return jackpotShare(popularity(combo, model), tickets) / jackpotShare(medianPopularity(model), tickets)
    }

    fun rank(pick: List<Int>, d: Draw): Int {
        val hit = pick.count { it in d.numbers }
        return when {
            hit == 6 -> 1
            hit == 5 && d.bonus in pick -> 2
            hit == 5 -> 3
            hit == 4 -> 4
            hit == 3 -> 5
            else -> 0
        }
    }

    /** 내 번호를 역대 모든 회차에 대입. ranks[r] = r등 횟수. */
    fun checkHistory(pick: List<Int>, draws: List<Draw>): CheckResult {
        val ranks = IntArray(6)
        var best = 0
        var bestDraw: Int? = null
        for (d in draws) {
            val r = rank(pick, d)
            ranks[r]++
            if (r != 0 && (best == 0 || r < best)) {
                best = r
                bestDraw = d.no
            }
        }
        return CheckResult(ranks, best, bestDraw)
    }

    // ---- 카이제곱 생존함수 (정규화 불완전 감마 Q(k/2, x/2)) ----
    fun chiSquareSurvival(x: Double, dof: Int): Double = gammaQ(dof / 2.0, x / 2.0)

    private fun gammaQ(a: Double, x: Double): Double {
        if (x <= 0) return 1.0
        return if (x < a + 1) 1 - gammaPSeries(a, x) else gammaQContinuedFraction(a, x)
    }

    private fun gammaPSeries(a: Double, x: Double): Double {
        var sum = 1.0 / a
        var term = sum
        var n = a
        for (i in 0 until 500) {
            n += 1
            term *= x / n
            sum += term
            if (abs(term) < abs(sum) * 1e-14) break
        }
        return sum * exp(-x + a * ln(x) - lnGamma(a))
    }

    private fun gammaQContinuedFraction(a: Double, x: Double): Double {
        var b = x + 1 - a
        var c = 1e300
        var d = 1 / b
        var h = d
        for (i in 1..500) {
            val an = -i * (i - a)
            b += 2
            d = an * d + b
            if (abs(d) < 1e-300) d = 1e-300
            c = b + an / c
            if (abs(c) < 1e-300) c = 1e-300
            d = 1 / d
            val del = d * c
            h *= del
            if (abs(del - 1) < 1e-14) break
        }
        return exp(-x + a * ln(x) - lnGamma(a)) * h
    }

    private fun lnGamma(z: Double): Double {
        // Lanczos 근사
        val g = 7.0
        val p = doubleArrayOf(
            0.99999999999980993, 676.5203681218851, -1259.1392167224028, 771.32342877765313,
            -176.61502916214059, 12.507343278686905, -0.13857109526572012,
            9.9843695780195716e-6, 1.5056327351493116e-7,
        )
        if (z < 0.5) return ln(Math.PI / kotlin.math.sin(Math.PI * z)) - lnGamma(1 - z)
        val zz = z - 1
        var a = p[0]
        val t = zz + g + 0.5
        for (i in 1 until p.size) a += p[i] / (zz + i)
        return 0.5 * ln(2 * Math.PI) + (zz + 0.5) * ln(t) - t + ln(a)
    }
}
