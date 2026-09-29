package io.github.cragcoffee.memoripple.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import io.github.cragcoffee.memoripple.ui.components.ProductSpacing

/**
 * 初回起動の一枚: the name, the one idea — the memo is who you were when you wrote it,
 * the comments are who you were when you read it again — and the way in. Nothing moves
 * here by product decision: Quiet Ripple means the UI itself stays still, and the
 * comments earn their entrance later, over the user's own words. Shown once;
 * はじめる marks it seen and it never returns. It touches no database and makes no data.
 */
@Composable
fun WelcomeScreen(onStart: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize().testTag("welcome_demo"),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = ProductSpacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "MemoRipple",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Box(Modifier.height(ProductSpacing.lg))
            Text(
                "メモは書いた時の自分。\nコメントは、それを読み返した時の自分。",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Box(Modifier.height(ProductSpacing.xxl))
            Button(
                onClick = onStart,
                modifier = Modifier.testTag("welcome_demo_start"),
            ) { Text("はじめる") }
        }
    }
}
