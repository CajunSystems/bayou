package com.cajunsystems.bayou;

import java.io.*;

/**
 * {@link BayouSerializer} backed by Java's built-in object serialization.
 * Requires {@code T} to implement {@link Serializable}.
 *
 * <p>Suitable for development and testing. For production consider a faster,
 * schema-evolution-friendly format such as Kryo or Protobuf.
 *
 * @param <T> the serializable type
 */
public class JavaSerializer<T extends Serializable> implements BayouSerializer<T> {

    @Override
    public byte[] serialize(T value) throws IOException {
        try (var bos = new ByteArrayOutputStream();
             var oos = new ObjectOutputStream(bos)) {
            oos.writeObject(value);
            return bos.toByteArray();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public T deserialize(byte[] bytes) throws IOException {
        try (var bis = new ByteArrayInputStream(bytes);
             var ois = new ObjectInputStream(bis)) {
            return (T) ois.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException("Class not found during deserialization", e);
        }
    }
}
