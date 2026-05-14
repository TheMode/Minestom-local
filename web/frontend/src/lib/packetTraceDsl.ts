import type { PacketRow } from './packetAgg.ts';
import { isClientBound } from './packetAgg.ts';

/// DSL filter for the packet trace search bar.
///
/// Examples:
///   class:Position dir:cb size:>200 subject:Steve  free text
///   !class:KeepAlive state:PLAY ~Position
export type DslOp = '=' | '>' | '<' | '>=' | '<=';

export type DslToken =
    | { kind: 'kv'; key: string; op: DslOp; val: string; neg: boolean; raw: string }
    | { kind: 'text'; val: string; neg: boolean; raw: string };

export type DslQuery = { tokens: DslToken[]; match: (p: ParsedRow) => boolean };

export type ParsedRow = PacketRow & {
    _bookmarked?: boolean;
    summary?: string;
};

const TOKEN_RE = /(!?)([a-zA-Z]+):((?:"[^"]*")|[^\s]+)|(!?)([^\s]+)/g;

export function parseQuery(q: string): DslQuery {
    const tokens: DslToken[] = [];
    let m: RegExpExecArray | null;
    TOKEN_RE.lastIndex = 0;
    while ((m = TOKEN_RE.exec(q))) {
        if (m[2]) {
            const neg = !!m[1];
            let val = m[3];
            let op: DslOp = '=';
            if (val.startsWith('>=') || val.startsWith('<=')) { op = val.slice(0, 2) as DslOp; val = val.slice(2); }
            else if (val.startsWith('>') || val.startsWith('<')) { op = val[0] as DslOp; val = val.slice(1); }
            else if (val.startsWith('"') && val.endsWith('"')) val = val.slice(1, -1);
            tokens.push({ kind: 'kv', key: m[2].toLowerCase(), op, val: val.toLowerCase(), neg, raw: m[0] });
        } else if (m[5]) {
            tokens.push({ kind: 'text', val: m[5].toLowerCase(), neg: !!m[4], raw: m[0] });
        }
    }

    function matchKV(p: ParsedRow, t: Extract<DslToken, { kind: 'kv' }>): boolean {
        const v = t.val;
        let target: number;
        switch (t.key) {
            case 'class': case 'c':
                return p.className.toLowerCase().includes(v);
            case 'dir':
                if (v === 'cb' || v === 'in' || v === 'clientbound') return isClientBound(p.direction);
                if (v === 'sb' || v === 'out' || v === 'serverbound') return p.direction === 'SERVERBOUND';
                return false;
            case 'state': case 's':
                return (p.state || '').toLowerCase().startsWith(v);
            case 'subject': case 'subj':
                return (p.subjectLabel || '').toLowerCase().includes(v) || String(p.subject).toLowerCase() === v;
            case 'group': case 'g':
                return (p.subjectGroup || '').toLowerCase() === v;
            case 'size':
                target = Number(v); if (Number.isNaN(target)) return false;
                if (t.op === '>')  return p.sizeBytes >  target;
                if (t.op === '<')  return p.sizeBytes <  target;
                if (t.op === '>=') return p.sizeBytes >= target;
                if (t.op === '<=') return p.sizeBytes <= target;
                return p.sizeBytes === target;
            case 'seq':
                target = Number(v); if (Number.isNaN(target)) return false;
                if (t.op === '>')  return p.seq >  target;
                if (t.op === '<')  return p.seq <  target;
                if (t.op === '>=') return p.seq >= target;
                if (t.op === '<=') return p.seq <= target;
                return p.seq === target;
            case 'has':
                return v === 'bookmark' ? !!p._bookmarked : false;
            default: return false;
        }
    }
    function matchText(p: ParsedRow, t: Extract<DslToken, { kind: 'text' }>): boolean {
        const v = t.val;
        return p.className.toLowerCase().includes(v)
            || (p.subjectLabel || '').toLowerCase().includes(v)
            || (p.summary || '').toLowerCase().includes(v);
    }

    function match(p: ParsedRow): boolean {
        for (const t of tokens) {
            const ok = t.kind === 'kv' ? matchKV(p, t) : matchText(p, t);
            if (t.neg ? ok : !ok) return false;
        }
        return true;
    }
    return { tokens, match };
}

/// Hash a class name → stable oklch hue for the row swatch + facet dot.
export function classColor(name: string): string {
    let h = 0;
    for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) & 0xffff;
    const hue = h % 360;
    return `oklch(72% 0.13 ${hue})`;
}
