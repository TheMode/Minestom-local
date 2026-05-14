import type { PacketRow } from './packetAgg.ts';

export type TapeFlushStats = {
    added: number;
    minSeq: number;
    maxSeq: number;
    length: number;
};

/// Append-only packet timeline, batched per animation frame. Out-of-order pushes
/// (`row.seq < lastSeq`) update an existing row in place rather than appending.
export class PacketTape {
    private readonly rows: PacketRow[] = [];
    private pending: PacketRow[] = [];
    private flushScheduled = false;
    private readonly onFlush: (stats: TapeFlushStats) => void;

    minSeq = 0;
    maxSeq = 0;

    constructor(onFlush: (stats: TapeFlushStats) => void) {
        this.onFlush = onFlush;
    }

    get length() { return this.rows.length; }

    clear() {
        this.rows.length = 0;
        this.pending = [];
        this.minSeq = 0;
        this.maxSeq = 0;
    }

    push(row: PacketRow) {
        if (!row.seq) return;
        this.pending.push(row);
        this.scheduleFlush();
    }

    loadHistory(rows: PacketRow[]) {
        if (!rows.length) return;
        let changed = 0;
        for (const r of rows) {
            if (!r.seq) continue;
            if (this.upsert(r)) changed++;
        }
        if (!changed) return;
        this.onFlush({
            added: changed,
            minSeq: this.minSeq,
            maxSeq: this.maxSeq,
            length: this.rows.length,
        });
    }

    private scheduleFlush() {
        if (this.flushScheduled) return;
        this.flushScheduled = true;
        requestAnimationFrame(() => this.flush());
    }

    flush() {
        this.flushScheduled = false;
        const batch = this.pending;
        this.pending = [];
        if (!batch.length) return;

        let changed = 0;
        for (const row of batch) if (this.upsert(row)) changed++;
        if (!changed) return;

        this.onFlush({
            added: changed,
            minSeq: this.minSeq,
            maxSeq: this.maxSeq,
            length: this.rows.length,
        });
    }

    private upsert(row: PacketRow): boolean {
        const idx = this.lowerBound(row.seq);
        if (idx < this.rows.length && this.rows[idx]!.seq === row.seq) {
            delete (this.rows[idx]! as PacketRow & { summary?: string }).summary;
            Object.assign(this.rows[idx]!, row);
            return true;
        }

        this.rows.splice(idx, 0, row);
        if (this.rows.length === 1) this.minSeq = row.seq;
        else if (row.seq < this.minSeq) this.minSeq = row.seq;
        if (row.seq > this.maxSeq) this.maxSeq = row.seq;
        return true;
    }

    rowAtSeq(seq: number): PacketRow | undefined {
        const idx = this.indexAt(seq);
        return idx < 0 ? undefined : this.rows[idx];
    }

    snapshot(): PacketRow[] {
        return this.rows.slice();
    }

    indexOfSeq(seq: number): number {
        return this.indexAt(seq);
    }

    forEachSampled(maxRows: number, visitor: (row: PacketRow) => void): void {
        const len = this.rows.length;
        if (len === 0 || maxRows <= 0) return;
        if (len <= maxRows) {
            for (const row of this.rows) visitor(row);
            return;
        }
        const last = len - 1;
        const denom = Math.max(1, maxRows - 1);
        for (let i = 0; i < maxRows; i++) visitor(this.rows[Math.round((i * last) / denom)]!);
    }

    /// Closest seq to `seq` that is actually in the buffer. Used for minimap-drag snapping.
    nearestSeq(seq: number): number {
        if (!this.rows.length) return Math.max(1, seq);
        const at = (i: number) => this.rows[i]!;
        let lo = 0, hi = this.rows.length;
        while (lo < hi) {
            const mid = (lo + hi) >> 1;
            if (at(mid).seq < seq) lo = mid + 1;
            else hi = mid;
        }
        if (lo >= this.rows.length) return at(this.rows.length - 1).seq;
        const next = at(lo).seq;
        if (lo === 0) return next;
        const prev = at(lo - 1).seq;
        return Math.abs(seq - prev) <= Math.abs(seq - next) ? prev : next;
    }

    findPrevSameClass(seq: number, className: string | undefined): PacketRow | null {
        const idx = this.indexAt(seq);
        if (idx <= 0) return null;
        if (!className) return this.rows[idx - 1]!;
        for (let j = idx - 1; j >= 0; j--) {
            const r = this.rows[j]!;
            if (r.className === className) return r;
        }
        return this.rows[idx - 1]!;
    }

    private indexAt(seq: number): number {
        if (!this.rows.length || !seq) return -1;
        const idx = this.lowerBound(seq);
        return idx < this.rows.length && this.rows[idx]!.seq === seq ? idx : -1;
    }

    private lowerBound(seq: number): number {
        let lo = 0, hi = this.rows.length;
        while (lo < hi) {
            const mid = (lo + hi) >> 1;
            if (this.rows[mid]!.seq < seq) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }
}
