/// Singleton hover state for the provenance source tooltip. A `ProvBadge` calls `show()` on
/// pointer-enter with its own element — the tooltip anchors to the badge's box so the value
/// itself never shifts — and `hide()` on leave. `ProvTooltipHost` reads `state` and renders the
/// floating readout. Only the raw source is stored: age ("Ns ago") is recomputed live in the
/// host from `ts`, so it keeps ticking while the pointer rests on the badge.

type ProvSource = {
    packetClass: string;
    seq: number | null;
    ts: number | null;
    direction: string;
};

/// Viewport-space box of the anchoring badge, captured at show time. `cx` is its horizontal
/// centre — the host places the tooltip above (or, near the top edge, below) this box.
type Anchor = { top: number; bottom: number; cx: number };

type ProvTip = {
    anchor: Anchor;
    field: string;
    source: ProvSource | null;
};

class ProvTooltipState {
    state = $state<ProvTip | null>(null);

    show(el: HTMLElement, tip: Omit<ProvTip, 'anchor'>): void {
        const r = el.getBoundingClientRect();
        this.state = { ...tip, anchor: { top: r.top, bottom: r.bottom, cx: r.left + r.width / 2 } };
    }

    hide(): void { this.state = null; }
}

export const provTooltip = new ProvTooltipState();
