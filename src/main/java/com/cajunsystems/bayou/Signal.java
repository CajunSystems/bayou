package com.cajunsystems.bayou;

/** Base type for lifecycle signals delivered to an actor's signal handler. */
public sealed interface Signal permits Terminated, LinkedActorDied {}
