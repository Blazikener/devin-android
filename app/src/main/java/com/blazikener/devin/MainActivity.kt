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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.blazikener.devin.notify.Notifications
import com.blazikener.devin.notify.SessionWatcher
import com.blazikener.devin.data.SessionFilter
import com.blazikener.devin.ui.create.CreateScreen
import com.blazikener.devin.ui.home.HomeScreen
import com.blazikener.devin.ui.login.LoginScreen
import com.blazikener.devin.ui.session.SessionScreen
import com.blazikener.devin.ui.sessions.SessionsScreen
import com.blazikener.devin.ui.settings.SettingsScreen
import com.blazikener.devin.ui.theme.DevinTheme
import com.blazikener.devin.ui.navigation.Routes

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
                val start = remember { if (account == null) "login" else "home" }
                val launch by pending
                LaunchedEffect(launch, account) {
                    val l = launch ?: return@LaunchedEffect
                    if (account == null) return@LaunchedEffect
                    pending.value = null
                    // Cold launches get a Home parent. Warm launches preserve the exact previous route.
                    if (nav.currentDestination?.route == Routes.Login) {
                        nav.navigate(Routes.Home) { popUpTo(Routes.Login) { inclusive = true } }
                    }
                    val alreadyOpen = l is Launch.OpenSession &&
                        nav.currentDestination?.route == Routes.Session &&
                        nav.currentBackStackEntry?.arguments?.getString("id") == l.id
                    if (alreadyOpen) return@LaunchedEffect
                    val route = when (l) {
                        is Launch.OpenSession -> Routes.session(l.id)
                        is Launch.NewSession -> Routes.create(l.prompt.orEmpty())
                    }
                    // Destination identity alone is not session identity: singleTop would replace
                    // Chat A with Chat B, or reuse an existing Create form and ignore shared text.
                    nav.navigate(route)
                }
                NavHost(
                    nav, startDestination = start,
                    enterTransition = { fadeIn(tween(220)) + slideInHorizontally(tween(280)) { it / 12 } },
                    exitTransition = { fadeOut(tween(160)) },
                    popEnterTransition = { fadeIn(tween(220)) },
                    popExitTransition = { fadeOut(tween(180)) + slideOutHorizontally(tween(240)) { it / 12 } },
                ) {
                    composable("login") {
                        LoginScreen(onSignedIn = {
                            SessionWatcher.schedule(this@MainActivity, settings.pollMinutes)
                            askNotifications()
                            nav.navigate("home") { popUpTo("login") { inclusive = true } }
                        })
                    }
                    composable("home") {
                        LaunchedEffect(Unit) { askNotifications() }
                        HomeScreen(
                            onOpen = { nav.navigate(Routes.session(it)) },
                            onSessions = { filter, search ->
                                nav.navigateRoot(Routes.sessions(filter, search))
                            },
                            onNewForm = { nav.navigate(Routes.create(it)) },
                            onSettings = { nav.navigate("settings") },
                        )
                    }
                    composable(
                        Routes.Sessions,
                        arguments = listOf(
                            navArgument("filter") { type = NavType.StringType; defaultValue = "" },
                            navArgument("search") { type = NavType.BoolType; defaultValue = false },
                        ),
                    ) { e ->
                        val initialFilter = e.arguments?.getString("filter").orEmpty()
                            .takeIf(String::isNotEmpty)?.let { value -> SessionFilter.entries.firstOrNull { it.name == value } }
                        val initialSearch = e.arguments?.getBoolean("search") ?: false
                        SessionsScreen(
                            onOpen = { nav.navigate(Routes.session(it)) },
                            onNew = { nav.navigate(Routes.create()) },
                            onSettings = { nav.navigate("settings") },
                            onBack = { nav.back() },
                            initialFilter = initialFilter,
                            initialSearch = initialSearch,
                            onHome = { nav.navigateRoot(Routes.Home) },
                        )
                    }
                    composable(Routes.Session, arguments = listOf(navArgument("id") { type = NavType.StringType })) { e ->
                        val id = e.arguments!!.getString("id")!!
                        SessionScreen(id, onBack = { nav.back() }, onOpenSession = { nav.navigate(Routes.session(it)) })
                    }
                    composable(Routes.Create, arguments = listOf(navArgument("prompt") { type = NavType.StringType; defaultValue = "" })) { e ->
                        val p = e.arguments?.getString("prompt")?.ifEmpty { null }
                        CreateScreen(p, onBack = { nav.back() }, onCreated = { id ->
                            nav.navigate(Routes.session(id)) { popUpTo(Routes.Create) { inclusive = true } }
                        })
                    }
                    composable("settings") {
                        SettingsScreen(onBack = { nav.back() }, onSignedOut = {
                            // popUpTo only clears active entries, not saveState root snapshots/VMs.
                            nav.clearBackStack(Routes.Sessions)
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

    private fun NavHostController.navigateRoot(route: String) {
        navigate(route) {
            popUpTo(Routes.Home) { saveState = true }
            launchSingleTop = true
            // An explicit shortcut must apply its filter/search instead of restoring stale args.
            restoreState = route == Routes.Home || route == Routes.sessions()
        }
    }

    private fun NavHostController.back() { if (!popBackStack()) navigate("home") }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !Notifications.canPost(this)) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
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
