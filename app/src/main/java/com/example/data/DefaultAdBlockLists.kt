package com.example.data

import com.example.model.AdBlockCategory
import com.example.model.AdBlockRule

object DefaultAdBlockLists {
  val builtinAdDomains =
    listOf(
      "doubleclick.net",
      "pagead2.googlesyndication.com",
      "googleadservices.com",
      "adservice.google.com",
      "ads.youtube.com",
      "admob.com",
      "ads.facebook.com",
      "an.facebook.com",
      "ads.tiktok.com",
      "ads-api.tiktok.com",
      "ads.twitter.com",
      "ads-twitter.com",
      "advertising.com",
      "adnxs.com",
      "rubiconproject.com",
      "pubmatic.com",
      "openx.net",
      "criteo.com",
      "outbrain.com",
      "taboola.com",
      "applovin.com",
      "unityads.unity3d.com",
      "vungle.com",
      "ironsrc.com",
      "inmobi.com",
      "smaato.net",
      "mopub.com",
      "chartboost.com",
      "startappservice.com",
      "tapjoy.com",
      "adcolony.com",
      "media.net",
      "mgid.com",
      "revcontent.com",
      "propellerads.com",
      "popads.net",
      "popcash.net",
      "adsterra.com",
      "exoclick.com",
      "juicyads.com",
      "yandexadexchange.net",
    )

  val builtinTrackerDomains =
    listOf(
      "google-analytics.com",
      "analytics.google.com",
      "app-measurement.com",
      "firebaselogging-pa.googleapis.com",
      "pixel.facebook.com",
      "connect.facebook.net",
      "analytics.tiktok.com",
      "log-va.tiktokv.com",
      "mixpanel.com",
      "api.mixpanel.com",
      "segment.io",
      "api.segment.io",
      "amplitude.com",
      "api.amplitude.com",
      "appsflyer.com",
      "adjust.com",
      "branch.io",
      "kochava.com",
      "singular.net",
      "flurry.com",
      "crashlytics.com",
      "sentry.io",
      "hotjar.com",
      "fullstory.com",
      "clarity.ms",
      "scorecardresearch.com",
      "quantserve.com",
      "omtrdc.net",
      "demdex.net",
      "bluekai.com",
      "krxd.net",
      "moatads.com",
      "doubleverify.com",
      "adsafeprotected.com",
    )

  val builtinMalwareDomains =
    listOf(
      "coinhive.com",
      "coin-hive.com",
      "authedmine.com",
      "cryptoloot.pro",
      "jsecoin.com",
      "minero.cc",
      "malwaredomainlist.com",
      "malware-check.disconnect.me",
      "phishing.army",
      "cryptominer.services",
      "webminepool.com",
      "browsermine.com",
    )

  fun buildDefaultRules(): List<AdBlockRule> = buildList {
    builtinAdDomains.forEach { d ->
      add(
        AdBlockRule(
          id = "builtin_ads_$d",
          domain = d,
          category = AdBlockCategory.ADS,
          isEnabled = true,
          source = "builtin",
        )
      )
    }
    builtinTrackerDomains.forEach { d ->
      add(
        AdBlockRule(
          id = "builtin_trk_$d",
          domain = d,
          category = AdBlockCategory.TRACKERS,
          isEnabled = true,
          source = "builtin",
        )
      )
    }
    builtinMalwareDomains.forEach { d ->
      add(
        AdBlockRule(
          id = "builtin_mal_$d",
          domain = d,
          category = AdBlockCategory.MALWARE,
          isEnabled = true,
          source = "builtin",
        )
      )
    }
  }

  // Reliable public hosts endpoints (StevenBlack unified hosts / AdAway) for manual list updates
  const val REMOTE_HOSTS_URL = "https://adaway.org/hosts.txt"
}
