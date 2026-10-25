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
    val picks: List<Pick> = emptyList(),
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
                // 시드 = 회차 번호 → 같은 주에는 누구나 같은 기본 추천을 본다.
                val (stats, picks) = withContext(Dispatchers.Default) {
                    LottoEngine.stats(list) to LottoEngine.pickUnpopular(list, model, target.toLong())
                }
                val weekly = repo.weekly(target)
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
            _state.value = _state.value.copy(picks = picks, generating = false, regenCount = s.regenCount + 1)
        }
    }
}
