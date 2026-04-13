package com.cajunsystems.bayou;

import com.cajunsystems.bayou.actor.StatefulActor;

record StatefulChildSpec<S, M>(String actorId,
                                StatefulActor<S, M> actor,
                                BayouSerializer<S> stateSerializer,
                                int snapshotInterval) implements ChildSpec {}
