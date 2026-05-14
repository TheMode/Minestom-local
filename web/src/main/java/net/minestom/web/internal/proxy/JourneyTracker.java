package net.minestom.web.internal.proxy;

import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/// Short-lived transfer cookies + per-player backend assignments.
///
/// A **journey** is one player's continuous run through the proxy: the chain of TCP sessions
/// they spawn as they move between backends. The journey id lives on the [net.minestom.web.internal.session.Session]
/// — this tracker just carries it across the disconnect/reconnect gap via a one-shot
/// `CookieStorePacket` payload. On `Intent.TRANSFER` reconnect the proxy asks for the cookie
/// via `CookieRequestPacket`, looks it up here, and the new TCP session adopts the prior
/// journey id + lands on the cookie's target address.
public final class JourneyTracker {
    public static final String COOKIE_KEY = "minestom-web:journey";

    /// Short window after a TransferPacket is sent during which the matching reconnect must
    /// arrive. Anything older is treated as a stale cookie (the client closed and came back
    /// via the front door); routes the connection through the default backend.
    private static final long PENDING_TTL_MS = 30_000L;

    private final Map<UUID, Pending> pendingByCookie = new ConcurrentHashMap<>();
    private final Map<UUID, Assignment> assignmentsByPlayer = new ConcurrentHashMap<>();

    /// Outstanding transfer: client received `TransferPacket` carrying [#cookieId], expected
    /// to reconnect within [#PENDING_TTL_MS] with the same value as a `ClientCookieResponse`.
    public record Pending(UUID cookieId, UUID journeyId, UUID playerUuid,
                          InetSocketAddress targetAddress,
                          @Nullable InetSocketAddress fromAddress, long mintedAt) {}

    /// The backend a player is currently assigned to. Updated when a session reveals its
    /// player UUID (see `SessionRegistry.markLive`).
    public record Assignment(InetSocketAddress address) {}

    /// Mint a one-shot transfer cookie. `journeyId` comes from the caller (typically
    /// `session.journeyId()`) so two concurrent moves for the same player can't diverge.
    /// Returns the pending record — the 16-byte payload that goes on the wire is
    /// `cookieBytes(pending.cookieId())`.
    public Pending mintTransfer(UUID playerUuid, UUID journeyId,
                                @Nullable InetSocketAddress from, InetSocketAddress target) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(journeyId, "journeyId");
        Objects.requireNonNull(target, "target");
        final UUID cookieId = UUID.randomUUID();
        final Pending pending = new Pending(cookieId, journeyId, playerUuid, target, from,
                System.currentTimeMillis());
        pendingByCookie.put(cookieId, pending);
        sweepStale();
        return pending;
    }

    /// Look up a cookie value carried by a TRANSFER reconnect. Returns the matching pending
    /// record and removes it (cookies are one-shot). Returns `null` if the bytes don't decode
    /// to a known cookie or the cookie expired.
    public @Nullable Pending consume(byte @Nullable [] cookieBytes) {
        if (cookieBytes == null || cookieBytes.length != 16) return null;
        final UUID id;
        try {
            final ByteBuffer buf = ByteBuffer.wrap(cookieBytes);
            id = new UUID(buf.getLong(), buf.getLong());
        } catch (RuntimeException _) {
            return null;
        }
        final Pending pending = pendingByCookie.remove(id);
        if (pending == null) return null;
        if (System.currentTimeMillis() - pending.mintedAt() > PENDING_TTL_MS) return null;
        return pending;
    }

    /// Stamp a player as currently assigned to `address` on `journeyId`. Called when a new
    /// connection (LOGIN or post-TRANSFER) finishes login and is about to flow PLAY traffic.
    public void recordAssignment(UUID playerUuid, UUID journeyId, InetSocketAddress address) {
        if (playerUuid == null || journeyId == null || address == null) return;
        assignmentsByPlayer.put(playerUuid, new Assignment(address));
    }

    /// The currently assigned backend for a player, or `null` if no journey is on file.
    public @Nullable Assignment current(UUID playerUuid) {
        return playerUuid == null ? null : assignmentsByPlayer.get(playerUuid);
    }

    /// Encode a cookie id as the 16-byte payload that goes on the wire.
    public static byte[] cookieBytes(UUID id) {
        final ByteBuffer buf = ByteBuffer.allocate(16);
        buf.putLong(id.getMostSignificantBits());
        buf.putLong(id.getLeastSignificantBits());
        return buf.array();
    }

    private void sweepStale() {
        final long cutoff = System.currentTimeMillis() - PENDING_TTL_MS;
        pendingByCookie.values().removeIf(p -> p.mintedAt() < cutoff);
    }
}
