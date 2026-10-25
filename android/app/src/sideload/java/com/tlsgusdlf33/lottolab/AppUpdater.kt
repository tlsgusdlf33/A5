package com.tlsgusdlf33.lottolab

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 직접 설치(sideload) 빌드의 앱 내 업데이트.
 * 저장소의 android/releases/latest.json 을 확인해 versionCode 가 더 높으면 APK 를 받아
 * SHA-256 을 검증한 뒤 시스템 설치 화면을 띄운다. 같은 서명 키로 서명된 APK 라서
 * 기존 앱 위에 데이터 그대로 업데이트된다.
 */
object AppUpdater {
    const val ENABLED = true

    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val text = get(BuildConfig.DATA_BASE_URL + "android/releases/latest.json")
                .bufferedReader().use { it.readText() }
            val o = JSONObject(text)
            UpdateInfo(
                versionCode = o.getInt("versionCode"),
                versionName = o.getString("versionName"),
                apkUrl = o.getString("apkUrl").let { if (it.startsWith("http")) it else BuildConfig.DATA_BASE_URL + it },
                sha256 = o.getString("sha256").lowercase(),
                notes = o.optString("notes"),
            ).takeIf { it.versionCode > BuildConfig.VERSION_CODE }
        }.getOrNull()
    }

    /** 성공하면 null, 실패하면 사용자에게 보여줄 오류 문구. */
    suspend fun install(activity: Activity, info: UpdateInfo, onProgress: (Float) -> Unit): String? {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            // 최초 1회: '이 출처의 앱 설치 허용' 설정 화면으로 보낸다.
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            )
            return "설정에서 '이 출처 허용'을 켠 뒤 다시 [업데이트]를 눌러 주세요."
        }
        val file = withContext(Dispatchers.IO) {
            runCatching { download(activity, info, onProgress) }.getOrNull()
        } ?: return "다운로드에 실패했습니다. 인터넷 연결을 확인해 주세요."
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", file)
        activity.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return null
    }

    private fun get(url: String) = (URL(url).openConnection() as HttpURLConnection).run {
        connectTimeout = 10_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", "LottoLab-Android")
        if (responseCode != 200) error("HTTP $responseCode")
        inputStream
    }

    private fun download(activity: Activity, info: UpdateInfo, onProgress: (Float) -> Unit): File? {
        val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "LottoLab-${info.versionName}.apk")
        val conn = URL(info.apkUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        val total = conn.contentLengthLong
        val md = MessageDigest.getInstance("SHA-256")
        conn.inputStream.use { input ->
            out.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    md.update(buf, 0, n)
                    done += n
                    if (total > 0) onProgress(done.toFloat() / total)
                }
            }
        }
        conn.disconnect()
        val hash = md.digest().joinToString("") { "%02x".format(it) }
        if (hash != info.sha256) {
            out.delete()
            return null  // 변조·손상된 파일은 설치하지 않는다
        }
        return out
    }
}
