package com.tlsgusdlf33.lottolab

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
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

private val Navy = Color(0xFF1E3A8A)

class MainActivity : ComponentActivity() {
    private var adsReady by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Ads.start(this) { adsReady = true }
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Navy, secondary = Color(0xFFFBC400))) {
                App(this, adsReady)
            }
        }
    }
}

private enum class Tab(val label: String) { PICKS("이번 주 추천"), RESULTS("당첨 결과"), ANALYSIS("분석"), CHECK("내 번호") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(activity: Activity, adsReady: Boolean, vm: LottoViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.PICKS) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("로또 번호 연구소", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Navy, titleContentColor = Color.White),
            )
        },
        bottomBar = {
            Column {
                if (adsReady) BannerAd(activity)
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = {
                                Icon(
                                    when (t) {
                                        Tab.PICKS -> Icons.Filled.Star
                                        Tab.RESULTS -> Icons.Filled.List
                                        Tab.ANALYSIS -> Icons.Filled.Info
                                        Tab.CHECK -> Icons.Filled.Search
                                    },
                                    contentDescription = t.label,
                                )
                            },
                            label = { Text(t.label, fontSize = 11.sp) },
                        )
                    }
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                state.loading -> Column(
                    Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("역대 당첨번호를 분석하는 중…")
                }
                state.error != null -> Column(
                    Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(state.error ?: "", textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = vm::refresh) { Text("다시 시도") }
                }
                else -> when (tab) {
                    Tab.PICKS -> PicksScreen(state, onRegenerate = {
                        vm.regenerate()
                        Ads.onUserAction(activity)
                    }, onRefresh = vm::refresh)
                    Tab.RESULTS -> ResultsScreen(state)
                    Tab.ANALYSIS -> AnalysisScreen(state)
                    Tab.CHECK -> CheckScreen(state, onCheck = { Ads.onUserAction(activity) })
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 공통 UI
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

private fun won(v: Long) = NumberFormat.getInstance(Locale.KOREA).format(v) + "원"

private fun sourceLabel(s: Source?) = when (s) {
    Source.NETWORK -> "최신 데이터"
    Source.CACHE -> "오프라인 · 저장된 데이터"
    Source.BUNDLED -> "오프라인 · 앱 기본 데이터"
    null -> ""
}

// ------------------------------------------------------------------ 이번 주 추천
@Composable
private fun PicksScreen(s: UiState, onRegenerate: () -> Unit, onRefresh: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Section("제${s.targetDraw}회 추천 번호") {
                Note("${s.targetDate} 추첨 · ${sourceLabel(s.source)} (제1~${s.draws.last().no}회 분석)")
                Spacer(Modifier.height(12.dp))
                s.picks.forEachIndexed { i, p ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 5.dp)) {
                        Text("${'A' + i}", fontWeight = FontWeight.Bold, modifier = Modifier.width(20.dp))
                        BallRow(p.numbers, size = 34)
                    }
                    Text(
                        "   인기도 ×${"%.2f".format(p.popularity)} · 1등 시 기대 수령액 보통 조합의 ×${"%.2f".format(p.shareVsMedian)}",
                        fontSize = 12.sp, color = Color(0xFF374151),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRegenerate, enabled = !s.generating) {
                        Text(if (s.generating) "뽑는 중…" else "새로 뽑기")
                    }
                    OutlinedButton(onClick = onRefresh) { Text("새로고침") }
                }
            }
        }
        s.weekly?.let { w ->
            item {
                Section("주간 분석 루틴 추천 (제${w.draw}회)") {
                    Note("매주 월요일 서버에서 최신 데이터로 재학습해 만든 5세트")
                    Spacer(Modifier.height(8.dp))
                    w.sets.forEachIndexed { i, set ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                            Text("${'A' + i}", fontWeight = FontWeight.Bold, modifier = Modifier.width(20.dp))
                            BallRow(set, size = 30)
                        }
                    }
                }
            }
        }
        item {
            Section("이 번호는 어떻게 골랐나요?") {
                Note(
                    "1. 역대 당첨번호 1,200여 회를 검정한 결과, 추첨은 통계적으로 무작위와 구분되지 않았습니다. " +
                        "핫·콜드·미출현 등 번호 예측 전략 19가지 모두 무작위보다 낫지 않았습니다.\n\n" +
                        "2. 그래서 당첨 확률을 높이는 대신 '당첨됐을 때 받는 돈'을 높입니다. 1~3등은 당첨금을 당첨자끼리 " +
                        "나누므로, 남들이 덜 고르는 조합일수록 같은 등수라도 더 많이 받습니다.\n\n" +
                        "3. 회차별 판매량과 3등 당첨자 수로 번호·패턴별 인기도를 학습하고, 학습에 쓰지 않은 회차에서 " +
                        "1·2등 당첨자 수를 예측해 검증했습니다(p=${"%.3f".format(s.model?.validatedP ?: 1.0)}). " +
                        "무작위 후보 5,000개 중 인기도가 가장 낮은 조합을 고릅니다."
                )
            }
        }
        item { Disclaimer() }
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

// ------------------------------------------------------------------ 당첨 결과
@Composable
private fun ResultsScreen(s: UiState) {
    val recent = s.draws.takeLast(30).reversed()
    LazyColumn(contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(recent, key = { it.no }) { d ->
            Section("제${d.no}회 · ${d.date}") {
                BallRow(d.numbers, d.bonus, size = 34)
                if (d.prizes.isNotEmpty() && d.prizes[0] > 0) {
                    Spacer(Modifier.height(6.dp))
                    Note("1등 1인당 ${won(d.prizes[0])}")
                }
            }
        }
        item { Disclaimer() }
    }
}

// ------------------------------------------------------------------ 분석
@Composable
private fun AnalysisScreen(s: UiState) {
    val st = s.stats ?: return
    val hot = (1..45).sortedByDescending { st.total[it] }.take(8)
    val cold = (1..45).sortedBy { st.total[it] }.take(8)
    val recentHot = (1..45).sortedByDescending { st.recent[it] }.take(8)
    val overdue = (1..45).sortedByDescending { st.gap[it] }.take(8)
    LazyColumn(contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Section("추첨은 공정한가? (무작위성 검정)") {
                Text(
                    "카이제곱 ${"%.1f".format(st.chiSquare)} (자유도 44) · p = ${"%.3f".format(st.chiP)}",
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                Note(
                    if (st.chiP > 0.05) "p > 0.05: 번호별 출현 횟수 차이는 우연으로 설명됩니다. 특정 번호가 더 잘 나온다는 근거가 없습니다."
                    else "p ≤ 0.05: 출현 횟수 차이가 우연보다 큽니다. 다만 여러 검정 중 하나일 수 있으니 주의하세요."
                )
            }
        }
        item {
            Section("번호별 출현 횟수 (제1~${s.draws.last().no}회)") {
                val max = (1..45).maxOf { st.total[it] }.toFloat()
                val min = (1..45).minOf { st.total[it] }.toFloat()
                (1..45).chunked(5).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        row.forEach { n ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(56.dp)) {
                                Ball(n, 28)
                                Text("${st.total[n]}", fontSize = 11.sp)
                                Box(
                                    Modifier.height(4.dp).width((10 + 40 * (st.total[n] - min) / (max - min + 1e-9f)).dp)
                                        .clip(RoundedCornerShape(2.dp)).background(Navy.copy(alpha = 0.6f))
                                )
                            }
                        }
                    }
                }
            }
        }
        item {
            Section("참고 통계 (예측력 없음)") {
                StatLine("역대 최다 출현", hot)
                StatLine("역대 최소 출현", cold)
                StatLine("최근 50회 최다", recentHot)
                StatLine("장기 미출현", overdue)
                Spacer(Modifier.height(6.dp))
                Note("백테스트 결과 위 번호들을 골라도 무작위보다 더 맞지 않았습니다. 재미로만 참고하세요.")
            }
        }
        item { Disclaimer() }
    }
}

@Composable
private fun StatLine(label: String, nums: List<Int>) {
    Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    BallRow(nums, size = 28)
    Spacer(Modifier.height(10.dp))
}

// ------------------------------------------------------------------ 내 번호 확인
@Composable
private fun CheckScreen(s: UiState, onCheck: () -> Unit) {
    val selected = remember { mutableStateListOf<Int>() }
    var result by remember { mutableStateOf<CheckResult?>(null) }
    var checkedSet by remember { mutableStateOf<List<Int>>(emptyList()) }
    var tick by remember { mutableIntStateOf(0) }
    LazyColumn(contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Section("번호 6개를 고르세요 (${selected.size}/6)") {
                (1..45).chunked(7).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { n ->
                            Box(Modifier.clickable {
                                if (n in selected) selected.remove(n) else if (selected.size < 6) selected.add(n)
                                result = null
                            }) { Ball(n, 38, dim = n !in selected) }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = selected.size == 6, onClick = {
                        checkedSet = selected.sorted()
                        result = LottoEngine.checkHistory(checkedSet, s.draws)
                        tick++
                        onCheck()
                    }) { Text("확인하기") }
                    OutlinedButton(onClick = { selected.clear(); result = null }) { Text("초기화") }
                }
            }
        }
        result?.let { r ->
            item(key = tick) {
                Section("결과") {
                    BallRow(checkedSet, size = 34)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (r.bestRank == 0) "역대 ${s.draws.size}회 중 5등 이상 당첨 이력이 없습니다."
                        else "역대 최고 ${r.bestRank}등 (제${r.bestDraw}회)",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Note((1..5).joinToString(" · ") { "${it}등 ${r.ranks[it]}회" })
                    s.model?.let { m ->
                        val pop = LottoEngine.popularity(checkedSet, m)
                        val share = LottoEngine.medianShare(s.draws, m, checkedSet)
                        Spacer(Modifier.height(10.dp))
                        Text("인기도 ×${"%.2f".format(pop)} · 1등 시 기대 수령액 보통 조합의 ×${"%.2f".format(share)}")
                        if (LottoEngine.obviousPattern(checkedSet)) {
                            Note("⚠️ 연속번호·등차수열·용지 한 줄 패턴은 많은 사람이 고르는 경향이 있어 실제 인기도가 더 높을 수 있습니다.")
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Note("과거에 당첨된 적이 있는지와 앞으로의 당첨 확률은 관계가 없습니다.")
                }
            }
        }
        item { Disclaimer() }
    }
}
