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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val s by g.settings.settings.collectAsStateWithLifecycle()
    val account by g.auth.account.collectAsStateWithLifecycle()
    var confirmOut by rememberSaveable { mutableStateOf(false) }
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
        TopAppBar(title = { Text("Settings") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } })
    }) { pad ->
        Column(Modifier.padding(pad).consumeWindowInsets(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).wrapContentWidth().widthIn(max = 720.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Section("Account")
            Text(account?.name ?: "", style = MaterialTheme.typography.titleMedium)
            Text("Org ${account?.orgId ?: ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (account?.baseUrl != null && account?.baseUrl != "https://api.devin.ai") Text(account!!.baseUrl, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { confirmOut = true }) { Text("Sign out") }
            HorizontalDivider()

            Section("Notifications")
            if (!canNotify) {
                Text("Notifications are off for this app, so you won't hear when Devin needs you.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = {
                    val needsPermission = Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    if (needsPermission) perm.launch(Manifest.permission.POST_NOTIFICATIONS) else openNotificationSettings(ctx)
                }) { Text("Allow notifications") }
            }
            Toggle("When Devin needs you", s.notifyNeedsYou) { v -> g.settings.update { it.copy(notifyNeedsYou = v) } }
            Toggle("When a session finishes or errors", s.notifyFinished) { v -> g.settings.update { it.copy(notifyFinished = v) } }
            Toggle("Every new Devin message", s.notifyMessages) { v -> g.settings.update { it.copy(notifyMessages = v) } }
            Text("Background check interval", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(15, 30, 60).forEach { m ->
                    FilterChip(selected = s.pollMinutes == m, onClick = {
                        g.settings.update { it.copy(pollMinutes = m) }; SessionWatcher.schedule(ctx, m)
                    }, label = { Text("$m min") })
                }
            }
            Text("While the app is open, sessions refresh every few seconds.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()

            Section("New sessions")
            Text("Default mode", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DEVIN_MODES.forEach { (v, label) -> FilterChip(selected = s.defaultMode == v, onClick = { g.settings.update { it.copy(defaultMode = v) } }, label = { Text(label) }) }
            }
            OutlinedTextField(
                value = s.defaultAcuLimit?.toString() ?: "", onValueChange = { t -> g.settings.update { it.copy(defaultAcuLimit = t.filter(Char::isDigit).take(6).toIntOrNull()?.takeIf { n -> n > 0 }) } },
                label = { Text("Default max ACUs") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Leave empty for no limit.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(
                value = s.createAsUserId ?: "", onValueChange = { t -> g.settings.update { it.copy(createAsUserId = t.trim().ifEmpty { null }) } },
                label = { Text("Act as user ID (service-user keys)") }, placeholder = { Text("user-…") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Sessions and messages are attributed to this user. Needs the impersonation permission on the key.") },
            )
            HorizontalDivider()

            if (Build.VERSION.SDK_INT >= 31) {
                var linksOn by remember { mutableStateOf(opensDevinLinks(ctx)) }
                LifecycleResumeEffect(Unit) { linksOn = opensDevinLinks(ctx); onPauseOrDispose {} }
                Section("Links")
                Text(
                    if (linksOn) "app.devin.ai session links open in this app."
                    else "app.devin.ai session links open in the browser. Add the link under Open by default to open them here.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                }) { Text("Open by default settings") }
                HorizontalDivider()
            }

            Section("Appearance & input")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ThemeMode.entries.forEach { m -> FilterChip(selected = s.theme == m, onClick = { g.settings.update { it.copy(theme = m) } }, label = { Text(m.name) }) }
            }
            if (Build.VERSION.SDK_INT >= 31) Toggle("Use wallpaper colors", s.dynamicColor) { v -> g.settings.update { it.copy(dynamicColor = v) } }
            Toggle("Enter key sends (hardware keyboards)", s.enterToSend) { v -> g.settings.update { it.copy(enterToSend = v) } }
            HorizontalDivider()
            Text("Devin for Android ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (confirmOut) AlertDialog(
        onDismissRequest = { confirmOut = false },
        title = { Text("Sign out?") }, text = { Text("Your API key and cached sessions are removed from this device.") },
        confirmButton = { TextButton(onClick = {
            confirmOut = false
            g.signOut()
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

@Composable private fun Section(t: String) = Text(t, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = value, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = null)
    }
}

private fun openNotificationSettings(ctx: android.content.Context) {
    val intent = if (Build.VERSION.SDK_INT >= 26) Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
    else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", ctx.packageName, null))
    ctx.startActivity(intent)
}
