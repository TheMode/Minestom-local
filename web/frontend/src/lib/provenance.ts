/// Context: `(field, anchorEl) => void` — pins the full history popover for `field`.
export const PROV_OPEN_KEY = Symbol('prov-open');

/// Context: `() => string | null` — the field whose history popover is currently pinned, so a
/// badge can mark itself `is-open` and suppress its hover tooltip while it owns the popover.
export const PROV_OPEN_FIELD_KEY = Symbol('prov-open-field');
