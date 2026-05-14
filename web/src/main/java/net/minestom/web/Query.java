package net.minestom.web;

/// A compiled MQL query. Immutable and thread-safe; callers run [#matches] on the target
/// session's state worker.
public interface Query {
    String source();
    boolean matches(PlayerState state);
}
