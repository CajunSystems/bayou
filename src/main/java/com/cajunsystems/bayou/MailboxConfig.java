package com.cajunsystems.bayou;

public record MailboxConfig(int capacity, OverflowStrategy overflowStrategy, OverflowListener listener) {

    public static MailboxConfig unbounded() {
        return new MailboxConfig(Integer.MAX_VALUE, OverflowStrategy.DROP_NEWEST, null);
    }

    public static MailboxConfig bounded(int capacity) {
        return bounded(capacity, OverflowStrategy.DROP_NEWEST);
    }

    public static MailboxConfig bounded(int capacity, OverflowStrategy strategy) {
        return new MailboxConfig(capacity, strategy, null);
    }

    public static MailboxConfig bounded(int capacity, OverflowStrategy strategy, OverflowListener listener) {
        return new MailboxConfig(capacity, strategy, listener);
    }

    public boolean isBounded() {
        return capacity != Integer.MAX_VALUE;
    }
}
