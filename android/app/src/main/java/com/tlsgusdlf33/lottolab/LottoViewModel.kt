package com.tlsgusdlf33.lottolab

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

data class UiState(
    val loading: Boolean = true,
    val error: String? = null,
    val draws: List<Draw> = emptyList(),
    val model: PopularityModel? = null,
    val stats: NumberStats? = null,
    val source: Source? = null,
    val targetDraw: Int = 0,
    val targetDate: String = "",
    /** 이 기기 전용 이번 주 추천 (주간 루틴과 같은 방법·같은 모델, 기기별 비밀 시드). */
    val picks: List<Pick> = emptyList(),
    /** 처음 쓴 회차부터의 성적표. */
    val report: ReportCard? = null,
    /** '새로 뽑기'로 만든 추가 조합. */
    val extraPicks: List<Pick> = emptyList(),
    val weekly: WeeklyPrediction? = null,
    val generating: Boolean = false,
    val regenCount: Int = 0,
)

class LottoViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = DataRepository(app)
    private val store = PersonalStore(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            runCatching {
                val draws = repo.draws()
                val model = repo.model().value
                val list = draws.value
                val target = list.last().no + 1
                val date = LocalDate.parse(list.last().date).plusDays(7).toString()
                // 검증 결과·재학습 모델은 주간 루틴(GitHub Actions) 결과, 번호는 기기별로 따로.
                val weekly = repo.weekly(target)
                val (stats, picks, report) = withContext(Dispatchers.Default) {
                    val sets = store.picksFor(target, list, model)
                    val tickets = LottoEngine.avgTickets(list)
                    val median = LottoEngine.medianPopularity(model)
                    val base = LottoEngine.jackpotShare(median, tickets)
                    val picks = sets.map {
                        val p = LottoEngine.popularity(it, model)
                        Pick(it, p, LottoEngine.jackpotShare(p, tickets) / base)
                    }
                    Triple(LottoEngine.stats(list), picks, PersonalStore.report(store.history(), list))
                }
                _state.value = _state.value.copy(
                    loading = false, draws = list, model = model, stats = stats, source = draws.source,
                    targetDraw = target, targetDate = date, picks = picks, weekly = weekly, report = report,
                )
            }.onFailure {
                _state.value = _state.value.copy(loading = false, error = it.message ?: "데이터를 불러오지 못했습니다")
            }
        }
    }

    fun regenerate() {
        val s = _state.value
        val model = s.model ?: return
        if (s.generating || s.draws.isEmpty()) return
        _state.value = s.copy(generating = true)
        viewModelScope.launch {
            val picks = withContext(Dispatchers.Default) {
                LottoEngine.pickUnpopular(s.draws, model, System.nanoTime())
            }
            _state.value = _state.value.copy(extraPicks = picks, generating = false, regenCount = s.regenCount + 1)
        }
    }
}
