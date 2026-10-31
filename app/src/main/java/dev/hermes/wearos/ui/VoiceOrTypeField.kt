package dev.hermes.wearos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text

/**
 * VOICE-OR-TYPE FIELD (SPEC §12.4, owner batch 3: "new session plus a tap-to-search box, searchable
 * via on-watch Vosk voice or keyboard — chips below the field for 🎙/⌨"). One component, three
 * places that need a query: session search, chat search, the slash overlay's filter.
 *
 * The pattern is the IME ladder `PromptEditor` already fights: the field is passive ("tap to
 * search") until tapped, then a real focused [BasicTextField] with the system keyboard appears and
 * the 🎙/⌨ chips sit UNDER it — a focused field plus chips below is the only arrangement that
 * survives the keyboard's window insets on a 480-face watch (a chip row above the field gets
 * covered, and the chip that opened the keyboard must never close it).
 *
 * 🎙 feeds the query from the SAME Vosk lane the orb uses — the caller's `onVoiceStart` hands this
 * field a dictation sink (see `WearApp`'s voice-sink lane: partials land live, a final REPLACES the
 * partial so the query never doubles) and ⌨ just focuses the text field. Both routes write the same
 * [value], so search code does not care which one the wrist chose.
 *
 * NOTE for the caller: owning the mic lifecycle stays with the caller (Vosk holds an exclusive mic
 * mutex); this composable only renders chips and reports chip taps.
 */
@Composable
fun VoiceOrTypeField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onSend: (String) -> Unit = {},
    voiceOn: Boolean = false,
    onVoiceStart: () -> Unit = {},
    onVoiceStop: () -> Unit = {},
    showKeyboardHint: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    // Partial-text bookkeeping: keep the STT partial separate from a typed prefix so voice edits a
    // voice query and typing edits a typed query, instead of concatenating the two mid-thought.
    var typed by remember { mutableStateOf(value) }
    LaunchedEffect(value) { if (!voiceOn && !focused) typed = value }
    // THE KEYBOARD LADDER (owner batch-6 #27: "I hit the keyboard button and … it doesn't").
    // This field used to only SWAP IN a BasicTextField — a newly composed text field does NOT
    // take focus by itself, so the keyboard never came: not on the passive tap, not on the ⌨
    // chip (whose "already focused" comment was a lie — nothing ever requested focus). Now the
    // same proven ladder PromptEditor uses runs on every focused=true edge: requestInput focus,
    // show(), and the SHOW_EXPLICIT imm bypass some Wear IMEs demand. One tap → one keyboard.
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    val imeCtl = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val imeView = androidx.compose.ui.platform.LocalView.current
    val imeCtx = androidx.compose.ui.platform.LocalContext.current
    fun raiseKeyboard() {
        runCatching { focus.requestFocus() }
        imeCtl?.show()
        try {
            val imm = imeCtx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager
            @Suppress("DEPRECATION") imm?.showSoftInput(imeView, 1)
        } catch (e: Exception) { /* IME is whatever the watch runs; best effort */ }
    }
    LaunchedEffect(focused) {
        if (!focused) return@LaunchedEffect
        runCatching { focus.requestFocus() }
        androidx.compose.runtime.withFrameNanos { }
        kotlinx.coroutines.delay(120)
        raiseKeyboard()
    }
    val fm = LocalFontScale.current
    val shape = RoundedCornerShape(14.dp)

    if (focused) {
        Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BasicTextField(
                value = typed,
                onValueChange = { typed = it; onValueChange(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .background(MaterialTheme.colors.surface.copy(alpha = 0.7f), shape)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                textStyle = TextStyle(color = MaterialTheme.colors.onBackground, fontSize = (15 * fm).sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colors.primary),
                // SINGLE LINE IS THE IME CONTRACT (owner batch-6 #27: keyboard's search button
                // "doesn't work"): a multi-line field makes Compose answer
                // getCurrentPositionCursor/ENTER-action negotiation with "multiline", the IME then
                // maps its action key to a NEWLINE INSERT and onSearch never fires — the search
                // glyph shows but is dead furniture. Queries are one-liners by nature; singleLine
                // hands the action key back to onSearch. It also DISMISSES the keyboard, landing
                // the wearer on the passive field with the term in it — the owner's expectation.
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    @Suppress("DEPRECATION") imeCtl?.hide() // IME-DONE GUARANTEE (PromptEditor's rule)
                    // FIELD-vs-COMPOSING REALITY: some Wear IMEs deliver the word only at the
                    // action moment (composing lives IME-side); if this logs empty, the field is
                    // right and the IME is shy — the wrist-proof answer comes from logcat, not
                    // from guessing at pixels.
                    android.util.Log.i("HermesWear", "field search action typed=${typed.take(40)}")
                    onSend(typed)
                    focused = false
                    focusManager.clearFocus(force = true)
                }),
                decorationBox = { inner ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (typed.isEmpty()) {
                            Text(
                                placeholder,
                                color = MaterialTheme.colors.onBackground.copy(alpha = 0.42f),
                                style = MaterialTheme.typography.body2.copy(fontSize = (15 * fm).sp),
                            )
                        }
                        Box(modifier = Modifier.widthIn(min = 1.dp)) { inner() }
                    }
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                FieldChip(
                    glyph = if (voiceOn) "🎙 ●" else "🎙",
                    active = voiceOn,
                    onClick = { if (voiceOn) onVoiceStop() else onVoiceStart() },
                )
                if (showKeyboardHint) {
                    // The ⌨ chip RAISES the keyboard — it used to be a no-op claiming "already
                    // focused" (nothing ever requested focus). It is the owner's literal bug ask:
                    // "I hit the keyboard button and … it doesn't".
                    FieldChip(glyph = "⌨", active = true, onClick = { raiseKeyboard() })
                }
                FieldChip(glyph = "↵", active = false, onClick = { onSend(typed) })
                FieldChip(glyph = "✕", active = false, onClick = {
                    focused = false
                    @Suppress("DEPRECATION") imeCtl?.hide()
                    focusManager.clearFocus(force = true)
                    if (voiceOn) onVoiceStop()
                })
            }
        }
        return
    }

    // Passive state: one row that says "tap to search" and keeps its two chips to the right.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 34.dp)
            .background(MaterialTheme.colors.surface.copy(alpha = 0.55f), shape)
            .clickable { focused = true }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("🔍", style = MaterialTheme.typography.caption1, color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f))
        Text(
            value.ifBlank { placeholder },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.body2.copy(fontSize = (if (value.isBlank()) 12.5f * fm else 14f * fm).sp),
            color = if (value.isBlank()) MaterialTheme.colors.onBackground.copy(alpha = 0.45f)
                else MaterialTheme.colors.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (value.isNotBlank()) {
            Text(
                "✕",
                modifier = Modifier
                    .clickable { onValueChange("") }
                    .padding(4.dp),
                style = MaterialTheme.typography.caption1,
                color = Color.White.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun FieldChip(glyph: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = 30.dp)
            .background(
                if (active) MaterialTheme.colors.primary.copy(alpha = 0.85f)
                else MaterialTheme.colors.surface.copy(alpha = 0.7f),
                RoundedCornerShape(15.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, style = MaterialTheme.typography.caption1, color = Color.White)
    }
}
