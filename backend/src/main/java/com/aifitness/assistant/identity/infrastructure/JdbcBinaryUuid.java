package com.aifitness.assistant.identity.infrastructure;

import java.nio.ByteBuffer;
import java.util.UUID;

final class JdbcBinaryUuid {

    private JdbcBinaryUuid() {}

    static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }

    static UUID uuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
