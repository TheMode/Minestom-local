import { formatMcJson } from '../lib/minecraftText.ts';
import { toast } from './toasts.svelte.ts';

type Tip = { x: number; y: number; text: string };

export function copyMcJson(value: unknown, okMessage = 'JSON copied'): void {
    const text = formatMcJson(value);
    if (!text) return;
    navigator.clipboard?.writeText(text)
        .then(() => toast(okMessage, 'ok'))
        .catch(() => toast('Copy failed', 'error'));
}

export function altCopyClick(e: MouseEvent, value: unknown, okMessage: string): void {
    if (!e.altKey || !formatMcJson(value)) return;
    e.preventDefault();
    e.stopPropagation();
    copyMcJson(value, okMessage);
}

/// Alt-hover JSON tooltip for Minecraft text (fixed host in App).
class McJsonTooltipState {
    alt = $state(false);
    tip = $state<Tip | null>(null);

    #value: unknown = null;
    #x = 0;
    #y = 0;
    // Cache the serialized text so a pure pointer-move (same value) doesn't re-run formatMcJson.
    #cachedFor: unknown = {};
    #cachedText: string | null = null;

    constructor() {
        if (typeof window === 'undefined') return;
        const onKey = (e: KeyboardEvent) => {
            this.alt = e.altKey;
            document.body.toggleAttribute('data-alt', e.altKey);
            this.#sync();
        };
        window.addEventListener('keydown', onKey);
        window.addEventListener('keyup', onKey);
        window.addEventListener('blur', () => {
            this.alt = false;
            document.body.removeAttribute('data-alt');
            this.#sync();
        });
    }

    track(value: unknown, e: Pick<PointerEvent, 'clientX' | 'clientY'> | null): void {
        if (!e) this.#value = null;
        else {
            this.#value = value;
            this.#x = e.clientX;
            this.#y = e.clientY;
        }
        this.#sync();
    }

    #sync(): void {
        if (this.#value == null || !this.alt) {
            this.tip = null;
            return;
        }
        if (this.#value !== this.#cachedFor) {
            this.#cachedFor = this.#value;
            this.#cachedText = formatMcJson(this.#value);
        }
        this.tip = this.#cachedText ? { x: this.#x, y: this.#y, text: this.#cachedText } : null;
    }
}

export const mcJsonTooltip = new McJsonTooltipState();
