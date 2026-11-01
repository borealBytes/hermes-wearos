package dev.hermes.wearos.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE ATTENTION POLICY, executable (batch-6 #31). The owner asked for these rules to be
 * written down AFTER voice answered them from the watch face (*"that gets in the way of
 * things… get this type of stuff also into our spec doc — we're having reversions"*). Each
 * test is one of the owner's stated situations; the SPEC §16.3 table and this file are the
 * two copies of the same law, and both halves of the app (ChatSession notifications,
 * VoiceHost speech) must route through AttentionPolicy to hold them.
 */
class AttentionPolicyTest {

    // ── voice: ONLY the live-on-screen chat ─────────────────────────────────────────
    @Test fun `the front chat in the foreground is the ONLY thing that may speak`() {
        assertTrue(AttentionPolicy.shouldSpeak(foreground = true, viewingChatId = "c1", chatId = "c1"))
    }

    @Test fun `the watch face is never talked at`() {
        // The exact complaint: wearer on the watch face (app background, chat still the last
        // one open) — the answer must go to the notification shade, silent on the TTS engine.
        assertFalse(AttentionPolicy.shouldSpeak(foreground = false, viewingChatId = "c1", chatId = "c1"))
        // Also: background + no viewing state at all.
        assertFalse(AttentionPolicy.shouldSpeak(foreground = false, viewingChatId = "", chatId = "c1"))
    }

    @Test fun `a different chat open means no voice from the invisible one`() {
        // Voice from a conversation the wearer is not looking at is the "talking in my pocket"
        // bug — foreground is not enough; it must be THAT chat on the chat screen.
        assertFalse(AttentionPolicy.shouldSpeak(foreground = true, viewingChatId = "c2", chatId = "c1"))
    }

    @Test fun `Sessions Settings Chats screens publish an empty viewing id — nothing speaks`() {
        // MainActivity clears viewingChatId off the chat page, so every list/settings surface
        // lands here.
        assertFalse(AttentionPolicy.shouldSpeak(foreground = true, viewingChatId = "", chatId = "c1"))
    }

    @Test fun `no active chat never speaks`() {
        assertFalse(AttentionPolicy.shouldSpeak(foreground = true, viewingChatId = "", chatId = ""))
    }

    // ── notifications: the exact complement — what is unseen MUST buzz ───────────────
    @Test fun `notifications are the exact complement of speech in every combination`() {
        for (fg in listOf(true, false)) {
            for (viewing in listOf("", "c1", "c2")) {
                for (chat in listOf("", "c1", "c2")) {
                    val speak = AttentionPolicy.shouldSpeak(fg, viewing, chat)
                    val notify = AttentionPolicy.shouldNotify(fg, viewing, chat)
                    assertTrue(
                        "speak/notify disagreed for fg=$fg viewing=$viewing chat=$chat",
                        speak != notify || (fg && viewing.isEmpty() && chat.isEmpty()),
                    )
                }
            }
        }
    }

    @Test fun `a desktop answer landing while the wearer watches ANOTHER chat still notifies`() {
        // #25's rule restated through this brain: the ONLY allowed silence is the session
        // actually top-of-screen; every other lane (watch/desktop/webui/API) notifies.
        assertTrue(AttentionPolicy.shouldNotify(foreground = true, viewingChatId = "other", chatId = "desk"))
    }

    @Test fun `the session you are watching does not double-buzz while you watch it`() {
        assertFalse(AttentionPolicy.shouldNotify(foreground = true, viewingChatId = "c1", chatId = "c1"))
    }
}
