package io.github.cragcoffee.memoripple.ui.notes

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag

/** One line about a note, asked for wherever a note is. Shared so the shelf and the page agree. */
@Composable
fun NoteTextDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    testTag: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // A dialog that is one text field exists to be typed into; the keyboard comes with it.
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
                    .focusRequester(focus)
                    .testTag("${testTag}_field"),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value) },
                modifier = Modifier.testTag("${testTag}_confirm"),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
        modifier = Modifier.testTag(testTag),
    )
}

/**
 * Deleting a note.
 *
 * Says what survives, because what a note holds is memos and they do survive. The thing being
 * destroyed is the binding, not the writing.
 */
@Composable
fun NoteDeleteDialog(testTag: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ノートを削除しますか？") },
        text = { Text("話はメモとして残ります。消えるのは並び順と章だけです。") },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag("${testTag}_confirm"),
            ) { Text("削除") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
        modifier = Modifier.testTag(testTag),
    )
}

/**
 * A new chapter, and which end of the note it stands at.
 *
 * Where it goes is asked at the moment it is made rather than left to be dragged afterwards,
 * because the two answers people actually want are the two ends. Anywhere else is a move, and
 * moving is what the handles are for.
 */
@Composable
fun ChapterAddDialog(onConfirm: (String, Boolean) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新しい章") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
                    .focusRequester(focus)
                    .testTag("note_add_chapter_dialog_field"),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value, false) },
                modifier = Modifier.testTag("note_add_chapter_at_end"),
            ) { Text("末尾に追加") }
        },
        dismissButton = {
            TextButton(
                onClick = { onConfirm(value, true) },
                modifier = Modifier.testTag("note_add_chapter_at_start"),
            ) { Text("冒頭に追加") }
        },
        modifier = Modifier.testTag("note_add_chapter_dialog"),
    )
}
