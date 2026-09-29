package io.github.cragcoffee.memoripple.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

object ProductSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

object ProductSize {
    val minimumTouchTarget = 48.dp
    // One left edge for the whole app. The top bar reaches the same 24dp by way of its own
    // 12dp padding plus half of the 48dp box its icon sits in, so chrome and content line up.
    val screenHorizontalPadding = 24.dp
    val compactTopBarHeight = 56.dp
    val compactTopBarContentMaxWidth = 720.dp
}

@Composable
fun ProductCompactTopBar(
    centerContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    action: @Composable () -> Unit = {},
    containerColor: Color = MaterialTheme.colorScheme.background,
) {
    Surface(
        color = containerColor,
        modifier = modifier.fillMaxWidth().heightIn(min = ProductSize.compactTopBarHeight),
    ) {
        Box(Modifier.fillMaxWidth()) {
            HorizontalDivider(
                modifier = Modifier.align(Alignment.BottomCenter),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .widthIn(max = ProductSize.compactTopBarContentMaxWidth)
                    .padding(horizontal = ProductSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .width(ProductSize.minimumTouchTarget)
                        .height(ProductSize.minimumTouchTarget),
                    contentAlignment = Alignment.Center,
                ) {
                    navigationIcon()
                }
                Box(
                    modifier = Modifier.weight(1f)
                        .heightIn(min = ProductSize.compactTopBarHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    centerContent()
                }
                // A minimum rather than a fixed width. An icon sits in 48dp; a word does not,
                // and a slot that refuses to grow breaks 編集 into 編 over 集.
                Box(
                    modifier = Modifier
                        .widthIn(min = ProductSize.minimumTouchTarget)
                        .height(ProductSize.minimumTouchTarget),
                    contentAlignment = Alignment.Center,
                ) {
                    action()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductTopBar(
    /** Null on a screen whose content already names it; the slot is then left empty. */
    title: String?,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { if (title != null) Text(title) },
        // The app scaffold has already stood clear of the status bar; a bar that pads for it
        // again opens a dead band above itself. And 56dp, the compact bar's height, is enough
        // for a row of 48dp targets — 64dp only spreads them apart.
        windowInsets = WindowInsets(0.dp),
        expandedHeight = ProductSize.compactTopBarHeight,
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "戻る")
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
        ),
    )
}

@Composable
fun SectionHeader(
    title: String,
    supportingText: String? = null,
    modifier: Modifier = Modifier,
    divider: Boolean = false,
) {
    Column(modifier.fillMaxWidth()) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        if (supportingText != null) {
            Text(
                supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = ProductSpacing.xs),
            )
        }
        if (divider) {
            HorizontalDivider(
                modifier = Modifier.padding(top = ProductSpacing.sm),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

@Composable
fun ProductEmptyState(
    title: String,
    description: String? = null,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    visual: (@Composable () -> Unit)? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    actionTestTag: String? = null,
    prominent: Boolean = false,
) {
    Column(
        modifier = modifier.padding(ProductSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        visual?.invoke()
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            title,
            style = if (prominent) {
                MaterialTheme.typography.titleLarge
            } else {
                MaterialTheme.typography.titleMedium
            },
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(
                top = if (icon == null && visual == null) 0.dp else ProductSpacing.lg,
            ),
        )
        if (description != null) {
            Text(
                description,
                style = if (prominent) {
                    MaterialTheme.typography.bodyLarge
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = ProductSpacing.sm)
                    .widthIn(max = 360.dp),
            )
        }
        if (actionLabel != null && onAction != null) {
            val actionModifier = Modifier
                .padding(top = if (prominent) ProductSpacing.xl else ProductSpacing.lg)
                .heightIn(min = if (prominent) 52.dp else ProductSize.minimumTouchTarget)
                .then(
                    if (actionTestTag == null) Modifier else Modifier.testTag(actionTestTag),
                )
            if (prominent) {
                Button(onClick = onAction, modifier = actionModifier) { Text(actionLabel) }
            } else {
                FilledTonalButton(onClick = onAction, modifier = actionModifier) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
fun ProductStatusChip(label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = ProductSpacing.sm, vertical = ProductSpacing.xs),
        )
    }
}

@Composable
fun ProductSettingsRow(
    title: String,
    supportingText: String,
    testTag: String,
    enabled: Boolean = true,
    trailingContent: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Text(
                supportingText,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = trailingContent,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ProductSize.minimumTouchTarget)
            .then(
                if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick)
                else Modifier,
            )
            .testTag(testTag)
            .semantics(mergeDescendants = true) {
                contentDescription = "$title、$supportingText"
                if (!enabled) disabled()
            },
    )
}

@Composable
fun ProductSheetHeader(
    title: String,
    onDismiss: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (onDismiss != null) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Outlined.Close, contentDescription = "閉じる")
            }
        }
    }
}

@Composable
fun ProductInfoBanner(
    title: String,
    supportingText: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    actionTestTag: String? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(
                horizontal = ProductSpacing.lg,
                vertical = ProductSpacing.sm,
            ),
            horizontalArrangement = Arrangement.spacedBy(ProductSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelLarge)
                Text(supportingText, style = MaterialTheme.typography.bodySmall)
            }
            FilledTonalButton(
                onClick = onAction,
                modifier = Modifier
                    .heightIn(min = ProductSize.minimumTouchTarget)
                    .then(
                        if (actionTestTag == null) Modifier else Modifier.testTag(actionTestTag),
                    ),
            ) { Text(actionLabel) }
        }
    }
}

@Composable
fun DestructiveTextButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(onClick = onClick, modifier = modifier) {
        Text(label, color = MaterialTheme.colorScheme.error)
    }
}
