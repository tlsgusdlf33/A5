package com.tlsgusdlf33.lottolab

import android.app.Activity
import android.util.DisplayMetrics
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AdMob 광고. 현재 광고 단위 ID 는 Google 공식 테스트 ID (app/build.gradle.kts).
 * 정책상 EEA/영국 사용자에게는 UMP 동의 화면을 먼저 띄운 뒤 광고를 초기화한다.
 */
object Ads {
    private val started = AtomicBoolean(false)
    private var interstitial: InterstitialAd? = null
    private var actions = 0
    private const val INTERSTITIAL_EVERY = 1

    fun start(activity: Activity, onReady: () -> Unit) {
        val consent = UserMessagingPlatform.getConsentInformation(activity)
        consent.requestConsentInfoUpdate(
            activity, ConsentRequestParameters.Builder().build(),
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                    if (consent.canRequestAds()) init(activity, onReady)
                }
            },
            { init(activity, onReady) },
        )
        if (consent.canRequestAds()) init(activity, onReady)
    }

    private fun init(activity: Activity, onReady: () -> Unit) {
        if (!started.compareAndSet(false, true)) return
        MobileAds.initialize(activity) {
            activity.runOnUiThread {
                onReady()
                loadInterstitial(activity)
            }
        }
    }

    private fun loadInterstitial(activity: Activity) {
        InterstitialAd.load(
            activity, BuildConfig.INTERSTITIAL_AD_UNIT, AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) { interstitial = ad }
                override fun onAdFailedToLoad(error: LoadAdError) { interstitial = null }
            },
        )
    }

    /** '새로 뽑기'·'확인하기'를 누를 때마다 전면 광고 (광고가 아직 로드되지 않았으면 건너뜀). */
    fun onUserAction(activity: Activity) {
        if (!started.get()) return
        actions++
        val ad = interstitial
        if (actions % INTERSTITIAL_EVERY == 0 && ad != null) {
            interstitial = null
            ad.show(activity)
            loadInterstitial(activity)
        }
    }
}

@Composable
fun BannerAd(activity: Activity, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { ctx ->
            val metrics: DisplayMetrics = ctx.resources.displayMetrics
            val widthDp = (metrics.widthPixels / metrics.density).toInt()
            AdView(ctx).apply {
                adUnitId = BuildConfig.BANNER_AD_UNIT
                setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, widthDp))
                loadAd(AdRequest.Builder().build())
            }
        },
    )
}
