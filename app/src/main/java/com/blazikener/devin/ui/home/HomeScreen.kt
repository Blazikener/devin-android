package com.blazikener.devin.ui.home

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.blazikener.devin.data.DEVIN_MODES
import com.blazikener.devin.data.CreateSessionRequest
import com.blazikener.devin.data.Session
import com.blazikener.devin.data.SessionFilter
import com.blazikener.devin.graph
import com.blazikener.devin.notify.SessionWatcher
import com.blazikener.devin.ui.components.DevinOrb
import com.blazikener.devin.ui.components.StatusDot
import com.blazikener.devin.ui.components.rememberReducedMotion
import com.blazikener.devin.ui.sessions.SessionsViewModel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpen: (String) -> Unit,
    onSessions: (filter: SessionFilter?, search: Boolean) -> Unit,
    onNewForm: (prompt: String) -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    val g = context.graph
    val vm: SessionsViewModel = viewModel {
        val app = context.applicationContext
        SessionsViewModel(g) { SessionWatcher.markSeen(app, it) }
    }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val account by g.auth.account.collectAsStateWithLifecycle()
    val settings by g.settings.settings.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var prompt by rememberSaveable { mutableStateOf("") }
    var chosenMode by rememberSaveable { mutableStateOf<String?>(null) }
    var modeChosen by rememberSaveable { mutableStateOf(false) }
    val mode = if (modeChosen) chosenMode else settings.defaultMode
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showModePicker by remember { mutableStateOf(false) }
    var greetingVisible by remember { mutableStateOf(false) }
    var statusVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                vm.refresh()
                val prefs = context.getSharedPreferences("watcher", Context.MODE_PRIVATE)
                SessionWatcher.check(context, vm.ui.value.all, prefs, Triple(false, false, false))
                delay(if (vm.ui.value.workingCount > 0) 10_000 else 30_000)
            }
        }
    }
    LaunchedEffect(Unit) {
        greetingVisible = true
        delay(520)
        statusVisible = true
    }

    fun send() {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty() || busy) return
        busy = true
        error = null
        scope.launch {
            runCatching {
                g.api.createSession(
                    CreateSessionRequest(
                        prompt = trimmed,
                        maxAcuLimit = settings.defaultAcuLimit?.takeIf { it > 0 },
                        createAsUserId = settings.createAsUserId,
                        devinMode = mode,
                    ),
                )
            }.onSuccess { session ->
                prompt = ""
                modeChosen = false
                busy = false
                onOpen(session.id)
            }.onFailure { failure ->
                error = failure.message ?: "Couldn't start the session"
                busy = false
            }
        }
    }

    fun navigateFromDrawer(action: () -> Unit) {
        scope.launch {
            drawerState.close()
            action()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            HomeDrawer(
                accountName = account?.name,
                ui = ui,
                openTarget = drawerState.targetValue == DrawerValue.Open,
                onSearch = { navigateFromDrawer { onSessions(null, true) } },
                onAllSessions = { navigateFromDrawer { onSessions(null, false) } },
                onNeedsYou = { navigateFromDrawer { onSessions(SessionFilter.NeedsYou, false) } },
                onSettings = { navigateFromDrawer(onSettings) },
                onOpen = { id -> navigateFromDrawer { onOpen(id) } },
                onViewAll = { navigateFromDrawer { onSessions(null, false) } },
                onNewSession = { navigateFromDrawer { onNewForm("") } },
            )
        },
    ) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                    Icon(Icons.Outlined.Menu, contentDescription = "Open menu")
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onSettings) {
                    Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                }
            }
            Box(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val firstName = account?.takeIf { it.userId != null && it.name != "Devin user" }?.name
                    val greeting = greeting(java.time.LocalTime.now().hour, firstName)
                    val entranceOffset = with(LocalDensity.current) { 12.dp.roundToPx() }
                    AnimatedVisibility(
                        visible = greetingVisible,
                        enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { entranceOffset },
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            HomeOrb(busy = busy)
                            Spacer(Modifier.height(22.dp))
                            Text(
                                greeting,
                                style = MaterialTheme.typography.headlineMedium,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    AnimatedVisibility(visible = statusVisible, enter = fadeIn(tween(220))) {
                        val needsYou = ui.needsYouCount
                        val working = ui.workingCount
                        Row(
                            Modifier.clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .clickable {
                                    scope.launch {
                                        drawerState.close()
                                        onSessions(if (needsYou > 0) SessionFilter.NeedsYou else null, false)
                                    }
                                }
                                .padding(horizontal = 18.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (needsYou == 0 && working == 0) "All caught up"
                                else "$needsYou need you · $working working",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
            Column(
                Modifier.fillMaxWidth().imePadding().navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                    )
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        BasicTextField(
                            value = prompt,
                            onValueChange = { prompt = it; error = null },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { send() }),
                            minLines = 1,
                            maxLines = 6,
                            decorationBox = { inner ->
                                Box {
                                    if (prompt.isEmpty()) {
                                        Text(
                                            "What should Devin build?",
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    inner()
                                }
                            },
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    val text = prompt
                                    prompt = ""
                                    onNewForm(text)
                                },
                                modifier = Modifier.size(38.dp).background(MaterialTheme.colorScheme.surfaceContainerLow, CircleShape),
                            ) {
                                Icon(Icons.Outlined.Add, contentDescription = "More options")
                            }
                            Spacer(Modifier.width(8.dp))
                            val modeLabel = DEVIN_MODES.firstOrNull { it.first == mode }?.second ?: "Default"
                            Row(
                                Modifier.clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                    .clickable(role = Role.Button, onClickLabel = "Change mode") { showModePicker = true }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(modeLabel, style = MaterialTheme.typography.labelLarge)
                            }
                            Spacer(Modifier.weight(1f))
                            val enabled = prompt.isNotBlank() && !busy
                            val enabledScale by animateFloatAsState(
                                targetValue = if (enabled) 1f else 0.85f,
                                animationSpec = spring(dampingRatio = 0.72f, stiffness = 520f),
                                label = "send enabled scale",
                            )
                            val interaction = remember { MutableInteractionSource() }
                            val pressed by interaction.collectIsPressedAsState()
                            val pressScale by animateFloatAsState(
                                targetValue = if (pressed) 0.92f else 1f,
                                animationSpec = spring(dampingRatio = 0.62f, stiffness = 650f),
                                label = "send press scale",
                            )
                            IconButton(
                                onClick = ::send,
                                enabled = enabled,
                                interactionSource = interaction,
                                modifier = Modifier.size(42.dp).graphicsLayer {
                                    scaleX = enabledScale * pressScale
                                    scaleY = enabledScale * pressScale
                                }.background(
                                    if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                                    CircleShape,
                                ),
                            ) {
                                AnimatedContent(targetState = busy, label = "send busy") { sending ->
                                    if (sending) {
                                        androidx.compose.material3.CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            color = MaterialTheme.colorScheme.surface,
                                            strokeWidth = 2.dp,
                                        )
                                    } else {
                                        Icon(
                                            Icons.Filled.ArrowUpward,
                                            contentDescription = "Send",
                                            tint = MaterialTheme.colorScheme.surface,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showModePicker) {
        ModalBottomSheet(onDismissRequest = { showModePicker = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                Text("Select mode", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                DEVIN_MODES.forEach { (value, label) ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            chosenMode = value
                            modeChosen = true
                            showModePicker = false
                        }.padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.weight(1f))
                        if (mode == value) Icon(Icons.Outlined.Check, contentDescription = "Selected")
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeDrawer(
    accountName: String?,
    ui: com.blazikener.devin.ui.sessions.SessionsUi,
    openTarget: Boolean,
    onSearch: () -> Unit,
    onAllSessions: () -> Unit,
    onNeedsYou: () -> Unit,
    onSettings: () -> Unit,
    onOpen: (String) -> Unit,
    onViewAll: () -> Unit,
    onNewSession: () -> Unit,
) {
    ModalDrawerSheet {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
        ) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, top = 20.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Devin",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onSearch) {
                        Icon(Icons.Outlined.Search, contentDescription = "Search sessions")
                    }
                }
                DrawerItem(0, openTarget) {
                    NavigationDrawerItem(
                        label = { Text("All sessions") },
                        selected = false,
                        onClick = onAllSessions,
                    )
                }
                DrawerItem(1, openTarget) {
                    NavigationDrawerItem(
                        label = { Text("Needs you") },
                        selected = false,
                        onClick = onNeedsYou,
                        badge = if (ui.needsYouCount > 0) {
                            { Badge { Text("${ui.needsYouCount}") } }
                        } else null,
                    )
                }
                DrawerItem(2, openTarget) {
                    NavigationDrawerItem(
                        label = { Text("Settings") },
                        selected = false,
                        onClick = onSettings,
                    )
                }
                androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text(
                    "Recents",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
                )
                ui.all.sortedByDescending(Session::updatedAt).take(8).forEachIndexed { index, session ->
                    DrawerItem(index + 3, openTarget) {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .clickable { onOpen(session.id) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            StatusDot(session.state)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                session.displayTitle,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                DrawerItem(11, openTarget) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = onViewAll)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("View all ›", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            androidx.compose.material3.HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(38.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(accountName?.trim()?.firstOrNull()?.uppercase() ?: "D", fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(10.dp))
                Text(accountName?.takeIf(String::isNotBlank) ?: "Devin", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(
                    Modifier.clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurface)
                        .clickable(onClick = onNewSession)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, tint = MaterialTheme.colorScheme.surface, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("New session", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.surface)
                }
            }
        }
    }
}

@Composable
private fun DrawerItem(index: Int, openTarget: Boolean, content: @Composable () -> Unit) {
    val alpha by animateFloatAsState(
        targetValue = if (openTarget) 1f else 0f,
        animationSpec = tween(durationMillis = 260, delayMillis = index.coerceAtMost(8) * 30),
        label = "drawer row alpha",
    )
    val offset by animateDpAsState(
        targetValue = if (openTarget) 0.dp else (-16).dp,
        animationSpec = tween(durationMillis = 260, delayMillis = index.coerceAtMost(8) * 30),
        label = "drawer row offset",
    )
    Box(
        Modifier.fillMaxWidth().graphicsLayer {
            this.alpha = alpha
            translationX = offset.toPx()
        },
    ) {
        content()
    }
}

@Composable
private fun HomeOrb(busy: Boolean) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val manager = remember(context) { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val accelerometer = remember(manager) { manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }
    val reducedMotion = rememberReducedMotion()
    var rotationX by remember { mutableFloatStateOf(0f) }
    var rotationY by remember { mutableFloatStateOf(0f) }
    val breath = if (reducedMotion) {
        1f
    } else {
        val transition = rememberInfiniteTransition(label = "home orb breathing")
        val value by transition.animateFloat(
            initialValue = 1f,
            targetValue = 1.03f,
            animationSpec = infiniteRepeatable(
                tween(durationMillis = 3000, easing = FastOutSlowInEasing),
                repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
            ),
            label = "home orb breath",
        )
        value
    }
    val busyScale by animateFloatAsState(
        targetValue = if (busy) 1.08f else 1f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 280f),
        label = "home orb busy scale",
    )

    LaunchedEffect(manager, accelerometer, lifecycle, reducedMotion) {
        if (accelerometer == null || reducedMotion) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val baseline = FloatArray(2)
            val filtered = FloatArray(2)
            var initialized = false
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (!initialized) {
                        baseline[0] = event.values[0]
                        baseline[1] = event.values[1]
                        filtered[0] = event.values[0]
                        filtered[1] = event.values[1]
                        initialized = true
                        return
                    }
                    val smoothing = 0.16f
                    filtered[0] += smoothing * (event.values[0] - filtered[0])
                    filtered[1] += smoothing * (event.values[1] - filtered[1])
                    rotationX = ((baseline[1] - filtered[1]) * 1.8f).coerceIn(-10f, 10f)
                    rotationY = ((filtered[0] - baseline[0]) * 1.8f).coerceIn(-10f, 10f)
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            manager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_UI)
            try {
                awaitCancellation()
            } finally {
                manager.unregisterListener(listener)
                rotationX = 0f
                rotationY = 0f
            }
        }
    }

    DevinOrb(
        modifier = Modifier.size(72.dp).graphicsLayer {
            this.rotationX = if (reducedMotion) 0f else rotationX
            this.rotationY = if (reducedMotion) 0f else rotationY
            val scale = if (busy) busyScale else breath
            scaleX = scale
            scaleY = scale
            cameraDistance = 12f * density
        },
        spin = busy && !reducedMotion,
    )
}

internal fun greeting(hour: Int, name: String?): String {
    val salutation = when (hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        else -> "Good evening"
    }
    val firstName = name?.trim()?.takeIf(String::isNotEmpty)?.split(Regex("\\s+"))?.firstOrNull()
    return if (firstName == null) salutation else "$salutation, $firstName"
}
