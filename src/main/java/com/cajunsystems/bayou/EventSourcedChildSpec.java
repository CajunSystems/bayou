package com.cajunsystems.bayou;

import com.cajunsystems.bayou.actor.EventSourcedActor;

record EventSourcedChildSpec<S, E, M>(String actorId,
                                       EventSourcedActor<S, E, M> actor,
                                       BayouSerializer<E> eventSerializer) implements ChildSpec {}
