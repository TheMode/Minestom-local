/** Flatten Minecraft JSON text components for DOM rendering (mirrors ComponentCodecs). */

export type McStyle = Record<string, unknown>;

export type McRun =
    | { kind: 'text'; text: string; style: McStyle; hover: Record<string, unknown> | null; click: Record<string, unknown> | null }
    | { kind: 'icon'; id: string; title: string; head?: boolean };

export const TRANSLATIONS: Record<string, string> = {
    'chat.type.text': '<%s> %s',
    'chat.type.announcement': '[%s] %s',
    'chat.type.emote': '* %s %s',
    'chat.type.admin': '[%s: %s]',
    'chat.type.team.sent': '-> %s <%s> %s',
    'chat.type.team.text': '%s <%s> %s',
    'multiplayer.player.joined': '%s joined the game',
    'multiplayer.player.left': '%s left the game',
    'multiplayer.player.joined.renamed': '%s (formerly known as %s) joined the game',
    'commands.message.display.incoming': '%s whispers to you: %s',
    'commands.message.display.outgoing': 'You whisper to %s: %s',
};

const NAMED_COLORS: Record<string, string> = {
    black: '#000000', dark_blue: '#0000aa', dark_green: '#00aa00', dark_aqua: '#00aaaa',
    dark_red: '#aa0000', dark_purple: '#aa00aa', gold: '#ffaa00', gray: '#aaaaaa',
    dark_gray: '#555555', blue: '#5555ff', green: '#55ff55', aqua: '#55ffff',
    red: '#ff5555', light_purple: '#ff55ff', yellow: '#ffff55', white: '#ffffff',
};

const DECORATIONS = ['bold', 'italic', 'underlined', 'strikethrough', 'obfuscated'] as const;
const FORMAT_RE = /%(?:(\d+)\$)?s/g;

type FormatPart = { kind: 'text' | 'arg'; text?: string; value?: unknown };

function resolveColor(v: unknown): string {
    const s = String(v ?? '');
    return NAMED_COLORS[s] || (/^#[0-9a-fA-F]{3,8}$/.test(s) ? s : '');
}

function inheritStyle(node: Record<string, unknown>, inherited: McStyle): McStyle {
    const style = { ...inherited };
    if (node.color != null) style.color = resolveColor(node.color);
    const shadow = node.shadow_color ?? node.shadowColor;
    if (shadow != null) style.shadowColor = resolveColor(shadow);
    for (const k of DECORATIONS) {
        if (node[k] === true) style[k] = true;
        if (node[k] === false) style[k] = false;
    }
    return style;
}

function asObject(node: unknown): Record<string, unknown> | null {
    return node != null && typeof node === 'object' && !Array.isArray(node)
        ? node as Record<string, unknown> : null;
}

function pick<T>(o: Record<string, unknown>, a: string, b: string): T | null {
    return (o[a] ?? o[b]) as T | null;
}

function extra(o: Record<string, unknown>): unknown[] {
    return Array.isArray(o.extra) ? o.extra : [];
}

function stripId(id: string): string {
    return id.replace(/^minecraft:/, '').replace(/^.*\//, '');
}

function iconId(o: Record<string, unknown>, sprite: boolean): string {
    if (sprite) {
        const s = o.sprite;
        return s != null ? stripId(String(s)) : 'air';
    }
    const raw = o.item ?? o;
    if (typeof raw === 'string') return stripId(raw);
    if (raw && typeof raw === 'object') {
        const id = (raw as Record<string, unknown>).id;
        if (typeof id === 'string') return stripId(id);
    }
    if (typeof o.id === 'string') return stripId(o.id);
    return 'air';
}

function itemCount(o: Record<string, unknown>): number {
    const raw = o.item;
    if (raw && typeof raw === 'object') {
        const c = (raw as Record<string, unknown>).count;
        if (typeof c === 'number') return c;
    }
    return typeof o.count === 'number' ? o.count : 1;
}

function headName(o: Record<string, unknown>): string {
    const p = o.player;
    if (typeof p === 'string') return p;
    if (p && typeof p === 'object' && typeof (p as Record<string, unknown>).name === 'string') {
        return (p as Record<string, unknown>).name as string;
    }
    return typeof o.name === 'string' ? o.name : '?';
}

function contentKind(node: unknown): string {
    if (node == null || typeof node === 'string' || typeof node === 'number' || typeof node === 'boolean') {
        return 'primitive';
    }
    if (Array.isArray(node)) return 'array';

    const o = node as Record<string, unknown>;
    switch (o.type != null ? String(o.type) : '') {
        case 'text': return 'text';
        case 'translatable': return 'translatable';
        case 'score': return 'score';
        case 'selector': return 'selector';
        case 'keybind': return 'keybind';
        case 'item': return 'item';
        case 'sprite': return 'sprite';
        case 'player_head': return 'player_head';
        case 'object':
            if (o.player != null) return 'player_head';
            if (o.sprite != null) return 'sprite';
            return 'compound';
    }

    if (o.text != null && o.translate == null && o.score == null && o.selector == null
        && o.keybind == null && o.player == null && o.sprite == null && o.item == null) return 'text';
    if (o.translate != null) return 'translatable';
    if (o.score != null) return 'score';
    if (o.selector != null) return 'selector';
    if (o.keybind != null) return 'keybind';
    if (o.item != null) return 'item';
    if (o.player != null) return 'player_head';
    if (o.sprite != null) return 'sprite';
    if (typeof o.id === 'string' && o.id.includes(':') && o.uuid == null
        && o.text == null && o.translate == null && o.score == null
        && o.selector == null && o.player == null && o.sprite == null) return 'item';

    return 'compound';
}

function runSig(style: McStyle, hover: unknown, click: unknown): string {
    return JSON.stringify([style.color, style.bold, style.italic, style.underlined,
        style.strikethrough, style.obfuscated, !!hover, !!click]);
}

function textNeedsSpan(style: McStyle, hover: unknown, click: unknown): boolean {
    return !!(hover || click || style.color || style.bold || style.italic
        || style.underlined || style.strikethrough || style.obfuscated || style.shadowColor);
}

function appendText(
    runs: McRun[], text: string, style: McStyle,
    hover: Record<string, unknown> | null, click: Record<string, unknown> | null,
) {
    if (!text) return;
    const sig = runSig(style, hover, click);
    const last = runs[runs.length - 1];
    if (last?.kind === 'text' && runSig(last.style, last.hover, last.click) === sig) {
        last.text += text;
        return;
    }
    runs.push({ kind: 'text', text, style, hover, click });
}

export function parseFormat(fmt: string, args: unknown[]): FormatPart[] {
    const parts: FormatPart[] = [];
    let i = 0, last = 0, m: RegExpExecArray | null;
    while ((m = FORMAT_RE.exec(fmt)) !== null) {
        if (m.index > last) parts.push({ kind: 'text', text: fmt.slice(last, m.index) });
        parts.push({ kind: 'arg', value: args[m[1] ? Number(m[1]) - 1 : i++] });
        last = m.index + m[0].length;
    }
    if (last < fmt.length) parts.push({ kind: 'text', text: fmt.slice(last) });
    return parts;
}

function walkExtra(o: Record<string, unknown>, style: McStyle, runs: McRun[]) {
    for (const child of extra(o)) walk(child, style, runs);
}

function walk(node: unknown, inherited: McStyle, runs: McRun[]) {
    if (node == null) return;

    if (typeof node === 'string' || typeof node === 'number' || typeof node === 'boolean') {
        appendText(runs, String(node), inherited, null, null);
        return;
    }
    if (Array.isArray(node)) {
        for (const child of node) walk(child, inherited, runs);
        return;
    }

    const o = asObject(node);
    if (!o) return;

    const style = inheritStyle(o, inherited);
    const hover = pick<Record<string, unknown>>(o, 'hover_event', 'hoverEvent');
    const click = pick<Record<string, unknown>>(o, 'click_event', 'clickEvent');

    switch (contentKind(node)) {
        case 'item': {
            const id = iconId(o, false);
            const n = itemCount(o);
            runs.push({ kind: 'icon', id, title: n > 1 ? `${id} ×${n}` : id });
            if (o.fallback != null) walk(o.fallback, inherited, runs);
            return;
        }
        case 'sprite':
            runs.push({ kind: 'icon', id: iconId(o, true), title: String(o.sprite ?? '') });
            if (o.fallback != null) walk(o.fallback, inherited, runs);
            return;
        case 'player_head':
            runs.push({ kind: 'icon', id: '', title: `head of ${headName(o)}`, head: true });
            if (o.fallback != null) walk(o.fallback, inherited, runs);
            return;
        case 'text':
            if (o.text != null && String(o.text) !== '') appendText(runs, String(o.text), style, hover, click);
            walkExtra(o, style, runs);
            return;
        case 'translatable': {
            const key = String(o.translate ?? '');
            const args = Array.isArray(o.with) ? o.with : [];
            const fmt = TRANSLATIONS[key] ?? o.fallback;
            if (fmt) {
                for (const part of parseFormat(String(fmt), args)) {
                    if (part.kind === 'text') appendText(runs, part.text ?? '', style, hover, click);
                    else if (part.value != null) walk(part.value, style, runs);
                }
            } else {
                appendText(runs, key, style, hover, click);
                if (args.length) {
                    appendText(runs, ' (', style, hover, click);
                    args.forEach((a, i) => {
                        if (i > 0) appendText(runs, ', ', style, hover, click);
                        walk(a, style, runs);
                    });
                    appendText(runs, ')', style, hover, click);
                }
            }
            walkExtra(o, style, runs);
            return;
        }
        case 'keybind':
            appendText(runs, String(o.keybind).replace(/^key\./, ''), style, hover, click);
            walkExtra(o, style, runs);
            return;
        case 'score': {
            const s = o.score as Record<string, unknown>;
            appendText(runs, `${s.name ?? ''}:${s.objective ?? ''}`, style, hover, click);
            walkExtra(o, style, runs);
            return;
        }
        case 'selector':
            appendText(runs, String(o.selector), style, hover, click);
            walkExtra(o, style, runs);
            return;
        default:
            walkExtra(o, style, runs);
    }
}

export function flattenMc(node: unknown): McRun[] {
    const runs: McRun[] = [];
    walk(node, {}, runs);
    return runs;
}

export function runNeedsSpan(run: McRun): boolean {
    return run.kind === 'text' && textNeedsSpan(run.style, run.hover, run.click);
}

export function classesFor(style: McStyle, hover: unknown, click: unknown): string {
    const cls = ['mc-segment'];
    if (style.bold) cls.push('mc-bold');
    if (style.italic) cls.push('mc-italic');
    if (style.underlined) cls.push('mc-under');
    if (style.strikethrough) cls.push('mc-strike');
    if (style.obfuscated) cls.push('mc-obfuscated');
    if (hover) cls.push('mc-has-hover');
    if (click) cls.push('mc-has-click');
    return cls.join(' ');
}

export function styleFor(style: McStyle): string {
    const out: string[] = [];
    if (style.color) out.push(`color: ${style.color}`);
    if (style.shadowColor) out.push(`text-shadow: 0 1px 0 ${style.shadowColor}, 0 0 4px ${style.shadowColor}`);
    return out.join('; ');
}

export function clickTitle(click: Record<string, unknown>): string {
    const action = click.action || 'click';
    const payload = click.payload ?? click.value;
    const text = typeof payload === 'object'
        ? ((payload as Record<string, unknown>)?.value ?? JSON.stringify(payload))
        : String(payload ?? '');
    return `${action}${text ? ': ' + text : ''}`;
}

export function hoverBody(hover: Record<string, unknown>): unknown {
    return hover.value ?? hover.contents ?? hover;
}

export const asMcObject = asObject;

/** Pretty-print a value for display / clipboard. */
export function formatMcJson(value: unknown): string {
    if (value == null || value === '') return '';
    if (typeof value === 'string') {
        const t = value.trim();
        if ((t.startsWith('{') && t.endsWith('}')) || (t.startsWith('[') && t.endsWith(']'))) {
            try { return JSON.stringify(JSON.parse(t), null, 2); } catch { /* plain text */ }
        }
        return value;
    }
    try {
        return JSON.stringify(value, null, 2);
    } catch {
        return String(value);
    }
}
