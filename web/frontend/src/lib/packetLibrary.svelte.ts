/// Saved-component library, keyed by bucket: items, components, records.
/// Persisted to localStorage so any packet field can recall a saved value across reloads.
///
/// Drag payload uses a per-bucket MIME type (`application/x-mn-lib-items` etc.) rather
/// than one shared MIME with the bucket embedded in the payload. Per the HTML5 DnD spec,
/// `DataTransfer#getData` returns `''` during dragenter/dragover (the actual data is only
/// readable on drop), so the bucket has to be visible via the MIME type to gate
/// `preventDefault` correctly during dragover.

import { toast } from '../state/toasts.svelte.ts';

export type LibraryBucket = 'items' | 'components' | 'records';

export type LibraryEntry<T = unknown> = {
    name: string;
    value: T;
};

export type LibraryState = {
    items: LibraryEntry<Record<string, unknown>>[];
    components: LibraryEntry<Record<string, unknown>>[];
    records: LibraryEntry<Record<string, unknown>>[];
};

const KEY = 'mn.packet.library.v1';

const SEED: LibraryState = { items: [], components: [], records: [] };

function load(): LibraryState {
    try {
        const raw = localStorage.getItem(KEY);
        if (raw) {
            const parsed = JSON.parse(raw);
            return { items: parsed.items ?? [], components: parsed.components ?? [], records: parsed.records ?? [] };
        }
    } catch {}
    return structuredClone(SEED);
}

class Library {
    state = $state<LibraryState>(load());

    save<T>(bucket: LibraryBucket, entry: LibraryEntry<T>): void {
        const list = this.state[bucket] as LibraryEntry<T>[];
        list.unshift(entry);
        this.persist();
    }

    remove(bucket: LibraryBucket, index: number): void {
        this.state[bucket].splice(index, 1);
        this.persist();
    }

    list<T>(bucket: LibraryBucket): LibraryEntry<T>[] {
        return this.state[bucket] as LibraryEntry<T>[];
    }

    private persist(): void {
        try {
            localStorage.setItem(KEY, JSON.stringify(this.state));
        } catch (e) {
            // Quota exceeded / private mode / other write failure — tell the user, otherwise
            // they'd see the entry in memory but lose it on next reload.
            toast('Library save failed: ' + ((e as Error).message || 'storage error'), 'error', 4000);
        }
    }
}

export const packetLibrary = new Library();

/// Map a packet-field kind to its library bucket, or `null` if the kind isn't libraryable.
export function kindToBucket(kind: string): LibraryBucket | null {
    if (kind === 'item') return 'items';
    if (kind === 'component') return 'components';
    if (kind === 'record') return 'records';
    return null;
}

const MIME_PREFIX = 'application/x-mn-lib-';
const mimeFor = (bucket: LibraryBucket) => MIME_PREFIX + bucket;

/// True when `dt` advertises a compatible library drag — call from dragenter/dragover to
/// decide whether to `preventDefault`. Payload isn't readable here; use `readLibraryDrag`
/// in the `drop` handler.
export function hasLibraryDrag(dt: DataTransfer | null, want: LibraryBucket): boolean {
    return !!dt && dt.types.includes(mimeFor(want));
}

export function readLibraryDrag(dt: DataTransfer | null, want: LibraryBucket): unknown | null {
    if (!hasLibraryDrag(dt, want)) return null;
    try {
        const raw = dt!.getData(mimeFor(want));
        return raw ? JSON.parse(raw) : null;
    } catch {
        return null;
    }
}
