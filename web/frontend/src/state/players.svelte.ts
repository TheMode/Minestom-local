import { api, bus } from '../lib/api.ts';
import type { PlayerSummary } from '../lib/types.ts';
import { Topics, type PlayersMessage, type PlayersSummaryMessage } from '../lib/topics.ts';
import { tweaks } from './tweaks.svelte.ts';

function goodUuid(u: string | undefined | null): u is string {
    return !!u && u !== 'null';
}

function mergeSummary(target: PlayerSummary, row: PlayerSummary): void {
    const out = target as Record<string, unknown>;
    for (const [key, value] of Object.entries(row)) {
        if (value === undefined) continue;
        if (key === 'traffic') out.traffic = { ...target.traffic, ...(value as object) };
        else out[key] = value;
    }
}

/// Shared players list — `players` WS for roster changes, `players:summary` for batched
/// table fields (ping, health, …). Full `player:{uuid}:state` patches are only used on the
/// open profile page, not for every online session.
class PlayersList {
    list = $state<PlayerSummary[]>([]);
    visible = $derived(tweaks.value.showDisconnected
        ? this.list
        : this.list.filter(p => !p.disconnectedAt));
    onlineCount = $derived(this.list.reduce((n, p) => p.disconnectedAt ? n : n + 1, 0));
    #booted = false;

    boot() {
        if (this.#booted) return;
        this.#booted = true;

        bus.subscribe<PlayersMessage>(Topics.players, msg => {
            if (!goodUuid(msg.uuid)) return;
            if (msg.event === 'remove') {
                this.list = this.list.filter(x => x.uuid !== msg.uuid);
                return;
            }
            if ((msg.event === 'add' || msg.event === 'disconnect') && msg.player) {
                const uuid = msg.uuid;
                const row = { ...msg.player, uuid };
                const i = this.list.findIndex(x => x.uuid === uuid);
                if (i >= 0) {
                    const next = this.list.slice();
                    next[i] = row;
                    this.list = next;
                } else {
                    this.list = [...this.list, row];
                }
            }
        });

        bus.subscribe<PlayersSummaryMessage>(Topics.playersSummary, msg => {
            const rows = msg.players;
            if (!rows?.length) return;
            let changed = false;
            const next = this.list.slice();
            for (const row of rows) {
                if (!goodUuid(row.uuid)) continue;
                const i = next.findIndex(x => x.uuid === row.uuid);
                if (i >= 0) {
                    mergeSummary(next[i], row);
                    changed = true;
                }
            }
            if (changed) this.list = next;
        });

        bus.addEventListener('open', () => {
            api<PlayerSummary[]>('/players')
                .then(rows => { this.list = rows.filter(p => goodUuid(p.uuid)); })
                .catch(() => {});
        });
    }
}

export const players = new PlayersList();
