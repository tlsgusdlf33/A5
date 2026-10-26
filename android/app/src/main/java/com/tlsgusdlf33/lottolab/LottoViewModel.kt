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
    /** 주간 루틴 결과를 못 받았을 때만 쓰는 앱 자체 생성 추천 (같은 방법, 다른 난수). */
    val picks: List<Pick> = emptyList(),
    /** '새로 뽑기'로 만든 추가 조합. */
    val extraPicks: List<Pick> = emptyList(),
    val weekly: WeeklyPrediction? = null,
    val generating: Boolean = false,
    val regenCount: Int = 0,
)

class LottoViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = DataRepository(app)
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
                // 기본 추천 = 주간 루틴이 올린 predictions/<회차>.json (알림·채점과 같은 번호).
                val weekly = repo.weekly(target)
                val (stats, picks) = withContext(Dispatchers.Default) {
                    LottoEngine.stats(list) to
                        if (weekly == null) LottoEngine.pickUnpopular(list, model, target.toLong()) else emptyList()
                }
                _state.value = _state.value.copy(
                    loading = false, draws = list, model = model, stats = stats, source = draws.source,
                    targetDraw = target, targetDate = date, picks = picks, weekly = weekly,
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
