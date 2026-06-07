package net.minestom.web.internal;

import org.jetbrains.annotations.Nullable;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import java.net.InetSocketAddress;
import java.util.Hashtable;

/// Parse and resolve Minecraft server addresses. Two flavours:
///
/// - **Plain.** [#parse] — accepts `host:port`, `[ipv6]:port`, or bare port; throws on DNS
///   failure. Use for `--bind` / `--dashboard` and anywhere the caller wants a literal socket
///   address.
/// - **Minecraft.** [#parseMinecraft] — additionally tries the `_minecraft._tcp.<host>` SRV
///   record before resolving. When the SRV exists its target + port wins; otherwise falls back
///   to the supplied host/port (or to port 25565 for bare hostnames). Mirrors the vanilla
///   client's connect-by-name behaviour.
///
/// Resolution is synchronous; the SRV lookup uses the JVM's bundled DNS provider via JNDI. On
/// timeout or no-record the lookup returns silently and the plain host:port is used.
public final class AddressResolver {
    /// Default Minecraft port — used when the input is a bare hostname with no SRV record.
    private static final int DEFAULT_PORT = 25565;

    private AddressResolver() {}

    /// Parse a `host:port` / `[ipv6]:port` / bare-port string. Bare-port form (e.g. `:8080` or
    /// `8080`) uses `defaultHost` as the host. Throws [IllegalArgumentException] on malformed
    /// input or unresolvable host.
    public static InetSocketAddress parse(String spec, String defaultHost) {
        return parse(spec, defaultHost, false);
    }

    /// Parse a Minecraft address spec — same shape as [#parse], but also tries SRV. A bare
    /// hostname (no port) is allowed and falls back to [#DEFAULT_PORT] when no SRV record is
    /// found.
    public static InetSocketAddress parseMinecraft(String spec, String defaultHost) {
        return parse(spec, defaultHost, true);
    }

    /// Convenience for runtime address strings (`movePlayer`, `Action.Move`). Unlike the
    /// two-arg form there is no `defaultHost` fallback — bare-port input like `":25577"` or
    /// `"25577"` throws, because runtime callers have no meaningful default and silently
    /// rewriting to localhost is a footgun.
    public static InetSocketAddress parseMinecraft(String spec) {
        if (spec == null || spec.isBlank()) throw new IllegalArgumentException("empty address");
        if (spec.startsWith(":") || spec.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("missing host in address: " + spec);
        }
        return parse(spec, "", true);
    }

    /// Resolve an already-split `host` + `port` pair. No SRV.
    private static InetSocketAddress resolve(String host, int port) {
        requirePort(port);
        final InetSocketAddress addr = new InetSocketAddress(host, port);
        if (addr.isUnresolved()) throw new IllegalArgumentException("could not resolve host: " + host);
        return addr;
    }

    /// Resolve `host` + `port` with SRV fallback. If `_minecraft._tcp.<host>` resolves, its
    /// target + port wins over `port`. Otherwise the supplied pair is used as-is.
    private static InetSocketAddress resolveMinecraft(String host, int port) {
        final SrvRecord srv = lookupMinecraftSrv(host);
        if (srv != null) return resolve(srv.target(), srv.port());
        return resolve(host, port);
    }

    private static InetSocketAddress parse(String spec, String defaultHost, boolean minecraftSrv) {
        if (spec == null || spec.isBlank()) throw new IllegalArgumentException("empty address");
        if (spec.startsWith("[")) {
            final int close = spec.indexOf(']');
            if (close < 0) throw new IllegalArgumentException("missing ']' in IPv6 address: " + spec);
            final String host = spec.substring(1, close);
            if (close + 1 >= spec.length() || spec.charAt(close + 1) != ':') {
                throw new IllegalArgumentException("expected ':<port>' after ']' in: " + spec);
            }
            final int port = parsePort(spec.substring(close + 2));
            return minecraftSrv ? resolveMinecraft(host, port) : resolve(host, port);
        }
        final int colon = spec.lastIndexOf(':');
        if (colon < 0) {
            // Bare port (all digits) → use default host. Otherwise treat as a bare hostname
            // (Minecraft mode only) and look up SRV / default port.
            if (!minecraftSrv || spec.chars().allMatch(Character::isDigit)) {
                return resolve(defaultHost, parsePort(spec));
            }
            return resolveMinecraft(spec, DEFAULT_PORT);
        }
        final String host = spec.substring(0, colon);
        final int port = parsePort(spec.substring(colon + 1));
        final String effectiveHost = host.isEmpty() ? defaultHost : host;
        return minecraftSrv ? resolveMinecraft(effectiveHost, port) : resolve(effectiveHost, port);
    }

    private static int parsePort(String s) {
        final int p;
        try { p = Integer.parseInt(s); }
        catch (NumberFormatException _) { throw new IllegalArgumentException("invalid port: " + s); }
        requirePort(p);
        return p;
    }

    private static void requirePort(int port) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("port out of range: " + port);
    }

    // ---- SRV ---------------------------------------------------------------------------

    private static @Nullable SrvRecord lookupMinecraftSrv(String host) {
        final String query = "_minecraft._tcp." + host;
        final Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory");
        InitialDirContext context = null;
        try {
            context = new InitialDirContext(env);
            final Attributes attrs = context.getAttributes(query, new String[]{"SRV"});
            final Attribute records = attrs.get("SRV");
            if (records == null) return null;
            SrvRecord best = null;
            final NamingEnumeration<?> values = records.getAll();
            while (values.hasMore()) {
                final SrvRecord record = parseSrvRecord(values.next().toString());
                if (record != null && (best == null || record.compareTo(best) < 0)) best = record;
            }
            return best;
        } catch (NamingException | RuntimeException _) {
            return null;
        } finally {
            if (context != null) {
                try { context.close(); } catch (NamingException _) {}
            }
        }
    }

    private static @Nullable SrvRecord parseSrvRecord(String value) {
        final String[] parts = value.trim().split("\\s+");
        if (parts.length != 4) return null;
        try {
            final int priority = Integer.parseInt(parts[0]);
            final int weight = Integer.parseInt(parts[1]);
            final int port = parsePort(parts[2]);
            String target = parts[3];
            if (target.endsWith(".")) target = target.substring(0, target.length() - 1);
            if (target.isBlank() || ".".equals(target)) return null;
            return new SrvRecord(priority, weight, port, target);
        } catch (IllegalArgumentException _) {
            return null;
        }
    }

    private record SrvRecord(int priority, int weight, int port, String target)
            implements Comparable<SrvRecord> {
        @Override
        public int compareTo(SrvRecord other) {
            final int byPriority = Integer.compare(priority, other.priority);
            if (byPriority != 0) return byPriority;
            // Higher weight wins among equal-priority records. Deterministic; the proxy only
            // needs one target per resolution.
            return Integer.compare(other.weight, weight);
        }
    }
}
