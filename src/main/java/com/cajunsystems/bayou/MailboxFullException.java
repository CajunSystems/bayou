package com.cajunsystems.bayou;

public class MailboxFullException extends RuntimeException {
    private final String actorId;
    private final int capacity;

    MailboxFullException(String actorId, int capacity) {
        super("Mailbox full for actor '" + actorId + "' (capacity=" + capacity +
              "). Use OverflowStrategy.DROP_OLDEST/DROP_NEWEST to drop silently, or increase capacity.");
        this.actorId = actorId;
        this.capacity = capacity;
    }

    public String actorId() { return actorId; }
    public int capacity() { return capacity; }
}
