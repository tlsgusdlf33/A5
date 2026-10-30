package com.tlsgusdlf33.lottolab

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import android.graphics.Canvas
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

/**
 * Google Play 스토어용 스크린샷: 실제 앱 화면(AppScaffold)을 실제 데이터로 그린다.
 * 약 1080x1920 (9:16) PNG → android/store/screenshots/
 *   ./gradlew testSideloadDebugUnitTest -PstoreScreenshots=true --tests '*StoreScreenshots*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h731dp-420dpi")
class StoreScreenshots {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val assets = File("src/main/assets")
    private val repoRoot = File("../..")
    private val out = File("../store/screenshots")

    @Before fun only() = assumeTrue(System.getProperty("store.screenshots") == "true")

    private fun ints(a: JSONArray) = List(a.length()) { a.getInt(it) }

    private fun draws(): List<Draw> {
        val a = JSONArray(File(assets, "draws.json").readText())
        return List(a.length()) { i ->
            val o = a.getJSONObject(i)
            val pz = o.optJSONArray("prizes") ?: JSONArray()
            Draw(o.getInt("draw"), o.getString("date"), ints(o.getJSONArray("numbers")).sorted(), o.getInt("bonus"),
                List(pz.length()) { pz.getLong(it) }, o.optLong("sales", 0))
        }
    }

    private fun model(): PopularityModel {
        val o = JSONObject(File(assets, "popularity_model.json").readText())
        val c = o.getJSONArray("coef")
        return PopularityModel(DoubleArray(c.length()) { c.getDouble(it) }, o.getDouble("z_mean"),
            o.getDouble("z_std"), o.getDouble("gamma"), o.getDouble("validated_p"))
    }

    private fun state(): UiState {
        val d = draws()
        val m = model()
        val target = d.last().no + 1
        val tickets = LottoEngine.avgTickets(d)
        val base = LottoEngine.jackpotShare(LottoEngine.medianPopularity(m), tickets)
        val picks = LottoEngine.pickUnpopular(d, m, 20261030L).map {
            Pick(it.numbers, it.popularity, LottoEngine.jackpotShare(it.popularity, tickets) / base)
        }
        // 성적표: 실제로 지난 회차들에 냈던 주간 추천 번호
        val hist = (d.last().no - 4..d.last().no).mapNotNull { n ->
            File(repoRoot, "predictions/$n.json").takeIf { it.exists() }?.let { f ->
                val sets = JSONObject(f.readText()).getJSONArray("sets")
                HistoryEntry(n, List(sets.length()) { ints(sets.getJSONArray(it)) }, 0)
            }
        } + HistoryEntry(target, picks.map { it.numbers }, 0)
        val summary = JSONObject(File(repoRoot, "data/research_summary.json").readText())
        val mc = summary.getJSONObject("machine_chi_p")
        val ver = Verification(
            drawsAnalyzed = d.size, chiP = summary.getDouble("chi_p"),
            machineChiP = mc.keys().asSequence().associateWith { mc.getDouble(it) },
            strategiesTested = summary.getInt("strategies_tested"), maxAbsZ = summary.getDouble("max_abs_z"),
            significant = emptyList(), backtestBest = "미출현 기간", backtestBestZ = 0.28,
            adopted = "균등 무작위+필터", popularityP = m.validatedP, popularityGain = summary.getDouble("popularity_gain"),
        )
        val weekly = WeeklyPrediction(target, "", emptyList(), "균등 무작위+필터", null, null, null, null, ver, null)
        return UiState(
            loading = false, draws = d, model = m, stats = LottoEngine.stats(d), source = Source.NETWORK,
            targetDraw = target, targetDate = LocalDate.parse(d.last().date).plusDays(7).toString(),
            picks = picks, report = PersonalStore.report(hist, d), weekly = weekly,
        )
    }

    private fun shot(tab: Tab, name: String) {
        val s = state()
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Navy, secondary = Color(0xFFFBC400))) {
                AppScaffold(s, tab, {}, {}, {}, {}, {})
            }
        }
        out.mkdirs()
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun picks() = shot(Tab.PICKS, "01-picks")
    @Test fun report() = shot(Tab.REPORT, "02-report")
    @Test fun results() = shot(Tab.RESULTS, "03-results")
    @Test fun analysis() = shot(Tab.ANALYSIS, "04-analysis")
    @Test fun check() = shot(Tab.CHECK, "05-check")
}
