package com.tlsgusdlf33.lottolab

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * 개인 전용 앱. 광고·로그인·업데이트·부가 기능 없이, 주간 루틴으로 받던 내용만 보여준다:
 * 이번 주 개인 추천, 당첨 범위, 이번 주 검증 결과, 지난주 결과 검토, 누적 성적표. 월요일 알림.
 */
class OwnerActivity : ComponentActivity() {
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WeeklyNotifier.schedule(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Navy)) { OwnerScreen() }
        }
    }
}

data class OwnerState(
    val loading: Boolean = true,
    val error: String? = null,
    val target: Int = 0,
    val date: String = "",
    val lastNo: Int = 0,
    val picks: List<Pick> = emptyList(),
    val weekly: WeeklyPrediction? = null,
    val report: ReportCard? = null,
    val sources: Map<Int, String> = emptyMap(),
)

class OwnerViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = DataRepository(app)
    private val store = OwnerStore(app)
    private val _s = MutableStateFlow(OwnerState())
    val state: StateFlow<OwnerState> = _s

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _s.value = _s.value.copy(loading = true, error = null)
            runCatching {
                val list = repo.draws().value
                val model = repo.model().value
                val target = list.last().no + 1
                val weekly = repo.weekly(target)
                val (picks, report) = withContext(Dispatchers.Default) {
                    val sets = store.picksFor(target, list, model, weekly?.sets ?: emptyList())
                    val tickets = LottoEngine.avgTickets(list)
                    val base = LottoEngine.jackpotShare(LottoEngine.medianPopularity(model), tickets)
                    sets.map {
                        val p = LottoEngine.popularity(it, model)
                        Pick(it, p, LottoEngine.jackpotShare(p, tickets) / base)
                    } to PersonalStore.report(store.history(), list)
                }
                _s.value = OwnerState(
                    loading = false, target = target,
                    date = LocalDate.parse(list.last().date).plusDays(7).toString(), lastNo = list.last().no,
                    picks = picks, weekly = weekly, report = report, sources = store.sources(),
                )
            }.onFailure { _s.value = _s.value.copy(loading = false, error = it.message ?: "불러오기 실패") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OwnerScreen(vm: OwnerViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("로또 개인 추천", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF111827), titleContentColor = Color.White),
            )
        },
    ) { pad ->
        when {
            s.loading -> Column(
                Modifier.padding(pad).fillMaxSize(), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("후보 5만 개를 분석하는 중… (처음 한 번은 몇 초 걸립니다)", textAlign = TextAlign.Center)
            }
            s.error != null -> Column(
                Modifier.padding(pad).fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(s.error ?: "")
                OutlinedButton(onClick = vm::refresh) { Text("다시 시도") }
            }
            else -> LazyColumn(
                Modifier.padding(pad), contentPadding = PaddingValues(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Section("제${s.target}회 개인 추천") {
                        Note("${s.date} 추첨 · 개인 전용 영역에서 후보 5만 개 · 세트 간 겹침 최소 · 일반 앱 사용자에게는 나오지 않는 조합")
                        Spacer(Modifier.height(10.dp))
                        PickRows(s.picks.map { it.numbers }, s.picks.map { it.popularity }, s.picks.map { it.shareVsMedian })
                        if (s.picks.isNotEmpty()) CoverageLine(s.picks.map { it.numbers }, null)
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = vm::refresh) { Text("새로고침") }
                    }
                }
                s.weekly?.verification?.let { v ->
                    item { VerificationCard(v, s.lastNo, s.picks.map { it.numbers }) }
                }
                s.report?.entries?.firstOrNull { it.done && it.entry.draw == s.lastNo }?.let { g ->
                    item {
                        Section("지난주 결과 검토 (제${g.entry.draw}회)") {
                            g.draw?.let { BallRow(it.numbers, it.bonus, size = 30) }
                            Spacer(Modifier.height(10.dp))
                            GradedSets(g)
                            Spacer(Modifier.height(8.dp))
                            Text("최고 ${g.bestHits}개 일치 · 당첨금 ${won(g.won)} / 구매 ${won(g.spent)}", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                s.report?.let { r ->
                    item { ReportSummary(r, "1게임 1,000원, 추천 5세트를 모두 샀다고 가정. 1243~1247회는 이전 주간 루틴, 1248회는 비공개 저장소에서 받은 번호입니다.") }
                    items(r.entries, key = { it.entry.draw }) { g -> ReportEntry(g, s.sources[g.entry.draw]) }
                }
                item {
                    Note("세트 하나의 당첨 확률은 모든 조합이 같습니다(1/8,145,060). 이 추천은 '하나라도 당첨' 확률과 당첨 시 수령액을 높입니다.")
                }
            }
        }
    }
}
