package net.minestom.web;

import java.util.UUID;

/// A named, reusable [Action] stored in the in-memory registry.
public record RegisteredAction(UUID id, String name, Action action) {}
