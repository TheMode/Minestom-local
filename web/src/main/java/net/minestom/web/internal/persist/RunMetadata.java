package net.minestom.web.internal.persist;

import org.jetbrains.annotations.Nullable;

/// Per-run metadata stamped onto the `sessions` row at open time. Lets a recorded file describe
/// the proxy that produced it: where it listened, where it forwarded, whether it ran in online
/// mode, the plugin channel that carries per-player NBT, and the host's OS/JVM info.
public record RunMetadata(
        @Nullable String bindAddress,
        @Nullable String upstreamAddress,
        @Nullable AuthMode authMode,
        @Nullable String dataChannel,
        @Nullable String hostInfo
) {
    public enum AuthMode { ONLINE, OFFLINE }

    public static final RunMetadata EMPTY = new RunMetadata(null, null, null, null, null);

    public static String currentHostInfo() {
        return System.getProperty("os.name") + "/" + System.getProperty("os.arch")
                + " jdk-" + System.getProperty("java.version");
    }
}
