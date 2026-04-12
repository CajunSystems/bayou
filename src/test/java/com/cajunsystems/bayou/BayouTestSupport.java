package com.cajunsystems.bayou;

import com.cajunsystems.gumbo.persistence.InMemoryPersistenceAdapter;
import com.cajunsystems.gumbo.service.SharedLogConfig;
import com.cajunsystems.gumbo.service.SharedLogService;

import java.io.IOException;

/**
 * Shared test utility: creates a fresh in-memory BayouSystem for each test.
 */
public final class BayouTestSupport {

    private BayouTestSupport() {}

    public static BayouSystem freshSystem() throws IOException {
        SharedLogConfig config = SharedLogConfig.builder()
                .persistenceAdapter(new InMemoryPersistenceAdapter())
                .build();
        SharedLogService log = SharedLogService.open(config);
        return new BayouSystem(log);
    }
}
