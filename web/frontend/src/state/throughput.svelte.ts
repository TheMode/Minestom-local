import { api, bus } from '../lib/api.ts';
import { Topics } from '../lib/topics.ts';
import type { ServerMetricsSample, ServerStatus } from '../lib/types.ts';

const SERIES_LEN = 120;
const SERIES_KEYS = ['ts', 'bytesIn', 'bytesOut', 'packetsIn', 'packetsOut', 'connections'] as const;
type SeriesKey = typeof SERIES_KEYS[number];
type ThroughputSeries = Record<SeriesKey, number[]>;

const emptySeries = (): ThroughputSeries =>
    Object.fromEntries(SERIES_KEYS.map(k => [k, []])) as ThroughputSeries;

/// Shared rolling buffer of backend metrics samples (1Hz) — seeded from `/api/server`, appended
/// from the `server:metrics` topic. Both the sidebar sparkline and the dashboard charts read it.
///
/// `$state.raw` skips Proxy wrapping for the deep arrays — reactivity fires only on the outer
/// `series` reassignment, which is all consumers need to recompute their derived views.
class Throughput {
    series = $state.raw<ThroughputSeries>(emptySeries());
    #booted = false;

    boot() {
        if (this.#booted) return;
        this.#booted = true;
        api<ServerStatus>('/server').then(s => {
            const hist = (s.history || []).slice(-SERIES_LEN);
            const next = emptySeries();
            for (const k of SERIES_KEYS) next[k] = hist.map(h => Number(h[k]) || 0);
            this.series = next;
        }).catch(() => {});
        bus.subscribe<ServerMetricsSample>(Topics.serverMetrics, sample => {
            const next = emptySeries();
            for (const k of SERIES_KEYS) {
                const arr = this.series[k].slice();
                arr.push(Number(sample[k]) || 0);
                if (arr.length > SERIES_LEN) arr.shift();
                next[k] = arr;
            }
            this.series = next;
        });
    }
}

export const throughput = new Throughput();

export function bytesSeries(s: ThroughputSeries): number[] {
    return s.bytesIn.map((v, i) => v + (s.bytesOut[i] ?? 0));
}
export function packetsSeries(s: ThroughputSeries): number[] {
    return s.packetsIn.map((v, i) => v + (s.packetsOut[i] ?? 0));
}
