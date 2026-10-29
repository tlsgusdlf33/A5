package com.tlsgusdlf33.lottolab

import android.content.Context

/** 일반 사용자용 앱(sideload·play): 기기별 번호, 개인 전용 영역 조합은 쓰지 않음. */
object Flavor {
    const val NOTIFY_LABEL = "내 추천 번호가 나왔어요"
    val launchActivity = MainActivity::class.java

    fun picksFor(ctx: Context, draw: Int, draws: List<Draw>, model: PopularityModel, publicSets: List<List<Int>>) =
        PersonalStore(ctx).picksFor(draw, draws, model)

    fun history(ctx: Context) = PersonalStore(ctx).history()
}
