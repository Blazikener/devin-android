package com.blazikener.devin

import android.app.Application
import android.content.Context
import com.blazikener.devin.data.AuthStore
import com.blazikener.devin.data.Cache
import com.blazikener.devin.data.CreateSessionGate
import com.blazikener.devin.data.DevinApi
import com.blazikener.devin.data.SettingsStore
import com.blazikener.devin.notify.Notifications
import com.blazikener.devin.notify.SessionWatcher

class AppGraph(context: Context) {
    val auth = AuthStore(context)
    val settings = SettingsStore(context)
    val cache = Cache(context)
    val api = DevinApi(
        baseUrl = { auth.baseUrl },
        apiKey = { auth.account.value?.apiKey },
        orgId = { auth.account.value?.orgId },
    )
    internal val createGate = CreateSessionGate(settings.createAttempts, api::createSession)
}

class DevinApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        Notifications.createChannels(this)
        if (graph.auth.account.value != null) SessionWatcher.schedule(this, graph.settings.value.pollMinutes)
    }
}

val Context.graph: AppGraph get() = (applicationContext as DevinApp).graph
