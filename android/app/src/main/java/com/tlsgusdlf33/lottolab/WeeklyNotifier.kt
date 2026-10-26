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
        val w = repo.weekly(target) ?: return Result.success()  // 아직 안 올라옴 → 다음 확인 때
        WeeklyNotifier.show(applicationContext, w)
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

    fun show(context: Context, w: WeeklyPrediction) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "주간 추천 번호", NotificationManager.IMPORTANCE_DEFAULT))
        val fmt = { s: List<Int> -> s.joinToString(" ") { "%02d".format(it) } }
        val review = w.review?.let { r ->
            val best = r.sets.minOfOrNull { if (it.rank == 0) 9 else it.rank } ?: 9
            ", 지난주 최고 ${r.bestHits}개 일치" + if (best < 9) "(${best}등)" else ""
        } ?: ""
        val title = "제${w.draw}회 추천 번호가 나왔어요"
        val text = "A ${fmt(w.sets[0])} / B ${fmt(w.sets.getOrElse(1) { emptyList() })}$review"
        val pi = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_lotto)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                w.sets.mapIndexed { i, s -> "${'A' + i}  ${fmt(s)}" }.joinToString("\n") + "\n" + review.removePrefix(", ")
            ))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(w.draw, n) }
    }
}
