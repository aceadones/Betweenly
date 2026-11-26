package com.nextlayer.betweenly

/**
 * Process-wide flags the chat code reads and the activity owner writes.
 *
 * [foreground] is true while [MainActivity] is in the foreground.
 * Chat notifications are posted only when this is false.
 */
object AppState {
    @Volatile
    var foreground: Boolean = false

    /**
     * True while the conversation thread is on screen. The chat list and the
     * camera page leave this false, so a new message can still count as unread.
     */
    @Volatile
    var chatOpen: Boolean = false

    /**
     * While true, [MainActivity] pulls the inbox every 2 seconds instead of 5.
     * Duet sets this for a listen-together session. It carries no message contents.
     */
    @Volatile
    var fastPoll: Boolean = false
}
