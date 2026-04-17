package com.cajunsystems.bayou;

/**
 * Factory for {@link TestProbe} — deterministic actor testing utilities.
 *
 * <pre>{@code
 * TestProbe<String> probe = TestKit.probe(system, "probe");
 * probe.ref().tell("hello");
 * probe.expectMessage("hello", Duration.ofSeconds(1));
 * }</pre>
 */
public final class TestKit {

    private TestKit() {}

    /**
     * Spawn a probe actor with the given {@code id} and return a {@link TestProbe} for it.
     *
     * @param system the actor system
     * @param id     unique actor ID for this probe
     * @param <M>    the message type
     * @return a new {@code TestProbe}
     */
    public static <M> TestProbe<M> probe(BayouSystem system, String id) {
        return new TestProbe<>(system, id);
    }
}
