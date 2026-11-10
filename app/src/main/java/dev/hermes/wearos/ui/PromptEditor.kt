package dev.hermes.wearos.ui

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import dev.hermes.wearos.domain.PromptEdit
import kotlinx.coroutines.delay

/**
 * Full-screen prompt editor (owner batch 2 asks #5/#6): prefilled real text field + the SYSTEM
 * Wear keyboard. tgwear's swipe-typing is just the Wear OS floating IME (Google Keyboard w/
 * glide) shown over a focused text field — so this file drives the STANDARD input method manager;
 * NO library needed (documented: androidx.wear:wear-input 1.0.0→1.2.0 contains only
 * WearableButtons/RemoteInputIntentHelper — no keyboard host exists in any release, so the
 * reflection/intent "fallback" IS the primary path here). Keyboard ladder on open:
 *   1. FocusRequester.requestFocus()          → Compose attaches the InputConnection
 *   2. LocalSoftwareKeyboardController.show() → the friendly API
 *   3. InputMethodManager.showSoftInput(SHOW_EXPLICIT) on the compose view — Wear images
 *      occasionally ignore (2); EXPLICIT bypasses the "user intent" heuristics.
 * Send is IME-Action.Send (keyboard's ✓), the orb-sized SEND button (enabled only when
 * PromptEdit.rewrap says the text is sendable), or… nothing else. Back-✕ closes without sending.
 */
@Composable
fun PromptEditorOverlay(
    original: String,
    hint: String,
    onDismiss: () -> Unit,
    onSend: (String) -> Unit,
    prefill: String = "", // draft typed before opening the editor (owner IME epic: never strand input)
) {
    var text by remember { mutableStateOf(prefill.ifEmpty { original }) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        focus.requestFocus()
        withFrameNanos { }
        delay(120)
        keyboard?.show()
        try {
            // SHOW_EXPLICIT (==1) was removed from the public SDK: the literal bypasses the
            // "was this a user gesture?" heuristics some Wear IMEs apply and force the show.
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            // Retry ladder (owner Galaxy Watch Ultra, One UI Watch 6 / Android 16, 2026-11-10):
            // the single 120 ms attempt lands BEFORE OneUI's IME session attaches to the new
            // focused InputConnection, so GBoard silently never rises (caret blinks, no keyboard).
            // Retry at 400/800 ms while the field is not accepting text, then a forced global
            // toggle as last resort — isAcceptingText guards it from ever HIDING a live keyboard.
            @Suppress("DEPRECATION")
            for (d in longArrayOf(0L, 400L, 800L)) {
                if (d > 0L) delay(d)
                if (imm?.isAcceptingText != true) imm?.showSoftInput(view, 1) else break
            }
            if (imm?.isAcceptingText != true) {
                @Suppress("DEPRECATION")
                imm?.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
            }
        } catch (e: Exception) { /* IME is whatever the watch runs; best effort */ }
    }
    val sendable = PromptEdit.rewrap(original, text)

    // IME-DONE GUARANTEE (Phase-A audit + drag-up epic): whatever ends this editor — Send action,
    // the always-visible send chip, or ✕ — the keyboard is explicitly hidden, so the IME can
    // never linger over the transcript when the field goes away.
    @Suppress("DEPRECATION")
    fun submit() = PromptEdit.rewrap(original, text)?.let {
        keyboard?.hide()
        onSend(it)
    }
    @Suppress("DEPRECATION")
    fun close() {
        keyboard?.hide()
        onDismiss()
    }

    // Activity-level overlay (NOT a Dialog): Dialog windows don't resize for the IME under
    // adjustResize, and on a 227-dp round face a keyboard covers ~40% of the screen — the chat
    // window resize keeps this field visible above it.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(top = 34.dp, bottom = 8.dp, start = 20.dp, end = 20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                hint,
                style = MaterialTheme.typography.caption3,
                color = MaterialTheme.colors.primary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text(
                "✕",
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onBackground,
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(MaterialTheme.colors.surface.copy(alpha = 0.45f))
                    .clickable(onClickLabel = "close editor", onClick = { close() })
                    .padding(horizontal = 14.dp, vertical = 4.dp),
            )
        }
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .focusRequester(focus),
            textStyle = MaterialTheme.typography.body1.copy(color = MaterialTheme.colors.onBackground),
            cursorBrush = SolidColor(MaterialTheme.colors.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
            decorationBox = { inner ->
                Box(modifier = Modifier.fillMaxWidth()) {
                    if (text.isEmpty()) {
                        Text(
                            "type or swipe…",
                            style = MaterialTheme.typography.body1,
                            color = MaterialTheme.colors.onBackground.copy(alpha = 0.35f),
                        )
                    }
                    inner()
                }
            },
        )
        Button(
            onClick = { submit() },
            enabled = sendable != null,
            modifier = Modifier.size(width = 120.dp, height = 36.dp),
            colors = ButtonDefaults.buttonColors(
                backgroundColor = MaterialTheme.colors.primary.copy(alpha = 0.9f),
                disabledBackgroundColor = MaterialTheme.colors.surface.copy(alpha = 0.25f),
            ),
        ) {
            Text("send ➤", style = MaterialTheme.typography.button, textAlign = TextAlign.Center)
        }
    }
}

/**
 * Tiny custom action sheet for a long-pressed user prompt (owner: "click on my old prompts and
 * hold with an edit option like desktop has"): full-bleed round-safe dialog, wear Buttons only
 * (no IconButton). Desktop would TRUNCATE the server history and re-run — the watch can't touch
 * gateway history, so "Edit & resend" APPENDS a new user turn in the same session (see
 * [PromptEdit] + the SPEC note there).
 */
@Composable
fun PromptActionSheet(
    preview: String,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(top = 32.dp, bottom = 16.dp, start = 16.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "“${preview.take(48)}${if (preview.length > 48) "…" else ""}”",
                style = MaterialTheme.typography.caption3,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
            Button(
                onClick = onEdit,
                modifier = Modifier.size(width = 190.dp, height = 38.dp),
            ) { Text("✎ Edit & resend", style = MaterialTheme.typography.button) }
            Button(
                onClick = onCopy,
                modifier = Modifier.size(width = 190.dp, height = 38.dp),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = MaterialTheme.colors.surface.copy(alpha = 0.35f),
                ),
            ) { Text("⧉ Copy", style = MaterialTheme.typography.button) }
            Button(
                onClick = onDismiss,
                modifier = Modifier.size(width = 90.dp, height = 32.dp),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = MaterialTheme.colors.surface.copy(alpha = 0.35f),
                ),
            ) { Text("close", style = MaterialTheme.typography.button) }
        }
    }
}
