package net.minestom.web.cli;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/// Microsoft device-code OAuth flow that ends with a Mojang minecraftservices `access_token`.
/// The user gets a short code and a URL; once they confirm in any browser the flow walks the
/// Xbox Live → XSTS → Mojang chain and returns the final token + the bot's profile.
///
/// Single public entry point: [#login(String)]. Returns synchronously after the user completes
/// (or times out). All HTTP calls use `HttpURLConnection` to avoid adding `java.net.http` to
/// the module graph.
///
/// You must register an Azure application with the `XboxLive.signin` delegated permission and
/// pass its client ID. There is no shared / default ID — using someone else's would leak
/// telemetry to their tenant and may be revoked. Registration is free and takes ~5 minutes at
/// `https://portal.azure.com → Microsoft Entra ID → App registrations → New registration`
/// (account types: personal Microsoft accounts; redirect URI: not needed for device flow).
public final class MicrosoftAuth {
    private static final String DEVICE_CODE_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
    private static final String TOKEN_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
    private static final String XBL_AUTH_URL = "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XSTS_AUTH_URL = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String MC_LOGIN_URL = "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String MC_PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile";

    private static final String SCOPE = "XboxLive.signin offline_access";

    public record Result(String accessToken, UUID profileUuid, String profileName) {}

    private MicrosoftAuth() {}

    /// Run the full sign-in flow. Prints user-facing instructions to stdout; blocks until the
    /// user confirms in the browser (or the device code expires).
    public static Result login(String clientId) throws IOException, InterruptedException {
        final DeviceCode device = requestDeviceCode(clientId);
        System.out.println();
        System.out.println("Open this URL in any browser:");
        System.out.println("    " + device.verificationUrl);
        System.out.println("Enter the code:");
        System.out.println("    " + device.userCode);
        System.out.println();
        System.out.printf("Waiting for confirmation (code expires in %d minutes)%n",
                Math.max(1, device.expiresIn / 60));

        final String msToken = pollForToken(clientId, device);
        final XblToken xbl = xboxLiveAuth(msToken);
        final XstsToken xsts = xstsAuthorize(xbl.token);
        final String mcToken = mojangLogin(xsts.token, xsts.userHash);
        final Profile profile = fetchProfile(mcToken);
        System.out.println();
        System.out.println("Signed in as " + profile.name + " (" + profile.uuid + ")");
        return new Result(mcToken, profile.uuid, profile.name);
    }

    // ---- step 1: device code request ----------------------------------------------------

    private record DeviceCode(String deviceCode, String userCode, String verificationUrl,
                              int expiresIn, int interval) {}

    private static DeviceCode requestDeviceCode(String clientId) throws IOException {
        final String form = "client_id=" + enc(clientId) + "&scope=" + enc(SCOPE);
        final Response r = postForm(DEVICE_CODE_URL, form);
        if (r.status != 200) throw apiError("device code request", r);
        final JsonObject body = JsonParser.parseString(r.body).getAsJsonObject();
        return new DeviceCode(
                body.get("device_code").getAsString(),
                body.get("user_code").getAsString(),
                body.get("verification_uri").getAsString(),
                body.get("expires_in").getAsInt(),
                body.has("interval") ? body.get("interval").getAsInt() : 5);
    }

    // ---- step 2: poll until the user signs in ------------------------------------------

    private static String pollForToken(String clientId, DeviceCode device) throws IOException, InterruptedException {
        final long deadline = System.nanoTime() + Duration.ofSeconds(device.expiresIn).toNanos();
        int intervalSeconds = device.interval;
        while (true) {
            if (System.nanoTime() > deadline) {
                throw new IOException("sign-in not completed in time — re-run --login");
            }
            Thread.sleep(intervalSeconds * 1000L);

            final String form = "grant_type=urn:ietf:params:oauth:grant-type:device_code"
                    + "&client_id=" + enc(clientId)
                    + "&device_code=" + enc(device.deviceCode);
            final Response r = postForm(TOKEN_URL, form);
            if (r.status == 200) {
                return JsonParser.parseString(r.body).getAsJsonObject()
                        .get("access_token").getAsString();
            }
            // 400 with a JSON body carrying `error` is the documented continue-or-fail signal.
            final JsonObject err;
            try { err = JsonParser.parseString(r.body).getAsJsonObject(); }
            catch (Exception _) { throw apiError("token poll", r); }
            final String code = err.has("error") ? err.get("error").getAsString() : "unknown";
            switch (code) {
                case "authorization_pending" -> { /* keep polling */ }
                case "slow_down" -> intervalSeconds += 5;
                case "expired_token" -> throw new IOException("sign-in code expired — re-run --login");
                case "authorization_declined" -> throw new IOException("sign-in declined by user");
                default -> {
                    final String desc = err.has("error_description")
                            ? err.get("error_description").getAsString() : "";
                    throw new IOException("Microsoft sign-in failed: " + code
                            + (desc.isEmpty() ? "" : " — " + desc));
                }
            }
        }
    }

    // ---- step 3: Xbox Live --------------------------------------------------------------

    private record XblToken(String token, String userHash) {}

    private static XblToken xboxLiveAuth(String msToken) throws IOException {
        final String json = """
                {"Properties":{"AuthMethod":"RPS","SiteName":"user.auth.xboxlive.com","RpsTicket":"d=%s"},"RelyingParty":"http://auth.xboxlive.com","TokenType":"JWT"}"""
                .formatted(msToken);
        final Response r = postJson(XBL_AUTH_URL, json);
        if (r.status != 200) throw apiError("Xbox Live auth", r);
        return parseXblOrXsts(r.body);
    }

    // ---- step 4: XSTS authorize ---------------------------------------------------------

    private record XstsToken(String token, String userHash) {}

    private static XstsToken xstsAuthorize(String xblToken) throws IOException {
        final String json = """
                {"Properties":{"SandboxId":"RETAIL","UserTokens":["%s"]},"RelyingParty":"rp://api.minecraftservices.com/","TokenType":"JWT"}"""
                .formatted(xblToken);
        final Response r = postJson(XSTS_AUTH_URL, json);
        if (r.status == 401) {
            // XSTS surfaces user-friendly failure modes as XErr codes; translate the common
            // ones rather than dumping the raw JSON, which would only confuse the user.
            JsonObject body;
            try { body = JsonParser.parseString(r.body).getAsJsonObject(); }
            catch (Exception _) { throw apiError("XSTS authorize", r); }
            final long xerr = body.has("XErr") ? body.get("XErr").getAsLong() : 0L;
            final String reason;
            if (xerr == 2148916233L) reason = "this Microsoft account has no Xbox profile — visit xbox.com once to create one";
            else if (xerr == 2148916235L) reason = "Xbox Live is not available in this account's country/region";
            else if (xerr == 2148916236L || xerr == 2148916237L) reason = "this account requires adult verification";
            else if (xerr == 2148916238L) reason = "this is a child account; an adult must add it to a Microsoft family";
            else reason = "XErr=" + xerr;
            throw new IOException("XSTS authorize failed: " + reason);
        }
        if (r.status != 200) throw apiError("XSTS authorize", r);
        final XblToken parsed = parseXblOrXsts(r.body);
        return new XstsToken(parsed.token, parsed.userHash);
    }

    /// XBL and XSTS share a response shape: `{Token, DisplayClaims:{xui:[{uhs:"..."}]}}`.
    private static XblToken parseXblOrXsts(String body) {
        final JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
        final String token = obj.get("Token").getAsString();
        final String userHash = obj.getAsJsonObject("DisplayClaims")
                .getAsJsonArray("xui")
                .get(0).getAsJsonObject()
                .get("uhs").getAsString();
        return new XblToken(token, userHash);
    }

    // ---- step 5: Mojang login -----------------------------------------------------------

    private static String mojangLogin(String xstsToken, String userHash) throws IOException {
        final String json = "{\"identityToken\":\"XBL3.0 x=" + userHash + ";" + xstsToken + "\"}";
        final Response r = postJson(MC_LOGIN_URL, json);
        if (r.status != 200) throw apiError("Mojang login_with_xbox", r);
        return JsonParser.parseString(r.body).getAsJsonObject().get("access_token").getAsString();
    }

    // ---- step 6: profile probe (also validates the token) -------------------------------

    public record Profile(UUID uuid, String name) {}

    /// Resolve a Minecraft access_token to its UUID + username via
    /// `GET /minecraft/profile`. Doubles as a token-validity check at startup.
    public static Profile fetchProfile(String mcToken) throws IOException {
        final Response r = get(MC_PROFILE_URL, "Bearer " + mcToken);
        if (r.status == 404) {
            // This Microsoft account doesn't own Minecraft, or no profile has been created yet.
            throw new IOException("no Minecraft profile on this Microsoft account — buy / migrate Minecraft Java Edition first");
        }
        if (r.status != 200) throw apiError("fetch profile", r);
        final JsonObject body = JsonParser.parseString(r.body).getAsJsonObject();
        return new Profile(
                parseUnhyphenatedUuid(body.get("id").getAsString()),
                body.get("name").getAsString());
    }

    private static UUID parseUnhyphenatedUuid(String s) {
        final String hyphenated = s.replaceFirst(
                "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)",
                "$1-$2-$3-$4-$5");
        return UUID.fromString(hyphenated);
    }

    // ---- HTTP helpers -------------------------------------------------------------------

    private record Response(int status, String body) {}

    private static Response postForm(String url, String formBody) throws IOException {
        return send(url, "POST", "application/x-www-form-urlencoded", null, formBody);
    }

    private static Response postJson(String url, String json) throws IOException {
        return send(url, "POST", "application/json", null, json);
    }

    private static Response get(String url, @Nullable String authorization) throws IOException {
        return send(url, "GET", null, authorization, null);
    }

    private static Response send(String url, String method, @Nullable String contentType,
                                 @Nullable String authorization, @Nullable String body) throws IOException {
        final HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(30_000);
        conn.setRequestProperty("Accept", "application/json");
        if (contentType != null) conn.setRequestProperty("Content-Type", contentType);
        if (authorization != null) conn.setRequestProperty("Authorization", authorization);
        if (body != null) {
            conn.setDoOutput(true);
            final byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(payload.length);
            try (OutputStream out = conn.getOutputStream()) { out.write(payload); }
        }
        final int status = conn.getResponseCode();
        final InputStream stream = (status >= 200 && status <= 299)
                ? conn.getInputStream() : conn.getErrorStream();
        final String responseBody = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        return new Response(status, responseBody);
    }

    private static IOException apiError(String stage, Response r) {
        final String snippet = r.body.length() > 256 ? r.body.substring(0, 256) + "…" : r.body;
        return new IOException(stage + " failed (HTTP " + r.status + "): " + snippet);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
