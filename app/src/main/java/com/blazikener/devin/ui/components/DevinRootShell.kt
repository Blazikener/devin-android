package com.blazikener.devin.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blazikener.devin.data.Session
import com.blazikener.devin.ui.navigation.RootDestination
import kotlinx.coroutines.launch

/** Root-only drawer. Detail/Create/Settings screens retain Up and never inherit a drawer. */
@Composable
fun DevinRootShell(
    selected: RootDestination,
    accountName: String?,
    recents: List<Session>,
    needsYouCount: Int,
    onHome: () -> Unit,
    onSessions: () -> Unit,
    onNeedsYou: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    content: @Composable (openMenu: () -> Unit) -> Unit,
) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    fun go(action: () -> Unit) { scope.launch { drawer.close(); action() } }
    BackHandler(drawer.isOpen) { scope.launch { drawer.close() } }
    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.fillMaxHeight().widthIn(max = 360.dp).padding(horizontal = 12.dp)) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Devin", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                            IconButton(onClick = { go(onSearch) }) { Icon(Icons.Outlined.Search, "Search sessions") }
                        }
                        NavigationDrawerItem(
                            label = { Text("Home") }, selected = selected == RootDestination.Home,
                            icon = { Icon(Icons.Outlined.Home, null) }, onClick = { go(onHome) },
                        )
                        NavigationDrawerItem(
                            label = { Text("Sessions") }, selected = selected == RootDestination.Sessions,
                            icon = { Icon(Icons.AutoMirrored.Outlined.List, null) }, onClick = { go(onSessions) },
                        )
                        // Needs you is a filter shortcut. It is never a separately selected destination.
                        NavigationDrawerItem(
                            label = { Text("Needs you") }, selected = false, onClick = { go(onNeedsYou) },
                            badge = { KnownCountBadge(needsYouCount) },
                        )
                        HorizontalDivider(Modifier.padding(vertical = 12.dp))
                        Text("Recent sessions", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                        recents.sortedByDescending(Session::updatedAt).take(8).forEach { session ->
                            NavigationDrawerItem(
                                label = { Text(session.displayTitle, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                selected = false, onClick = { go { onOpen(session.id) } },
                                icon = { StatusDot(session.state) },
                            )
                        }
                        NavigationDrawerItem(
                            label = { Text("Settings") }, selected = false,
                            icon = { Icon(Icons.Outlined.Settings, null) }, onClick = { go(onSettings) },
                        )
                    }
                    HorizontalDivider()
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(accountName?.takeIf(String::isNotBlank) ?: "Your workspace",
                            style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(12.dp))
                        FilledTonalButton(onClick = { go(onNew) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.Add, null)
                            Spacer(Modifier.width(8.dp))
                            Text("New session")
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(UnofficialDisclaimer, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
    ) { content { scope.launch { drawer.open() } } }
}

const val UnofficialDisclaimer = "Unofficial client for Devin. Not affiliated with Cognition."
