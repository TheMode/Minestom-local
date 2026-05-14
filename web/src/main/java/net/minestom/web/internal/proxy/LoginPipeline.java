package net.minestom.web.internal.proxy;

import net.minestom.server.extras.mojangAuth.MojangCrypt;
import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.client.common.ClientCookieResponsePacket;
import net.minestom.server.network.packet.client.handshake.ClientHandshakePacket;
import net.minestom.server.network.packet.client.login.ClientEncryptionResponsePacket;
import net.minestom.server.network.packet.client.login.ClientLoginStartPacket;
import net.minestom.server.network.packet.server.ServerPacket;
import net.kyori.adventure.text.Component;
import net.minestom.server.network.packet.server.common.CookieRequestPacket;
import net.minestom.server.network.packet.server.login.EncryptionRequestPacket;
import net.minestom.server.network.packet.server.login.LoginDisconnectPacket;
import net.minestom.server.network.packet.server.login.LoginSuccessPacket;
import net.minestom.server.network.packet.server.login.SetCompressionPacket;
import net.minestom.server.network.player.GameProfile;
import net.minestom.server.registry.Registries;
import net.minestom.server.utils.mojang.MojangUtils;
import net.minestom.web.BackendRouter;
import net.minestom.web.BackendTarget;
import net.minestom.web.MojangAuth;
import net.minestom.web.ProxyConfig;
import net.minestom.web.internal.codec.PacketDecoder;
import org.jetbrains.annotations.Nullable;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.io.IOException;
import java.math.BigInteger;
import java.net.SocketAddress;
import java.nio.channels.SocketChannel;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/// Login termination for both legs of the proxy.
///
/// The proxy plays "server" to the client (Mojang `hasJoined` runs against the player's
/// session) and "client" to the upstream (Mojang `join` runs against the bot's session).
/// Each leg ends with its own AES key; once both are installed, the proxy can decrypt
/// + inspect + re-encrypt every byte in either direction.
///
/// **Address-driven routing.** After reading the client handshake the pipeline asks the
/// [BackendRouter] which address to dial. On `Intent.TRANSFER` reconnects the pipeline first
/// requests the journey cookie via [CookieRequestPacket] / [ClientCookieResponsePacket] and
/// feeds the resolved target address back into the router context — so cookie-driven transfers
/// always re-land on the address the original `movePlayer` minted them for.
public final class LoginPipeline {
    private static final SecureRandom RANDOM = new SecureRandom();

    /// Upstream sent a [LoginDisconnectPacket] during the bot's login handshake.
    public static final class UpstreamRejected extends IOException {
        private final Component reason;
        UpstreamRejected(Component reason) { this.reason = reason; }
        public Component reason() { return reason; }
    }

    public record ClientLegResult(ClientLoginStartPacket loginStart,
                                  EncryptionRequestPacket encryptionRequest,
                                  ClientEncryptionResponsePacket encryptionResponse,
                                  GameProfile playerProfile,
                                  SecretKey aesKey) {}

    private record UpstreamLegResult(@Nullable PacketDecoder.EncryptionContext cipher,
                                     int compressionThreshold) {}

    public record Result(SocketChannel upstream,
                         BackendTarget backend,
                         @Nullable JourneyTracker.Pending consumedCookie,
                         @Nullable PacketDecoder.EncryptionContext clientCipher,
                         @Nullable PacketDecoder.EncryptionContext upstreamCipher,
                         int compressionThreshold,
                         ClientHandshakePacket handshake,
                         @Nullable ClientLegResult clientLeg,
                         byte[] initialClientBytes,
                         byte[] initialUpstreamBytes) {}

    private LoginPipeline() {}

    /// Read the client's handshake, decide whether this is a STATUS ping (no auth, route to
    /// default), a fresh LOGIN, or a TRANSFER reconnect (cookie-driven route). Then run both
    /// auth legs against the chosen address and the appropriate bot identity, and forge a
    /// LoginSuccess carrying the real player profile so the client never sees the bot identity.
    public static Result run(SocketChannel client, ProxyConfig config, BackendRouter router,
                             JourneyTracker journeys, Registries registries) throws IOException {
        final NetworkBuffer clientCarry = PacketDecoder.newCarry(registries);
        final NetworkBuffer upstreamCarry = PacketDecoder.newCarry(registries);

        final ClientHandshakePacket handshake = LoginIo.readClient(
                client, clientCarry, ConnectionState.HANDSHAKE, null, -1, ClientHandshakePacket.class);

        // Status pings AND offline-mode proxies (no Mojang bot) flow through transparently:
        // pick a backend, dial it, forward the handshake, and let the byte pump take over.
        // The client-side encryption dance is online-mode only.
        if (handshake.intent() == ClientHandshakePacket.Intent.STATUS || config.mojang() == null) {
            final BackendRouter.Context.Intent intent = mapIntent(handshake.intent());
            final BackendTarget chosen = chooseBackend(handshake, null, config, router, intent);
            final SocketChannel upstream = openUpstream(chosen);
            try {
                LoginIo.writeClient(upstream, ConnectionState.HANDSHAKE, handshake, null, -1, registries);
                return new Result(upstream, chosen, null, null, null, -1,
                        handshake, null,
                        unreadBytes(clientCarry), unreadBytes(upstreamCarry));
            } catch (Throwable t) {
                try { upstream.close(); } catch (IOException _) {}
                throw t;
            }
        }

        // LOGIN or TRANSFER, online-mode: read LoginStart immediately so we can also consume
        // the journey cookie before opening any upstream socket.
        final ClientLoginStartPacket loginStart = LoginIo.readClient(
                client, clientCarry, ConnectionState.LOGIN, null, -1, ClientLoginStartPacket.class);

        JourneyTracker.Pending consumedCookie = null;
        if (handshake.intent() == ClientHandshakePacket.Intent.TRANSFER) {
            LoginIo.writeServer(client, ConnectionState.LOGIN,
                    new CookieRequestPacket(JourneyTracker.COOKIE_KEY), null, -1, registries);
            final ClientCookieResponsePacket cookieResp = LoginIo.readClient(
                    client, clientCarry, ConnectionState.LOGIN, null, -1, ClientCookieResponsePacket.class);
            if (JourneyTracker.COOKIE_KEY.equals(cookieResp.key())) {
                consumedCookie = journeys.consume(cookieResp.value());
            }
        }

        final BackendTarget chosen = chooseBackend(handshake, consumedCookie,
                config, router, mapIntent(handshake.intent()));

        // Phase 2: open the upstream socket NOW that we know where to dial.
        final SocketChannel upstream = openUpstream(chosen);
        try {
            LoginIo.writeClient(upstream, ConnectionState.HANDSHAKE, handshake, null, -1, registries);

            final ClientLegResult clientLeg = authenticateClientLeg(client, clientCarry, loginStart,
                    client.getRemoteAddress(), registries);

            // Client is AES from EncryptionResponse onward, including any forwarded disconnect.
            final PacketDecoder.EncryptionContext clientCipher = makeCipher(clientLeg.aesKey());

            // Per-target bot identity falls back to the process-wide MojangAuth.
            final MojangAuth bot = chosen.mojang() != null ? chosen.mojang() : config.mojang();
            final UpstreamLegResult upstreamLeg;
            try {
                upstreamLeg = bot == null
                        ? new UpstreamLegResult(null, -1)
                        : authenticateUpstreamLeg(upstream, upstreamCarry, bot, registries);
            } catch (UpstreamRejected rejected) {
                try {
                    LoginIo.writeServer(client, ConnectionState.LOGIN,
                            new LoginDisconnectPacket(rejected.reason()),
                            clientCipher.encrypt(), -1, registries);
                } catch (IOException _) {}
                throw rejected;
            }
            final int compression = upstreamLeg.compressionThreshold();
            if (compression > 0) {
                LoginIo.writeServer(client, ConnectionState.LOGIN, new SetCompressionPacket(compression),
                        clientCipher.encrypt(), -1, registries);
            }
            LoginIo.writeServer(client, ConnectionState.LOGIN, new LoginSuccessPacket(clientLeg.playerProfile()),
                    clientCipher.encrypt(), compression, registries);

            return new Result(upstream, chosen, consumedCookie, clientCipher, upstreamLeg.cipher(),
                    compression,
                    handshake, clientLeg,
                    unreadBytes(clientCarry), unreadBytes(upstreamCarry));
        } catch (Throwable t) {
            try { upstream.close(); } catch (IOException _) {}
            throw t;
        }
    }

    private static BackendTarget chooseBackend(ClientHandshakePacket handshake,
                                               @Nullable JourneyTracker.Pending cookie,
                                               ProxyConfig config, BackendRouter router,
                                               BackendRouter.Context.Intent intent) throws IOException {
        final BackendRouter.Context ctx = new BackendRouter.Context(
                config.defaultBackend(),
                handshake.serverAddress(),
                handshake.serverPort(),
                handshake.protocolVersion(),
                intent,
                cookie == null ? null : cookie.targetAddress());
        final BackendTarget chosen = router.route(ctx);
        if (chosen == null) throw new IOException("router refused connection");
        return chosen;
    }

    private static BackendRouter.Context.Intent mapIntent(ClientHandshakePacket.Intent intent) {
        return switch (intent) {
            case STATUS -> BackendRouter.Context.Intent.STATUS;
            case TRANSFER -> BackendRouter.Context.Intent.TRANSFER;
            case LOGIN -> BackendRouter.Context.Intent.LOGIN;
        };
    }

    private static SocketChannel openUpstream(BackendTarget chosen) throws IOException {
        // Sockets from `SocketChannel.open(address)` are blocking by default — what we need
        // for the auth dance — and they get switched to non-blocking by the connection worker.
        return SocketChannel.open(chosen.address());
    }

    /// Run the proxy-as-server handshake against a freshly accepted client whose
    /// `ClientLoginStartPacket` has already been consumed by the caller. Runs the RSA + Mojang
    /// `hasJoined` round-trip and returns the verified player profile + the AES key shared
    /// with the client. The cipher is NOT installed on the socket — the caller does that once
    /// it's ready to also send encrypted frames back.
    private static ClientLegResult authenticateClientLeg(SocketChannel client, NetworkBuffer carry,
                                                         ClientLoginStartPacket loginStart,
                                                         SocketAddress clientAddress,
                                                         Registries registries) throws IOException {
        final KeyPair keyPair = MojangCrypt.generateKeyPair();
        if (keyPair == null) throw new IOException("RSA keypair generation failed");

        final byte[] nonce = new byte[4];
        RANDOM.nextBytes(nonce);
        final EncryptionRequestPacket encryptionRequest =
                new EncryptionRequestPacket("", keyPair.getPublic().getEncoded(), nonce, true);
        LoginIo.writeServer(client, ConnectionState.LOGIN, encryptionRequest, null, -1, registries);

        final ClientEncryptionResponsePacket response = LoginIo.readClient(
                client, carry, ConnectionState.LOGIN, null, -1, ClientEncryptionResponsePacket.class);

        final byte[] verifyToken = MojangCrypt.decryptUsingKey(keyPair.getPrivate(), response.encryptedVerifyToken());
        if (!Arrays.equals(verifyToken, nonce)) {
            throw new IOException("client encryption nonce mismatch");
        }
        final SecretKey sharedSecret = MojangCrypt.decryptByteToSecretKey(keyPair.getPrivate(), response.sharedSecret());

        final byte[] digest = MojangCrypt.digestData("", keyPair.getPublic(), sharedSecret);
        if (digest == null) throw new IOException("server-hash digest failed");
        final String serverId = new BigInteger(digest).toString(16);

        final GameProfile playerProfile = profileFromHasJoined(loginStart.username(), serverId, clientAddress);
        return new ClientLegResult(loginStart, encryptionRequest, response, playerProfile, sharedSecret);
    }

    /// Run the proxy-as-client handshake against an already-connected upstream socket. Sends
    /// the forwarded handshake + a `LoginStart` carrying the bot identity, then if the
    /// upstream is in online mode performs the RSA + Mojang `join` round-trip and installs
    /// AES. Stops at (and consumes) the upstream's `LoginSuccess`. Returns the negotiated
    /// cipher context (null when the upstream is offline-mode) plus the compression threshold.
    private static UpstreamLegResult authenticateUpstreamLeg(SocketChannel upstream, NetworkBuffer carry,
                                                             MojangAuth bot, Registries registries) throws IOException {
        if (bot.profileUuid() == null || bot.profileName() == null) {
            throw new IllegalArgumentException(
                    "MojangAuth must have profileUuid and profileName resolved before reaching the pipeline");
        }
        LoginIo.writeClient(upstream, ConnectionState.LOGIN,
                new ClientLoginStartPacket(bot.profileName(), bot.profileUuid()),
                null, -1, registries);

        PacketDecoder.EncryptionContext cipher = null;
        int compressionThreshold = -1;
        while (true) {
            final ServerPacket.Login packet = LoginIo.readServer(upstream, carry, ConnectionState.LOGIN,
                    cipher == null ? null : cipher.decrypt(), compressionThreshold, ServerPacket.Login.class);
            switch (packet) {
                case EncryptionRequestPacket req -> {
                    if (cipher != null) throw new IOException("upstream sent EncryptionRequest twice");
                    cipher = makeCipher(exchangeUpstreamEncryption(upstream, req, bot, registries));
                }
                case SetCompressionPacket(int threshold) -> {
                    compressionThreshold = threshold;
                }
                case LoginSuccessPacket _ -> {
                    return new UpstreamLegResult(cipher, compressionThreshold);
                }
                case LoginDisconnectPacket(Component reason) -> throw new UpstreamRejected(reason);
                default -> throw new IOException("unexpected upstream login packet: "
                        + packet.getClass().getSimpleName());
            }
        }
    }

    private static SecretKey exchangeUpstreamEncryption(SocketChannel upstream, EncryptionRequestPacket req,
                                                        MojangAuth bot, Registries registries) throws IOException {
        final PublicKey upstreamPubKey = parseRsaPublicKey(req.publicKey());
        final SecretKey sharedSecret = generateAesKey();

        final byte[] digest = MojangCrypt.digestData(req.serverId(), upstreamPubKey, sharedSecret);
        if (digest == null) throw new IOException("server-hash digest failed");
        final String serverHash = new BigInteger(digest).toString(16);

        MojangUtils.joinSession(bot.accessToken(), bot.profileUuid(), serverHash);

        final byte[] encryptedSecret = rsaEncrypt(upstreamPubKey, sharedSecret.getEncoded());
        final byte[] encryptedNonce = rsaEncrypt(upstreamPubKey, req.verifyToken());
        LoginIo.writeClient(upstream, ConnectionState.LOGIN,
                new ClientEncryptionResponsePacket(encryptedSecret, encryptedNonce),
                null, -1, registries);
        return sharedSecret;
    }

    // ---- helpers ------------------------------------------------------------------------

    private static PacketDecoder.EncryptionContext makeCipher(SecretKey key) {
        return new PacketDecoder.EncryptionContext(
                MojangCrypt.getCipher(Cipher.ENCRYPT_MODE, key),
                MojangCrypt.getCipher(Cipher.DECRYPT_MODE, key));
    }

    private static byte[] unreadBytes(NetworkBuffer buffer) {
        return buffer.read(NetworkBuffer.RAW_BYTES);
    }

    private static SecretKey generateAesKey() {
        try {
            final KeyGenerator kg = KeyGenerator.getInstance("AES");
            kg.init(128, RANDOM);
            return kg.generateKey();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES key generation failed", e);
        }
    }

    private static PublicKey parseRsaPublicKey(byte[] encoded) throws IOException {
        try {
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(encoded));
        } catch (GeneralSecurityException e) {
            throw new IOException("upstream public key invalid", e);
        }
    }

    private static byte[] rsaEncrypt(PublicKey key, byte[] data) throws IOException {
        try {
            final Cipher c = Cipher.getInstance("RSA");
            c.init(Cipher.ENCRYPT_MODE, key);
            return c.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IOException("RSA encryption failed", e);
        }
    }

    private static GameProfile profileFromHasJoined(String username, String serverId, SocketAddress clientAddress) throws IOException {
        final var json = MojangUtils.authenticateSession(username, serverId, clientAddress);
        final UUID uuid = UUID.fromString(json.get("id").getAsString()
                .replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
        final String name = json.get("name").getAsString();
        final List<GameProfile.Property> properties = new ArrayList<>();
        for (var element : json.get("properties").getAsJsonArray()) {
            final var obj = element.getAsJsonObject();
            properties.add(new GameProfile.Property(
                    obj.get("name").getAsString(),
                    obj.get("value").getAsString(),
                    obj.has("signature") ? obj.get("signature").getAsString() : null));
        }
        return new GameProfile(uuid, name, properties);
    }
}
