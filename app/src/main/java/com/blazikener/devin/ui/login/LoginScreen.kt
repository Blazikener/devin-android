package com.blazikener.devin.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.blazikener.devin.graph
import com.blazikener.devin.ui.components.openUrl

@Composable
fun LoginScreen(onSignedIn: () -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val vm: LoginViewModel = viewModel { LoginViewModel(g) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current

    LaunchedEffect(ui.signedIn) { if (ui.signedIn) onSignedIn() }

    Scaffold { pad ->
        Box(Modifier.fillMaxSize().padding(pad).imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Spacer(Modifier.height(24.dp))
                Text("Devin", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "Run and steer your Devin sessions from your phone. Sign in with a Devin API key; it's stored encrypted on this device only.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = ui.key, onValueChange = vm::setKey,
                    label = { Text("API key") }, placeholder = { Text("cog_…") },
                    singleLine = true, enabled = !ui.busy, modifier = Modifier.fillMaxWidth().testTag("apiKey"),
                    visualTransformation = if (ui.showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = if (ui.needOrg || ui.advanced) ImeAction.Next else ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); vm.submit() }),
                    trailingIcon = {
                        IconButton(onClick = vm::toggleShowKey) {
                            Icon(
                                if (ui.showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (ui.showKey) "Hide key" else "Show key",
                            )
                        }
                    },
                )
                if (ui.needOrg || ui.advanced) OutlinedTextField(
                    value = ui.orgId, onValueChange = vm::setOrgId, label = { Text("Organization ID") },
                    placeholder = { Text("org-…") }, singleLine = true, enabled = !ui.busy, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = if (ui.advanced) ImeAction.Next else ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); vm.submit() }),
                )
                if (ui.advanced) OutlinedTextField(
                    value = ui.baseUrl, onValueChange = vm::setBaseUrl, label = { Text("API base URL") },
                    singleLine = true, enabled = !ui.busy, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); vm.submit() }),
                )
                ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                Button(onClick = { focus.clearFocus(); vm.submit() }, enabled = !ui.busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    if (ui.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Sign in")
                }
                TextButton(onClick = { openUrl(ctx, "https://app.devin.ai/settings/service-users") }) {
                    Text("Get an API key (Settings → Service users)")
                }
                TextButton(onClick = vm::toggleAdvanced) {
                    Text(if (ui.advanced) "Hide advanced" else "Advanced: org ID, enterprise API URL")
                }
            }
        }
    }
}
