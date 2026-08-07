package net.minestom.web;

import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;
import java.util.Objects;

/// A Minecraft server the proxy can route a player to. The `address` doubles as the target's
/// identity for display and journey-tracking purposes — there is no pre-registration step. Any
/// reachable host:port works as a target the moment the router (or [ProxyServer#movePlayer])
/// names it.
///
/// `mojang` overrides the process-wide [ProxyConfig#mojang] when the target requires a
/// different bot identity. Most deployments leave it `null` and use the process-wide auth.
public record BackendTarget(
        InetSocketAddress address,
        @Nullable MojangAuth mojang
) {
    public BackendTarget {
        Objects.requireNonNull(address, "address is required");
    }

    public BackendTarget(InetSocketAddress address) {
        this(address, null);
    }

    /// Render the address as `host:port` — the canonical wire/display form used by the
    /// dashboard, persistence, and MQL's `player.backend`.
    public String label() {
        return address.getHostString() + ":" + address.getPort();
    }
}
