package com.cajunsystems.bayou;

/** Delivered to a linked actor when the other end of the link dies. */
public record LinkedActorDied(String actorId, Throwable cause) implements Signal {}
