package com.blazikener.devin.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.blazikener.devin.ui.theme.DevinDimens

/** Known positive values only: no empty badge node for zero, unavailable or loading counts. */
@Composable
fun KnownCountBadge(count: Int?, modifier: Modifier = Modifier) {
    if (count != null && count > 0) Badge(modifier) { Text(count.toString()) }
}

@Composable
fun DevinIconButton(label: String, icon: ImageVector, onClick: () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true,
) {
    IconButton(onClick, modifier.sizeIn(minWidth = DevinDimens.touchTarget, minHeight = DevinDimens.touchTarget), enabled) {
        Icon(icon, contentDescription = label)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevinTopBar(title: String, navigationIcon: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() }) },
        navigationIcon = navigationIcon, actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

/** Flat outline shared by search, file and composer surfaces; never an alpha-only edge. */
@Composable
fun DevinOutlinedSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier, shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(DevinDimens.outlineWidth, MaterialTheme.colorScheme.outline),
        content = content)
}

@Composable
fun DevinSearchField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(value, onValueChange, modifier.fillMaxWidth(),
        label = { Text("Search your sessions") }, singleLine = true,
        shape = MaterialTheme.shapes.medium,
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        ))
}
