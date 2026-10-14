package net.mtree.livingplayers.chat;

/** The fixed set of things a player can say that a bot understands. Everything else is SMALL_TALK. */
public enum Intent {
    GREETING, FAREWELL, THANKS, INSULT, ASK_NAME, ASK_STATUS, ASK_LOCATION,
    FOLLOW_REQUEST, STOP_REQUEST, HELP_REQUEST, TRADE_REQUEST, SMALL_TALK;

    public boolean isAction() { return this == FOLLOW_REQUEST || this == STOP_REQUEST || this == HELP_REQUEST || this == TRADE_REQUEST; }
}
