package com.superessence.cuttinx.ads

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.superessence.cuttinx.BuildConfig
import com.unity3d.ads.IUnityAdsInitializationListener
import com.unity3d.ads.IUnityAdsLoadListener
import com.unity3d.ads.IUnityAdsShowListener
import com.unity3d.ads.UnityAds
import com.unity3d.ads.UnityAdsShowOptions
import com.unity3d.ads.metadata.MetaData
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-screen rewarded adverts that unlock the Labo CUTTINX technologies.
 *
 * The APK is distributed outside the big stores, where Google AdMob often has no advert to give,
 * so the networks are tried in a chain: AdMob, then Unity Ads (when a Game ID is built in), and
 * when nobody has an advert the caller is told with [showRewarded]'s onNoAd so the user is never
 * stuck. The consent form (RGPD) is shown first when the law requires it.
 */
object Ads {
    @Volatile private var started = false
    @Volatile private var unityReady = false

    private val unityEnabled get() = BuildConfig.UNITY_GAME_ID.isNotBlank()
    private fun consent(a: Activity): ConsentInformation = UserMessagingPlatform.getConsentInformation(a)

    /** Asks for consent when needed (once), then starts the advert SDKs. Call it from the activity. */
    fun init(activity: Activity) {
        if (com.superessence.cuttinx.core.smart.Plus.active) return   // CUTTINX Plus: no advert at all
        val ci = consent(activity)
        runCatching {
            ci.requestConsentInfoUpdate(activity, ConsentRequestParameters.Builder().build(), {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { start(activity, ci) }
            }, { start(activity, ci) })
        }
        start(activity, ci)
        unityInit(activity, ci) { }
    }

    private fun start(a: Activity, ci: ConsentInformation) {
        if (started || !ci.canRequestAds()) return
        started = true
        runCatching { MobileAds.initialize(a.applicationContext) {} }
    }

    /**
     * Loads and shows one rewarded advert. [onReward] only when it was watched to the end,
     * [onNoAd] when no network has an advert right now, [onError] when the user closed it early.
     * Exactly one callback runs, on the main thread.
     */
    fun showRewarded(activity: Activity, onReward: () -> Unit, onNoAd: () -> Unit, onError: (String) -> Unit) {
        val once = AtomicBoolean(false)
        val main = Handler(Looper.getMainLooper())
        fun finish(block: () -> Unit) { if (once.compareAndSet(false, true)) main.post(block) }
        val reward = { finish(onReward) }
        val noAd = { finish(onNoAd) }
        val early = { finish { onError("Pub fermée avant la fin : la technologie reste verrouillée.") } }
        // nothing may leave the dialog spinning forever (an advert already on screen is left alone)
        val showing = AtomicBoolean(false)
        val onShow = { showing.set(true) }
        main.postDelayed({ if (!showing.get()) noAd() }, 45_000)

        val ci = consent(activity)
        start(activity, ci)
        val fallback = { showing.set(false); unity(activity, ci, onShow, reward, noAd, early) }
        if (!ci.canRequestAds()) { fallback(); return }
        try {
            RewardedAd.load(activity, BuildConfig.ADMOB_REWARDED_ID, AdRequest.Builder().build(), object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    var earned = false
                    ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                        override fun onAdDismissedFullScreenContent() { if (earned) reward() else early() }
                        override fun onAdFailedToShowFullScreenContent(e: AdError) { fallback() }
                    }
                    onShow(); ad.show(activity) { earned = true }
                }
                override fun onAdFailedToLoad(e: LoadAdError) { fallback() }
            })
        } catch (e: Throwable) {
            fallback()
        }
    }

    private fun unityInit(ctx: Context, ci: ConsentInformation, then: (Boolean) -> Unit) {
        if (!unityEnabled) { then(false); return }
        if (unityReady || UnityAds.isInitialized) { unityReady = true; then(true); return }
        runCatching {
            // personalised adverts only with the user's consent
            MetaData(ctx.applicationContext).apply { set("gdpr.consent", ci.canRequestAds()); commit() }
            UnityAds.initialize(ctx.applicationContext, BuildConfig.UNITY_GAME_ID, false, object : IUnityAdsInitializationListener {
                override fun onInitializationComplete() { unityReady = true; then(true) }
                override fun onInitializationFailed(error: UnityAds.UnityAdsInitializationError?, message: String?) { then(false) }
            })
        }.onFailure { then(false) }
    }

    private fun unity(activity: Activity, ci: ConsentInformation, onShow: () -> Unit, onReward: () -> Unit, onNoAd: () -> Unit, onEarly: () -> Unit) {
        unityInit(activity, ci) { ok ->
            if (!ok) { onNoAd(); return@unityInit }
            val placement = BuildConfig.UNITY_REWARDED_ID
            runCatching {
                UnityAds.load(placement, object : IUnityAdsLoadListener {
                    override fun onUnityAdsAdLoaded(placementId: String?) {
                        onShow()
                        runCatching {
                            UnityAds.show(activity, placement, UnityAdsShowOptions(), object : IUnityAdsShowListener {
                                override fun onUnityAdsShowFailure(placementId: String?, error: UnityAds.UnityAdsShowError?, message: String?) { onNoAd() }
                                override fun onUnityAdsShowStart(placementId: String?) {}
                                override fun onUnityAdsShowClick(placementId: String?) {}
                                override fun onUnityAdsShowComplete(placementId: String?, state: UnityAds.UnityAdsShowCompletionState?) {
                                    if (state == UnityAds.UnityAdsShowCompletionState.COMPLETED) onReward() else onEarly()
                                }
                            })
                        }.onFailure { onNoAd() }
                    }
                    override fun onUnityAdsFailedToLoad(placementId: String?, error: UnityAds.UnityAdsLoadError?, message: String?) { onNoAd() }
                })
            }.onFailure { onNoAd() }
        }
    }
}
