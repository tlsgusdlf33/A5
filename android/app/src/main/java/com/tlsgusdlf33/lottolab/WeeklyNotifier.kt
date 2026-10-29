package com.tlsgusdlf33.lottolab

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * 주간 추천 알림 — Claude 루틴 대신 앱이 직접 띄운다.
 * 몇 시간마다 가볍게 확인해, GitHub Actions 가 새 회차 추천(predictions/<회차>.json)을
 * 올렸으면 회차당 한 번만 알림을 보낸다. (월요일 08:00 KST 생성 → 보통 월요일 오전 중 도착)
 */
class WeeklyCheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val repo = DataRepository(applicationContext)
        val latest = repo.latestDrawNo() ?: return Result.retry()
        val target = latest + 1
        val prefs = applicationContext.getSharedPreferences("notify", Context.MODE_PRIVATE)
        if (prefs.getInt("last_notified", 0) >= target) return Result.success()
        // 주간 재학습(월요일 08:00 GitHub Actions)이 올라온 뒤 그 모델로 내 번호를 만든다.
        val w = repo.weekly(target) ?: return Result.success()  // 아직 안 올라옴 → 다음 확인 때
        val draws = repo.draws().value
        if (draws.last().no + 1 != target) return Result.retry()
        val sets = Flavor.picksFor(applicationContext, target, draws, repo.model().value, w.sets)
        val last = PersonalStore.report(Flavor.history(applicationContext), draws).entries
            .firstOrNull { it.done && it.entry.draw == latest }
        WeeklyNotifier.show(applicationContext, target, sets, last)
        prefs.edit().putInt("last_notified", target).apply()
        return Result.success()
    }
}

object WeeklyNotifier {
    private const val CHANNEL = "weekly_picks"

    fun schedule(context: Context) {
        val req = PeriodicWorkRequestBuilder<WeeklyCheckWorker>(4, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork("weekly-check", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun show(context: Context, draw: Int, sets: List<List<Int>>, last: GradedEntry?) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "주간 추천 번호", NotificationManager.IMPORTANCE_HIGH))
        val fmt = { s: List<Int> -> s.joinToString(" ") { "%02d".format(it) } }
        val review = last?.let { g ->
            val best = g.rows.filter { it.rank > 0 }.minOfOrNull { it.rank }
            ", 지난주 최고 ${g.bestHits}개 일치" + (best?.let { "(${it}등)" } ?: "")
        } ?: ""
        val title = "제${draw}회 ${Flavor.NOTIFY_LABEL}"
        val text = "A ${fmt(sets[0])} / B ${fmt(sets.getOrElse(1) { emptyList() })}$review"
        val pi = PendingIntent.getActivity(
            context, 0, Intent(context, Flavor.launchActivity).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_lotto)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                sets.mapIndexed { i, s -> "${'A' + i}  ${fmt(s)}" }.joinToString("\n") + "\n" + review.removePrefix(", ")
            ))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(draw, n) }
    }
}
