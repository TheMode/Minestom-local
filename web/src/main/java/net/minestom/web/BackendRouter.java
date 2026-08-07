package net.minestom.web;

import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;

/// Decides which backend (`host:port`) a freshly accepted client connects to. Invoked after the
/// client handshake has been read, before any upstream socket is dialled. Embedders install a
/// custom router via [ProxyServer.Builder#router]; the default returns the configured default
/// backend address on LOGIN and honours the journey cookie's target on TRANSFER reconnects.
///
/// **Transfer-aware.** The proxy passes a [Context] that flags whether this is a fresh `LOGIN`
/// connection or a `TRANSFER` reconnect carrying a cookie minted by an earlier
/// `movePlayer(...)`. For transfer reconnects [Context#targetFromCookie] is the address the
/// cookie was minted against — implementations can honour it directly or override.
@FunctionalInterface
public interface BackendRouter {
    /// Pick a backend for this connection. Return `null` to refuse the connection entirely
    /// (the proxy will close the socket without forwarding).
    @Nullable BackendTarget route(Context ctx);

    /// Returns the configured default backend on LOGIN and the cookie's address on TRANSFER.
    /// Suitable for the common case where every player starts on one server and only moves
    /// via explicit `proxy.movePlayer(...)` calls.
    static BackendRouter defaultRouter() {
        return ctx -> {
            final InetSocketAddress address = ctx.targetFromCookie() != null
                    ? ctx.targetFromCookie() : ctx.defaultBackend();
            return address == null ? null : new BackendTarget(address);
        };
    }

    /// Read-only view of what the proxy knows when it has to choose a backend.
    record Context(
            InetSocketAddress defaultBackend,
            String handshakeHostname,
            int handshakePort,
            int protocolVersion,
            Intent intent,
            /// On `TRANSFER` reconnects: the address the journey cookie was minted against.
            /// `null` for `LOGIN` or for transfers that arrived without a matching cookie.
            @Nullable InetSocketAddress targetFromCookie
    ) {
        public enum Intent { LOGIN, TRANSFER, STATUS }
    }
}
