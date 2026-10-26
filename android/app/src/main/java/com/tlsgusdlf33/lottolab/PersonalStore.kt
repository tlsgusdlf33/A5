package com.tlsgusdlf33.lottolab

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/** 회차별로 이 기기에 추천한 번호 (처음 만든 순간 고정 → 성적표 기준). */
data class HistoryEntry(val draw: Int, val sets: List<List<Int>>, val createdAt: Long)

data class GradedRow(val hits: List<Int>, val rank: Int, val prize: Long)

data class GradedEntry(val entry: HistoryEntry, val draw: Draw?, val rows: List<GradedRow>) {
    val done get() = draw != null
    val won get() = rows.sumOf { it.prize }
    val spent get() = entry.sets.size * 1000L
    val bestHits get() = rows.maxOfOrNull { it.hits.size } ?: 0
}

data class ReportCard(
    val entries: List<GradedEntry>,
    val rounds: Int,
    val games: Int,
    val spent: Long,
    val won: Long,
    val rankCounts: IntArray,
    val hitCounts: IntArray,
    val expectedWins: Double,
)

/**
 * 사용자별 다른 번호: 설치 시 만든 비밀 시드(기기 밖으로 나가지 않음)와 회차 번호로 난수를 정한다.
 * 앱 사용자끼리 같은 번호가 몰리지 않아 1등 시 나눠 가질 사람이 늘지 않고, 공개 코드로도 재현할 수 없다.
 */
class PersonalStore(context: Context) {
    private val prefs = context.getSharedPreferences("personal", Context.MODE_PRIVATE)

    private val deviceSeed: Long
        get() = prefs.getLong("device_seed", 0L).takeIf { it != 0L } ?: SecureRandom().nextLong().also {
            prefs.edit().putLong("device_seed", it).apply()
        }

    fun seedFor(draw: Int): Long = deviceSeed xor (draw.toLong() * -0x61c8864680b583ebL)

    fun history(): List<HistoryEntry> {
        val a = JSONArray(prefs.getString("history", "[]"))
        return List(a.length()) { i ->
            val o = a.getJSONObject(i)
            val sets = o.getJSONArray("sets")
            HistoryEntry(
                o.getInt("draw"),
                List(sets.length()) { j -> sets.getJSONArray(j).let { s -> List(s.length()) { s.getInt(it) } } },
                o.optLong("created", 0),
            )
        }.sortedBy { it.draw }
    }

    fun get(draw: Int): HistoryEntry? = history().firstOrNull { it.draw == draw }

    @Synchronized
    fun put(draw: Int, sets: List<List<Int>>): HistoryEntry {
        get(draw)?.let { return it }  // 한 번 정한 번호는 바꾸지 않는다
        val e = HistoryEntry(draw, sets, System.currentTimeMillis())
        val all = history() + e
        val a = JSONArray()
        all.forEach { h ->
            a.put(JSONObject().put("draw", h.draw).put("created", h.createdAt)
                .put("sets", JSONArray(h.sets.map { JSONArray(it) })))
        }
        prefs.edit().putString("history", a.toString()).apply()
        return e
    }

    /** 이번 회차 내 번호: 이미 있으면 그대로, 없으면 만들고 고정. */
    fun picksFor(draw: Int, draws: List<Draw>, model: PopularityModel): List<List<Int>> =
        get(draw)?.sets ?: put(draw, LottoEngine.pickUnpopular(draws, model, seedFor(draw)).map { it.numbers }).sets

    companion object {
        fun report(history: List<HistoryEntry>, draws: List<Draw>): ReportCard {
            val byNo = draws.associateBy { it.no }
            val graded = history.sortedByDescending { it.draw }.map { h ->
                val d = byNo[h.draw]
                GradedEntry(h, d, if (d == null) emptyList() else h.sets.map { s ->
                    val r = LottoEngine.rank(s, d)
                    GradedRow(s.filter { it in d.numbers }, r, if (r == 0) 0 else d.prizes.getOrNull(r - 1)
                        ?.takeIf { it > 0 } ?: when (r) { 5 -> 5000L; 4 -> 50000L; else -> 0L })
                })
            }
            val done = graded.filter { it.done }
            val ranks = IntArray(6)
            val hits = IntArray(7)
            done.forEach { g -> g.rows.forEach { ranks[it.rank]++; hits[it.hits.size]++ } }
            val games = done.sumOf { it.entry.sets.size }
            return ReportCard(
                graded, done.size, games, done.sumOf { it.spent }, done.sumOf { it.won }, ranks, hits,
                games * LottoEngine.P_SINGLE_5TH,
            )
        }
    }
}
