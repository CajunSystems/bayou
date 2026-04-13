package com.cajunsystems.bayou;

import com.cajunsystems.bayou.actor.Actor;

record StatelessChildSpec<M>(String actorId, Actor<M> actor) implements ChildSpec {}
