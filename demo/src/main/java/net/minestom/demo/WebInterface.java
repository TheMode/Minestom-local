package net.minestom.demo;

import com.sun.management.OperatingSystemMXBean;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.text.Component;
import net.minestom.server.MinecraftServer;
import net.minestom.server.adventure.audience.Audiences;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.event.server.ServerTickMonitorEvent;
import net.minestom.server.timer.TaskSchedule;
import net.minestom.web.ControlBridge;
import net.minestom.web.ControlPacket;
import net.minestom.web.ProxyConfig;
import net.minestom.web.ProxyServer;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/// Demo wiring for the web interface. The proxy holds the public Minecraft port and forwards
/// to an upstream Minestom server bound to a loopback port; the dashboard binds to
/// `MINESTOM_WEB_DASHBOARD_PORT` (default 8080). The control bridge carries console lines,
/// 1 Hz JVM/tick metrics, and global NBT into the dashboard.
public final class WebInterface {

    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getenv().getOrDefault("MINESTOM_WEB_INTERFACE", "true"));

    public static String bindHost() {
        return ENABLED ? "127.0.0.1" : "0.0.0.0";
    }

    public static int bindPort() {
        return ENABLED ? env("MINESTOM_WEB_UPSTREAM_PORT", 25566) : 25565;
    }

    public static void register() {
        if (!ENABLED) return;
        final int proxy = env("MINESTOM_WEB_PROXY_PORT", 25565);
        final int dashboard = env("MINESTOM_WEB_DASHBOARD_PORT", 8080);

        final ProxyServer web = ProxyServer.builder()
                .bindProxy(new InetSocketAddress("0.0.0.0", proxy))
                .defaultBackend(new InetSocketAddress("127.0.0.1", bindPort()))
                .bindDashboard(new InetSocketAddress("127.0.0.1", dashboard))
                .token(System.getenv("MINESTOM_WEB_TOKEN"))
                .build();
        web.start();
        Runtime.getRuntime().addShutdownHook(new Thread(web::close, "Minestom-Web-Shutdown"));

        final ControlBridge bridge = web.control();
        bridge.setOnOutbound(WebInterface::handleOutbound);
        teeConsole(bridge);
        schedulePumps(bridge);

        System.out.printf("[web] proxy on 0.0.0.0:%d → 127.0.0.1:%d · dashboard http://127.0.0.1:%d/%n",
                proxy, bindPort(), dashboard);
    }

    /// Run dashboard-initiated packets on the tick thread so handlers see the same threading
    /// guarantees as a player-typed command.
    private static void handleOutbound(ControlPacket packet) {
        MinecraftServer.getSchedulerManager().scheduleNextTick(() -> {
            final var cm = MinecraftServer.getConnectionManager();
            switch (packet) {
                case ControlPacket.Command(String c) -> {
                    final var commands = MinecraftServer.getCommandManager();
                    commands.execute(commands.getConsoleSender(), c.startsWith("/") ? c.substring(1) : c);
                }
                case ControlPacket.Broadcast(Component m) -> Audiences.players().sendMessage(m);
                case ControlPacket.Kick(UUID id, String reason) -> {
                    final var p = cm.getOnlinePlayerByUuid(id);
                    if (p != null) p.kick(Component.text(reason));
                }
                default -> {
                }
            }
        });
    }

    /// Tee stdout/stderr into ConsoleLine packets so dashboard subscribers see SLF4J output.
    /// Logback resolves the underlying stream per-write, so swapping in after init still works.
    private static void teeConsole(ControlBridge bridge) {
        System.setOut(linePump(System.out, bridge, "INFO"));
        System.setErr(linePump(System.err, bridge, "ERROR"));
    }

    private static PrintStream linePump(PrintStream original, ControlBridge bridge, String level) {
        final ByteArrayOutputStream buf = new ByteArrayOutputStream(256);
        final ThreadLocal<Boolean> reentrant = ThreadLocal.withInitial(() -> false);
        return new PrintStream(new OutputStream() {
            @Override
            public synchronized void write(int b) {
                original.write(b);
                capture(b);
            }

            @Override
            public synchronized void write(byte[] b, int off, int len) {
                original.write(b, off, len);
                for (int i = 0; i < len; i++) capture(b[off + i] & 0xFF);
            }

            @Override
            public void flush() {
                original.flush();
            }

            private void capture(int b) {
                if (b == '\n') flushLine();
                else if (b != '\r') buf.write(b);
            }

            private void flushLine() {
                if (buf.size() == 0 || reentrant.get()) {
                    buf.reset();
                    return;
                }
                final String msg = buf.toString(StandardCharsets.UTF_8);
                buf.reset();
                reentrant.set(true);
                try {
                    bridge.receive(new ControlPacket.ConsoleLine(System.currentTimeMillis(), level, msg));
                } catch (Throwable ignored) {
                } finally {
                    reentrant.set(false);
                }
            }
        }, true);
    }

    private static void schedulePumps(ControlBridge bridge) {
        final var os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        final var runtime = ManagementFactory.getRuntimeMXBean();
        final var threads = ManagementFactory.getThreadMXBean();
        final var heap = ManagementFactory.getMemoryMXBean();
        final long maxMem = Runtime.getRuntime().maxMemory();
        final var scheduler = MinecraftServer.getSchedulerManager();
        final var connections = MinecraftServer.getConnectionManager();

        final AtomicLong msptNanos = new AtomicLong();
        MinecraftServer.getGlobalEventHandler().addListener(ServerTickMonitorEvent.class,
                e -> msptNanos.set((long) (e.getTickMonitor().getTickTime() * 1_000_000.0)));

        scheduler.submitTask(() -> {
            final double mspt = msptNanos.get() / 1_000_000.0;
            final double tps = mspt > 0 ? Math.min(MinecraftServer.TICK_PER_SECOND, 1000.0 / mspt) : MinecraftServer.TICK_PER_SECOND;
            bridge.receive(new ControlPacket.Metrics(
                    System.currentTimeMillis(),
                    Math.max(0.0, os.getCpuLoad()),
                    heap.getHeapMemoryUsage().getUsed(), maxMem,
                    threads.getThreadCount(), runtime.getUptime(),
                    mspt, tps,
                    connections.getOnlinePlayers().size()));
            return TaskSchedule.seconds(1);
        });

        scheduler.submitTask(() -> {
            bridge.receive(new ControlPacket.ServerData(CompoundBinaryTag.builder()
                    .putString("event", "winter_celebration")
                    .putInt("season", 2)
                    .putInt("onlinePlayers", connections.getOnlinePlayers().size())
                    .putLong("epochMs", System.currentTimeMillis())
                    .build()));
            return TaskSchedule.seconds(2);
        });

        // Per-player NBT on the reserved minestom:web/data channel — proxy intercepts it, the
        // client never sees the packet but the dashboard sees the decoded NBT.
        MinecraftServer.getGlobalEventHandler().addListener(PlayerSpawnEvent.class, event -> {
            final var player = event.getPlayer();
            player.scheduler().submitTask(() -> {
                if (!player.isOnline()) return TaskSchedule.stop();
                final CompoundBinaryTag data = CompoundBinaryTag.builder()
                        .putString("rank", (player.getUuid().hashCode() & 0xF) == 0 ? "vip" : "member")
                        .putInt("kills", (int) ((System.currentTimeMillis() / 1000) % 50))
                        .putString("partyId", UUID.nameUUIDFromBytes(player.getUuid().toString().getBytes()).toString())
                        .putLong("lastSeenMs", System.currentTimeMillis())
                        .build();
                player.sendPluginMessage(ProxyConfig.DEFAULT_DATA_CHANNEL, encode(data));
                return TaskSchedule.seconds(1);
            });
        });
    }

    private static byte[] encode(CompoundBinaryTag tag) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            BinaryTagIO.writer().write(tag, out, BinaryTagIO.Compression.NONE);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        return out.toByteArray();
    }

    private static int env(String name, int def) {
        return Integer.parseInt(System.getenv().getOrDefault(name, Integer.toString(def)));
    }

    private WebInterface() {
    }
}
