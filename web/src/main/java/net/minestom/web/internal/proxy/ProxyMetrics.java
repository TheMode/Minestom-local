package net.minestom.web.internal.proxy;

import net.minestom.server.codec.Codec;
import net.minestom.server.codec.StructCodec;

import java.util.concurrent.atomic.LongAdder;

public record ProxyMetrics(
        long connectionsAccepted,
        long loginFailures,
        long decodeErrors,
        long encodeDrops,
        long injectRejected,
        long injectDropped
) {
    public static final StructCodec<ProxyMetrics> CODEC = StructCodec.struct(
            "connectionsAccepted", Codec.LONG, ProxyMetrics::connectionsAccepted,
            "loginFailures", Codec.LONG, ProxyMetrics::loginFailures,
            "decodeErrors", Codec.LONG, ProxyMetrics::decodeErrors,
            "encodeDrops", Codec.LONG, ProxyMetrics::encodeDrops,
            "injectRejected", Codec.LONG, ProxyMetrics::injectRejected,
            "injectDropped", Codec.LONG, ProxyMetrics::injectDropped,
            ProxyMetrics::new);

    public record Live(
            LongAdder connectionsAccepted,
            LongAdder loginFailures,
            LongAdder decodeErrors,
            LongAdder encodeDrops,
            LongAdder injectRejected,
            LongAdder injectDropped
    ) {
        public static Live create() {
            return new Live(new LongAdder(), new LongAdder(), new LongAdder(), new LongAdder(),
                    new LongAdder(), new LongAdder());
        }

        public ProxyMetrics snapshot() {
            return new ProxyMetrics(
                    connectionsAccepted.sum(), loginFailures.sum(), decodeErrors.sum(),
                    encodeDrops.sum(), injectRejected.sum(), injectDropped.sum());
        }
    }
}
