package net.minestom.web.internal.codec;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minestom.server.codec.Codec;
import net.minestom.server.codec.Transcoder;
import net.minestom.server.registry.Registries;
import net.minestom.server.registry.RegistryTranscoder;

/// Encode/decode helpers for dashboard wire types via [Transcoder#JSON].
public final class WebJson {
    public static final Transcoder<JsonElement> CODER = coder(Registries.vanilla());

    public static final Codec<JsonElement> ELEMENT = Codec.RAW_VALUE.transform(
            raw -> raw.convertTo(CODER).orElseThrow(),
            value -> Codec.RawValue.of(CODER, value));

    private WebJson() {}

    public static Transcoder<JsonElement> coder(Registries registries) {
        return new RegistryTranscoder<>(Transcoder.JSON, registries);
    }

    public static <T> JsonElement encode(Codec<T> codec, T value) {
        return encode(codec, value, CODER);
    }

    public static <T> JsonElement encode(Codec<T> codec, T value, Transcoder<JsonElement> coder) {
        return codec.encode(coder, value).orElseThrow();
    }

    public static <T> JsonObject encodeAsObject(Codec<T> codec, T value) {
        return encodeAsObject(codec, value, CODER);
    }

    public static <T> JsonObject encodeAsObject(Codec<T> codec, T value, Transcoder<JsonElement> coder) {
        return encode(codec, value, coder).getAsJsonObject();
    }

    public static <T> T decode(Codec<T> codec, JsonElement json) {
        return codec.decode(CODER, json).orElseThrow();
    }

}
