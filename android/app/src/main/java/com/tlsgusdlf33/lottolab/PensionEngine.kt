package com.tlsgusdlf33.lottolab

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import kotlin.random.Random

/** 연금복권720+ 한 회차: 조(1~5) + 6자리, 보너스 6자리. */
data class PensionDraw(val round: Int, val date: String, val group: Int, val number: String, val bonus: String)

data class PensionTicket(val group: Int, val number: String)

/** 공개 저장소 predictions/pension/<회차>.json 의 검증 요약. */
data class PensionVerification(
    val rounds: Int, val groupP: Double, val minDigitP: Double, val tests: Int,
    val significant: List<String>, val backtestBest: String, val backtestBestZ: Double, val adopted: String,
)

/** pension.py 와 같은 규칙. 당첨금 고정·번호당 한 장 판매라 인기도 전략은 없고, 커버리지만 쓴다. */
object PensionEngine {
    const val TICKETS = 5
    val PRIZES = mapOf(1 to 7_000_000L * 240, 2 to 1_000_000L * 120, 3 to 1_000_000L, 4 to 100_000L,
        5 to 50_000L, 6 to 5_000L, 7 to 1_000L)
    const val BONUS_PRIZE = 1_000_000L * 120

    /** 개인 전용 영역 (pension.is_reserved 와 같은 식). 일반 앱은 이 번호를 쓰지 않는다. */
    fun isReserved(number: String): Boolean {
        var h = 0L
        for (ch in number) h = (h * 131 + (ch - '0')) % 1_000_003
        return h % 7 == 0L
    }

    fun tailMatch(a: String, b: String): Int {
        var k = 0
        while (k < 6 && a[5 - k] == b[5 - k]) k++
        return k
    }

    /** 본 등수(0 = 낙첨)와 보너스 당첨 여부. */
    fun grade(t: PensionTicket, d: PensionDraw): Pair<Int, Boolean> {
        val main = if (t.number == d.number) (if (t.group == d.group) 1 else 2)
        else when (tailMatch(t.number, d.number)) { 5 -> 3; 4 -> 4; 3 -> 5; 2 -> 6; 1 -> 7; else -> 0 }
        return main to (t.number == d.bonus)
    }

    fun prize(t: PensionTicket, d: PensionDraw): Long {
        val (main, bonus) = grade(t, d)
        return (PRIZES[main] ?: 0L) + if (bonus) BONUS_PRIZE else 0L
    }

    /** 조 1~5 를 한 장씩, 끝자리는 서로 다르게(하나라도 당첨 50%), 개인 영역 번호는 제외. */
    fun pick(seed: Long): List<PensionTicket> {
        val rng = Random(seed)
        val tails = ('0'..'9').shuffled(rng).take(TICKETS)
        val groups = (1..5).shuffled(rng)
        val out = mutableListOf<PensionTicket>()
        for (i in 0 until TICKETS) {
            while (true) {
                val n = buildString { repeat(5) { append('0' + rng.nextInt(10)) }; append(tails[i]) }
                if (!isReserved(n) && out.none { it.number == n }) { out += PensionTicket(groups[i], n); break }
            }
        }
        return out.sortedBy { it.number.last() }
    }

    /** '하나라도 당첨'(7등 이상) 확률: 끝자리가 서로 다른 개수 / 10. */
    fun pAny(tickets: List<PensionTicket>) = tickets.map { it.number.last() }.toSet().size / 10.0

    fun randomPAny(n: Int) = 1 - Math.pow(0.9, n.toDouble())

    fun rankName(main: Int, bonus: Boolean) =
        (if (main == 0) (if (bonus) "" else "낙첨") else "${main}등") + if (bonus) (if (main == 0) "보너스" else " + 보너스") else ""

    fun parseDraws(text: String): List<PensionDraw> {
        val a = JSONArray(text)
        return List(a.length()) { i ->
            val o = a.getJSONObject(i)
            PensionDraw(o.getInt("round"), o.getString("date"), o.getInt("group"),
                o.getString("number").padStart(6, '0'), o.getString("bonus").padStart(6, '0'))
        }.sortedBy { it.round }
    }

    fun parseVerification(text: String): PensionVerification? = runCatching {
        val v = JSONObject(text).getJSONObject("verification")
        val sig = v.optJSONArray("significant") ?: JSONArray()
        PensionVerification(v.getInt("rounds"), v.getDouble("group_p"), v.getDouble("min_digit_p"), v.optInt("tests"),
            List(sig.length()) { sig.getString(it) }, v.getString("backtest_best"),
            v.getDouble("backtest_best_validate_z"), v.getString("adopted"))
    }.getOrNull()
}

data class PensionEntry(val round: Int, val tickets: List<PensionTicket>)

data class PensionGraded(val entry: PensionEntry, val draw: PensionDraw?) {
    val won get() = draw?.let { d -> entry.tickets.sumOf { PensionEngine.prize(it, d) } } ?: 0L
    val spent get() = entry.tickets.size * 1000L
}

/** 기기별 연금복권 추천 기록 (회차마다 처음 만든 번호로 고정 → 성적표). */
class PensionStore(context: Context) {
    private val prefs = context.getSharedPreferences("pension", Context.MODE_PRIVATE)

    private val seed: Long
        get() = prefs.getLong("seed", 0L).takeIf { it != 0L } ?: SecureRandom().nextLong().also {
            prefs.edit().putLong("seed", it).apply()
        }

    fun history(): List<PensionEntry> {
        val a = JSONArray(prefs.getString("history", "[]"))
        return List(a.length()) { i ->
            val o = a.getJSONObject(i)
            val t = o.getJSONArray("tickets")
            PensionEntry(o.getInt("round"), List(t.length()) { j ->
                t.getJSONArray(j).let { PensionTicket(it.getInt(0), it.getString(1)) }
            })
        }.sortedBy { it.round }
    }

    @Synchronized
    fun ticketsFor(round: Int): List<PensionTicket> {
        history().firstOrNull { it.round == round }?.let { return it.tickets }
        val tickets = PensionEngine.pick(seed xor (round * -0x61c8864680b583ebL))
        val a = JSONArray()
        (history() + PensionEntry(round, tickets)).forEach { e ->
            a.put(JSONObject().put("round", e.round)
                .put("tickets", JSONArray(e.tickets.map { JSONArray().put(it.group).put(it.number) })))
        }
        prefs.edit().putString("history", a.toString()).apply()
        return tickets
    }
}
