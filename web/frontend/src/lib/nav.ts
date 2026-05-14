// Path-based navigation primitive. Replaces hash routing — the dashboard binds real URLs
// (e.g. `/players/abcd`) and the server falls back to index.html for any non-API path so
// deep links survive a refresh.

const NAV_EVENT = 'mw:nav';

/// Push a new URL onto the history stack. Accepts an absolute path with optional query
/// (e.g. `/packets?uuid=abcd&seq=42`). Same-URL pushes are skipped so duplicate clicks don't
/// stack history entries.
export function navigate(to: string): void {
    const current = location.pathname + location.search;
    if (to === current) return;
    history.pushState(null, '', to);
    window.dispatchEvent(new Event(NAV_EVENT));
}

/// Replace the current URL without adding a history entry (e.g. playhead / filter sync).
export function replaceUrl(to: string): void {
    const current = location.pathname + location.search;
    if (to === current) return;
    history.replaceState(null, '', to);
    window.dispatchEvent(new Event(NAV_EVENT));
}

/// Wire a router-aware link handler. Any `<a href="/...">` click reaches this; modifier keys
/// and external targets bypass it so middle-click and ctrl-click still open a new tab.
export function onLinkClick(e: MouseEvent): void {
    if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
    const a = e.target instanceof Element ? e.target.closest<HTMLAnchorElement>('a[href]') : null;
    if (!a) return;
    const href = a.getAttribute('href');
    if (!href || !href.startsWith('/') || a.target === '_blank' || a.hasAttribute('download')) return;
    e.preventDefault();
    navigate(href);
}

export const NAV_EVENT_NAME = NAV_EVENT;
