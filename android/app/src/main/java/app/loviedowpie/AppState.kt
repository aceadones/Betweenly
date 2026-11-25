package app.loviedowpie

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
     * True while the Chat tab is on screen. Incoming chat does not add to the
     * unread count in that case. The camera page is not the Chat tab.
     */
    @Volatile
    var chatOpen: Boolean = false

    /**
     * While true, [MainActivity] pulls the inbox every 2 seconds instead of 5.
     * Music sessions set this. It carries no message contents.
     */
    @Volatile
    var fastPoll: Boolean = false
}
