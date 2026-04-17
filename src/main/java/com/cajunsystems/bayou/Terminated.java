package com.cajunsystems.bayou;

/** Delivered to a watching actor when the watched actor stops (crash or graceful stop). */
public record Terminated(String actorId) implements Signal {}
