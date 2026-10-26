package com.tlsgusdlf33.lottolab

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** 주간 루틴이 저장소에 올린 공식 추천 (predictions/<회차>.json). 예전 파일엔 일부 필드가 없을 수 있다. */
data class WeeklyPrediction(
    val draw: Int,
    val date: String,
    val sets: List<List<Int>>,
    val strategy: String,
    val popularity: List<Double>?,
    val shareVsMedian: List<Double>?,
    val maxOverlap: Int?,
    val coverage: Coverage?,
    val verification: Verification?,
    val review: Review?,
)

data class Coverage(val pAny5th: Double, val randomAny5th: Double, val pAny4th: Double, val randomAny4th: Double)

data class Verification(
    val drawsAnalyzed: Int,
    val chiP: Double,
    val machineChiP: Map<String, Double>,
    val strategiesTested: Int?,
    val maxAbsZ: Double?,
    val significant: List<String>,
    val backtestBest: String,
    val backtestBestZ: Double,
    val adopted: String,
    val popularityP: Double?,
    val popularityGain: Double?,
)

data class ReviewSet(val numbers: List<Int>, val hits: List<Int>, val bonusHit: Boolean, val rank: Int, val prize: Long)

data class Review(
    val draw: Int,
    val date: String,
    val numbers: List<Int>,
    val bonus: Int,
    val sets: List<ReviewSet>,
    val bestHits: Int,
    val won: Long,
    val spent: Long,
)

enum class Source { NETWORK, CACHE, BUNDLED }

data class Loaded<T>(val value: T, val source: Source)

/**
 * 데이터 우선순위: 네트워크 → 앱 내부 캐시 → APK 에 포함된 기본 데이터.
 * 오프라인에서도 앱이 동작하도록 항상 마지막 단계가 성공한다.
 */
class DataRepository(private val context: Context) {
    private val drawsUrl = "https://smok95.github.io/lotto/results/all.json"
    private val base = BuildConfig.DATA_BASE_URL

    suspend fun draws(): Loaded<List<Draw>> = withContext(Dispatchers.IO) {
        val cache = File(context.filesDir, "draws_raw.json")
        fetch(drawsUrl)?.let { text ->
            runCatching { parseMirror(text) }.getOrNull()?.takeIf { it.size > 1000 }?.let {
                cache.writeText(text)
                return@withContext Loaded(it, Source.NETWORK)
            }
        }
        if (cache.exists()) {
            runCatching { parseMirror(cache.readText()) }.getOrNull()?.let {
                return@withContext Loaded(it, Source.CACHE)
            }
        }
        Loaded(parseBundled(asset("draws.json")), Source.BUNDLED)
    }

    suspend fun model(): Loaded<PopularityModel> = withContext(Dispatchers.IO) {
        val cache = File(context.filesDir, "popularity_model.json")
        fetch(base + "data/popularity_model.json")?.let { text ->
            runCatching { parseModel(text) }.getOrNull()?.let {
                cache.writeText(text)
                return@withContext Loaded(it, Source.NETWORK)
            }
        }
        if (cache.exists()) {
            runCatching { parseModel(cache.readText()) }.getOrNull()?.let {
                return@withContext Loaded(it, Source.CACHE)
            }
        }
        Loaded(parseModel(asset("popularity_model.json")), Source.BUNDLED)
    }

    /** 가장 최근 추첨 회차 번호 (작은 파일만 받아 알림 확인용으로 사용). */
    suspend fun latestDrawNo(): Int? = withContext(Dispatchers.IO) {
        fetch("https://smok95.github.io/lotto/results/latest.json")?.let {
            runCatching { JSONObject(it).getInt("draw_no") }.getOrNull()
        }
    }

    suspend fun weekly(draw: Int): WeeklyPrediction? = withContext(Dispatchers.IO) {
        val cache = File(context.filesDir, "weekly_$draw.json")
        val text = fetch(base + "predictions/$draw.json")?.also { cache.writeText(it) }
            ?: cache.takeIf { it.exists() }?.readText()
            ?: return@withContext null
        runCatching { parseWeekly(text) }.getOrNull()
    }

    private fun doubles(a: JSONArray?) = a?.let { List(it.length()) { i -> it.getDouble(i) } }

    private fun parseWeekly(text: String): WeeklyPrediction {
        val o = JSONObject(text)
        val sets = o.getJSONArray("sets").let { a -> List(a.length()) { ints(a.getJSONArray(it)) } }
        val cov = o.optJSONObject("coverage")?.let {
            Coverage(it.getDouble("p_any_5th"), it.getDouble("random_p_any_5th"),
                it.optDouble("p_any_4th", 0.0), it.optDouble("random_p_any_4th", 0.0))
        }
        val ver = o.optJSONObject("verification")?.let { v ->
            val mc = v.optJSONObject("machine_chi_p")
            Verification(
                drawsAnalyzed = v.optInt("draws_analyzed"),
                chiP = v.optDouble("chi_p", Double.NaN),
                machineChiP = mc?.keys()?.asSequence()?.associateWith { mc.getDouble(it) } ?: emptyMap(),
                strategiesTested = v.optInt("strategies_tested").takeIf { it > 0 },
                maxAbsZ = v.optDouble("max_abs_z").takeIf { !it.isNaN() },
                significant = v.optJSONArray("significant")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList(),
                backtestBest = v.optString("backtest_best"),
                backtestBestZ = v.optDouble("backtest_best_validate_z", Double.NaN),
                adopted = v.optString("adopted"),
                popularityP = v.optDouble("popularity_p").takeIf { !it.isNaN() },
                popularityGain = v.optDouble("popularity_gain").takeIf { !it.isNaN() },
            )
        }
        val rev = o.optJSONObject("review")?.let { r ->
            val rs = r.getJSONArray("sets")
            Review(
                draw = r.getInt("draw"), date = r.optString("date"), numbers = ints(r.getJSONArray("numbers")),
                bonus = r.getInt("bonus"),
                sets = List(rs.length()) { i ->
                    val x = rs.getJSONObject(i)
                    ReviewSet(ints(x.getJSONArray("numbers")), ints(x.getJSONArray("hits")),
                        x.optBoolean("bonus_hit"), x.getInt("rank"), x.optLong("prize"))
                },
                bestHits = r.optInt("best_hits"), won = r.optLong("won"), spent = r.optLong("spent"),
            )
        }
        return WeeklyPrediction(
            draw = o.getInt("draw"), date = o.optString("date"), sets = sets, strategy = o.optString("strategy"),
            popularity = doubles(o.optJSONArray("popularity")),
            shareVsMedian = doubles(o.optJSONArray("share_vs_median")),
            maxOverlap = o.optInt("max_overlap", -1).takeIf { it >= 0 },
            coverage = cov, verification = ver, review = rev,
        )
    }

    private fun asset(name: String) = context.assets.open(name).bufferedReader().use { it.readText() }

    private fun fetch(url: String): String? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "LottoLab-Android")
        try {
            if (c.responseCode != 200) null else c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    private fun ints(a: JSONArray) = List(a.length()) { a.getInt(it) }

    /** smok95 미러 형식 (동행복권 데이터). */
    private fun parseMirror(text: String): List<Draw> {
        val a = JSONArray(text)
        return List(a.length()) { i ->
            val o = a.getJSONObject(i)
            val divs = o.optJSONArray("divisions") ?: JSONArray()
            Draw(
                no = o.getInt("draw_no"),
                date = o.getString("date").take(10),
                numbers = ints(o.getJSONArray("numbers")).sorted(),
                bonus = o.getInt("bonus_no"),
                prizes = List(minOf(5, divs.length())) { divs.getJSONObject(it).optLong("prize", 0) },
                sales = o.optLong("total_sales_amount", 0),
            )
        }.sortedBy { it.no }
    }

    /** 저장소 data/draws.json 형식 (APK 기본 데이터). */
    private fun parseBundled(text: String): List<Draw> {
        val a = JSONArray(text)
        return List(a.length()) { i ->
            val o = a.getJSONObject(i)
            val prizes = o.optJSONArray("prizes") ?: JSONArray()
            Draw(
                no = o.getInt("draw"),
                date = o.getString("date"),
                numbers = ints(o.getJSONArray("numbers")).sorted(),
                bonus = o.getInt("bonus"),
                prizes = List(prizes.length()) { prizes.getLong(it) },
                sales = o.optLong("sales", 0),
            )
        }.sortedBy { it.no }
    }

    private fun parseModel(text: String): PopularityModel {
        val o = JSONObject(text)
        val coef = o.getJSONArray("coef")
        return PopularityModel(
            coef = DoubleArray(coef.length()) { coef.getDouble(it) },
            zMean = o.getDouble("z_mean"),
            zStd = o.getDouble("z_std"),
            gamma = o.getDouble("gamma"),
            validatedP = o.optDouble("validated_p", 1.0),
        )
    }
}
