package net.minestom.web.cli;

import net.minestom.web.MojangAuth;
import net.minestom.web.ProxyConfig;
import net.minestom.web.ProxyServer;
import net.minestom.web.internal.AddressResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

/// Standalone entry point. Run the proxy in front of one or more Minecraft servers, expose the
/// dashboard on a separate port, and block until SIGINT. Reads only CLI arguments — no env
/// vars, no config files — so behavior is fully reproducible from the command line.
///
/// ```
/// java -p libs -m net.minestom.web/net.minestom.web.cli.Main \
///      --backend play.example.com:25565 \
///      --bind 0.0.0.0:25577 \
///      --dashboard 127.0.0.1:8080 \
///      --token "$WEB_TOKEN"
/// ```
public final class Main {
    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

    static void main(String[] args) {
        final Options opts;
        try {
            opts = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("minestom-web: " + e.getMessage());
            System.err.println("Try 'minestom-web --help' for usage.");
            System.exit(2);
            return;
        }
        if (opts.help) {
            System.out.println(USAGE);
            return;
        }
        if (opts.login) {
            System.exit(runLogin(opts));
            return;
        }

        final ProxyServer.Builder builder = ProxyServer.builder()
                .bindDashboard(opts.dashboard)
                .token(opts.token)
                .decodedPacketCacheSize(opts.decodedPacketCacheSize)
                .dataChannel(opts.dataChannel);

        if (opts.replayMode) {
            builder.replayMode(true);
        } else {
            final MojangAuth mojang;
            try {
                mojang = resolveMojang(opts.mojangTokenInline, opts.mojangTokenFile,
                        opts.mojangProfileUuid, opts.mojangProfileName);
            } catch (IllegalArgumentException e) {
                System.err.println("minestom-web: " + e.getMessage());
                System.exit(2);
                return;
            }
            final MojangAuth resolved;
            try {
                resolved = resolveBotProfile(mojang);
            } catch (IOException e) {
                System.err.println("minestom-web: failed to resolve bot profile from access token: " + e.getMessage());
                System.err.println("Pass --mojang-profile-uuid + --mojang-profile-name to skip this lookup.");
                System.exit(1);
                return;
            }
            if (opts.backend == null) {
                System.err.println("minestom-web: --backend <host:port> is required");
                System.exit(2);
                return;
            }
            builder.bindProxy(opts.bind)
                    .defaultBackend(opts.backend)
                    .persistence(opts.persistence)
                    .mojang(resolved);
        }

        final ProxyServer server = builder.build();

        final CountDownLatch shutdown = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("Shutting down…");
            try {
                server.close();
            } catch (Exception e) {
                LOGGER.warn("error during shutdown", e);
            }
            shutdown.countDown();
        }, "Minestom-Web-Shutdown"));

        server.start();
        try {
            shutdown.await();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private record Options(
            InetSocketAddress bind,
            InetSocketAddress backend,
            InetSocketAddress dashboard,
            String token,
            int decodedPacketCacheSize,
            String dataChannel,
            Path persistence,
            String mojangTokenInline,
            Path mojangTokenFile,
            UUID mojangProfileUuid,
            String mojangProfileName,
            boolean login,
            String msClientId,
            boolean replayMode,
            boolean help
    ) {
        static Options parse(String[] args) {
            InetSocketAddress bind = new InetSocketAddress("0.0.0.0", 25565);
            InetSocketAddress backend = null;
            InetSocketAddress dashboard = new InetSocketAddress("127.0.0.1", 8080);
            String token = null;
            int decodedPacketCacheSize = 5000;
            String dataChannel = ProxyConfig.DEFAULT_DATA_CHANNEL;
            Path persistence = Path.of("sessions.db");
            String mojangToken = null;
            Path mojangTokenFile = null;
            UUID mojangProfileUuid = null;
            String mojangProfileName = null;
            boolean login = false;
            String msClientId = null;
            boolean replayMode = false;
            boolean help = false;

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (kind(arg)) {
                    case "-h", "--help" -> help = true;
                    case "-b", "--bind" -> bind = AddressResolver.parse(value(args, i++, arg), "0.0.0.0");
                    case "--backend" -> backend = AddressResolver.parseMinecraft(value(args, i++, arg), "127.0.0.1");
                    case "-d", "--dashboard" -> dashboard = AddressResolver.parse(value(args, i++, arg), "127.0.0.1");
                    case "-t", "--token" -> token = value(args, i++, arg);
                    case "--decoded-packet-cache" ->
                            decodedPacketCacheSize = parseNonNegativeInt(value(args, i++, arg), "--decoded-packet-cache");
                    case "--data-channel" -> dataChannel = value(args, i++, arg);
                    case "--persistence" -> {
                        String v = value(args, i++, arg);
                        persistence = v.equalsIgnoreCase("none") ? null : Path.of(v);
                    }
                    case "--mojang-token" -> mojangToken = value(args, i++, arg);
                    case "--mojang-token-file" -> mojangTokenFile = Path.of(value(args, i++, arg));
                    case "--mojang-profile-uuid" -> mojangProfileUuid = parseUuid(value(args, i++, arg));
                    case "--mojang-profile-name" -> mojangProfileName = value(args, i++, arg);
                    case "--login" -> login = true;
                    case "--ms-client-id" -> msClientId = value(args, i++, arg);
                    case "--replay-mode" -> replayMode = true;
                    default -> throw new IllegalArgumentException("unknown option: " + arg);
                }
            }

            return new Options(bind, backend, dashboard, token, decodedPacketCacheSize,
                    dataChannel, persistence,
                    mojangToken, mojangTokenFile, mojangProfileUuid, mojangProfileName,
                    login, msClientId, replayMode, help);
        }

        /// Returns the flag name (`--foo` from `--foo=bar`); `value()` consumes the rest.
        private static String kind(String arg) {
            int eq = arg.indexOf('=');
            return eq < 0 ? arg : arg.substring(0, eq);
        }

        private static String value(String[] args, int i, String arg) {
            int eq = arg.indexOf('=');
            if (eq >= 0) return arg.substring(eq + 1);
            if (i + 1 >= args.length) throw new IllegalArgumentException("missing value for " + arg);
            return args[i + 1];
        }
    }

    /// Sign in to Microsoft and write the resulting Mojang access_token to `--mojang-token-file`.
    /// Returns a shell exit code — 0 on success, 1 on a flow-level failure (network, expired
    /// code, no Minecraft entitlement, etc.). Required flags are validated here rather than at
    /// parse time so the proxy mode is unaffected by their absence.
    private static int runLogin(Options opts) {
        if (opts.msClientId == null || opts.msClientId.isBlank()) {
            System.err.println("minestom-web: --login requires --ms-client-id");
            return 2;
        }
        if (opts.mojangTokenFile == null) {
            System.err.println("minestom-web: --login requires --mojang-token-file (output path)");
            return 2;
        }
        if (opts.mojangTokenInline != null) {
            System.err.println("minestom-web: --login conflicts with --mojang-token (file output only)");
            return 2;
        }
        try {
            final MicrosoftAuth.Result result = MicrosoftAuth.login(opts.msClientId);
            Files.writeString(opts.mojangTokenFile, result.accessToken());
            // Best-effort tighten to rw-------. Windows / non-POSIX filesystems silently skip.
            try {
                Files.setPosixFilePermissions(opts.mojangTokenFile,
                        PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException | IOException _) {}
            System.out.println("Token written to " + opts.mojangTokenFile);
            System.out.println();
            System.out.println("Re-run with: --mojang-token-file " + opts.mojangTokenFile
                    + " \\");
            System.out.println("             --mojang-profile-uuid " + result.profileUuid() + " \\");
            System.out.println("             --mojang-profile-name " + result.profileName());
            return 0;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            System.err.println("Login interrupted.");
            return 1;
        } catch (IOException e) {
            System.err.println("Login failed: " + e.getMessage());
            return 1;
        }
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid UUID: " + value);
        }
    }

    /// Fill in `profileUuid` / `profileName` via `GET /minecraft/profile` when the user only
    /// supplied a token. No-op when both fields are already present or no token is configured.
    /// Returns the same instance if nothing changed; throws if the lookup fails.
    private static MojangAuth resolveBotProfile(MojangAuth mojang) throws IOException {
        if (mojang == null) return null;
        if (mojang.profileUuid() != null && mojang.profileName() != null) return mojang;
        final MicrosoftAuth.Profile profile = MicrosoftAuth.fetchProfile(mojang.accessToken());
        LOGGER.info("Mojang bot identity resolved: {} ({})", profile.name(), profile.uuid());
        return new MojangAuth(mojang.accessToken(),
                mojang.profileUuid() != null ? mojang.profileUuid() : profile.uuid(),
                mojang.profileName() != null ? mojang.profileName() : profile.name());
    }

    /// Reconcile the four Mojang flags into a single optional [MojangAuth]. The token may come
    /// inline (`--mojang-token`) or from a file (`--mojang-token-file`); the file form is
    /// preferred because CLI args leak through `ps`. Profile overrides are accepted only when
    /// a token is present; otherwise they are dead config and we flag it as a user error.
    private static MojangAuth resolveMojang(String inlineToken, Path tokenFile,
                                            UUID profileUuid, String profileName) {
        if (inlineToken != null && tokenFile != null) {
            throw new IllegalArgumentException("--mojang-token and --mojang-token-file are mutually exclusive");
        }
        final String token;
        if (tokenFile != null) {
            try {
                token = Files.readString(tokenFile).strip();
            } catch (IOException e) {
                throw new IllegalArgumentException("failed to read --mojang-token-file " + tokenFile + ": " + e.getMessage());
            }
            if (token.isEmpty()) throw new IllegalArgumentException("--mojang-token-file is empty: " + tokenFile);
        } else {
            token = inlineToken;
        }
        if (token == null) {
            if (profileUuid != null || profileName != null) {
                throw new IllegalArgumentException("--mojang-profile-* requires --mojang-token or --mojang-token-file");
            }
            return null;
        }
        return new MojangAuth(token, profileUuid, profileName);
    }

    private static int parseNonNegativeInt(String s, String flag) {
        int n;
        try {
            n = Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid integer for " + flag + ": " + s);
        }
        if (n < 0) throw new IllegalArgumentException(flag + " must be >= 0");
        return n;
    }

    private static final String USAGE = """
            Usage: minestom-web [options]

            Run the Minestom web proxy + dashboard standalone, in front of a Minecraft
            server. The proxy accepts player connections on --bind, sends fresh logins to
            --backend, and the dashboard exposes the live view on --dashboard. Players can
            be moved to any other reachable address at runtime via POST /api/players/{uuid}
            /move — backends are not pre-registered.

            Options:
              -b, --bind <host:port>      Public proxy bind address (default 0.0.0.0:25565)
                  --backend <host:port>   Landing target for fresh LOGIN connections. Required.
              -d, --dashboard <host:port> Dashboard HTTP/WebSocket bind (default 127.0.0.1:8080)
              -t, --token <secret>        Dashboard auth token (optional)
                  --decoded-packet-cache <n>
                                          Per-session decoded packet cache size (default 5000)
                  --data-channel <id>     Plugin channel for per-player NBT
                                          (default %s)
                  --persistence <path>    Session SQLite path, or 'none' to disable
                                          (default sessions.db)
                  --mojang-token <tok>    Mojang minecraftservices access_token used to
                                          authenticate the proxy to an online-mode upstream.
                                          Leaks via 'ps' — prefer --mojang-token-file.
                  --mojang-token-file <p> Read the Mojang access_token from a file.
                  --mojang-profile-uuid <uuid>
                                          Bot account UUID. Optional; auto-resolved from the
                                          access_token at startup if omitted.
                  --mojang-profile-name <name>
                                          Bot account username. Optional; auto-resolved from
                                          the access_token at startup if omitted.
                  --login                 Sign in to Microsoft via device-code flow, exchange
                                          for a Mojang token, and write it to the path given
                                          by --mojang-token-file. Then exit (does not start
                                          the proxy). Requires --ms-client-id.
                  --ms-client-id <id>     Azure application ID used by --login. Register your
                                          own at portal.azure.com (Microsoft Entra ID → App
                                          registrations) with the XboxLive.signin permission.
                  --replay-mode           Run the dashboard standalone without the TCP proxy.
                                          The homepage becomes a drop zone — each browser tab
                                          uploads a sessions.sqlite file produced by a prior
                                          live run, and the dashboard replays it in isolation.
                                          --bind / --backend / --mojang-* / --persistence are
                                          ignored.
              -h, --help                  Show this help and exit

            Addresses accept host:port, :port (with default host), or [ipv6]:port.
            """.formatted(ProxyConfig.DEFAULT_DATA_CHANNEL);

    private Main() {
    }
}
