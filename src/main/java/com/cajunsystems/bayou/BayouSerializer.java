package com.cajunsystems.bayou;

import java.io.IOException;

/**
 * Pluggable serialization contract for actor messages, events, and snapshots.
 * Implementations must be thread-safe as they may be called concurrently.
 *
 * <p>A default Java-serialization-backed implementation is available via {@link JavaSerializer}.
 * For production use, prefer Kryo or Protobuf for better performance and schema evolution.
 *
 * @param <T> the type to serialize
 */
public interface BayouSerializer<T> {
    byte[] serialize(T value) throws IOException;
    T deserialize(byte[] bytes) throws IOException;
}
