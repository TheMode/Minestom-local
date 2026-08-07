export const ACCENT_OPTIONS = [
    { key: 'emerald',  acc: 'oklch(78% 0.18 148)', deep: 'oklch(54% 0.14 148)' },
    { key: 'gold',     acc: 'oklch(82% 0.16 80)',  deep: 'oklch(58% 0.14 70)'  },
    { key: 'diamond',  acc: 'oklch(82% 0.12 200)', deep: 'oklch(56% 0.10 200)' },
    { key: 'amethyst', acc: 'oklch(72% 0.18 310)', deep: 'oklch(50% 0.16 310)' },
    { key: 'redstone', acc: 'oklch(72% 0.22 25)',  deep: 'oklch(48% 0.20 25)'  },
];

const DEFAULTS = {
    accent:  'emerald',
    density: 'comfortable',
    glint:   true,
    hudScan: true,
    /// Show players who have left (their profile + packet history stay queryable until the
    /// backend TTL evicts them). Default on so disconnects don't make rows vanish; flip off
    /// for the lean live-only view.
    showDisconnected: true,
};

const STORE_KEY = 'mc-console-tweaks';

function load() {
    try {
        const stored = JSON.parse(localStorage.getItem(STORE_KEY) || '{}');
        return { ...DEFAULTS, ...stored };
    } catch { return { ...DEFAULTS }; }
}

function applyToDocument(t) {
    const r = document.documentElement;
    const opt = ACCENT_OPTIONS.find(o => o.key === t.accent) || ACCENT_OPTIONS[0];
    r.style.setProperty('--acc',      opt.acc);
    r.style.setProperty('--acc-deep', opt.deep);
    r.style.setProperty('--acc-soft', `color-mix(in oklab, ${opt.acc} 14%, transparent)`);
    r.style.setProperty('--acc-line', `color-mix(in oklab, ${opt.acc} 35%, transparent)`);
    r.dataset.density = t.density;
    r.dataset.glint   = String(!!t.glint);
}

class Tweaks {
    value = $state(load());

    constructor() {
        applyToDocument(this.value);
    }

    set(patch) {
        this.value = { ...this.value, ...patch };
        try { localStorage.setItem(STORE_KEY, JSON.stringify(this.value)); } catch {}
        applyToDocument(this.value);
    }
}

export const tweaks = new Tweaks();
