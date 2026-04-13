package com.cajunsystems.bayou;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StrategyModelTest {

    // ── RestartWindow ────────────────────────────────────────────────────────

    @Test
    void restartWindowStoresValues() {
        var window = new RestartWindow(5, Duration.ofSeconds(60));
        assertThat(window.maxRestarts()).isEqualTo(5);
        assertThat(window.within()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void restartWindowUnlimitedConstant() {
        assertThat(RestartWindow.UNLIMITED.maxRestarts()).isEqualTo(Integer.MAX_VALUE);
        assertThat(RestartWindow.UNLIMITED.within()).isEqualTo(Duration.ZERO);
    }

    @Test
    void restartWindowRejectsNegativeMax() {
        assertThatThrownBy(() -> new RestartWindow(-1, Duration.ofSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void restartWindowRejectsNullDuration() {
        assertThatThrownBy(() -> new RestartWindow(5, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── OneForOneStrategy ────────────────────────────────────────────────────

    @Test
    void oneForOneStrategyReturnsRestart() {
        var strategy = new OneForOneStrategy(RestartWindow.UNLIMITED);
        assertThat(strategy.decide("child-1", new RuntimeException("boom")))
                .isEqualTo(RestartDecision.RESTART);
    }

    @Test
    void oneForOneStrategyStoresWindow() {
        var window = new RestartWindow(3, Duration.ofSeconds(30));
        var strategy = new OneForOneStrategy(window);
        assertThat(strategy.restartWindow()).isSameAs(window);
    }

    @Test
    void oneForOneStrategyRejectsNullWindow() {
        assertThatThrownBy(() -> new OneForOneStrategy(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── AllForOneStrategy ────────────────────────────────────────────────────

    @Test
    void allForOneStrategyReturnsRestartAll() {
        var strategy = new AllForOneStrategy(RestartWindow.UNLIMITED);
        assertThat(strategy.decide("child-1", new RuntimeException("boom")))
                .isEqualTo(RestartDecision.RESTART_ALL);
    }

    @Test
    void allForOneStrategyStoresWindow() {
        var window = new RestartWindow(5, Duration.ofSeconds(60));
        var strategy = new AllForOneStrategy(window);
        assertThat(strategy.restartWindow()).isSameAs(window);
    }

    @Test
    void allForOneStrategyRejectsNullWindow() {
        assertThatThrownBy(() -> new AllForOneStrategy(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Custom lambda strategy ───────────────────────────────────────────────

    @Test
    void lambdaStrategyIsSupported() {
        SupervisionStrategy custom = (childId, cause) ->
                cause instanceof IllegalStateException
                        ? RestartDecision.STOP
                        : RestartDecision.RESTART;

        assertThat(custom.decide("actor", new IllegalStateException()))
                .isEqualTo(RestartDecision.STOP);
        assertThat(custom.decide("actor", new RuntimeException()))
                .isEqualTo(RestartDecision.RESTART);
    }
}
