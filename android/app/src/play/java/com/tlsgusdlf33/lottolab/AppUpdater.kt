package com.tlsgusdlf33.lottolab

import android.app.Activity

/** Play 빌드: 업데이트는 Google Play 가 담당하므로 자체 업데이트 기능 없음. */
object AppUpdater {
    const val ENABLED = false

    suspend fun check(): UpdateInfo? = null

    suspend fun install(activity: Activity, info: UpdateInfo, onProgress: (Float) -> Unit): String? = null
}
