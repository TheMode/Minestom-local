package net.minestom.web;

import java.util.List;
import java.util.Map;

/// Declarative action discriminator for [Routine].
public sealed interface Action {
    record Inject(String className, Map<String, Object> fields) implements Action {}

    /// `component` is an expression ([String]) or literal [net.kyori.adventure.text.Component] JSON object.
    record Chat(Object component) implements Action {
        public Chat {
            if (component == null) throw new IllegalArgumentException("component required");
        }
    }

    record SetCustom(String key, String value) implements Action {}

    /// Transfer the player to another Minecraft server. Any reachable address works — the
    /// proxy doesn't pre-register backends.
    ///
    /// `address` is an **expression source** ([net.minestom.web.internal.expression.ExpressionEngine]),
    /// evaluated against the player on each fire and then handed to
    /// [net.minestom.web.internal.AddressResolver#parseMinecraft]. The evaluated string accepts the same
    /// shapes as the vanilla client connect dialog — `"play.example.com"` (SRV → fallback to
    /// 25565), `"play.example.com:25577"` (explicit port), or `"[ipv6]:25565"`. Dynamic
    /// targets can interpolate player/global state, e.g. `"\"region-\" + xpLevel + \".example.com\""`.
    record Move(String address) implements Action {
        public Move {
            if (address == null || address.isBlank()) throw new IllegalArgumentException("address required");
        }
    }

    record Sequence(List<Action> actions) implements Action {}
}
