// Shared helpers — pure functions; safe to import anywhere.

const ESC = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };
export const escapeHtml = (s: unknown): string =>
    String(s ?? '').replace(/[&<>"']/g, c => ESC[c as keyof typeof ESC]);

const UNITS = ['B', 'KB', 'MB', 'GB', 'TB'];
export function humanBytes(b: unknown): string {
    let value = Number(b) || 0;
    if (!value) return '0 B';
    let i = 0;
    while (value >= 1024 && i < UNITS.length - 1) { value /= 1024; i++; }
    const fixed = i === 0 ? 0 : (value < 10 ? 2 : 1);
    return value.toFixed(fixed) + ' ' + UNITS[i];
}

/// Compact byte size for the dense packet-trace UI (`B`/`k`/`M`), distinct from [humanBytes]'
/// wider `KB`/`MB` form used elsewhere.
export function fmtBytesShort(b: unknown): string {
    const v = Number(b) || 0;
    if (v < 1024) return v + 'B';
    if (v < 1024 * 1024) return (v / 1024).toFixed(1) + 'k';
    return (v / 1024 / 1024).toFixed(2) + 'M';
}

export function humanDuration(ms: unknown): string {
    const value = Number(ms) || 0;
    if (!value) return '0s';
    const s = Math.floor(value / 1000);
    if (s < 60) return `${s}s`;
    const m = Math.floor(s / 60), sec = s % 60;
    if (m < 60) return `${m}m ${sec}s`;
    const h = Math.floor(m / 60), min = m % 60;
    if (h < 24) return `${h}h ${min}m`;
    const d = Math.floor(h / 24), hr = h % 24;
    return `${d}d ${hr}h`;
}

export function humanNumber(n: unknown): string {
    const value = Number(n) || 0;
    if (value < 1000) return String(value);
    if (value < 1e6) return (value / 1e3).toFixed(value < 10_000 ? 1 : 0) + 'k';
    if (value < 1e9) return (value / 1e6).toFixed(value < 10_000_000 ? 1 : 0) + 'M';
    return (value / 1e9).toFixed(1) + 'B';
}

export const fmtTime = (ts: unknown): string => {
    if (!ts) return '';
    const d = new Date(Number(ts));
    return d.toTimeString().slice(0, 8) + '.' + String(d.getMilliseconds()).padStart(3, '0');
};

export const shortClass = (full: unknown): string => {
    const value = String(full ?? '');
    if (!value) return '';
    const i = value.lastIndexOf('.');
    return i < 0 ? value : value.slice(i + 1);
};

export const shortUuid = (u: unknown): string => (u ? String(u).slice(0, 8) : '—');

/// Drop the `minecraft:` namespace prefix from a resource id, leaving the bare path.
export const stripNamespace = (id: unknown): string => String(id ?? '').replace(/^minecraft:/, '');

export function debounce<T extends unknown[]>(fn: (...args: T) => void, ms = 200): (...args: T) => void {
    let h: ReturnType<typeof setTimeout> | undefined;
    return (...args) => {
        if (h) clearTimeout(h);
        h = setTimeout(() => fn(...args), ms);
    };
}

const ROMAN = ['', 'I', 'II', 'III', 'IV', 'V', 'VI', 'VII', 'VIII', 'IX', 'X', 'XI', 'XII', 'XIII', 'XIV', 'XV'];
export const toRoman = (n: number | null | undefined): string =>
    (n == null || n < 1) ? '' : (n > 15 ? String(n) : ROMAN[n] ?? '');

export const formatEffectDuration = (s: number | null | undefined): string => {
    if (s == null || s < 0 || !Number.isFinite(s) || s > 1e6) return '∞';
    if (s >= 600) return Math.floor(s / 60) + 'm';
    const m = Math.floor(s / 60), r = s % 60;
    return `${m}:${String(r).padStart(2, '0')}`;
};

export function fmtAge(ms: unknown): string {
    const value = Number(ms);
    if (!Number.isFinite(value)) return '—';
    const s = value / 1000;
    if (s < 1)    return s.toFixed(2) + 's';
    if (s < 60)   return s.toFixed(1).replace('.0', '') + 's';
    if (s < 3600) return Math.floor(s / 60) + 'm ' + Math.floor(s % 60) + 's';
    return Math.floor(s / 3600) + 'h';
}

