package com.tlsgusdlf33.lottolab

/** 저장소 android/releases/latest.json 에 적힌 최신 버전 정보. */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val notes: String,
)
