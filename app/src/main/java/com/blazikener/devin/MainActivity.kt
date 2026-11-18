package com.blazikener.devin

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.blazikener.devin.notify.Notifications
import com.blazikener.devin.notify.SessionWatcher
import com.blazikener.devin.ui.create.CreateScreen
import com.blazikener.devin.ui.login.LoginScreen
import com.blazikener.devin.ui.session.SessionScreen
import com.blazikener.devin.ui.sessions.SessionsScreen
import com.blazikener.devin.ui.settings.SettingsScreen
import com.blazikener.devin.ui.theme.DevinTheme

sealed interface Launch {
    data class OpenSession(val id: String) : Launch
    data class NewSession(val prompt: String?) : Launch
}

class MainActivity : ComponentActivity() {
    private val pending = mutableStateOf<Launch?>(null)
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // On recreation (rotation, process death) the nav back stack already holds a handled launch;
        // one still waiting for sign-in is replayed.
        if (savedInstanceState?.getBoolean(LAUNCH_PENDING) != false) pending.value = parse(intent)
        setContent {
            val g = graph
            val settings by g.settings.settings.collectAsStateWithLifecycle()
            val account by g.auth.account.collectAsStateWithLifecycle()
            DevinTheme(settings.theme, settings.dynamicColor) {
                val nav = rememberNavController()
                val start = remember { if (account == null) "login" else "sessions" }
                val launch by pending
                LaunchedEffect(launch, account) {
                    val l = launch ?: return@LaunchedEffect
                    if (account == null) return@LaunchedEffect
                    pending.value = null
                    when (l) {
                        is Launch.OpenSession -> nav.navigate("session/${l.id}")
                        is Launch.NewSession -> nav.navigate("create?prompt=" + enc(l.prompt.orEmpty()))
                    }
                }
                NavHost(nav, startDestination = start) {
                    composable("login") {
                        LoginScreen(onSignedIn = {
                            SessionWatcher.schedule(this@MainActivity, settings.pollMinutes)
                            askNotifications()
                            nav.navigate("sessions") { popUpTo("login") { inclusive = true } }
                        })
                    }
                    composable("sessions") {
                        LaunchedEffect(Unit) { askNotifications() }
                        SessionsScreen(
                            onOpen = { nav.navigate("session/$it") },
                            onNew = { nav.navigate("create?prompt=") },
                            onSettings = { nav.navigate("settings") },
                        )
                    }
                    composable("session/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { e ->
                        val id = e.arguments!!.getString("id")!!
                        SessionScreen(id, onBack = { nav.back() }, onOpenSession = { nav.navigate("session/$it") })
                    }
                    composable("create?prompt={prompt}", arguments = listOf(navArgument("prompt") { type = NavType.StringType; defaultValue = "" })) { e ->
                        val p = e.arguments?.getString("prompt")?.ifEmpty { null }
                        CreateScreen(p, onBack = { nav.back() }, onCreated = { id ->
                            nav.navigate("session/$id") { popUpTo("sessions") }
                        })
                    }
                    composable("settings") {
                        SettingsScreen(onBack = { nav.back() }, onSignedOut = {
                            nav.navigate("login") { popUpTo(0) { inclusive = true } }
                        })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        parse(intent)?.let { pending.value = it }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(LAUNCH_PENDING, pending.value != null)
    }

    private fun NavHostController.back() { if (!popBackStack()) navigate("sessions") }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !Notifications.canPost(this)) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        private fun enc(s: String) = android.net.Uri.encode(s)
        private val sessionUrl = Regex("""/sessions/([0-9a-fA-F]{32}|devin-[0-9a-fA-F]{32})""")

        fun parse(intent: Intent?): Launch? {
            intent ?: return null
            intent.getStringExtra(Notifications.EXTRA_SESSION_ID)?.let { return Launch.OpenSession(normalizeId(it)) }
            return when (intent.action) {
                Intent.ACTION_VIEW -> intent.data?.toString()?.let { sessionUrl.find(it) }?.groupValues?.get(1)?.let { Launch.OpenSession(normalizeId(it)) }
                Intent.ACTION_SEND -> Launch.NewSession(
                    listOfNotNull(intent.getStringExtra(Intent.EXTRA_SUBJECT), intent.getStringExtra(Intent.EXTRA_TEXT)).joinToString("\n\n").ifBlank { null }
                )
                ACTION_NEW_SESSION -> Launch.NewSession(null)
                else -> null
            }
        }
        /** The v3 API addresses sessions as `devin-<hex>`; web URLs use the bare hex. */
        fun normalizeId(id: String) = if (id.startsWith("devin-")) id else "devin-$id"
        private const val LAUNCH_PENDING = "launch_pending"
const val ACTION_NEW_SESSION = "com.blazikener.devin.NEW_SESSION"
    }
}
