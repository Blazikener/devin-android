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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.blazikener.devin.data.CreateAttempt
import com.blazikener.devin.ui.create.CreateScreen
import com.blazikener.devin.ui.home.HomeScreen
import com.blazikener.devin.ui.login.LoginScreen
import com.blazikener.devin.ui.session.SessionScreen
import com.blazikener.devin.ui.sessions.SessionsScreen
import com.blazikener.devin.ui.settings.SettingsScreen
import com.blazikener.devin.ui.theme.DevinTheme
import com.blazikener.devin.ui.navigation.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed interface Launch {
    data class OpenSession(val id: String) : Launch
    data class NewSession(val prompt: String?) : Launch
}

internal fun initialLaunch(launchPending: Boolean?, decode: () -> Launch?): Launch? =
    if (launchPending == false) null else decode()

internal fun savedSessionOnLaunch(launch: Launch?, destination: String?, attempts: List<CreateAttempt>): String? =
    if (launch == null && destination == Routes.Home) attempts.firstNotNullOfOrNull { it.sessionId } else null

class MainActivity : ComponentActivity() {
    private val pending = mutableStateOf<Launch?>(null)
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // On recreation (rotation, process death) the nav back stack already holds a handled launch;
        // one still waiting for sign-in is replayed.
        pending.value = initialLaunch(savedInstanceState?.getBoolean(LAUNCH_PENDING)) { parse(intent) }
        setContent {
            val g = graph
            val settings by g.settings.settings.collectAsStateWithLifecycle()
            val account by g.auth.account.collectAsStateWithLifecycle()
            DevinTheme(settings.theme, settings.dynamicColor) {
                val nav = rememberNavController()
                val scope = rememberCoroutineScope()
                val start = remember { if (account == null) "login" else "home" }
                val launch by pending
                val checkedRecovery = remember(account) { mutableStateOf(false) }
                LaunchedEffect(launch, account) {
                    if (account == null) return@LaunchedEffect
                    val destination = nav.currentBackStackEntryFlow.first { it.destination.route != Routes.Login }.destination.route
                    if (!checkedRecovery.value) {
                        checkedRecovery.value = true
                        savedSessionOnLaunch(launch, destination, g.createGate.attempts.value)?.let { id ->
                            nav.openSessionFromLaunch(id)
                            g.createGate.acknowledgeOpened(id)
                        }
                    }
                    val l = launch ?: return@LaunchedEffect
                    // Cold launches get a Home parent. Warm launches preserve the exact previous route.
                    if (nav.currentDestination?.route == Routes.Login) {
                        nav.navigate(Routes.Home) { popUpTo(Routes.Login) { inclusive = true } }
                    }
                    when (l) {
                        is Launch.OpenSession -> nav.openSessionFromLaunch(l.id)
                        is Launch.NewSession -> {
                            g.createGate.discardCompletedForIncomingPrompt(l.prompt)
                            nav.openCreateFromLaunch(l.prompt)
                        }
                    }
                    pending.value = null
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
                                nav.showSessionsRoot(filter, search)
                            },
                            onNewForm = { prompt, mode ->
                                scope.launch {
                                    g.createGate.discardCompletedForIncomingPrompt(prompt)
                                    nav.navigate(Routes.create(prompt, mode))
                                }
                            },
                            onSettings = { nav.navigate("settings") },
                            onCheckSessions = { nav.showSessionsFromCreateRecovery() },
                        )
                    }
                    composable(
                        Routes.Sessions,
                        arguments = listOf(
                            navArgument("filter") { type = NavType.StringType; defaultValue = "" },
                            navArgument("search") { type = NavType.BoolType; defaultValue = false },
                        ),
                    ) { e ->
                        val encodedRequest by e.savedStateHandle.getStateFlow<String?>(
                            SessionNavigationState.SessionsRequest, null,
                        ).collectAsStateWithLifecycle()
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
                            onHome = { nav.showHomeRoot() },
                            navigationRequest = SessionsRequest.decode(encodedRequest),
                            onNavigationRequestHandled = { e.savedStateHandle[SessionNavigationState.SessionsRequest] = null },
                        )
                    }
                    composable(Routes.Session, arguments = listOf(navArgument("id") { type = NavType.StringType })) { e ->
                        val id = e.arguments!!.getString("id")!!
                        SessionScreen(id, onBack = { nav.back() }, onOpenSession = { nav.navigate(Routes.session(it)) })
                    }
                    composable(Routes.Create, arguments = listOf(
                        navArgument("prompt") { type = NavType.StringType; defaultValue = "" },
                        navArgument("mode") { type = NavType.StringType; nullable = true; defaultValue = null },
                    )) { e ->
                        val p = e.arguments?.getString("prompt")?.ifEmpty { null }
                        CreateScreen(p, modeOverride = e.arguments?.getString("mode"), onBack = { nav.back() }, onCheckSessions = { nav.showSessionsFromCreateRecovery() }, onCreated = { id ->
                            nav.navigate(Routes.session(id)) { popUpTo(Routes.Create) { inclusive = true } }
                        })
                    }
                    composable("settings") {
                        SettingsScreen(onBack = { nav.back() }, onSignedOut = {
                            g.applicationScope.launch { g.createGate.forgetAll() }
                            nav.clearProtectedNavigation()
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
