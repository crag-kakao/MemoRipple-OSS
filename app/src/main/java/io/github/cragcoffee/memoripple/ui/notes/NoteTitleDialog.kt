package io.github.cragcoffee.memoripple.ui.notes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.cragcoffee.memoripple.ui.components.ProductSize
import io.github.cragcoffee.memoripple.ui.components.ProductSpacing

/**
 * What a note is called and what it says under that, asked before it exists.
 *
 * A note used to be made the moment the button was pressed, which left a shelf of things all called
 * 「新しいノート」 and no way to tell them apart until each was opened. Naming a piece of writing is
 * the first thing its writer does anyway; asking here costs a screen and saves the renaming.
 *
 * The whole screen is given to the question so there is nothing else to answer. Leaving without an
 * answer makes nothing, which is why the way out says 閉じる rather than キャンセル: there is no
 * half-made note to cancel.
 */
@Composable
fun NoteTitleDialog(onConfirm: (String, String) -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var subtitle by remember { mutableStateOf("") }
    // The second page asks for something optional, so it is asked second: nobody is stopped by it.
    var askingSubtitle by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // Both pages exist to be typed into, so the caret and the keyboard arrive with them.
    LaunchedEffect(askingSubtitle) {
        focus.requestFocus()
        keyboard?.show()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().testTag("note_title_dialog"),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxSize().imePadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .heightIn(min = ProductSize.compactTopBarHeight)
                        .padding(horizontal = ProductSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { if (askingSubtitle) askingSubtitle = false else onDismiss() },
                        modifier = Modifier.testTag("note_title_close"),
                    ) { Text(if (askingSubtitle) "戻る" else "閉じる") }
                    Box(Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            if (askingSubtitle) onConfirm(title, subtitle) else askingSubtitle = true
                        },
                        enabled = title.isNotBlank(),
                        modifier = Modifier.testTag(
                            if (askingSubtitle) "note_subtitle_done" else "note_title_next",
                        ),
                    ) { Text(if (askingSubtitle) "完了" else "次へ") }
                }
                Box(
                    modifier = Modifier.fillMaxSize()
                        .padding(horizontal = ProductSize.screenHorizontalPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            if (askingSubtitle) {
                                "サブタイトルを入れてください。\n自分を励ます言葉でもいいですよ。"
                            } else {
                                "新しいノートを作ります。\n名前を入れてください。"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = if (askingSubtitle) subtitle else title,
                            onValueChange = { if (askingSubtitle) subtitle = it else title = it },
                            singleLine = true,
                            placeholder = {
                                Text(
                                    if (askingSubtitle) "例：今日もお疲れ！" else "例：夜明け前に君と",
                                    style = MaterialTheme.typography.bodyLarge,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            },
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                textAlign = TextAlign.Center,
                            ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    if (askingSubtitle) {
                                        onConfirm(title, subtitle)
                                    } else if (title.isNotBlank()) {
                                        askingSubtitle = true
                                    }
                                },
                            ),
                            modifier = Modifier.fillMaxWidth()
                                .padding(top = ProductSpacing.xl)
                                .focusRequester(focus)
                                .testTag(
                                    if (askingSubtitle) "note_subtitle_field" else "note_title_field",
                                ),
                        )
                    }
                }
            }
        }
    }
}
