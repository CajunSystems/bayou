package com.cajunsystems.bayou;

@FunctionalInterface
public interface OverflowListener {
    void onOverflow(String actorId, int capacity, OverflowStrategy strategy);
}
