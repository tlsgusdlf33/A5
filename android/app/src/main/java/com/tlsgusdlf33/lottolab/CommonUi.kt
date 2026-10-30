package com.tlsgusdlf33.lottolab

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.NumberFormat
import java.util.Locale

val Navy = Color(0xFF1E3A8A)

fun ballColor(n: Int): Color = when (n) {
    in 1..10 -> Color(0xFFFBC400)
    in 11..20 -> Color(0xFF69C8F2)
    in 21..30 -> Color(0xFFFF7272)
    in 31..40 -> Color(0xFFAAAAAA)
    else -> Color(0xFFB0D840)
}

@Composable
fun Ball(n: Int, size: Int = 38, dim: Boolean = false) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(if (dim) Color(0xFFE2E4EA) else ballColor(n)),
        contentAlignment = Alignment.Center,
    ) {
        Text("$n", color = if (dim) Color(0xFF8A8F9C) else Color.White, fontWeight = FontWeight.Bold,
            fontSize = (size * 0.4).sp)
    }
}

@Composable
fun BallRow(nums: List<Int>, bonus: Int? = null, size: Int = 38, highlight: Set<Int>? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        nums.forEach { Ball(it, size, dim = highlight != null && it !in highlight) }
        if (bonus != null) {
            Text("+", fontWeight = FontWeight.Bold, color = Color.Gray)
            Ball(bonus, size)
        }
    }
}

@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Navy)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
fun Note(text: String) {
    Text(text, fontSize = 12.sp, color = Color(0xFF6B7280), lineHeight = 17.sp)
}

fun won(v: Long) = NumberFormat.getInstance(Locale.KOREA).format(v) + "원"


fun pct(x: Double) = "%.2f%%".format(x * 100)

@Composable
fun PickRows(sets: List<List<Int>>, pops: List<Double>?, shares: List<Double>?) {
    sets.forEachIndexed { i, set ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 5.dp)) {
            Text("${'A' + i}", fontWeight = FontWeight.Bold, modifier = Modifier.width(20.dp))
            BallRow(set, size = 34)
        }
        val p = pops?.getOrNull(i)
        val sh = shares?.getOrNull(i)
        if (p != null && sh != null) {
            Text(
                "   인기도 ×${"%.2f".format(p)} · 1등 시 기대 수령액 보통 조합의 ×${"%.2f".format(sh)}",
                fontSize = 12.sp, color = Color(0xFF374151),
            )
        }
    }
}

@Composable
fun CoverageLine(sets: List<List<Int>>, maxOverlap: Int?) {
    val mine = remember(sets) { LottoEngine.pAnyMatch(sets, 3) }
    val rand = LottoEngine.randomAny5th(sets.size)
    val ov = maxOverlap ?: sets.flatMap { a -> sets.filter { it !== a }.map { b -> a.count { it in b } } }.maxOrNull() ?: 0
    Spacer(Modifier.height(8.dp))
    Text(
        "🎯 ${sets.size}세트 중 하나라도 5등 이상: ${pct(mine)}",
        fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
    )
    Note(
        "아무렇게나 고른 ${sets.size}세트 평균 ${pct(rand)} 대비 ${"%+.1f".format((mine / rand - 1) * 100)}% · " +
            "세트끼리 겹치는 번호 최대 ${ov}개 (조합 수로 정확히 계산)"
    )
}


@Composable
fun VerificationCard(v: Verification, lastNo: Int, mySets: List<List<Int>>) {
    Section("이번 주 검증 결과") {
        Text("① 추첨 편향 검정", fontWeight = FontWeight.SemiBold)
        Note(
            "제1~${lastNo}회 번호 빈도 p=${"%.2f".format(v.chiP)}" +
                (if (v.machineChiP.isNotEmpty()) " · 추첨기별 p=" + v.machineChiP.entries.sortedBy { it.key }
                    .joinToString(", ") { "${it.key}호기 ${"%.2f".format(it.value)}" } else "") +
                "\n→ ${if (v.chiP > 0.05) "무작위와 구분되지 않음 (특정 번호가 잘 나오지 않음)" else "편향 신호 있음 — 추가 확인 필요"}"
        )
        Spacer(Modifier.height(8.dp))
        Text("② 번호 예측 전략 백테스트", fontWeight = FontWeight.SemiBold)
        Note(
            (v.strategiesTested?.let { "${it}개 전략 워크포워드 검정, 최대 |z|=${"%.2f".format(v.maxAbsZ ?: 0.0)} · " } ?: "") +
                (if (v.significant.isEmpty()) "유의한 전략 없음" else "유의: ${v.significant.joinToString()}") +
                "\n선택 구간 1위 '${v.backtestBest}' → 검증 구간 z=${"%+.2f".format(v.backtestBestZ)}" +
                "\n→ 채택: ${v.adopted}"
        )
        Spacer(Modifier.height(8.dp))
        Text("③ 인기도 모델 (당첨금 분할)", fontWeight = FontWeight.SemiBold)
        Note(
            "학습에 안 쓴 회차로 검증 p=${"%.3f".format(v.popularityP ?: 1.0)} " +
                "(${if ((v.popularityP ?: 1.0) < 0.05) "통과" else "미통과 — 사용 안 함"})" +
                (v.popularityGain?.let { "\n인기도 하위 1% 조합의 1등 기대 수령액: 보통 조합의 ×${"%.2f".format(it)}" } ?: "")
        )
        if (mySets.isNotEmpty()) {
            val sets = mySets
            val p5 = remember(sets) { LottoEngine.pAnyMatch(sets, 3) }
            val p4 = remember(sets) { LottoEngine.pAnyMatch(sets, 4) }
            Spacer(Modifier.height(8.dp))
            Text("④ 당첨 범위 (승률) — 내 번호 기준", fontWeight = FontWeight.SemiBold)
            Note(
                "5세트 중 하나라도 5등 이상 ${pct(p5)} (무작위 5세트 ${pct(LottoEngine.randomAny5th(5))})" +
                    "\n하나라도 4등 이상 ${pct(p4)}"
            )
        }
    }
}

@Composable
fun GradedSets(g: GradedEntry, size: Int = 26) {
    g.entry.sets.forEachIndexed { i, set ->
        val row = g.rows.getOrNull(i)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
            Text("${'A' + i}", fontWeight = FontWeight.Bold, modifier = Modifier.width(20.dp))
            BallRow(set, size = size, highlight = row?.hits?.toSet())  // 추첨 전이면 강조 없이 원래 색
            Spacer(Modifier.width(6.dp))
            if (row != null) Text(
                if (row.rank == 0) "${row.hits.size}개" else "${row.rank}등",
                fontSize = 12.sp,
                fontWeight = if (row.rank > 0) FontWeight.Bold else FontWeight.Normal,
                color = if (row.rank > 0) Color(0xFFB45309) else Color(0xFF6B7280),
            )
        }
    }
}

@Composable
fun ReportSummary(r: ReportCard, note: String = "1게임 1,000원, 추천 5세트를 모두 샀다고 가정한 기록입니다. 로또의 평균 환수율은 약 50%입니다.") {
    Section("누적 성적표") {
        if (r.rounds == 0) {
            Note("아직 추첨이 끝난 회차가 없습니다. 추천을 받은 회차부터 매주 자동으로 채점됩니다.")
        } else {
            Text("${r.rounds}회차 · ${r.games}게임", fontWeight = FontWeight.SemiBold)
            Text("구매 ${won(r.spent)} · 당첨 ${won(r.won)} · 환수율 ${"%.0f".format(100.0 * r.won / r.spent)}%",
                fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Navy)
            Spacer(Modifier.height(8.dp))
            Text((1..5).joinToString("  ") { "${it}등 ${r.rankCounts[it]}" }, fontSize = 14.sp)
            Note(
                "5등 이상 ${(1..5).sumOf { r.rankCounts[it] }}게임 (무작위 기대 ${"%.1f".format(r.expectedWins)}게임)\n" +
                    "일치 개수: " + (0..6).filter { r.hitCounts[it] > 0 }.joinToString(", ") { "${it}개 ${r.hitCounts[it]}" }
            )
            Spacer(Modifier.height(6.dp))
            Note(note)
        }
    }
}

@Composable
fun ReportEntry(g: GradedEntry, label: String? = null) {
    Section("제${g.entry.draw}회" + (g.draw?.let { " · ${it.date}" } ?: " · 추첨 전") + (label?.let { " · $it" } ?: "")) {
        g.draw?.let {
            BallRow(it.numbers, it.bonus, size = 26)
            Spacer(Modifier.height(8.dp))
        }
        GradedSets(g)
        if (g.done) {
            Spacer(Modifier.height(6.dp))
            Text("최고 ${g.bestHits}개 · 당첨금 ${won(g.won)}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun Disclaimer() {
    Text(
        "⚠️ 모든 조합의 1등 당첨 확률은 1/8,145,060으로 같습니다. 이 앱은 당첨 확률을 높이지 않으며, " +
            "당첨을 보장하지 않습니다. 동행복권과 무관한 개인 제작 앱입니다. 로또는 만 19세 이상만 구매할 수 있으며, " +
            "감당할 수 있는 범위에서만 즐기세요. 도박 문제 상담: 국번 없이 1336",
        fontSize = 11.sp, color = Color(0xFF9CA3AF), lineHeight = 15.sp,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
    )
}

