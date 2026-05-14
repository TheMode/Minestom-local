package net.minestom.web;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/// Credentials the proxy uses to log in to an online-mode upstream. The proxy holds **one**
/// Mojang account; every incoming player is forwarded to the upstream under this identity.
///
/// `accessToken` is the `minecraftservices.com` access token (the one returned by
/// `POST /authentication/login_with_xbox`), **not** the Microsoft / XSTS token. Tokens are
/// short-lived (~24h) — refreshing them is out of scope for the proxy, restart with a fresh
/// token.
///
/// `profileUuid` and `profileName` describe the bot and **must be non-null** by the time the
/// proxy uses this record. They may be left `null` here for convenience; callers can resolve
/// them via [net.minestom.web.cli.MicrosoftAuth#fetchProfile] (the `--login` CLI flow and the bundled `Main` do
/// this automatically at startup). Providing them explicitly also lets the proxy start
/// without Mojang reachability.
public record MojangAuth(
        String accessToken,
        @Nullable UUID profileUuid,
        @Nullable String profileName
) {
    public MojangAuth {
        Objects.requireNonNull(accessToken, "accessToken is required");
        if (accessToken.isBlank()) throw new IllegalArgumentException("accessToken is blank");
    }

    public MojangAuth(String accessToken) {
        this(accessToken, null, null);
    }
}
