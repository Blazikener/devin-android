package com.blazikener.devin.ui.settings

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ChevronRight
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import com.blazikener.devin.BuildConfig
import com.blazikener.devin.data.DEVIN_MODES
import com.blazikener.devin.data.ThemeMode
import com.blazikener.devin.graph
import com.blazikener.devin.notify.Notifications
import com.blazikener.devin.notify.SessionWatcher
import com.blazikener.devin.ui.components.DevinIconButton
import com.blazikener.devin.ui.components.DevinTopBar
import com.blazikener.devin.ui.components.UnofficialDisclaimer
import com.blazikener.devin.ui.theme.DevinDimens

private enum class SettingsPage(val title: String) {
    Overview("Settings"), Appearance("Appearance"), Notifications("Notifications"),
    Defaults("Default session setup"), Account("Account and access"),
}

@Composable
fun SettingsScreen(onBack: () -> Unit, onSignedOut: () -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val s by g.settings.settings.collectAsStateWithLifecycle()
    val account by g.auth.account.collectAsStateWithLifecycle()
    var confirmOut by rememberSaveable { mutableStateOf(false) }
    var page by rememberSaveable { mutableStateOf(SettingsPage.Overview) }
    val savedPages = rememberSaveableStateHolder()
    val back: () -> Unit = { if (page == SettingsPage.Overview) onBack() else page = SettingsPage.Overview }
    BackHandler(enabled = page != SettingsPage.Overview, onBack = back)
    var canNotify by remember { mutableStateOf(Notifications.canPost(ctx)) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        canNotify = granted
        val activity = ctx as? Activity
        if (!granted && activity != null && !activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) openNotificationSettings(ctx)
    }
    LifecycleResumeEffect(Unit) {
        canNotify = Notifications.canPost(ctx)
        onPauseOrDispose {}
    }

    Scaffold(topBar = {
        DevinTopBar(page.title, navigationIcon = { DevinIconButton("Back", Icons.AutoMirrored.Outlined.ArrowBack, back) })
    }) { pad ->
        savedPages.SaveableStateProvider(page.name) {
            Column(Modifier.padding(pad).consumeWindowInsets(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).wrapContentWidth().widthIn(max = 720.dp).padding(DevinDimens.screenPadding), verticalArrangement = Arrangement.spacedBy(DevinDimens.itemGap)) {
                if (page == SettingsPage.Overview) {
                    Section("Personal workspace")
                    Column {
                        DestinationRow("Appearance", themeLabel(s.theme), Icons.Outlined.Palette) { page = SettingsPage.Appearance }
                        Divider()
                        DestinationRow("Notifications", "Replies and session updates", Icons.Outlined.Notifications) { page = SettingsPage.Notifications }
                        Divider()
                        DestinationRow("Default session setup", "Mode and ACU limit", Icons.Outlined.Tune) { page = SettingsPage.Defaults }
                        Divider()
                        DestinationRow("Account and access", account?.name?.let { "$it · Connected" } ?: "Not connected", Icons.Outlined.AccountCircle) { page = SettingsPage.Account }
                    }
                }
                if (page == SettingsPage.Account) {
                    Section("Signed in as")
                    Text(account?.name ?: "", style = MaterialTheme.typography.titleMedium)
                    Supporting("Organization: ${account?.orgId.orEmpty()}")
                    account?.baseUrl?.takeIf { it != "https://api.devin.ai" }?.let { Supporting(it) }
                    OutlinedButton(onClick = { confirmOut = true }, modifier = Modifier.heightIn(min = DevinDimens.touchTarget)) { Text("Sign out") }
                    Divider()
                    Section("Access")
                    OutlinedTextField(
                        value = s.createAsUserId ?: "", onValueChange = { t -> g.settings.update { it.copy(createAsUserId = t.trim().ifEmpty { null }) } },
                        label = { Text("Act as user ID (service-user keys)") }, placeholder = { Text("user-…") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text("Sessions and messages are attributed to this user. Needs the impersonation permission on the key.") },
                        colors = preferenceFieldColors(),
                    )
                }

                if (page == SettingsPage.Notifications) {
                    Section("Session updates")
                    if (!canNotify) {
                        Text("Notifications are off for this app, so you won't hear when Devin needs you.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = {
                            val needsPermission = Build.VERSION.SDK_INT >= 33 &&
                                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                            if (needsPermission) perm.launch(Manifest.permission.POST_NOTIFICATIONS) else openNotificationSettings(ctx)
                        }, modifier = Modifier.heightIn(min = DevinDimens.touchTarget)) { Text("Allow notifications") }
                    }
                    Toggle("When Devin needs you", s.notifyNeedsYou) { v -> g.settings.update { it.copy(notifyNeedsYou = v) } }
                    Toggle("When a session finishes or errors", s.notifyFinished) { v -> g.settings.update { it.copy(notifyFinished = v) } }
                    Toggle("Every new Devin message", s.notifyMessages) { v -> g.settings.update { it.copy(notifyMessages = v) } }
                    Divider()
                    Section("Background check interval")
                    Column(Modifier.selectableGroup()) {
                        listOf(15, 30, 60).forEach { m ->
                            ChoiceRow("$m minutes", selected = s.pollMinutes == m, onClick = {
                                g.settings.update { it.copy(pollMinutes = m) }; SessionWatcher.schedule(ctx, m)
                            })
                        }
                    }
                    Text("While the app is open, sessions refresh every few seconds.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                if (page == SettingsPage.Defaults) {
                    Section("Default mode")
                    Column(Modifier.selectableGroup()) {
                        DEVIN_MODES.forEach { (v, label) -> ChoiceRow(label, selected = s.defaultMode == v, onClick = { g.settings.update { it.copy(defaultMode = v) } }) }
                    }
                    Divider()
                    OutlinedTextField(
                        value = s.defaultAcuLimit?.toString() ?: "", onValueChange = { t -> g.settings.update { it.copy(defaultAcuLimit = t.filter(Char::isDigit).take(6).toIntOrNull()?.takeIf { n -> n > 0 }) } },
                        label = { Text("Default max ACUs") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text("Leave empty for no limit.") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = preferenceFieldColors(),
                    )
                }

                if (page == SettingsPage.Account && Build.VERSION.SDK_INT >= 31) {
                    var linksOn by remember { mutableStateOf(opensDevinLinks(ctx)) }
                    LifecycleResumeEffect(Unit) { linksOn = opensDevinLinks(ctx); onPauseOrDispose {} }
                    Divider()
                    Section("Session links")
                    Text(
                        if (linksOn) "app.devin.ai session links open in this app."
                        else "app.devin.ai session links open in the browser. Add the link under Open by default to open them here.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = {
                        ctx.startActivity(Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                    }, modifier = Modifier.heightIn(min = DevinDimens.touchTarget)) { Text("Open by default settings") }
                }

                if (page == SettingsPage.Appearance) {
                    Section("Theme")
                    Column(Modifier.selectableGroup()) {
                        ThemeMode.entries.forEach { m -> ChoiceRow(themeLabel(m), selected = s.theme == m, onClick = { g.settings.update { it.copy(theme = m) } }) }
                    }
                    Divider()
                    Toggle("Use wallpaper colors", s.dynamicColor,
                        supporting = if (Build.VERSION.SDK_INT >= 31) "Use your Android color palette instead of the default jade theme." else "Available on Android 12 and later.",
                        enabled = Build.VERSION.SDK_INT >= 31,
                    ) { v -> g.settings.update { it.copy(dynamicColor = v) } }
                    Divider()
                    Toggle("Enter key sends", s.enterToSend, supporting = "For hardware keyboards. Otherwise, Enter starts a new line.") { v -> g.settings.update { it.copy(enterToSend = v) } }
                }
                if (page == SettingsPage.Overview || page == SettingsPage.Account) {
                    Column(Modifier.padding(top = DevinDimens.sectionGap), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Supporting("Devin for Android ${BuildConfig.VERSION_NAME}")
                        Supporting(UnofficialDisclaimer)
                    }
                }
            }
        }
    }
    if (confirmOut) AlertDialog(
        onDismissRequest = { confirmOut = false },
        title = { Text("Sign out?") }, text = { Text("Your API key, cached sessions, and drafts are removed from this device.") },
        confirmButton = { TextButton(onClick = {
            confirmOut = false; SessionWatcher.cancel(ctx); g.auth.clear(); g.cache.clear(); g.settings.clearDrafts()
            ctx.getSharedPreferences("watcher", 0).edit().clear().apply(); onSignedOut()
        }) { Text("Sign out") } },
        dismissButton = { TextButton(onClick = { confirmOut = false }) { Text("Cancel") } },
    )
}

@RequiresApi(31)
private fun opensDevinLinks(ctx: Context): Boolean {
    val state = ctx.getSystemService(DomainVerificationManager::class.java)
        ?.getDomainVerificationUserState(ctx.packageName) ?: return false
    return state.isLinkHandlingAllowed && (state.hostToStateMap["app.devin.ai"] ?: DomainVerificationUserState.DOMAIN_STATE_NONE) != DomainVerificationUserState.DOMAIN_STATE_NONE
}

@Composable private fun Section(t: String) = Text(t, style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { heading() })

@Composable
private fun Supporting(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Divider() = HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = DevinDimens.outlineWidth)

@Composable
private fun preferenceFieldColors() = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outline)

private fun themeLabel(mode: ThemeMode) = when (mode) {
    ThemeMode.System -> "System default"
    ThemeMode.Light -> "Light"
    ThemeMode.Dark -> "Dark"
}

@Composable
private fun DestinationRow(title: String, summary: String, icon: ImageVector, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).heightIn(min = 88.dp).padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Supporting(summary)
        }
        Icon(Icons.AutoMirrored.Outlined.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick)
        .heightIn(min = 56.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        RadioButton(selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, supporting: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = value, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .heightIn(min = 64.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            supporting?.let { Supporting(it) }
        }
        Switch(checked = value, onCheckedChange = null, enabled = enabled)
    }
}

private fun openNotificationSettings(ctx: android.content.Context) {
    val intent = if (Build.VERSION.SDK_INT >= 26) Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
    else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", ctx.packageName, null))
    ctx.startActivity(intent)
}
