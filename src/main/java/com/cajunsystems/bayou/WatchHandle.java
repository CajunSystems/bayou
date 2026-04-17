package com.cajunsystems.bayou;

/** Handle returned by watch registration; call {@link #cancel()} to stop receiving signals. */
public interface WatchHandle {
    void cancel();
}
