package net.minestom.web.internal.session;

/// Receives [SessionEvent]s on the subscription's drainer thread (not the session worker).
@FunctionalInterface
public interface SessionListener {
    void onEvent(SessionEvent event);
}
