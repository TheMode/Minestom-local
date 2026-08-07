/// Reactive helper for anchored floating popovers. Owns positioning, outside-click,
/// optional escape, and scroll/resize handling (scroll/resize closes the popover) so each
/// popover component only declares *where* it sits relative to its anchor.
///
/// Must be called from a component `<script>` or `.svelte.ts` reactive context.

type Anchor = () => HTMLElement | null;
type Pop<T extends HTMLElement> = () => T | null;
type Place<T extends HTMLElement> = (anchorRect: DOMRect, pop: T) => { left: number; top: number };

type Opts = {
    /// Close on Escape. Off by default.
    escape?: boolean;
    /// Pointer event for outside-click detection. Defaults to 'mousedown'.
    /// 'pointerdown' fires earlier — pair with `deferOutsideClick` so the click that
    /// opened the popover isn't seen by the close listener.
    closeEvent?: 'mousedown' | 'pointerdown';
    /// Touched inside the reposition effect; whenever the returned value changes,
    /// `place` re-runs. Use for content-dependent positioning (e.g. clamping a
    /// pop above the anchor when its own height has just grown).
    repositionWhen?: () => unknown;
    /// Delay outside-click listener install by one tick — needed when the same
    /// event that opened the popover would otherwise be picked up as outside.
    deferOutsideClick?: boolean;
    /// Close when the page scrolls. On by default — correct for viewport-anchored
    /// (`position: fixed`) popovers, which detach from their anchor on scroll. Set false for
    /// document-anchored (`position: absolute`, placed in document space) popovers that scroll
    /// *with* their anchor and should stay open. Note the scroll listener is capture-phase, so
    /// leaving it on also closes the popover when its own inner content scrolls.
    closeOnScroll?: boolean;
};

export function anchoredPopover<T extends HTMLElement>(
    anchor: Anchor,
    pop: Pop<T>,
    place: Place<T>,
    onClose: () => void,
    opts: Opts = {},
): { pos: { left: number; top: number } } {
    const pos = $state({ left: 0, top: 0 });
    const closeEvent = opts.closeEvent ?? 'mousedown';
    const closeOnScroll = opts.closeOnScroll !== false;

    function reposition(): void {
        const a = anchor();
        const p = pop();
        if (!a || !p) return;
        const next = place(a.getBoundingClientRect(), p);
        pos.left = next.left;
        pos.top = next.top;
    }

    $effect(() => {
        opts.repositionWhen?.();
        reposition();
    });

    $effect(() => {
        const a = anchor();
        if (!a) return undefined;

        window.addEventListener('resize', onClose);
        if (closeOnScroll) window.addEventListener('scroll', onClose, true);

        const onDown = (e: Event) => {
            const t = e.target as Node;
            const p = pop();
            if (p?.contains(t) || a.contains(t)) return;
            onClose();
        };
        let deferred: ReturnType<typeof setTimeout> | null = null;
        if (opts.deferOutsideClick) {
            deferred = setTimeout(() => document.addEventListener(closeEvent, onDown, true), 0);
        } else {
            document.addEventListener(closeEvent, onDown, true);
        }

        const onKey = opts.escape
            ? (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); }
            : null;
        if (onKey) document.addEventListener('keydown', onKey);

        return () => {
            window.removeEventListener('resize', onClose);
            if (closeOnScroll) window.removeEventListener('scroll', onClose, true);
            document.removeEventListener(closeEvent, onDown, true);
            if (onKey) document.removeEventListener('keydown', onKey);
            if (deferred != null) clearTimeout(deferred);
        };
    });

    return { pos };
}

/// Fixed-position tooltip placement from pointer coords (flips near viewport edges).
export function floatingTipStyle(x: number, y: number, w: number, h: number, pad = 14): string {
    const right = x + w + pad > window.innerWidth;
    const below = y + h + pad > window.innerHeight;
    const transform = (right ? 'translateX(-100%)' : '') + ' ' + (below ? 'translateY(-100%)' : '');
    return `left:${right ? x - pad : x + pad}px;top:${below ? y - pad : y + pad}px;transform:${transform.trim()}`;
}
