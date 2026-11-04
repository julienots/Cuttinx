package com.superessence.cuttinx.ads

import android.app.Activity
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

/**
 * Full-screen rewarded adverts (Google AdMob) that unlock the Labo CUTTINX technologies.
 * The consent form (RGPD) is shown first when the law requires it. The ad unit comes from the
 * build (gradle property cuttinx.admobRewardedId); Google's test unit is used by default.
 */
object Ads {
    @Volatile private var started = false

    private fun consent(a: Activity): ConsentInformation = UserMessagingPlatform.getConsentInformation(a)

    /** Asks for consent when needed (once), then starts the advert SDK. Call it from the activity. */
    fun init(activity: Activity) {
        val ci = consent(activity)
        runCatching {
            ci.requestConsentInfoUpdate(activity, ConsentRequestParameters.Builder().build(), {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { start(activity, ci) }
            }, { start(activity, ci) })
        }
        start(activity, ci)
    }

    private fun start(a: Activity, ci: ConsentInformation) {
        if (started || !ci.canRequestAds()) return
        started = true
        runCatching { MobileAds.initialize(a.applicationContext) {} }
    }

    /** Loads and shows one rewarded advert. [onReward] only when it was watched to the end. */
    fun showRewarded(activity: Activity, onReward: () -> Unit, onError: (String) -> Unit) {
        val ci = consent(activity)
        start(activity, ci)
        if (!ci.canRequestAds()) {
            onError("Les pubs ne sont pas autorisées sur cet appareil : accepte-les dans la fenêtre de confidentialité, puis réessaie.")
            runCatching { UserMessagingPlatform.showPrivacyOptionsForm(activity) { } }
            return
        }
        try {
            RewardedAd.load(activity, BuildConfig.ADMOB_REWARDED_ID, AdRequest.Builder().build(), object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    var earned = false
                    ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                        override fun onAdDismissedFullScreenContent() {
                            if (earned) onReward() else onError("Pub fermée avant la fin : la technologie reste verrouillée.")
                        }
                        override fun onAdFailedToShowFullScreenContent(e: AdError) { onError("La pub n'a pas pu s'afficher. Réessaie.") }
                    }
                    ad.show(activity) { earned = true }
                }
                override fun onAdFailedToLoad(e: LoadAdError) { onError("Aucune pub disponible pour l'instant (connexion internet ?). Réessaie dans un moment.") }
            })
        } catch (e: Throwable) {
            onError("Les pubs ne sont pas disponibles sur cet appareil.")
        }
    }
}
