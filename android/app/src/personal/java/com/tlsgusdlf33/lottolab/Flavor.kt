package com.tlsgusdlf33.lottolab

import android.content.Context

/** 개인 전용 앱: 개인 영역(isReserved) 안에서 후보 5만 개·세트 간 겹침 0개로 고른다. */
object Flavor {
    const val NOTIFY_LABEL = "개인 추천 번호가 나왔어요"
    val launchActivity = OwnerActivity::class.java

    fun picksFor(ctx: Context, draw: Int, draws: List<Draw>, model: PopularityModel, publicSets: List<List<Int>>) =
        OwnerStore(ctx).picksFor(draw, draws, model, publicSets)

    fun history(ctx: Context) = OwnerStore(ctx).history()
}
