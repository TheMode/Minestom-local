package net.minestom.web;

import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;
import java.nio.file.Path;

/// Run-time configuration for [ProxyServer]. In live mode the proxy needs a TCP bind, a default
/// backend address, and a dashboard. In replay mode only the dashboard is needed — set
/// [#replayMode] true and leave [#bind] / [#defaultBackend] `null`; per-browser SQLite replays
/// are wired up at runtime.
///
/// There is no pre-registered backend roster: the proxy can move players to **any** reachable
/// address via [ProxyServer#proxy] `.movePlayer(uuid, host, port)`. `defaultBackend` is only the
/// landing target for fresh `LOGIN` connections.
public record ProxyConfig(
        @Nullable InetSocketAddress bind,
        @Nullable InetSocketAddress defaultBackend,
        /// Hostname/port the proxy advertises to clients when issuing a `TransferPacket`. Falls
        /// back to [#bind] when null. Set this explicitly when the proxy binds to a wildcard
        /// address (`0.0.0.0` / `::`) — clients can't reconnect to a wildcard.
        @Nullable InetSocketAddress publicAddress,
        InetSocketAddress dashboard,
        @Nullable String token,
        int decodedPacketCacheSize,
        String dataChannel,
        @Nullable Path persistencePath,
        @Nullable MojangAuth mojang,
        boolean replayMode
) {
    public static final String DEFAULT_DATA_CHANNEL = "minestom:web/data";

    public ProxyConfig {
        if (dashboard == null) throw new IllegalArgumentException("dashboard address is required");
        if (!replayMode) {
            if (bind == null) throw new IllegalArgumentException("bind address is required in live mode");
            if (defaultBackend == null) {
                throw new IllegalArgumentException("defaultBackend address is required in live mode");
            }
        }
        if (decodedPacketCacheSize < 0) throw new IllegalArgumentException("decodedPacketCacheSize < 0");
    }

    /// Reachable proxy address for `TransferPacket` — prefers [#publicAddress] but falls back
    /// to [#bind]. Used by [net.minestom.web.internal.proxy.TcpAcceptor] when telling a client
    /// where to reconnect.
    public @Nullable InetSocketAddress reachableAddress() {
        return publicAddress != null ? publicAddress : bind;
    }
}
