package net.minestom.web.internal.session;

import net.minestom.web.PlayerState;
import net.minestom.web.RegisteredRoutine;

import java.util.List;
import java.util.function.Consumer;

/// Typed messages a producer sends into a [Session] mailbox. They are fire-and-forget — the
/// body runs on the session worker thread when the mailbox is drained.
public sealed interface SessionMessage {

    record Mutate(Consumer<PlayerState> body) implements SessionMessage {}

    record SetRoutines(List<RegisteredRoutine> routines) implements SessionMessage {}
}
