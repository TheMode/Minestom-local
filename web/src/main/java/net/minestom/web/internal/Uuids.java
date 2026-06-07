package net.minestom.web.internal;

import java.nio.ByteBuffer;
import java.util.UUID;

/// UUID ↔ 16-byte big-endian (most-significant then least-significant long) conversion. Shared by
/// the SQLite archive (`BLOB(16)` columns) and the proxy's transfer cookies so the byte layout
/// lives in exactly one place.
public final class Uuids {
    private Uuids() {}

    public static byte[] toBytes(UUID uuid) {
        final ByteBuffer buf = ByteBuffer.allocate(16);
        buf.putLong(uuid.getMostSignificantBits());
        buf.putLong(uuid.getLeastSignificantBits());
        return buf.array();
    }

    public static UUID fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length != 16) {
            throw new IllegalArgumentException("not a 16-byte UUID: " + (bytes == null ? "null" : bytes.length));
        }
        final ByteBuffer buf = ByteBuffer.wrap(bytes);
        return new UUID(buf.getLong(), buf.getLong());
    }
}
