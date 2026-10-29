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
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale


class MainActivity : ComponentActivity() {
    private var adsReady by mutableStateOf(false)
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Ads.start(this) { adsReady = true }
        WeeklyNotifier.schedule(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Navy, secondary = Color(0xFFFBC400))) {
                App(this, adsReady)
            }
        }
    }
}

private enum class Tab(val label: String) {
    PICKS("이번 주 추천"), REPORT("성적표"), RESULTS("당첨 결과"), ANALYSIS("분석"), CHECK("내 번호")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(activity: Activity, adsReady: Boolean, vm: LottoViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.PICKS) }
    UpdatePrompt(activity)
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
                                        Tab.REPORT -> Icons.Filled.DateRange
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
                    Tab.REPORT -> ReportScreen(state)
                    Tab.RESULTS -> ResultsScreen(state)
                    Tab.ANALYSIS -> AnalysisScreen(state)
                    Tab.CHECK -> CheckScreen(state, onCheck = { Ads.onUserAction(activity) })
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 앱 내 업데이트 (sideload 빌드)
@Composable
private fun UpdatePrompt(activity: Activity) {
    if (!AppUpdater.ENABLED) return
    var info by remember { mutableStateOf<UpdateInfo?>(null) }
    var dismissed by rememberSaveable { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { info = AppUpdater.check() }
    val u = info ?: return
    if (dismissed) return
    AlertDialog(
        onDismissRequest = { if (progress == null) dismissed = true },
        title = { Text("새 버전 ${u.versionName}") },
        text = {
            Column {
                if (u.notes.isNotBlank()) Text(u.notes)
                Spacer(Modifier.height(8.dp))
                Text("현재 ${BuildConfig.VERSION_NAME} · 다시 설치하지 않고 데이터 그대로 업데이트됩니다.", fontSize = 12.sp)
                progress?.let {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
                }
                message?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, fontSize = 12.sp, color = Color(0xFFB91C1C))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = progress == null, onClick = {
                message = null
                progress = 0f
                scope.launch {
                    message = AppUpdater.install(activity, u) { p -> progress = p }
                    progress = null
                }
            }) { Text(if (progress == null) "업데이트" else "받는 중…") }
        },
        dismissButton = {
            TextButton(enabled = progress == null, onClick = { dismissed = true }) { Text("나중에") }
        },
    )
}

// ------------------------------------------------------------------ 공통 UI
private fun sourceLabel(s: Source?) = when (s) {
    Source.NETWORK -> "최신 데이터"
    Source.CACHE -> "오프라인 · 저장된 데이터"
    Source.BUNDLED -> "오프라인 · 앱 기본 데이터"
    null -> ""
}

// ------------------------------------------------------------------ 이번 주 추천
@Composable
private fun PicksScreen(s: UiState, onRegenerate: () -> Unit, onRefresh: () -> Unit) {
    val w = s.weekly
    LazyColumn(contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Section("제${s.targetDraw}회 추천 번호") {
                Note("${s.targetDate} 추첨 · 이 기기 전용 번호 (사용자마다 다름) · 제1~${s.draws.last().no}회 분석" +
                    if (w == null) " · ${sourceLabel(s.source)}" else "")
                Spacer(Modifier.height(10.dp))
                PickRows(s.picks.map { it.numbers }, s.picks.map { it.popularity }, s.picks.map { it.shareVsMedian })
                if (s.picks.isNotEmpty()) CoverageLine(s.picks.map { it.numbers }, null)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRegenerate, enabled = !s.generating) {
                        Text(if (s.generating) "뽑는 중…" else "추가 조합 뽑기")
                    }
                    OutlinedButton(onClick = onRefresh) { Text("새로고침") }
                }
            }
        }
        if (s.extraPicks.isNotEmpty()) {
            item(key = "extra-${s.regenCount}") {
                Section("추가 조합 (앱에서 생성)") {
                    Note("같은 방법(저인기 + 겹침 최소화)으로 새 난수를 써서 만든 5세트")
                    Spacer(Modifier.height(8.dp))
                    PickRows(s.extraPicks.map { it.numbers }, s.extraPicks.map { it.popularity },
                        s.extraPicks.map { it.shareVsMedian })
                    CoverageLine(s.extraPicks.map { it.numbers }, null)
                }
            }
        }
        w?.verification?.let { v -> item { VerificationCard(v, s.draws.last().no, s.picks.map { it.numbers }) } }
        s.report?.entries?.firstOrNull { it.done && it.entry.draw == s.draws.last().no }?.let { g ->
            item { ReviewCard(g) }
        }
        item {
            Section("이 번호는 어떻게 골랐나요?") {
                Note(
                    "1. 당첨 확률: 역대 당첨번호를 검정한 결과 추첨은 무작위와 구분되지 않았고, 핫·콜드·미출현 등 " +
                        "번호 예측 전략도 무작위보다 낫지 않았습니다. 세트 1개의 확률은 어떤 번호든 같습니다.\n\n" +
                        "2. 당첨 범위(승률): 그래서 5세트끼리 번호가 최대한 겹치지 않게 골라, 같은 5,000원으로 " +
                        "'하나라도 당첨'될 확률을 높입니다. 아무렇게나 고른 5세트보다 약 2% 높습니다.\n\n" +
                        "3. 당첨금: 1~3등은 당첨금을 나눠 갖기 때문에 남들이 덜 고르는 조합을 씁니다. 판매량과 3등 " +
                        "당첨자 수로 학습한 인기도 모델이 학습에 안 쓴 회차의 1·2등 당첨자 수까지 맞혔습니다" +
                        "(p=${"%.3f".format(s.model?.validatedP ?: 1.0)}).\n\n" +
                        "4. 매주 월요일 최신 회차까지 다시 검정·학습해 위 결과를 새로 만듭니다."
                )
            }
        }
        item { Disclaimer() }
    }
}

@Composable
private fun ReviewCard(g: GradedEntry) {
    val d = g.draw ?: return
    Section("지난주 결과 검토 (제${d.no}회)") {
        Note("${d.date} 당첨번호 · 이 기기에서 받은 추천 기준")
        Spacer(Modifier.height(6.dp))
        BallRow(d.numbers, d.bonus, size = 30)
        Spacer(Modifier.height(10.dp))
        GradedSets(g)
        Spacer(Modifier.height(8.dp))
        Text("최고 ${g.bestHits}개 일치 · 당첨금 ${won(g.won)} / 구매 ${won(g.spent)}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Note("5세트로 5등 이상이 하나라도 나올 확률은 매주 약 11.6%라서 대부분의 주는 낙첨입니다. 누적 결과는 '성적표' 탭에서 보세요.")
    }
}

// ------------------------------------------------------------------ 성적표
// ------------------------------------------------------------------ 성적표
@Composable
private fun ReportScreen(s: UiState) {
    val r = s.report ?: return
    LazyColumn(contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ReportSummary(r) }
        items(r.entries, key = { it.entry.draw }) { g -> ReportEntry(g) }
        item { Disclaimer() }
    }
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
