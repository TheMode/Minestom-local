// Imperative chart cores — callers (the .svelte wrappers) can
// drive them via `bind:this` + lifecycle effects.

type Series = {
    key: string;
    label?: string;
    color: string;
    area?: boolean;
};

type Padding = { top: number; right: number; bottom: number; left: number };
type ChartData = Record<string, number[]>;
type Formatter = (value: number) => string;

function resolveColor(c: string | undefined, el: Element): string {
    if (!c) return '#9ca3af';
    if (typeof c === 'string' && c.startsWith('var(')) {
        const name = c.slice(4, -1).trim();
        const v = getComputedStyle(el).getPropertyValue(name).trim();
        return v || '#9ca3af';
    }
    return c;
}

export class SparklineCore {
    cv: HTMLCanvasElement;
    ctx: CanvasRenderingContext2D;
    color: string;
    fill?: string;
    max: number;
    data: number[] = [];
    _ro: ResizeObserver;

    constructor(canvas: HTMLCanvasElement, { color = 'var(--acc)', fill, max = 60 }: { color?: string; fill?: string; max?: number } = {}) {
        this.cv = canvas;
        const ctx = canvas.getContext('2d');
        if (!ctx) throw new Error('2D canvas context is unavailable');
        this.ctx = ctx;
        this.color = color; this.fill = fill; this.max = max;
        this._resize();
        this._ro = new ResizeObserver(() => this._resize());
        this._ro.observe(canvas);
    }
    _resize(): void {
        const dpr = devicePixelRatio || 1;
        const { clientWidth: w, clientHeight: h } = this.cv;
        this.cv.width  = Math.max(1, Math.round(w * dpr));
        this.cv.height = Math.max(1, Math.round(h * dpr));
        this.ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
        this.draw();
    }
    set(values: ArrayLike<unknown> | null | undefined): void {
        this.data = Array.from(values || []).slice(-this.max).map(v => Number(v) || 0);
        this.draw();
    }
    draw(): void {
        const { ctx, cv, data } = this;
        const w = cv.clientWidth, h = cv.clientHeight;
        ctx.clearRect(0, 0, w, h);
        if (data.length < 2) return;
        const stroke = resolveColor(this.color, cv);
        const fill = this.fill ? resolveColor(this.fill, cv) : (stroke + '33');
        const min = Math.min(...data), max = Math.max(...data);
        const range = (max - min) || 1;
        const stepX = w / (data.length - 1);
        ctx.beginPath();
        data.forEach((v, i) => {
            const x = i * stepX;
            const y = h - ((v - min) / range) * (h - 4) - 2;
            i === 0 ? ctx.moveTo(x, y) : ctx.lineTo(x, y);
        });
        ctx.strokeStyle = stroke; ctx.lineWidth = 1.5; ctx.lineJoin = 'round';
        ctx.stroke();
        if (fill && fill !== 'transparent') {
            ctx.lineTo(w, h); ctx.lineTo(0, h); ctx.closePath();
            ctx.fillStyle = fill; ctx.fill();
        }
    }
    destroy(): void { this._ro?.disconnect(); }
}

const SVG_NS = 'http://www.w3.org/2000/svg';
const DEFAULT_PADDING = { top: 10, right: 14, bottom: 22, left: 44 };

export class ChartCore {
    container: HTMLElement;
    series: Series[];
    opts: {
        yLabel: string;
        yFormat: Formatter;
        xFormat: Formatter;
        padding: Padding;
        gridX: number;
        gridY: number;
        showAxes: boolean;
    };
    data: ChartData = {};
    xValues: Array<string | number> | null = null;
    svg: SVGSVGElement;
    tip: HTMLDivElement;
    legend?: HTMLDivElement;
    _rafPending = false;
    _ro: ResizeObserver;
    _onHover: (e: MouseEvent) => void;
    _hideTip: () => void;
    _ih = 0;
    _iw = 0;
    _ix = 0;
    _iy = 0;
    _n = 0;

    constructor(container: HTMLElement, {
        series,
        yLabel,
        yFormat,
        xFormat,
        padding,
        gridX,
        gridY,
        showLegend,
        showAxes,
    }: {
        series?: Series[];
        yLabel?: string;
        yFormat?: Formatter;
        xFormat?: Formatter;
        padding?: Padding;
        gridX?: number;
        gridY?: number;
        showLegend?: boolean;
        showAxes?: boolean;
    }) {
        this.container = container;
        container.classList.add('chart');
        this.series = series || [];
        this.opts = { yLabel: yLabel || '', yFormat: yFormat || (v => String(Math.round(v))),
                      xFormat: xFormat || (i => String(i)), padding: padding || DEFAULT_PADDING,
                      gridX: gridX ?? 6, gridY: gridY ?? 4, showAxes: showAxes ?? true };
        container.innerHTML = '';
        this.svg = document.createElementNS(SVG_NS, 'svg');
        this.svg.setAttribute('preserveAspectRatio', 'none');
        container.appendChild(this.svg);

        this.tip = document.createElement('div');
        this.tip.className = 'chart-tip';
        container.appendChild(this.tip);

        if ((showLegend ?? true) && this.series.length > 1) {
            this.legend = document.createElement('div');
            this.legend.className = 'chart-legend';
            this.legend.innerHTML = this.series.map(s =>
                `<span class="chart-legend-item"><span class="chart-legend-swatch" style="background:${s.color}"></span>${s.label || s.key}</span>`
            ).join('');
            container.appendChild(this.legend);
        }

        this._ro = new ResizeObserver(() => {
            if (this._rafPending) return;
            this._rafPending = true;
            requestAnimationFrame(() => { this._rafPending = false; this.draw(); });
        });
        this._ro.observe(this.svg);

        this._onHover = this._handleHover.bind(this);
        this._hideTip = this._handleHideTip.bind(this);
        this.svg.addEventListener('mousemove', this._onHover);
        this.svg.addEventListener('mouseleave', this._hideTip);
    }
    set(data: ChartData | null | undefined, xValues: Array<string | number> | null = null): void {
        this.data = data || {};
        this.xValues = xValues;
        this.draw();
    }
    _bounds(): { yMin: number; yMax: number; n: number } {
        let yMin = Infinity, yMax = -Infinity, n = 0;
        for (const s of this.series) {
            const arr = this.data[s.key] || [];
            n = Math.max(n, arr.length);
            for (const v of arr) {
                if (!Number.isFinite(v)) continue;
                if (v < yMin) yMin = v;
                if (v > yMax) yMax = v;
            }
        }
        if (!Number.isFinite(yMin) || !Number.isFinite(yMax)) { yMin = 0; yMax = 1; }
        if (yMin === yMax) { yMin = Math.max(0, yMin - 1); yMax = yMax + 1; }
        const pad = (yMax - yMin) * 0.08;
        return { yMin: Math.max(0, yMin - pad), yMax: yMax + pad, n };
    }
    draw(): void {
        const w = this.svg.clientWidth || this.container.clientWidth;
        const h = this.svg.clientHeight || Math.max(120, this.container.clientHeight - (this.legend ? 28 : 0));
        if (w <= 0 || h <= 0) return;
        const { padding, gridX, gridY, showAxes, yFormat, xFormat, yLabel } = this.opts;
        const ix = padding.left, iy = padding.top;
        const iw = w - padding.left - padding.right;
        const ih = h - padding.top - padding.bottom;
        this.svg.setAttribute('viewBox', `0 0 ${w} ${h}`);
        const { yMin, yMax, n } = this._bounds();
        this._ih = ih; this._iw = iw; this._ix = ix; this._iy = iy; this._n = n;
        const parts: string[] = [];
        for (let i = 0; i <= gridY; i++) {
            const y = iy + (i / gridY) * ih;
            const yV = yMax - (i / gridY) * (yMax - yMin);
            parts.push(`<line class="chart-grid" x1="${ix}" x2="${ix + iw}" y1="${y}" y2="${y}" stroke="color-mix(in oklab, currentColor 14%, transparent)" stroke-width="1" shape-rendering="crispEdges"/>`);
            if (showAxes) parts.push(`<text x="${ix - 6}" y="${y + 4}" text-anchor="end" class="chart-axis" fill="currentColor" opacity="0.5">${yFormat(yV)}</text>`);
        }
        for (let i = 0; i <= gridX; i++) {
            const x = ix + (i / gridX) * iw;
            parts.push(`<line class="chart-grid" x1="${x}" x2="${x}" y1="${iy}" y2="${iy + ih}" stroke="color-mix(in oklab, currentColor 8%, transparent)" stroke-width="1" shape-rendering="crispEdges"/>`);
            if (showAxes && n > 1) {
                const sampleIdx = Math.round((i / gridX) * (n - 1));
                const lbl = this.xValues ? this._fmtTime(this.xValues[sampleIdx]) : xFormat(sampleIdx);
                parts.push(`<text x="${x}" y="${iy + ih + 14}" text-anchor="middle" class="chart-axis" fill="currentColor" opacity="0.5">${lbl}</text>`);
            }
        }
        if (yLabel) parts.push(`<text x="${ix}" y="${iy - 4}" class="chart-axis" fill="currentColor" opacity="0.5">${yLabel}</text>`);
        const range = (yMax - yMin) || 1;
        for (const s of this.series) {
            const arr = this.data[s.key] || [];
            if (arr.length < 2 || n < 2) continue;
            const pts: Array<[number, number]> = [];
            for (let i = 0; i < arr.length; i++) {
                const v = Number(arr[i]);
                if (!Number.isFinite(v)) continue;
                const x = ix + (i / (n - 1)) * iw;
                const y = iy + ih - ((v - yMin) / range) * ih;
                if (Number.isFinite(x) && Number.isFinite(y)) pts.push([x, y]);
            }
            if (pts.length < 2) continue;
            const d = pts.map(([x, y], i) => (i === 0 ? 'M' : 'L') + x.toFixed(1) + ' ' + y.toFixed(1)).join(' ');
            if (s.area) {
                const last = pts.at(-1);
                if (!last) continue;
                const a = d + ` L ${last[0].toFixed(1)} ${iy + ih} L ${pts[0][0].toFixed(1)} ${iy + ih} Z`;
                parts.push(`<path d="${a}" fill="${s.color}" fill-opacity="0.18"/>`);
            }
            parts.push(`<path d="${d}" fill="none" stroke="${s.color}" stroke-width="1.5" stroke-linejoin="miter" vector-effect="non-scaling-stroke"/>`);
        }
        parts.push(`<line class="chart-crosshair" x1="0" x2="0" y1="${iy}" y2="${iy + ih}" visibility="hidden"/>`);
        for (const s of this.series) {
            parts.push(`<circle class="chart-marker chart-marker-${s.key}" cx="0" cy="0" r="3.5" fill="var(--bg-0)" stroke="${s.color}" stroke-width="2" visibility="hidden"/>`);
        }
        this.svg.innerHTML = parts.join('');
    }
    _handleHover(e: MouseEvent): void {
        if (!this._n || this._n < 2) return;
        const rect = this.svg.getBoundingClientRect();
        const xRel = Math.max(0, Math.min(1, (e.clientX - rect.left - this._ix) / this._iw));
        const idx = Math.round(xRel * (this._n - 1));
        const xPx = this._ix + (idx / (this._n - 1)) * this._iw;
        const ch = this.svg.querySelector('.chart-crosshair');
        if (ch) { ch.setAttribute('x1', String(xPx)); ch.setAttribute('x2', String(xPx)); ch.setAttribute('visibility', 'visible'); }
        const { yMin, yMax } = this._bounds();
        const range = (yMax - yMin) || 1;
        const rows = this.series.map(s => {
            const v = (this.data[s.key] || [])[idx];
            const marker = this.svg.querySelector(`.chart-marker-${s.key}`);
            if (v == null || !Number.isFinite(v)) { if (marker) marker.setAttribute('visibility', 'hidden'); return ''; }
            if (marker) {
                const yPx = this._iy + this._ih - ((v - yMin) / range) * this._ih;
                marker.setAttribute('cx', String(xPx)); marker.setAttribute('cy', String(yPx)); marker.setAttribute('visibility', 'visible');
            }
            return `<div class="tip-row"><span class="tip-swatch" style="background:${s.color}"></span><span>${s.label || s.key}</span><span class="tip-val">${this.opts.yFormat(v)}</span></div>`;
        }).join('');
        const tsLabel = this.xValues ? this._fmtTime(this.xValues[idx]) : '#' + idx;
        this.tip.innerHTML = `<div class="tip-ts">${tsLabel}</div>${rows}`;
        this.tip.dataset.show = '1';
        const cw = this.container.clientWidth;
        const left = Math.min(cw - this.tip.offsetWidth - 4, Math.max(4, e.clientX - rect.left + 12));
        this.tip.style.left = left + 'px'; this.tip.style.top = (this._iy + 4) + 'px';
    }
    _handleHideTip(): void {
        const ch = this.svg.querySelector('.chart-crosshair');
        ch?.setAttribute('visibility', 'hidden');
        this.svg.querySelectorAll('.chart-marker').forEach(m => m.setAttribute('visibility', 'hidden'));
        this.tip.dataset.show = '0';
    }
    _fmtTime(ts: string | number | undefined): string {
        if (!ts) return '';
        if (typeof ts === 'number') return new Date(ts).toTimeString().slice(0, 8);
        return String(ts);
    }
    destroy(): void {
        this._ro?.disconnect();
        this.svg.removeEventListener('mousemove', this._onHover);
        this.svg.removeEventListener('mouseleave', this._hideTip);
    }
}
