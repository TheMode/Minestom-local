// Imperative minimap renderer (WebGPU terrain + DOM markers). The .svelte wrapper bootstraps it.

import { api, bus } from './api.ts';
import { playerMinimap, type MinimapEntityDto, type MinimapTileDto, type PlayerMinimapMessage } from './topics.ts';
import { decodeTileBase64, MinimapCamera, MinimapTerrain } from './minimap-camera.ts';
import { escapeHtml as escAttr } from './util.ts';
import {
    DISABLED_HTML, SHELL_HTML, CONTEXT_MENU_HTML, WAYPOINT_DRAFT_HTML, WP_COLORS, WP_ICONS,
} from './minimap-templates.ts';
import { bindViewport } from './minimap-input.ts';
import { toast } from '../state/toasts.svelte.ts';
import { showEntityTooltip, moveEntityTooltip, hideEntityTooltip } from '../state/entityTooltip.svelte.ts';
type Vec3 = [number, number, number];
type Vec2 = [number, number];
type PlayerMapState = {
    position: Vec3;
    rotation: Vec2;
};
type MinimapEntity = MinimapEntityDto;
type MinimapTile = MinimapTileDto;
type Waypoint = {
    id: string | number;
    name: string;
    x: number;
    y?: number;
    z: number;
    color: string;
    icon: string;
};
type MinimapEls = {
    viewport: HTMLElement;
    canvas: HTMLCanvasElement;
    overlay: HTMLCanvasElement;
    markers: HTMLElement;
    cardinals: HTMLElement;
    player: HTMLElement;
    coords: HTMLElement;
    inset: HTMLElement;
    zoombar: HTMLElement;
    scaleLbl: HTMLElement;
    scaleBar: HTMLElement;
    filters: HTMLElement;
    wpList: HTMLElement;
    wpCount: HTMLElement;
};
type Unsubscribe = () => void;

const MIN_ZOOM = 0.15;
const MAX_ZOOM = 8;
const SMOOTH_RATE     = 10;
const SMOOTH_POS_SNAP = 0.01;
const SMOOTH_YAW_SNAP = 0.5;

const GROUP_META = {
    players:     { label: 'Players',     glyph: '◆', sprite: 'diamond',  color: 'var(--em-player)'  },
    hostile:     { label: 'Hostile',     glyph: '▲', sprite: 'triangle', color: 'var(--em-hostile)' },
    passive:     { label: 'Passive',     glyph: '■', sprite: 'square',   color: 'var(--em-passive)' },
    items:       { label: 'Items',       glyph: '+', sprite: 'plus',     color: 'var(--em-item)'    },
    projectiles: { label: 'Projectiles', glyph: '·', sprite: 'dot',      color: 'var(--em-proj)'    },
    vehicles:    { label: 'Vehicles',    glyph: '◆', sprite: 'diamond',  color: 'var(--em-vehicle)' },
};
const GROUP_KEYS = Object.keys(GROUP_META);
/// Entity hit-test radius in CSS pixels, squared for cheap dx²+dy² compare.
const ENTITY_HIT_R2 = 64;
const SPRITE_RADIUS = 3.5;

/// Append `pts` (flat x0,y0,x1,y1,…) as `sprite` shapes. Caller wraps with beginPath/fill
/// so all sprites of one shape rasterize in one pass.
function buildSpritePath(ctx: CanvasRenderingContext2D, sprite: string, pts: number[]) {
    const r = SPRITE_RADIUS;
    switch (sprite) {
        case 'diamond':
            for (let i = 0; i < pts.length; i += 2) {
                const x = pts[i], y = pts[i + 1];
                ctx.moveTo(x, y - r); ctx.lineTo(x + r, y);
                ctx.lineTo(x, y + r); ctx.lineTo(x - r, y);
                ctx.closePath();
            }
            break;
        case 'triangle': {
            const h = r * 0.95;
            for (let i = 0; i < pts.length; i += 2) {
                const x = pts[i], y = pts[i + 1];
                ctx.moveTo(x, y - r); ctx.lineTo(x + h, y + r * 0.6);
                ctx.lineTo(x - h, y + r * 0.6);
                ctx.closePath();
            }
            break;
        }
        case 'square': {
            const s = r * 0.85;
            for (let i = 0; i < pts.length; i += 2) {
                ctx.rect(pts[i] - s, pts[i + 1] - s, s * 2, s * 2);
            }
            break;
        }
        case 'plus': {
            const w = 1.1;
            for (let i = 0; i < pts.length; i += 2) {
                const x = pts[i], y = pts[i + 1];
                ctx.rect(x - r, y - w, r * 2, w * 2);
                ctx.rect(x - w, y - r, w * 2, r * 2);
            }
            break;
        }
        case 'dot': {
            const dr = r * 0.55;
            for (let i = 0; i < pts.length; i += 2) {
                const x = pts[i], y = pts[i + 1];
                ctx.moveTo(x + dr, y);
                ctx.arc(x, y, dr, 0, Math.PI * 2);
            }
            break;
        }
    }
}
const CARDINAL = ['N', 'E', 'S', 'W'];
const CARDINAL_8 = ['N', 'NE', 'E', 'SE', 'S', 'SW', 'W', 'NW'];

const ENABLED_KEY   = 'mw-minimap-enabled';
const WP_KEY_PREFIX = 'mw-minimap-wp:';

function loadEnabled()         { try { return localStorage.getItem(ENABLED_KEY) !== '0'; } catch { return true; } }
function saveEnabled(on)       { try { localStorage.setItem(ENABLED_KEY, on ? '1' : '0'); } catch {} }
function loadWaypoints(uuid)   { try { return JSON.parse(localStorage.getItem(WP_KEY_PREFIX + uuid) || '[]'); } catch { return []; } }
function saveWaypoints(uuid, l){ try { localStorage.setItem(WP_KEY_PREFIX + uuid, JSON.stringify(l)); } catch {} }

function clamp(v, lo, hi) { return Math.max(lo, Math.min(hi, v)); }
function shortestAngleDiff(from, to) { return ((to - from) % 360 + 540) % 360 - 180; }
function closest<T extends Element = HTMLElement>(event, selector: string): T | null {
    return event.target instanceof Element ? event.target.closest(selector) as T | null : null;
}

export class MinimapCore {
    host: HTMLElement;
    uuid: string;
    enabled: boolean;
    player: PlayerMapState;
    entities: MinimapEntity[];
    displayX: number | null;
    displayZ: number;
    displayYaw: number;
    _terrain: MinimapTerrain | null;
    zoom: number;
    follow: boolean;
    northUp: boolean;
    panX: number;
    panZ: number;
    twist: number;
    fullscreen: boolean;
    showGrid: boolean;
    showCardinals: boolean;
    filters: Record<string, boolean>;
    waypoints: Waypoint[];
    unsubs: Unsubscribe[];
    _els: MinimapEls | null;
    _markerEls: Map<string, HTMLElement>;
    _raf: number;
    _lastTick: number;
    _terrainInit: number;
    _ro?: ResizeObserver;
    _fsEsc?: (event: KeyboardEvent) => void;
    contextMenu?: HTMLElement | null;
    paused?: boolean;
    _filterChips?: Record<string, HTMLButtonElement>;
    /// Normalized self-UUID for the per-entity self-skip in the hot loop.
    _selfUuidLower: string;
    /// Last camera built by `render()`; hit-tests reuse it for an identical projection.
    _lastCamera: MinimapCamera | null;
    /// Entity under the pointer, tracked so tooltip enter/leave only fire on transitions.
    _hoverEntityId: number | null;
    /// CSS-variable → resolved color. Lazy; cleared on shell re-render to pick up theme swaps.
    _colorCache: Map<string, string> | null;
    /// Per-group screen-coord buffers for `drawEntities`; reused frame to frame.
    _entityBuckets: Record<string, number[]> | null;

    constructor(host: HTMLElement, uuid: string) {
        this.host = host;
        this.uuid = uuid;
        this._selfUuidLower = String(uuid || '').toLowerCase();
        this.enabled = loadEnabled();
        this.player = { position: [0, 64, 0], rotation: [0, 0] };
        this.entities = [];
        this.displayX = null; this.displayZ = 0; this.displayYaw = 0;
        this._terrain = null;
        this.zoom = 1.0; this.follow = true; this.northUp = true;
        this.panX = 0; this.panZ = 0; this.twist = 0;
        this.fullscreen = false; this.showGrid = false; this.showCardinals = false;
        this.filters = Object.fromEntries(GROUP_KEYS.map(k => [k, true]));
        this.waypoints = loadWaypoints(uuid);
        this.unsubs = [];
        this._els = null;
        this._markerEls = new Map();
        this._raf = 0;
        this._lastTick = 0;
        this._terrainInit = 0;
        this._lastCamera = null;
        this._hoverEntityId = null;
        this._colorCache = null;
        this._entityBuckets = null;
    }

    boot() {
        this.host.classList.add('panel', 'mm-host', 'mm-root');
        this.renderShell();
        if (!this.enabled) return;
        this.subscribe();
    }

    subscribe() {
        this.unsubs.push(bus.subscribe<PlayerMinimapMessage>(playerMinimap(this.uuid), msg => this.applyFrame(msg)));
    }

    async fetchInitial() {
        try { this.applySnapshot(await api(`/players/${encodeURIComponent(this.uuid)}/minimap`)); }
        catch {}
    }

    updatePlayer(p) {
        if (!p || this.paused) return;
        if (!this.enabled) return;
        this.requestRender();
    }

    applySnapshot(snap: PlayerMinimapMessage) {
        if (!snap || this.paused) return;
        this._terrain?.clear();
        this.applyFrame(snap, true);
    }

    applyFrame(msg: PlayerMinimapMessage, fromSnapshot = false) {
        if (!msg || this.paused) return;
        if (typeof msg.posX === 'number') this.player.position[0] = msg.posX;
        if (typeof msg.posY === 'number') this.player.position[1] = msg.posY;
        if (typeof msg.posZ === 'number') this.player.position[2] = msg.posZ;
        if (typeof msg.yaw === 'number') this.player.rotation[0] = msg.yaw;
        this.seedDisplayFromTarget();
        if (Array.isArray(msg.entities)) {
            this.entities = msg.entities;
            this.renderFilters();
        }
        const tiles = fromSnapshot ? msg.chunks : msg.loaded;
        if (Array.isArray(tiles)) for (const c of tiles) this.ingestTile(c as MinimapTile);
        if (Array.isArray(msg.unloaded) && this._terrain) {
            for (const c of msg.unloaded) this._terrain.removeTile(c.x, c.z);
        }
        this.requestRender();
    }

    setPaused(p) { this.paused = !!p; }

    seedDisplayFromTarget() {
        if (this.displayX != null) return;
        this.displayX = this.player.position[0];
        this.displayZ = this.player.position[2];
        this.displayYaw = this.player.rotation[0] || 0;
    }

    ingestTile(c: MinimapTile) {
        if (!c?.tile || !this._terrain) return;
        this._terrain.setTile(c.x | 0, c.z | 0, decodeTileBase64(c.tile));
    }

    renderShell() {
        this.host.innerHTML = this.enabled ? SHELL_HTML() : DISABLED_HTML(this.uuid);
        if (!this.enabled) {
            this._els = null;
            this.host.querySelector<HTMLButtonElement>('[data-act="enable"]')!.onclick = () => this.toggleEnabled(true);
            return;
        }
        const q = <T extends Element = HTMLElement>(sel: string) => this.host.querySelector<T>(sel)!;
        this._els = {
            viewport: q('[data-mm-viewport]'), canvas: q<HTMLCanvasElement>('[data-mm-canvas]'),
            overlay: q<HTMLCanvasElement>('[data-mm-overlay]'), markers: q('[data-mm-markers]'),
            cardinals: q('[data-mm-cardinals]'), player: q('[data-mm-player]'),
            coords: q('[data-mm-coords]'), inset: q('[data-mm-inset]'),
            zoombar: q('[data-mm-zoombar]'), scaleLbl: q('[data-mm-scale-l]'),
            scaleBar: q('.mm-scale-bar'), filters: q('[data-mm-filters]'),
            wpList: q('[data-mm-wp-list]'), wpCount: q('[data-mm-wp-count]'),
        };
        this._markerEls.clear();
        this._hoverEntityId = null;
        this._colorCache = null;
        this._filterChips = null;
        this._els.markers.onclick = e => this.onMarkersClick(e);
        this.initTerrain(this._els.canvas);
        this.bindHeader(); this.bindToolbar(); this.bindFilters();
        bindViewport(this); this.bindWaypoints();
        this.renderFilters(); this.renderWaypointList(); this.wireResize();
        this.requestRender();
    }

    async initTerrain(canvas: HTMLCanvasElement) {
        const init = ++this._terrainInit;
        this._terrain?.dispose();
        this._terrain = null;
        try {
            const terrain = await MinimapTerrain.create(canvas);
            if (init !== this._terrainInit || !this.enabled || this._els?.canvas !== canvas) {
                terrain.dispose();
                return;
            }
            this._terrain = terrain;
            this.fetchInitial();
            this.requestRender();
        } catch (err) {
            if (init !== this._terrainInit || !this.enabled) return;
            toast('WebGPU required for minimap');
            this.toggleEnabled(false);
        }
    }

    onMarkersClick(e) {
        const wpEl = closest<HTMLElement>(e, '.mm-wp');
        if (!wpEl) return;
        const wp = this.waypoints.find(w => w.id === wpEl.dataset.wp);
        if (wp) this.centerOnWaypoint(wp);
    }

    wireResize() {
        if (this._ro) { this._ro.disconnect(); this._ro = null; }
        if (typeof ResizeObserver === 'undefined') return;
        this._ro = new ResizeObserver(() => this.requestRender());
        this._ro.observe(this._els.viewport);
    }

    bindHeader() {
        const a = (sel, fn) => this.host.querySelector<HTMLButtonElement>(`[data-act="${sel}"]`)!.onclick = fn;
        a('recenter', () => {
            this.panX = 0; this.panZ = 0; this.follow = true; this.twist = 0;
            this.snapDisplay();
            this.requestRender();
        });
        a('north', () => {
            this.northUp = !this.northUp;
            const btn = this.host.querySelector<HTMLButtonElement>('[data-act="north"]')!;
            btn.textContent = this.northUp ? 'N' : '↻';
            btn.classList.toggle('is-on', this.northUp);
            btn.title = this.northUp ? 'North-up · click to follow' : 'Follow yaw · click to lock N';
            this.requestRender();
        });
        a('full',    () => this.toggleFullscreen());
        a('disable', () => this.toggleEnabled(false));
    }

    bindToolbar() {
        const a = (sel, fn) => this.host.querySelector<HTMLButtonElement>(`[data-act="${sel}"]`)!.onclick = fn;
        a('zin',  () => this.setZoom(this.zoom / 1.25));
        a('zout', () => this.setZoom(this.zoom * 1.25));
        const tog = (key: 'showGrid' | 'showCardinals', sel) => {
            const btn = this.host.querySelector<HTMLButtonElement>(`[data-act="${sel}"]`)!;
            btn.onclick = () => { this[key] = !this[key]; btn.classList.toggle('is-on', this[key]); this.requestRender(); };
        };
        tog('showGrid', 'grid');
        tog('showCardinals', 'card');
        a('help', () => toast('Drag: pan · scroll/pinch: zoom · 2-finger twist: rotate · double-tap: waypoint · right-click: menu'));
    }

    bindFilters() {
        this._els.filters.onclick = e => {
            const chip = closest<HTMLButtonElement>(e, '.mm-chip');
            if (!chip) return;
            const g = chip.dataset.group;
            this.filters[g] = !this.filters[g];
            chip.classList.toggle('is-on', this.filters[g]);
            this.requestRender();
        };
    }

    bindWaypoints() {
        this.host.querySelector<HTMLButtonElement>('[data-act="wp-here"]')!.onclick = () =>
            this.openWaypointDraft([this.player.position[0], this.player.position[2]]);
        this._els.wpList.onclick = e => {
            const item = closest<HTMLElement>(e, '.mm-wp-item');
            if (!item) return;
            if (closest(e, '.mm-wp-x')) {
                e.stopPropagation();
                this.waypoints = this.waypoints.filter(w => w.id !== item.dataset.id);
                saveWaypoints(this.uuid, this.waypoints);
                this.renderWaypointList(); this.requestRender();
                return;
            }
            const wp = this.waypoints.find(w => w.id === item.dataset.id);
            if (!wp) return;
            this.centerOnWaypoint(wp);
        };
    }

    setZoom(v) { this.zoom = clamp(v, MIN_ZOOM, MAX_ZOOM); this.requestRender(); }

    startManualPan() {
        if (this.follow) this.snapDisplay();
        this.follow = false;
    }

    centerOnWaypoint(wp: Waypoint) {
        this.startManualPan();
        const [baseX, baseZ] = this.effectiveCenter(false);
        this.panX = wp.x - baseX;
        this.panZ = wp.z - baseZ;
        this.requestRender();
    }

    toggleEnabled(on) {
        this.enabled = on;
        saveEnabled(on);
        if (!on) {
            this.unsubs.forEach(fn => fn()); this.unsubs = [];
            this._terrainInit++;
            this._terrain?.dispose();
            this._terrain = null;
        }
        this.renderShell();
        if (on) this.subscribe();
    }

    toggleFullscreen() {
        this.fullscreen = !this.fullscreen;
        this.host.classList.toggle('is-fullscreen', this.fullscreen);
        if (this.fullscreen) {
            this._fsEsc = ev => { if (ev.key === 'Escape') this.toggleFullscreen(); };
            document.addEventListener('keydown', this._fsEsc);
        } else if (this._fsEsc) {
            document.removeEventListener('keydown', this._fsEsc);
            this._fsEsc = null;
        }
        this.requestRender();
    }

    requestRender() {
        if (this._raf || !this.enabled) return;
        this._raf = requestAnimationFrame(now => {
            this._raf = 0;
            const dt = this._lastTick ? Math.min((now - this._lastTick) / 1000, 0.1) : 1 / 60;
            this._lastTick = now;
            const moving = this.tickInterpolators(dt);
            this.render();
            if (moving) this.requestRender(); else this._lastTick = 0;
        });
    }

    snapDisplay() {
        this.displayX = this.player.position[0];
        this.displayZ = this.player.position[2];
        this.displayYaw = this.player.rotation[0] || 0;
    }

    buildCamera(w: number, h: number) {
        const [cx, cz] = this.cameraCenter();
        return new MinimapCamera(w, h, cx, cz, this.zoom, this.mapRotation());
    }

    tickInterpolators(dt) {
        const f = 1 - Math.exp(-SMOOTH_RATE * dt);
        let moving = false;
        if (this.displayX != null) {
            const tx = this.player.position[0], tz = this.player.position[2];
            if (this.follow) {
                const dx = tx - this.displayX, dz = tz - this.displayZ;
                if (Math.abs(dx) > SMOOTH_POS_SNAP || Math.abs(dz) > SMOOTH_POS_SNAP) {
                    this.displayX += dx * f; this.displayZ += dz * f; moving = true;
                } else { this.displayX = tx; this.displayZ = tz; }
            }
            const ty = this.player.rotation[0] || 0;
            const dyaw = shortestAngleDiff(this.displayYaw, ty);
            if (Math.abs(dyaw) > SMOOTH_YAW_SNAP) {
                this.displayYaw = (this.displayYaw + dyaw * f + 360) % 360;
                moving = true;
            } else { this.displayYaw = ty; }
        }
        return moving;
    }

    /// Effective map center in world coords: the smoothed display position once seeded,
    /// else the raw live position. When `requireFollow` is set, the smoothed value is only
    /// used while following — a manual pan falls back to the live position.
    effectiveCenter(requireFollow: boolean): [number, number] {
        const seeded = this.displayX != null && (!requireFollow || this.follow);
        if (seeded) return [this.displayX as number, this.displayZ];
        return [this.player.position[0], this.player.position[2]];
    }

    cameraCenter() {
        const [cx, cz] = this.effectiveCenter(false);
        return [cx + this.panX, cz + this.panZ];
    }

    mapRotation() {
        if (this.northUp) return this.twist;
        return (Math.PI - this.displayYaw * Math.PI / 180) + this.twist;
    }

    viewportToWorld(px, py) {
        const v = this._els?.viewport;
        if (!v) return [0, 0];
        return this.buildCamera(v.clientWidth, v.clientHeight).screenToWorld(px, py);
    }

    render() {
        if (!this.enabled || !this._els) return;
        const v = this._els.viewport;
        const W = v.clientWidth, H = v.clientHeight;
        if (W <= 0 || H <= 0) return;
        const camera = this.buildCamera(W, H);
        this._lastCamera = camera;
        this._terrain?.render(camera);
        this.renderOverlay(camera);
        this.renderMarkers(camera);
        this.renderCardinals(W);
        this.renderHeader();
        this.renderZoomBar();
        this.renderPlayer(camera);
        this.renderScale();
    }

    renderOverlay(camera: MinimapCamera) {
        const canvas = this._els.overlay;
        const W = camera.width, H = camera.height;
        const dpr = window.devicePixelRatio || 1;
        if (canvas.width !== W * dpr || canvas.height !== H * dpr) { canvas.width = W * dpr; canvas.height = H * dpr; }
        const ctx = canvas.getContext('2d');
        ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
        ctx.imageSmoothingEnabled = false;
        ctx.clearRect(0, 0, W, H);
        if (this.showGrid) this.drawGrid(ctx, camera, W, H);
        this.drawEntities(ctx, camera, W, H);
    }

    drawGrid(ctx: CanvasRenderingContext2D, camera: MinimapCamera, W: number, H: number) {
        let step = 16;
        while (step / camera.zoom < 8) step *= 2;
        const halfDiag = Math.SQRT2 * Math.max(W, H) * camera.zoom / 2 + step;
        const x0 = Math.floor((camera.centerX - halfDiag) / step) * step;
        const x1 = Math.ceil((camera.centerX + halfDiag) / step) * step;
        const z0 = Math.floor((camera.centerZ - halfDiag) / step) * step;
        const z1 = Math.ceil((camera.centerZ + halfDiag) / step) * step;
        ctx.lineWidth = 1;
        ctx.strokeStyle = 'rgba(0,0,0,0.28)';
        ctx.beginPath();
        for (let x = x0; x <= x1; x += step) {
            const [ax, az] = camera.worldToScreen(x, z0);
            const [bx, bz] = camera.worldToScreen(x, z1);
            ctx.moveTo(ax, az); ctx.lineTo(bx, bz);
        }
        for (let z = z0; z <= z1; z += step) {
            const [ax, az] = camera.worldToScreen(x0, z);
            const [bx, bz] = camera.worldToScreen(x1, z);
            ctx.moveTo(ax, az); ctx.lineTo(bx, bz);
        }
        ctx.stroke();
    }

    /// One beginPath/fill/stroke per group regardless of entity count.
    drawEntities(ctx: CanvasRenderingContext2D, camera: MinimapCamera, W: number, H: number) {
        const entities = this.entities;
        if (entities.length === 0) return;
        const halfW = W / 2, halfH = H / 2;
        const margin = SPRITE_RADIUS + 1;
        const selfUuid = this._selfUuidLower;
        const filters = this.filters;

        let buckets = this._entityBuckets;
        if (!buckets) {
            buckets = this._entityBuckets = {};
            for (const k of GROUP_KEYS) buckets[k] = [];
        }
        for (const k of GROUP_KEYS) buckets[k].length = 0;

        for (let i = 0, n = entities.length; i < n; i++) {
            const e = entities[i];
            if (filters[e.group] === false) continue;
            if (e.uuid && e.uuid === selfUuid) continue;
            const b = buckets[e.group];
            if (!b) continue;
            const o = camera.projectOffset(e.x, e.z);
            const sx = o[0], sy = o[1];
            if (sx < -halfW - margin || sx > halfW + margin) continue;
            if (sy < -halfH - margin || sy > halfH + margin) continue;
            b.push(halfW + sx, halfH + sy);
        }

        ctx.lineWidth = 1;
        ctx.strokeStyle = 'rgba(0,0,0,0.6)';
        for (const g of GROUP_KEYS) {
            const pts = buckets[g];
            if (pts.length === 0) continue;
            const meta = GROUP_META[g];
            ctx.fillStyle = this._resolveColor(meta.color);
            ctx.beginPath();
            buildSpritePath(ctx, meta.sprite, pts);
            ctx.fill();
            ctx.stroke();
        }
    }

    _resolveColor(token: string): string {
        let cache = this._colorCache;
        if (!cache) cache = this._colorCache = new Map();
        const cached = cache.get(token);
        if (cached !== undefined) return cached;
        let resolved = token;
        if (token.startsWith('var(')) {
            const name = token.slice(4, -1).trim();
            resolved = getComputedStyle(this.host).getPropertyValue(name).trim() || '#fff';
        }
        cache.set(token, resolved);
        return resolved;
    }

    /// Waypoint DOM markers. Entities are drawn on the overlay canvas via `drawEntities`.
    renderMarkers(camera: MinimapCamera) {
        const host = this._els.markers;
        const halfW = camera.width / 2, halfH = camera.height / 2, inset = 10;
        const [px, pz] = this.effectiveCenter(true);
        const seen = new Set<string>();

        for (const wp of this.waypoints) {
            const [sx, sy] = camera.worldToOffset(wp.x, wp.z);
            if (Math.abs(sx) > halfW - inset || Math.abs(sy) > halfH - inset) continue;
            const key = 'wp:' + wp.id;
            seen.add(key);
            let el = this._markerEls.get(key);
            if (!el) {
                el = document.createElement('div');
                el.className = 'mm-wp';
                el.dataset.wp = String(wp.id);
                el.style.setProperty('--wp-c', wp.color);
                el.innerHTML = `<span class="mm-wp-dot">${wp.icon}</span><span class="mm-wp-label">${escAttr(wp.name)}<i class="mm-wp-d"></i></span>`;
                this._markerEls.set(key, el);
                host.appendChild(el);
            }
            el.style.transform = `translate(${sx.toFixed(2)}px,${sy.toFixed(2)}px)`;
            const dText = Math.round(Math.hypot(wp.x - px, wp.z - pz)) + 'm';
            const di = el.querySelector<HTMLElement>('.mm-wp-d')!;
            if (di.textContent !== dText) di.textContent = dText;
        }

        for (const [key, el] of this._markerEls) {
            if (!seen.has(key)) { el.remove(); this._markerEls.delete(key); }
        }
    }

    /// Nearest entity within `ENTITY_HIT_R2` of viewport (px,py), or null. Linear scan.
    _entityHitAt(px: number, py: number): MinimapEntity | null {
        const camera = this._lastCamera;
        const entities = this.entities;
        if (!camera || entities.length === 0) return null;
        const halfW = camera.width / 2, halfH = camera.height / 2;
        const ox = px - halfW, oy = py - halfH;
        const selfUuid = this._selfUuidLower;
        const filters = this.filters;
        let best: MinimapEntity | null = null;
        let bestD = ENTITY_HIT_R2;
        for (let i = 0, n = entities.length; i < n; i++) {
            const e = entities[i];
            if (filters[e.group] === false) continue;
            if (e.uuid && e.uuid === selfUuid) continue;
            const o = camera.projectOffset(e.x, e.z);
            const ddx = o[0] - ox, ddy = o[1] - oy;
            const d = ddx * ddx + ddy * ddy;
            if (d < bestD) { bestD = d; best = e; }
        }
        return best;
    }

    _handleHover(ev: PointerEvent) {
        const r = this._els.viewport.getBoundingClientRect();
        const ent = this._entityHitAt(ev.clientX - r.left, ev.clientY - r.top);
        const id = ent ? ent.id : null;
        if (id !== this._hoverEntityId) {
            this._hoverEntityId = id;
            this._els.viewport.style.cursor = ent ? 'pointer' : '';
            if (ent) {
                const dist = Math.hypot(ent.x - this.player.position[0], ent.z - this.player.position[2]);
                showEntityTooltip({ ...ent, distance: dist }, ev);
            } else {
                hideEntityTooltip();
            }
        } else if (ent) {
            moveEntityTooltip(ev);
        }
    }

    _clearHover() {
        if (this._hoverEntityId == null) return;
        this._hoverEntityId = null;
        this._els.viewport.style.cursor = '';
        hideEntityTooltip();
    }

    renderCardinals(W) {
        const host = this._els.cardinals;
        if (!this.showCardinals) { host.innerHTML = ''; return; }
        const half = W / 2, inset = 24;
        const rot = this.mapRotation();
        const TAU = Math.PI * 2;
        let html = '';
        for (let i = 0; i < 4; i++) {
            const ang = i * (TAU / 4) - Math.PI / 2 + rot;
            const ux = Math.cos(ang), uy = Math.sin(ang);
            const k = (half - inset) / Math.max(Math.abs(ux), Math.abs(uy));
            html += `<span class="mm-cardinal ${CARDINAL[i] === 'N' ? 'is-n' : ''}" style="transform:translate(${(ux * k).toFixed(1)}px,${(uy * k).toFixed(1)}px) translate(-50%,-50%)">${CARDINAL[i]}</span>`;
        }
        host.innerHTML = html;
    }

    renderHeader() {
        const p = this.player.position;
        const coords = `${Math.round(p[0])} ${Math.round(p[1])} ${Math.round(p[2])}`;
        if (this._els.coords.textContent !== coords) this._els.coords.textContent = coords;
        this._els.inset.innerHTML = `<span class="acc">${Math.round(p[0])}</span> <span>${Math.round(p[1])}</span> <span class="acc">${Math.round(p[2])}</span>`;
    }

    renderZoomBar() {
        const t = (Math.log(this.zoom) - Math.log(MIN_ZOOM)) / (Math.log(MAX_ZOOM) - Math.log(MIN_ZOOM));
        this._els.zoombar.style.height = `${(1 - t) * 100}%`;
    }

    renderPlayer(camera: MinimapCamera) {
        const [px, pz] = this.effectiveCenter(true);
        const [sx, sy] = camera.worldToOffset(px, pz);
        const yaw = this.displayYaw * Math.PI / 180;
        const dx = -Math.sin(yaw), dz = Math.cos(yaw);
        const rot = camera.rotation;
        const rx = Math.cos(rot) * dx - Math.sin(rot) * dz;
        const rz = Math.sin(rot) * dx + Math.cos(rot) * dz;
        const heading = Math.atan2(rx, -rz) * 180 / Math.PI;
        this._els.player.style.transform = `translate(${sx.toFixed(2)}px,${sy.toFixed(2)}px) translate(-50%,-50%) rotate(${heading.toFixed(1)}deg)`;
    }

    renderScale() {
        const pxPerBlock = 1 / this.zoom;
        let pick = 4;
        for (const t of [4, 8, 16, 32, 64, 128, 256, 512]) {
            if (t * pxPerBlock < 60) pick = t;
            else { pick = t; break; }
        }
        this._els.scaleLbl.textContent = pick + 'm';
        this._els.scaleBar.style.width = (pick * pxPerBlock) + 'px';
    }

    renderFilters() {
        if (!this._els) return;
        const counts = Object.fromEntries(GROUP_KEYS.map(k => [k, 0]));
        for (const e of this.entities) if (counts[e.group] !== undefined) counts[e.group]++;

        if (!this._filterChips) {
            const frag = document.createDocumentFragment();
            this._filterChips = {};
            for (const g of GROUP_KEYS) {
                const meta = GROUP_META[g];
                const chip = document.createElement('button');
                chip.className = 'mm-chip' + (this.filters[g] ? ' is-on' : '');
                chip.dataset.group = g;
                chip.style.setProperty('--chip-c', meta.color);
                chip.innerHTML = `<span class="mm-chip-glyph">${meta.glyph}</span>`
                    + `<span class="mm-chip-name">${meta.label}</span>`
                    + `<span class="mm-chip-count">${counts[g]}</span>`;
                frag.appendChild(chip);
                this._filterChips[g] = chip;
            }
            this._els.filters.replaceChildren(frag);
            return;
        }

        for (const g of GROUP_KEYS) {
            const chip = this._filterChips[g];
            if (!chip) continue;
            chip.classList.toggle('is-on', !!this.filters[g]);
            const countEl = chip.querySelector('.mm-chip-count');
            const nextCount = String(counts[g] || 0);
            if (countEl && countEl.textContent !== nextCount) countEl.textContent = nextCount;
        }
    }

    renderWaypointList() {
        if (!this._els) return;
        this._els.wpCount.textContent = '·' + this.waypoints.length;
        const px = this.player.position[0], pz = this.player.position[2];
        this._els.wpList.innerHTML = this.waypoints.map(wp => {
            const dx = wp.x - px, dz = wp.z - pz;
            const d = Math.hypot(dx, dz);
            const bear = ((Math.atan2(dx, -dz) * 180 / Math.PI) + 360) % 360;
            const compass = CARDINAL_8[Math.floor((bear + 22.5) / 45) % 8];
            return `<li class="mm-wp-item" data-id="${wp.id}">
                <span class="mm-wp-color" style="background:${wp.color}">${wp.icon}</span>
                <span class="mm-wp-text">
                    <span class="mm-wp-name">${escAttr(wp.name)}</span>
                    <span class="mm-wp-meta">${wp.x} ${wp.y} ${wp.z}</span>
                </span>
                <span class="mm-wp-bearing"><b>${compass}</b><i>${Math.round(d)}m</i></span>
                <button class="mm-wp-x" title="Remove">✕</button>
            </li>`;
        }).join('');
    }

    openContextMenu(cx, cy, worldXZ) {
        this.closeContextMenu();
        const el = document.createElement('div');
        el.className = 'mm-ctx';
        el.style.left = cx + 'px'; el.style.top = cy + 'px';
        el.innerHTML = CONTEXT_MENU_HTML(worldXZ);
        document.body.appendChild(el);
        requestAnimationFrame(() => {
            const r = el.getBoundingClientRect(), pad = 8;
            el.style.left = Math.max(pad, Math.min(innerWidth  - r.width  - pad, cx)) + 'px';
            el.style.top  = Math.max(pad, Math.min(innerHeight - r.height - pad, cy)) + 'px';
        });
        el.onclick = e => {
            const act = (e.target as Element).closest('button')?.getAttribute('data-a');
            if (act === 'add') this.openWaypointDraft(worldXZ);
            else if (act === 'copy') {
                navigator.clipboard?.writeText(`${Math.round(worldXZ[0])} 64 ${Math.round(worldXZ[1])}`);
                toast('Coordinates copied');
            }
            this.closeContextMenu();
        };
        const off = ev => { if (!closest(ev, '.mm-ctx')) { this.closeContextMenu(); document.removeEventListener('pointerdown', off, true); } };
        setTimeout(() => document.addEventListener('pointerdown', off, true), 0);
        this.contextMenu = el;
    }
    closeContextMenu() { if (this.contextMenu) { this.contextMenu.remove(); this.contextMenu = null; } }

    openWaypointDraft(worldXZ) {
        const draft = {
            id: 'wp-' + Date.now(),
            x: Math.round(worldXZ[0]), y: Math.round(this.player.position[1]), z: Math.round(worldXZ[1]),
            name: 'Waypoint ' + (this.waypoints.length + 1),
            color: WP_COLORS[0], icon: WP_ICONS[0],
        };
        const scrim = document.createElement('div');
        scrim.className = 'overlay-scrim overlay-scrim--modal';
        scrim.innerHTML = WAYPOINT_DRAFT_HTML(draft);
        document.body.appendChild(scrim);
        scrim.addEventListener('click', e => { if (e.target === scrim) scrim.remove(); });
        const radioBind = (attr, field) => scrim.querySelectorAll<HTMLButtonElement>(`[${attr}]`).forEach(b => b.onclick = () => {
            draft[field] = b.getAttribute(attr);
            scrim.querySelectorAll<HTMLButtonElement>(`[${attr}]`).forEach(x => x.classList.remove('is-on'));
            b.classList.add('is-on');
        });
        radioBind('data-color', 'color');
        radioBind('data-icon',  'icon');
        scrim.querySelectorAll<HTMLButtonElement>('[data-a="cancel"]').forEach(b => b.onclick = () => scrim.remove());
        scrim.querySelector<HTMLButtonElement>('[data-a="save"]')!.onclick = () => {
            draft.name = scrim.querySelector<HTMLInputElement>('[data-f="name"]')!.value || draft.name;
            draft.x = parseInt(scrim.querySelector<HTMLInputElement>('[data-f="x"]')!.value, 10) || 0;
            draft.y = parseInt(scrim.querySelector<HTMLInputElement>('[data-f="y"]')!.value, 10) || 64;
            draft.z = parseInt(scrim.querySelector<HTMLInputElement>('[data-f="z"]')!.value, 10) || 0;
            this.waypoints.push(draft);
            saveWaypoints(this.uuid, this.waypoints);
            scrim.remove();
            this.renderWaypointList();
            this.requestRender();
        };
    }

    destroy() {
        this.unsubs.forEach(fn => fn()); this.unsubs = [];
        this._terrainInit++;
        this._terrain?.dispose();
        this._terrain = null;
        if (this._raf) cancelAnimationFrame(this._raf);
        if (this._ro) { this._ro.disconnect(); this._ro = null; }
        if (this._fsEsc) { document.removeEventListener('keydown', this._fsEsc); this._fsEsc = null; }
        this.closeContextMenu();
        this._markerEls.clear();
        hideEntityTooltip();
        this.host.classList.remove('mm-host', 'mm-root', 'is-fullscreen');
        this.host.innerHTML = '';
    }
}
