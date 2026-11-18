package com.blazikener.devin.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.blazikener.devin.data.Account
import com.blazikener.devin.data.AuthStore
import com.blazikener.devin.data.DevinApi
import com.blazikener.devin.graph
import com.blazikener.devin.ui.components.openUrl
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(onSignedIn: () -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var baseUrl by remember { mutableStateOf(AuthStore.DEFAULT_BASE_URL) }
    var orgId by remember { mutableStateOf("") }
    var needOrg by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val k = key.trim()
        if (k.isEmpty()) { error = "Paste your API key"; return }
        busy = true; error = null
        scope.launch {
            val probe = DevinApi({ baseUrl.trim() }, { k }, { orgId.trim().ifEmpty { null } })
            runCatching { probe.self(k) }
                .onSuccess { self ->
                    val org = orgId.trim().ifEmpty { null } ?: self.effectiveOrgId
                    if (org == null) {
                        needOrg = true; error = "This key isn't tied to one organization. Enter the org ID (org-…)."
                    } else {
                        g.auth.save(Account(k, org, self.displayName, self.userId, baseUrl.trim().trimEnd('/')))
                        onSignedIn()
                    }
                }
                .onFailure { error = it.message ?: "Couldn't reach Devin" }
            busy = false
        }
    }

    Scaffold { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(48.dp))
            Text("Devin", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
            Text(
                "Run and steer your Devin sessions from your phone. Sign in with a Devin API key; it's stored encrypted on this device only.",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = key, onValueChange = { key = it; error = null },
                label = { Text("API key") }, placeholder = { Text("cog_…") },
                singleLine = true, modifier = Modifier.fillMaxWidth().testTag("apiKey"),
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                trailingIcon = {
                    IconButton(onClick = { show = !show }) {
                        Icon(if (show) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = "Show key")
                    }
                },
            )
            if (needOrg || advanced) OutlinedTextField(
                value = orgId, onValueChange = { orgId = it }, label = { Text("Organization ID") },
                placeholder = { Text("org-…") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            if (advanced) OutlinedTextField(
                value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("API base URL") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Button(onClick = ::submit, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Sign in")
            }
            TextButton(onClick = { openUrl(ctx, "https://app.devin.ai/settings/service-users") }) {
                Text("Get an API key (Settings → Service users)")
            }
            TextButton(onClick = { advanced = !advanced }) {
                Text(if (advanced) "Hide advanced" else "Advanced: org ID, enterprise API URL")
            }
        }
    }
}
