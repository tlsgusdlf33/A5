package com.tlsgusdlf33.lottolab

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/**
 * 개인 앱의 회차별 추천 기록. 시드는 이 폰에서만 만들고 밖으로 내보내지 않는다.
 * 처음 실행 시 이전 주간 루틴·비공개 저장소에서 받은 번호(1243~1248회)를 불러와 성적표를 이어 간다.
 */
class OwnerStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("owner", Context.MODE_PRIVATE)

    private val seed: Long
        get() = prefs.getLong("seed", 0L).takeIf { it != 0L } ?: SecureRandom().nextLong().also {
            prefs.edit().putLong("seed", it).apply()
        }

    private fun load(): MutableList<Pair<HistoryEntry, String>> {
        val text = prefs.getString("history", null)
            ?: runCatching { context.assets.open("owner_history.json").bufferedReader().use { it.readText() } }
                .getOrDefault("[]")  // 이전 기록 파일은 비공개 저장소에만 있다(공개 저장소엔 없음)
        val a = JSONArray(text)
        return MutableList(a.length()) { i ->
            val o = a.getJSONObject(i)
            val sets = o.getJSONArray("sets")
            HistoryEntry(
                o.getInt("draw"),
                List(sets.length()) { j -> sets.getJSONArray(j).let { s -> List(s.length()) { s.getInt(it) } } },
                o.optLong("created", 0),
            ) to o.optString("source", "개인 앱")
        }
    }

    private fun save(all: List<Pair<HistoryEntry, String>>) {
        val a = JSONArray()
        all.sortedBy { it.first.draw }.forEach { (h, src) ->
            a.put(JSONObject().put("draw", h.draw).put("created", h.createdAt).put("source", src)
                .put("sets", JSONArray(h.sets.map { JSONArray(it) })))
        }
        prefs.edit().putString("history", a.toString()).apply()
    }

    fun history(): List<HistoryEntry> = load().map { it.first }.sortedBy { it.draw }

    fun sources(): Map<Int, String> = load().associate { it.first.draw to it.second }

    @Synchronized
    fun picksFor(draw: Int, draws: List<Draw>, model: PopularityModel, publicSets: List<List<Int>>): List<List<Int>> {
        val all = load()
        all.firstOrNull { it.first.draw == draw }?.let { return it.first.sets }
        val sets = LottoEngine.pickOwner(draws, model, seed xor (draw * -0x61c8864680b583ebL), publicSets).map { it.numbers }
        all += HistoryEntry(draw, sets, System.currentTimeMillis()) to "개인 앱"
        save(all)
        return sets
    }
}
