import type { ToastItem, ToastKind } from '../lib/types.ts';

/// Imperative trigger from anywhere — usable in non-Svelte code (e.g. lib/api error toasts).
class Toasts {
    items = $state<ToastItem[]>([]);
    #seq = 0;

    push(message: string, kind: ToastKind = 'ok', ttlMs = 3000): void {
        const id = ++this.#seq;
        this.items = [...this.items, { id, message, kind, ttlMs }];
        setTimeout(() => {
            this.items = this.items.filter(x => x.id !== id);
        }, ttlMs);
    }
}

export const toasts = new Toasts();

export function toast(message: string, kind: ToastKind = 'ok', ttlMs = 3000): void {
    toasts.push(message, kind, ttlMs);
}
