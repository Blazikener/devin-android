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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppGraph(context: Context) {
    private val appContext = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var signOutJob: Job? = null
    val auth = AuthStore(context)
    val settings = SettingsStore(context)
    val cache = Cache(context)
    val api = DevinApi(
        baseUrl = { auth.baseUrl },
        apiKey = { auth.account.value?.apiKey },
        orgId = { auth.account.value?.orgId },
    )
    internal val createGate = CreateSessionGate(settings.createAttempts, api::createSession)

    internal fun signOut() {
        if (signOutJob?.isActive == true) return
        signOutJob = appScope.launch {
            createGate.forgetAll()
            SessionWatcher.cancel(appContext)
            cache.clear()
            settings.clearDrafts()
            appContext.getSharedPreferences("watcher", Context.MODE_PRIVATE).edit().clear().apply()
            auth.clear()
        }
    }
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
