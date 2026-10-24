package com.victor.traillink

import android.app.Application
import com.victor.traillink.data.Prefs
import com.victor.traillink.service.LinkService
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil

class TrailLinkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.load(this)
        com.victor.traillink.data.RemovedPartners.init(this)
        LinkService.createChannels(this)
        com.victor.traillink.call.CallManager.init(this)
        MapLibre.getInstance(this)
        com.victor.traillink.map.world.WorldMaps.init(this)
        // Be polite to the public USGS tile server: few parallel requests and an honest User-Agent.
        val dispatcher = Dispatcher().apply { maxRequests = 6; maxRequestsPerHost = 4 }
        HttpRequestUtil.setOkHttpClient(OkHttpClient.Builder().dispatcher(dispatcher).addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder()
                .header("User-Agent", com.victor.traillink.map.world.WorldSources.userAgent(BuildConfig.VERSION_NAME)).build())
        }.build())
    }
}
