package com.tlsgusdlf33.lottolab

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** 주간 루틴이 저장소에 올린 공식 추천 (predictions/<회차>.json). */
data class WeeklyPrediction(val draw: Int, val date: String, val sets: List<List<Int>>, val strategy: String)

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

    suspend fun weekly(draw: Int): WeeklyPrediction? = withContext(Dispatchers.IO) {
        val text = fetch(base + "predictions/$draw.json") ?: return@withContext null
        runCatching {
            val o = JSONObject(text)
            val sets = o.getJSONArray("sets").let { a -> List(a.length()) { ints(a.getJSONArray(it)) } }
            WeeklyPrediction(o.getInt("draw"), o.optString("date"), sets, o.optString("strategy"))
        }.getOrNull()
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
