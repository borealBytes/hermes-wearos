package dev.hermes.wearos.domain

/**
 * THE ATTENTION POLICY (owner 2026-10-31, stated as a permanent requirement after three
 * "reversion" complaints — batch-5 #19/#22 re-tests, batch-6 #25 desktop notifications, and
 * the voice-out fix that became this section; the SPEC §16.3 text mirrors this file):
 *
 * > Any completion or approval from ANY lane — watch, desktop, web UI, plain API — must reach
 * > me. The channel depends on exactly one thing: whether that session is LIVE IN FRONT OF ME.
 * > • Chat open on the chat screen, app foreground: it renders inline and speaks (voice out as
 * >   configured) — NO system notification, that would be redundant noise.
 * > • Anywhere else (watch face, ambient, another app, Sessions/Settings/Chats screens, another
 * >   chat open, screen off): it goes to SYSTEM NOTIFICATIONS and stays SILENT — no TTS from a
 * >   pocket. Approvals notify with their actions; landed answers notify "Answer ready".
 *
 * The rule is ONE decision function: [shouldSpeak] true ⇔ [shouldNotify] false. Voice that
 * leaks while the wearer is away (the complaint) and notifications that buzz while they watch
 * (its twin) are the same bug — whoever implements either half MUST route through this file so
 * the halves can never disagree again.
 */
object AttentionPolicy {

    /**
     * May this chat's traffic be SPOKEN? Only while the app is foreground AND [chatId] is the
     * chat screen's front chat. An empty [chatId] (no active chat) speaks nothing; foreground
     * with a different chat open does NOT count (voice from an invisible conversation is the
     * "talking in my pocket" bug); background does NOT count even if that chat is the last one
     * open (the watch face case that started this rule).
     */
    fun shouldSpeak(foreground: Boolean, viewingChatId: String, chatId: String): Boolean =
        foreground && chatId.isNotEmpty() && viewingChatId == chatId

    /**
     * Must this event go to Wear OS notifications? The exact complement of [shouldSpeak]: what
     * the wearer cannot see live, they must hear BUZZ instead of not hear at all.
     */
    fun shouldNotify(foreground: Boolean, viewingChatId: String, chatId: String): Boolean =
        !shouldSpeak(foreground, viewingChatId, chatId)
}
