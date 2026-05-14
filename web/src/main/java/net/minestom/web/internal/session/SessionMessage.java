package net.minestom.web.internal.session;

import net.minestom.web.PlayerState;
import net.minestom.web.RegisteredRoutine;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/// Typed messages a producer sends into a [Session] mailbox. Each carries its own ack/reply
/// future. Futures complete on the session worker thread — don't block them.
public sealed interface SessionMessage {

    record Mutate(Consumer<PlayerState> body, CompletableFuture<Void> ack) implements SessionMessage {}

    record SetRoutines(List<RegisteredRoutine> routines) implements SessionMessage {}
}
