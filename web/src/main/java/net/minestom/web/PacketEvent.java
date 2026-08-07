package net.minestom.web;

import net.minestom.server.network.ConnectionState;

/// One packet on a connection timeline. This is the packet list/facet API shape and the
/// row persisted to SQLite; decoded packet objects are cached separately for inspector detail.
public record PacketEvent(
        long seq,
        long ts,
        Direction direction,
        ConnectionState state,
        String className,
        int sizeBytes,
        String subject,
        String subjectLabel,
        String subjectGroup,
        long ioEventSeq
) {}
