package com.tlsgusdlf33.lottolab

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LottoEngineTest {
    private val assets = File("src/main/assets")

    private fun model(): PopularityModel {
        val o = JSONObject(File(assets, "popularity_model.json").readText())
        val c = o.getJSONArray("coef")
        return PopularityModel(DoubleArray(c.length()) { c.getDouble(it) }, o.getDouble("z_mean"),
            o.getDouble("z_std"), o.getDouble("gamma"), o.getDouble("validated_p"))
    }

    private fun draws(): List<Draw> {
        val a = JSONArray(File(assets, "draws.json").readText())
        return List(a.length()) { i ->
            val o = a.getJSONObject(i)
            val n = o.getJSONArray("numbers")
            Draw(o.getInt("draw"), o.getString("date"), List(6) { n.getInt(it) }.sorted(), o.getInt("bonus"),
                sales = o.optLong("sales", 0))
        }
    }

    /** Python(lotto_features.py + lotto_predict.popularity) 과 같은 값이어야 한다. */
    @Test
    fun popularityMatchesPython() {
        val m = model()
        assertEquals(0.8577037583815398, LottoEngine.popularity(listOf(13, 14, 28, 29, 37, 42), m), 1e-9)
        assertEquals(1.1617239116183613, LottoEngine.popularity(listOf(1, 2, 3, 4, 5, 6), m), 1e-9)
        assertEquals(1.1479709067276762, LottoEngine.popularity(listOf(7, 12, 17, 27, 33, 40), m), 1e-9)
    }

    @Test
    fun picksSatisfyConstraints() {
        val d = draws()
        val picks = LottoEngine.pickUnpopular(d, model(), 1248)
        assertEquals(5, picks.size)
        val past = d.map { it.numbers }.toSet()
        for ((i, p) in picks.withIndex()) {
            assertEquals(6, p.numbers.toSet().size)
            assertTrue(p.numbers.all { it in 1..45 })
            assertFalse(p.numbers in past)
            assertFalse(LottoEngine.obviousPattern(p.numbers))
            assertTrue(p.popularity < 1.0 && p.shareVsMedian > 1.0)
            for (q in picks.drop(i + 1)) assertTrue(p.numbers.count { it in q.numbers } <= 2)
        }
        // 같은 시드 → 같은 결과 (주간 기본 추천 재현성)
        assertEquals(picks, LottoEngine.pickUnpopular(d, model(), 1248))
    }

    @Test
    fun chiSquareMatchesScipy() {
        // scipy.stats.chi2.sf(29.3, 44) = 0.9562..., chi2.sf(60.48, 44) ≈ 0.05
        assertEquals(0.956, LottoEngine.chiSquareSurvival(29.3, 44), 0.002)
        assertEquals(0.05, LottoEngine.chiSquareSurvival(60.48, 44), 0.002)
        val s = LottoEngine.stats(draws())
        assertTrue(s.chiP > 0.05)
    }

    @Test
    fun rankRules() {
        val d = Draw(1, "2024-01-01", listOf(1, 2, 3, 4, 5, 6), 7)
        assertEquals(1, LottoEngine.rank(listOf(1, 2, 3, 4, 5, 6), d))
        assertEquals(2, LottoEngine.rank(listOf(1, 2, 3, 4, 5, 7), d))
        assertEquals(3, LottoEngine.rank(listOf(1, 2, 3, 4, 5, 8), d))
        assertEquals(4, LottoEngine.rank(listOf(1, 2, 3, 4, 9, 8), d))
        assertEquals(5, LottoEngine.rank(listOf(1, 2, 3, 10, 9, 8), d))
        assertEquals(0, LottoEngine.rank(listOf(1, 2, 11, 10, 9, 8), d))
    }
}
