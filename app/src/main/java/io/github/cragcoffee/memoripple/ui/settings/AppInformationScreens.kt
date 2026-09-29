package io.github.cragcoffee.memoripple.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.cragcoffee.memoripple.R
import io.github.cragcoffee.memoripple.ui.components.ProductSettingsRow
import io.github.cragcoffee.memoripple.ui.components.ProductSpacing
import io.github.cragcoffee.memoripple.ui.components.ProductTopBar
import io.github.cragcoffee.memoripple.ui.components.SectionHeader

@Composable
fun AboutScreen(
    versionName: String,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = { ProductTopBar(title = "MemoRippleについて", onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ProductSpacing.xl, vertical = ProductSpacing.lg)
                .testTag("about_screen"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
                modifier = Modifier.size(72.dp).testTag("about_brand_mark"),
            )
            Text(
                text = "MemoRipple",
                style = MaterialTheme.typography.displaySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = ProductSpacing.md).semantics { heading() },
            )
            Text(
                text = "メモや日記を残し、時間を越えて自分の言葉と再会するためのアプリです。" +
                    "記録にコメントを重ねたり、未来の自分へ言葉を送ったりできます。",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Start,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = ProductSpacing.lg),
            )
            ProductSettingsRow(
                title = "バージョン",
                supportingText = versionName,
                testTag = "about_version",
            )
        }
    }
}

@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = { ProductTopBar(title = "プライバシー", onBack = onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("privacy_screen"),
            contentPadding = PaddingValues(
                start = ProductSpacing.xl,
                end = ProductSpacing.xl,
                top = ProductSpacing.lg,
                bottom = ProductSpacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(ProductSpacing.xl),
        ) {
            item {
                Text(
                    text = "MemoRippleでのデータの扱いについて、現在のアプリの動作を説明します。",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            item {
                PrivacyFact(
                    title = "端末内の記録",
                    text = "メモ、コメント、日記、未来コメント、添付写真、設定は端末内に保存されます。" +
                        "封印中や受取待ちの未来コメントは、開封条件を満たすまで本文を表示しません。" +
                        "これは画面上の非表示であり、暗号化ではありません。",
                )
            }
            item {
                PrivacyFact(
                    title = "手動バックアップ",
                    text = "バックアップは、操作時に選んだ端末上のファイルへ保存します。" +
                        "バックアップには添付写真、封印中の本文を含む未来コメントと設定も含まれ、" +
                        "ファイル自体は暗号化されません。復元すると現在のアプリデータを置き換えます。",
                )
            }
            item {
                PrivacyFact(
                    title = "Google Drive",
                    text = "Google Driveへのバックアップは任意で、有効にした場合や実行した場合だけ使います。" +
                        "MemoRippleのバックアップを、ご自身のGoogle Driveのアプリ専用領域へ保存します。" +
                        "通常のDriveファイル一覧には表示されません。" +
                        "利用する権限はアプリ専用領域だけで、認可に使うトークンはMemoRippleの" +
                        "永続データには保存しません。",
                )
            }
            item {
                PrivacyFact(
                    title = "チャット",
                    text = "チャットの会話は端末内に保存されます。" +
                        "チャットの会話は、手動バックアップとGoogle Driveのバックアップには含まれません。",
                )
            }
            item {
                PrivacyFact(
                    title = "Local AI",
                    text = "AIの処理は端末内で行います。AIモデル（GGUF）はアプリに含まれていません。" +
                        "モデルのダウンロードを開始したときだけ、Hugging FaceへHTTPSで接続します。" +
                        "メモやチャットの内容をダウンロード先へ送ることはありません。",
                )
            }
            item {
                PrivacyFact(
                    title = "読み上げ",
                    text = "読み上げを開始したとき、対象の文章を端末で選択されているAndroidの" +
                        "読み上げエンジンへ渡します。データの扱いは選択中のエンジンに依存します。",
                )
            }
            item {
                PrivacyFact(
                    title = "Androidオーバーレイ",
                    text = "保存したコメントを他のアプリの上へ表示できます。MemoRippleは、" +
                        "表示中の他のアプリの内容を読み取ったり記録したりしません。",
                )
            }
        }
    }
}

@Composable
private fun PrivacyFact(title: String, text: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(title = title)
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = ProductSpacing.sm),
        )
    }
}
