package net.minestom.web.internal.persist;

import net.minestom.server.network.ConnectionState;
import net.minestom.web.Direction;
import net.minestom.web.PacketEvent;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;

/// Producer messages enqueued from [PersistentHistory] record methods onto the writer thread's
/// queue. The writer drains a batch and dispatches each op via pattern match — every JDBC bind
/// stays on one thread.
sealed interface Op {

    record OpenConnection(UUID id, long sessionId, @Nullable UUID journeyId,
                          @Nullable String upstreamAddress,
                          String address, long tsMs) implements Op {}

    record OpenJourney(UUID journeyId, @Nullable UUID playerUuid, long tsMs) implements Op {}

    record JourneyPlayerUuid(UUID journeyId, UUID playerUuid) implements Op {}

    record InitConnection(UUID id,
                          @Nullable ConnectionState stateSb,
                          @Nullable ConnectionState stateCb,
                          int compression) implements Op {}

    record CloseConnection(UUID id, long tsMs) implements Op {}

    record Io(UUID id, long seq, long tsMs, Direction direction, byte[] payload) implements Op {}

    record Checkpoint(UUID id,
                      long packetSeq,
                      long ioEventSeq,
                      @Nullable ConnectionState stateSb,
                      @Nullable ConnectionState stateCb,
                      int compression) implements Op {}

    record PacketRow(UUID id, PacketEvent event) implements Op {}

    /// Inline barrier — `complete(null)` releases on a successful commit, `complete(error)` on
    /// rollback or writer death. The waiter rethrows the error so flushSync callers can't
    /// mistake a rolled-back batch for a durable one.
    final class Sync implements Op {
        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile @Nullable Throwable error;

        void complete(@Nullable Throwable err) {
            this.error = err;
            latch.countDown();
        }

        void await() throws InterruptedException {
            latch.await();
        }

        @Nullable Throwable error() {
            return error;
        }
    }

    /// Sentinel that tells the writer thread to drain remaining ops, close its connection, and
    /// exit. Enqueued by [PersistentHistory#close].
    enum Shutdown implements Op {
        INSTANCE
    }
}
